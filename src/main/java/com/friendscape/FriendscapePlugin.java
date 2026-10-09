package com.friendscape;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "friendscape",
	description = "Tracks your progress in Friendscape clan Events: bingo, Skill of the week, Boss of the week",
	tags = {"bingo", "clan", "event", "sotw", "botw", "competition", "friendscape"}
)
public class FriendscapePlugin extends Plugin
{
	/** Developer override for the API base, e.g. {@code -Dfriendscape.apiBase=http://localhost:3000/api/}. */
	static final String API_BASE_PROPERTY = "friendscape.apiBase";
	private static final String DEFAULT_API_BASE = "https://www.friendscape.cc/api/";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private FriendscapeConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	private FriendscapeApi api;
	private FriendscapePanel panel;
	private NavigationButton navButton;

	// Written on the client thread, read by the API gate on OkHttp threads and by the scheduler.
	private volatile boolean loggedIn;
	private volatile boolean worldTracked;
	private volatile String rsn;
	private volatile long accountHash;
	private volatile ConnectionStatus connection = ConnectionStatus.CONNECTING;

	private final XpTracker xpTracker = new XpTracker();
	/** The next Reading carries every skill: after login, sending turning on, or an Event's start or end. */
	private volatile boolean sendAllSkills = true;
	/** The marks the next every-skill Reading carries: empty, except for a start or end Reading. */
	private volatile Set<Moment> pendingMoments = Set.of();
	/** When an Event the last Reading found starts or ends; written on OkHttp threads. */
	private final AtomicReference<Instant> momentReadingAt = new AtomicReference<>();
	/** The last answer to a Reading, for which moments have passed; written on OkHttp threads. */
	private volatile ReadingsResponse lastResponse;

