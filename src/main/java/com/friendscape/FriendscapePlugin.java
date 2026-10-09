package com.friendscape;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
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

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private Notifier notifier;

	@Inject
	private ChatMessageManager chatMessageManager;

	private HttpUrl apiBase;
	private FriendscapeApi api;
	private FriendscapePanel panel;
	private FriendscapeOverlay overlay;
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
	/** The last answer to discovery, null until one comes; the cards come from it. OkHttp threads. */
	private volatile DiscoveryResponse discovery;
	private final ProblemNotices notices = new ProblemNotices();

	@Provides
	FriendscapeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FriendscapeConfig.class);
	}

	@Override
	protected void startUp()
	{
		apiBase = apiBase();
		api = new FriendscapeApi(okHttpClient, gson, apiBase, this::mayTransmit);
		resetReadings();
		panel = new FriendscapePanel(this::setSending, new FriendscapePanel.CardActions()
		{
			@Override
			public void leave(String slug)
			{
				setLeft(slug, true);
			}

			@Override
			public void rejoin(String slug)
			{
				setLeft(slug, false);
			}

			@Override
			public void openEventPage(String slug)
			{
				LinkBrowser.browse(FriendscapeApi.eventPage(apiBase, slug));
			}
		});
		panel.setOnOpen(this::discover);
		overlay = new FriendscapeOverlay(this, () -> config.overlay() && mayTransmit());
		overlayManager.add(overlay);

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
		overlayManager.remove(overlay);
		loggedIn = false;
		worldTracked = false;
		rsn = null;
		connection = ConnectionStatus.CONNECTING;
		resetReadings();
		notices.reset();
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
				if (readingsWanted(Instant.now()))
				{
					sendReadings(xpTracker.takeChanged(Instant.now()), Set.of());
				}
				loggedIn = false;
				rsn = null;
				connection = ConnectionStatus.CONNECTING;
				resetReadings();
				// A new session: problems may be told again
				notices.reset();
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
			discover();
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
		if (!FriendscapeConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if (FriendscapeConfig.LEFT_EVENTS.equals(event.getKey()))
		{
			refreshPanel();
			return;
		}
		if (!FriendscapeConfig.SEND_DATA.equals(event.getKey()))
		{
			return;
		}
		if (config.sendData())
		{
			checkHealth();
			discover();
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

	/** Cards and standings stay fresh while logged in (SPEC 10.3). */
	@Schedule(period = 60, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void pollDiscovery()
	{
		discover();
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
		DiscoveryResponse found = discovery;
		if (moment != null && !now.isBefore(moment) && momentReadingAt.compareAndSet(moment, null))
		{
			sendAllSkills = true;
			// Either may know of the moment: a Reading's answer of an end, discovery of a new start.
			// An extra mark is harmless: the server applies one only within a minute of the moment.
			Set<Moment> due = EnumSet.noneOf(Moment.class);
			if (last != null)
			{
				due.addAll(last.momentsDue(now));
			}
			if (found != null)
			{
				due.addAll(found.asReadings(left()).momentsDue(now));
			}
			pendingMoments = due;
		}
		if (!mayTransmit() || !readingsWanted(now))
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
		DiscoveryResponse found = discovery;
		Set<String> muted = found == null ? Set.of() : found.muted(left());
		api.sendReadings(name, accountHash, xp, moments, muted, response ->
		{
			lastResponse = response;
			scheduleMomentReading(response.without(left()));
			// A status the cards do not show yet (held, removed, ended): refresh them now
			DiscoveryResponse shown = discovery;
			if (shown == null || !shown.asReadings(Set.of()).statuses().equals(response.statuses()))
			{
				discover();
			}
		});
	}

	/** Brings the next start or end Reading forward to the one {@code known} expects, if earlier. */
	private void scheduleMomentReading(ReadingsResponse known)
	{
		Instant next = known.nextMomentReading(Instant.now());
		if (next != null)
		{
			momentReadingAt.accumulateAndGet(next, (current, proposed) ->
				current == null || proposed.isBefore(current) ? proposed : current);
		}
	}

	/**
	 * Whether a Reading could count anywhere. Waits for discovery: a Reading sent before it answers
	 * could be for an Event left on this install.
	 */
	private boolean readingsWanted(Instant now)
	{
		DiscoveryResponse found = discovery;
		return found != null && found.wantsReadings(left(), now);
	}

	/**
	 * Fetches the cards: on login, on panel open, when sending turns on and every minute. Also reports
	 * whether the server is reachable, which the header dot shows.
	 */
	private void discover()
	{
		String name = rsn;
		if (name == null || !mayTransmit())
		{
			return;
		}
		api.discover(name, accountHash, response ->
		{
			discovery = response;
			scheduleMomentReading(response.asReadings(left()));
			List<ProblemNotices.Notice> told = notices.check(response, left());
			for (ProblemNotices.Notice notice : told)
			{
				tell(notice);
			}
			refreshPanel();
		}, up ->
		{
			ConnectionStatus now = up ? ConnectionStatus.CONNECTED : ConnectionStatus.UNREACHABLE;
			if (connection != now)
			{
				connection = now;
				refreshPanel();
			}
		});
	}

	/** A chat line, red with the notifier for a problem (SPEC 10.4). */
	private void tell(ProblemNotices.Notice notice)
	{
		String message = new ChatMessageBuilder()
			.append(notice.isProblem() ? ChatColorType.HIGHLIGHT : ChatColorType.NORMAL)
			.append("[Friendscape] " + notice.getText())
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message)
			.build());
		if (notice.isProblem() && config.notifyOnProblems())
		{
			notifier.notify("Friendscape: " + notice.getText());
		}
	}

	/** Events left on this install (SPEC 10.3): nothing more is sent for them. */
	private Set<String> left()
	{
		return LeftEvents.parse(config.leftEvents());
	}

	private void setLeft(String slug, boolean leave)
	{
		Set<String> left = new HashSet<>(left());
		boolean changed = leave ? left.add(slug) : left.remove(slug);
		if (!changed)
		{
			return;
		}
		if (!leave)
		{
			// Back in: the next Reading carries every skill, as at login
			sendAllSkills = true;
		}
		configManager.setConfiguration(FriendscapeConfig.GROUP, FriendscapeConfig.LEFT_EVENTS, LeftEvents.format(left));
	}

	private void resetReadings()
	{
		xpTracker.reset();
		sendAllSkills = true;
		pendingMoments = Set.of();
		momentReadingAt.set(null);
		lastResponse = null;
		discovery = null;
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
		DiscoveryResponse found = discovery;
		List<EventCard> cards = found == null ? null : found.cards(left(), Instant.now(), ZoneId.systemDefault());
		FriendscapeOverlay shownOverlay = overlay;
		if (shownOverlay != null)
		{
			shownOverlay.setCards(cards == null ? List.of() : cards);
		}
		PanelState state = PanelState.builder()
			.sending(config.sendData())
			.rsn(rsn)
			.status(status())
			.cards(cards)
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
