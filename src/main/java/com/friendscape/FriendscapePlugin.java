package com.friendscape;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.temporal.ChronoUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
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
	name = "Friendscape Events",
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

	private FriendscapeApi api;
	private FriendscapePanel panel;
	private NavigationButton navButton;

	// Written on the client thread, read by the API gate on OkHttp threads and by the scheduler.
	private volatile boolean loggedIn;
	private volatile boolean worldTracked;
	private volatile String rsn;
	private volatile ConnectionStatus connection = ConnectionStatus.CONNECTING;

	@Provides
	FriendscapeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FriendscapeConfig.class);
	}

	@Override
	protected void startUp()
	{
		api = new FriendscapeApi(okHttpClient, apiBase(), this::mayTransmit);
		panel = new FriendscapePanel(this::setSending);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Friendscape Events")
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
				loggedIn = false;
				rsn = null;
				connection = ConnectionStatus.CONNECTING;
				api.cancelAll();
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
		if (rsn != null)
		{
			return;
		}
		Player player = client.getLocalPlayer();
		if (player != null && player.getName() != null)
		{
			rsn = player.getName();
			refreshPanel();
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