	@Provides
	FriendscapeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FriendscapeConfig.class);
	}

	@Override
	protected void startUp()
	{
		api = new FriendscapeApi(okHttpClient, gson, apiBase(), this::mayTransmit);
		resetReadings();
		panel = new FriendscapePanel(this::setSending);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("friendscape")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Enabled mid-session: pick up the current login instead of waiting for the next one
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				handleLogin();
			}
			refreshPanel();
		});
	}

	@Override
	protected void shutDown()
	{
		api.cancelAll();
		clientToolbar.removeNavigation(navButton);
		loggedIn = false;
		worldTracked = false;
		rsn = null;
		connection = ConnectionStatus.CONNECTING;
		resetReadings();
		// api stays set: an async @Schedule tick racing shutdown must not hit null, and the gate refuses anyway
		navButton = null;
		panel = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				handleLogin();
				break;
			case HOPPING:
			case LOGGING_IN:
				loggedIn = false;
				api.cancelAll();
				break;
			case LOGIN_SCREEN:
				api.cancelAll();
				// The logout Reading: still permitted, as loggedIn only clears below
				sendReadings(xpTracker.takeChanged(Instant.now()), Set.of());
				loggedIn = false;
				rsn = null;
				connection = ConnectionStatus.CONNECTING;
				resetReadings();
				break;
			default:
				return;
		}
		refreshPanel();
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		// The local player's name is not set on the LOGGED_IN tick itself
		if (rsn == null)
		{
			Player player = client.getLocalPlayer();
			if (player == null || player.getName() == null)
			{
				return;
			}
			accountHash = client.getAccountHash();
			rsn = player.getName();
			refreshPanel();
		}
		sendDueReadings(Instant.now());
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (mayTransmit())
		{
			xpTracker.observe(skillSlug(event.getSkill()), event.getXp());
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!FriendscapeConfig.GROUP.equals(event.getGroup()) || !FriendscapeConfig.SEND_DATA.equals(event.getKey()))
		{
			return;
		}
		if (config.sendData())
		{
			checkHealth();
		}
		else
		{
			api.cancelAll();
			connection = ConnectionStatus.CONNECTING;
			resetReadings();
		}
		refreshPanel();
	}

	@Schedule(period = 60, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void retryUnreachable()
	{
		if (connection == ConnectionStatus.UNREACHABLE)
		{
			checkHealth();
		}
	}

	private void handleLogin()
	{
		// LOGGED_IN also fires after every loading screen, so only ping when the connection is unknown
		boolean wasLoggedIn = loggedIn;
		loggedIn = true;
		worldTracked = WorldFilter.isTracked(client.getWorldType());
		if (!wasLoggedIn || connection != ConnectionStatus.CONNECTED)
		{
			checkHealth();
		}
	}

	/**
	 * XP Readings (SPEC 5.2): every skill at login and at an Event's start or end, then changes once
	 * a minute. The ones at a start or end are marked as such.
	 */
	private void sendDueReadings(Instant now)
	{
		Instant moment = momentReadingAt.get();
		ReadingsResponse last = lastResponse;
		// Compare-and-set: a retry an answer schedules meanwhile must not be lost
		if (moment != null && !now.isBefore(moment) && momentReadingAt.compareAndSet(moment, null))
		{
			sendAllSkills = true;
			pendingMoments = last == null ? Set.of() : last.momentsDue(now);
		}
		if (!mayTransmit())
		{
			return;
		}
		if (sendAllSkills)
		{
			Map<String, Long> skills = currentSkills();
			if (skills == null)
			{
				return;
			}
			sendAllSkills = false;
			Set<Moment> moments = pendingMoments;
			pendingMoments = Set.of();
			sendReadings(xpTracker.takeAll(skills, now), moments);
		}
		else if (xpTracker.due(now))
		{
			sendReadings(xpTracker.takeChanged(now), Set.of());
		}
	}

	/** Every skill's XP by slug, or null while the client has not loaded stats yet. */
	private Map<String, Long> currentSkills()
	{
		if (client.getOverallExperience() <= 0)
		{
			return null;
		}
		Map<String, Long> skills = new HashMap<>();
		for (Skill skill : Skill.values())
		{
			String slug = skillSlug(skill);
			// Overall is the sum, which XpTracker adds; its enum constant is deprecated
			if (!XpTracker.OVERALL.equals(slug))
			{
				skills.put(slug, (long) client.getSkillExperience(skill));
			}
		}
		return skills;
	}

	private void sendReadings(Map<String, Long> xp, Set<Moment> moments)
	{
		String name = rsn;
		if (xp.isEmpty() || name == null)
		{
			return;
		}
		api.sendReadings(name, accountHash, xp, moments, response ->
		{
			// Per-Event statuses feed the cards (ticket 014); for now only start and end Readings use them
			lastResponse = response;
			Instant next = response.nextMomentReading(Instant.now());
			if (next != null)
			{
				momentReadingAt.set(next);
			}
		});
	}

	private void resetReadings()
	{
		xpTracker.reset();
		sendAllSkills = true;
		pendingMoments = Set.of();
		momentReadingAt.set(null);
		lastResponse = null;
	}

	/** The server's key for a skill, e.g. {@code runecraft}. */
	private static String skillSlug(Skill skill)
	{
		return skill.getName().toLowerCase(Locale.ROOT);
	}

	private void checkHealth()
	{
		if (!mayTransmit())
		{
			return;
		}
		connection = ConnectionStatus.CONNECTING;
		refreshPanel();
		api.checkHealth(up ->
		{
			connection = up ? ConnectionStatus.CONNECTED : ConnectionStatus.UNREACHABLE;
			refreshPanel();
		});
	}

	private boolean mayTransmit()
	{
		return config.sendData() && loggedIn && worldTracked;
	}

	private void setSending(boolean on)
	{
		configManager.setConfiguration(FriendscapeConfig.GROUP, FriendscapeConfig.SEND_DATA, on);
	}

	private ConnectionStatus status()
	{
		if (!config.sendData())
		{
			return ConnectionStatus.OFF;
		}
		if (!loggedIn)
		{
			return ConnectionStatus.LOGGED_OUT;
		}
		if (!worldTracked)
		{
			return ConnectionStatus.WORLD_NOT_TRACKED;
		}
		return connection;
	}

	private void refreshPanel()
	{
		PanelState state = PanelState.builder()
			.sending(config.sendData())
			.rsn(rsn)
			.status(status())
			.build();
		FriendscapePanel target = panel;
		if (target != null)
		{
			SwingUtilities.invokeLater(() -> target.update(state));
		}
	}

	private static HttpUrl apiBase()
	{
		String override = System.getProperty(API_BASE_PROPERTY);
		if (override != null)
		{
			HttpUrl url = HttpUrl.parse(override.endsWith("/") ? override : override + "/");
			if (url != null)
			{
				log.info("Using API base {}", url);
				return url;
			}
			log.warn("Ignoring invalid {}: {}", API_BASE_PROPERTY, override);
		}
		return HttpUrl.get(DEFAULT_API_BASE);
	}
}
