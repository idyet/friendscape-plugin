package com.friendscape;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.Notifier;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.FlashNotification;
import net.runelite.client.config.Notification;
import net.runelite.client.config.NotificationSound;
import net.runelite.client.config.RequestFocusType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "friendscape",
	description = "Tracks your progress in Friendscape Skill, Boss, or Bingo Events",
	tags = {"friendscape", "clan", "bingo", "sotw", "skill of the week", "botw", "boss of the week", "event", "competition"}
)
public class FriendscapePlugin extends Plugin
{
	/** Developer override for the API base, e.g. {@code -Dfriendscape.apiBase=http://localhost:3000/api/}. */
	static final String API_BASE_PROPERTY = "friendscape.apiBase";
	/** Dev host until the final release; switch to {@code https://www.friendscape.cc/api/} then. */
	private static final String DEFAULT_API_BASE = "https://dev.friendscape.cc/api/";
	/** The send queue (SPEC 10.5), under {@code .runelite/friendscape/}. */
	private static final File SUBMISSIONS_DIR = new File(new File(RuneLite.RUNELITE_DIR, "friendscape"), "submissions");
	/** A frame not drawn by then (client minimised, say) means the screenshot failed. */
	private static final long SCREENSHOT_TIMEOUT_SECONDS = 5;
	/** A Team completion's toast (SPEC 10.4): a tray message even while playing, with no sound. */
	private static final Notification COMPLETION_TOAST = new Notification()
		.withEnabled(true)
		.withInitialized(true)
		.withOverride(true)
		.withTray(true)
		.withTrayIconType(TrayIcon.MessageType.NONE)
		.withRequestFocus(RequestFocusType.OFF)
		.withSound(NotificationSound.OFF)
		.withFlash(FlashNotification.DISABLED)
		.withSendWhenFocused(true);

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

	@Inject
	private ItemManager itemManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private ScheduledExecutorService executor;

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
	private final TeamCompletions completions = new TeamCompletions();
	/** Fed on the client thread only; its watch is set there too, from any thread via invokeLater. */
	private volatile SubmissionDetector detector;
	/** Null until the queue opens on the executor, or for good if it cannot. */
	private volatile SubmissionSender sender;
	/**
	 * Bumped by every reset (logout, sending off): an answer to a call made before it is dropped, as
	 * cancelling cannot stop a callback already running.
	 */
	private final AtomicInteger session = new AtomicInteger();

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
		detector = new SubmissionDetector(itemManager::canonicalize, id -> itemManager.getItemComposition(id).getName());
		resetReadings();
		executor.execute(this::openSubmissions);
		panel = new FriendscapePanel(this::setSending, new FriendscapePanel.CardActions()
		{
			@Override
			public void leave(int number)
			{
				setLeft(number, true);
			}

			@Override
			public void rejoin(int number)
			{
				setLeft(number, false);
			}

			@Override
			public void openEventPage(int number)
			{
				LinkBrowser.browse(FriendscapeApi.eventPage(apiBase, number));
			}

			@Override
			public void openUploadPage(int number)
			{
				LinkBrowser.browse(FriendscapeApi.uploadPage(apiBase, number));
			}

			@Override
			public void report(int number)
			{
				String name = rsn;
				if (name == null)
				{
					return;
				}
				api.report(name, accountHash, number, message -> chat(message, ChatColorType.NORMAL));
			}

			@Override
			public void openBoard(int number, int teamNumber)
			{
				LinkBrowser.browse(FriendscapeApi.teamPage(apiBase, number, teamNumber));
			}

			@Override
			public void openTile(int number, int teamNumber, int tile)
			{
				LinkBrowser.browse(FriendscapeApi.tilePage(apiBase, number, teamNumber, tile));
			}

			@Override
			public void setBoardOpen(int number, boolean open)
			{
				Set<Integer> closed = new HashSet<>(StoredEventNumbers.parse(config.closedBoards()));
				if (open ? closed.remove(number) : closed.add(number))
				{
					configManager.setConfiguration(FriendscapeConfig.GROUP, FriendscapeConfig.CLOSED_BOARDS,
						StoredEventNumbers.format(closed));
				}
			}
		}, this::loadTileIcon);
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

	/** Opens the send queue, deleting files a previous run orphaned. Executor thread. */
	private void openSubmissions()
	{
		try
		{
			sender = new SubmissionSender(SubmissionQueue.open(SUBMISSIONS_DIR, gson), api, new SubmissionSender.Listener()
			{
				@Override
				public void tell(ProblemNotices.Notice notice)
				{
					FriendscapePlugin.this.tell(notice);
				}

				@Override
				public void queueChanged()
				{
					refreshPanel();
				}
			});
			refreshPanel();
		}
		catch (IOException e)
		{
			log.error("Cannot open the Submission queue in {}; drops will not be sent", SUBMISSIONS_DIR, e);
		}
	}

	@Override
	protected void shutDown()
	{
		cancelAll();
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(overlay);
		loggedIn = false;
		worldTracked = false;
		rsn = null;
		connection = ConnectionStatus.CONNECTING;
		resetReadings();
		notices.reset();
		completions.reset();
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
				cancelAll();
				break;
			case LOGIN_SCREEN:
				cancelAll();
				// The logout Reading: still permitted, as loggedIn only clears below
				if (readingsWanted(Instant.now()))
				{
					sendReadings(xpTracker.takeChanged(Instant.now()), Map.of(), Set.of());
				}
				loggedIn = false;
				rsn = null;
				connection = ConnectionStatus.CONNECTING;
				resetReadings();
				// A new session: problems may be told again, boards are seen afresh
				notices.reset();
				completions.reset();
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
		detector.tick();
		sendDueReadings(Instant.now());
	}

	/** NPC kills (SPEC 10.2). */
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		capture(detector.loot(event.getItems()));
	}

	/** Raids, chests, clues, minigames and pickpockets, from the core Loot Tracker (SPEC 10.2). */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (SubmissionDetector.countsLoot(event.getType()))
		{
			capture(detector.loot(event.getItems()));
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (mayTransmit())
		{
			xpTracker.observe(skillSlug(event.getSkill()), event.getXp());
		}
	}

	/** KC Readings (SPEC 5.2): sent on each kill-count, clue or rifts chat message, never queued. */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM
			&& type != ChatMessageType.FRIENDSCHATNOTIFICATION)
		{
			return;
		}
		if (type == ChatMessageType.GAMEMESSAGE)
		{
			capture(detector.chat(event.getMessage()));
		}
		KcMessages.Kc kc = KcMessages.parse(event.getMessage());
		if (kc != null && mayTransmit() && readingsWanted(Instant.now()))
		{
			sendReadings(Map.of(), Map.of(kc.getName(), kc.getCount()), Set.of());
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
			// A Rejoin may bring back an Event's start or end Reading: schedule it now, not at the next poll
			discover();
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
			cancelAll();
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

	/** Retries queued Submissions as their backoff allows (SPEC 10.5). */
	@Schedule(period = 10, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void flushSubmissions()
	{
		SubmissionSender target = sender;
		if (target == null || rsn == null || !mayTransmit())
		{
			return;
		}
		DiscoveryResponse found = discovery;
		SubmissionSender.Context context = found == null
			? new SubmissionSender.Context(Set.of(), Map.of(), config.chatOnCounted())
			: new SubmissionSender.Context(found.muted(left()), found.eventNames(), config.chatOnCounted());
		target.flush(accountHash, context, Instant.now());
	}

	/**
	 * Captures watched drops (SPEC 5.4): one screenshot of the next frame for them all, then each
	 * into the send queue. A failed screenshot still sends them, as needs evidence, and says so
	 * loudly. Client thread.
	 */
	private void capture(List<SubmissionDetector.Drop> drops)
	{
		String name = rsn;
		if (drops.isEmpty() || name == null || !mayTransmit())
		{
			return;
		}
		String capturedAt = Instant.now().toString();
		List<QueuedSubmission> submissions = new ArrayList<>();
		for (SubmissionDetector.Drop drop : drops)
		{
			submissions.add(new QueuedSubmission(UUID.randomUUID().toString(), Long.toString(accountHash), name,
				drop.getKind().wire(), drop.getItemId(), drop.getName(),
				drop.getQuantity(), capturedAt, new ArrayList<>(drop.getEvents())));
		}
		AtomicBoolean settled = new AtomicBoolean();
		drawManager.requestNextFrameListener(image ->
		{
			if (settled.compareAndSet(false, true))
			{
				BufferedImage frame = ImageUtil.bufferedImageFromImage(image);
				executor.execute(() -> queueCaptured(submissions, png(frame)));
			}
		});
		executor.schedule(() ->
		{
			if (settled.compareAndSet(false, true))
			{
				log.warn("No frame drawn for a screenshot within {} s", SCREENSHOT_TIMEOUT_SECONDS);
				queueCaptured(submissions, null);
			}
		}, SCREENSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	/** The frame as PNG, or null (logged) when it cannot be encoded. */
	private static byte[] png(BufferedImage frame)
	{
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (ImageIO.write(frame, "png", out))
			{
				return out.toByteArray();
			}
			log.warn("No PNG writer for the screenshot");
		}
		catch (IOException e)
		{
			log.warn("Could not encode the screenshot", e);
		}
		return null;
	}

	/** Queues captured drops and sends them. Executor thread. */
	private void queueCaptured(List<QueuedSubmission> submissions, byte[] png)
	{
		if (png == null)
		{
			for (QueuedSubmission submission : submissions)
			{
				tell(new ProblemNotices.Notice("Could not take a screenshot of " + submission.getName()
					+ ". Upload one on the event page so it counts.", true));
			}
		}
		SubmissionSender target = sender;
		if (target == null)
		{
			log.warn("Submission queue unavailable; {} drop(s) not sent", submissions.size());
			return;
		}
		try
		{
			target.add(submissions, png);
		}
		catch (IOException e)
		{
			log.error("Could not queue {} drop(s)", submissions.size(), e);
			for (QueuedSubmission submission : submissions)
			{
				tell(new ProblemNotices.Notice("Could not save " + submission.getName()
					+ " to send. Upload a screenshot on the event page to claim it.", true));
			}
			return;
		}
		flushSubmissions();
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
			sendReadings(xpTracker.takeAll(skills, now), Map.of(), moments);
		}
		else if (xpTracker.due(now))
		{
			sendReadings(xpTracker.takeChanged(now), Map.of(), Set.of());
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

	private void sendReadings(Map<String, Long> xp, Map<String, Long> kcChat, Set<Moment> moments)
	{
		String name = rsn;
		if ((xp.isEmpty() && kcChat.isEmpty()) || name == null)
		{
			return;
		}
		DiscoveryResponse found = discovery;
		Set<Integer> muted = found == null ? Set.of() : found.muted(left());
		int sentIn = session.get();
		api.sendReadings(name, accountHash, xp, kcChat, moments, muted, response ->
		{
			if (session.get() != sentIn)
			{
				return;
			}
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
		int sentIn = session.get();
		api.discover(name, accountHash, response ->
		{
			if (session.get() != sentIn)
			{
				return;
			}
			discovery = response;
			long hash = accountHash;
			WatchList watch = WatchList.of(response, left());
			clientThread.invokeLater(() -> detector.setWatch(watch));
			SubmissionSender target = sender;
			if (target != null)
			{
				executor.execute(() -> target.prune(hash, response, Instant.now()));
			}
			scheduleMomentReading(response.asReadings(left()));
			List<ProblemNotices.Notice> told = notices.check(response, left());
			for (ProblemNotices.Notice notice : told)
			{
				tell(notice);
			}
			for (String completed : completions.check(response, left()))
			{
				celebrate(completed);
			}
			refreshPanel();
		}, reachable ->
		{
			ConnectionStatus answered = reachable ? ConnectionStatus.CONNECTED : ConnectionStatus.UNREACHABLE;
			if (session.get() == sentIn && connection != answered)
			{
				connection = answered;
				refreshPanel();
			}
		});
	}

	/** A chat line, red with the notifier for a problem (SPEC 10.4). */
	private void tell(ProblemNotices.Notice notice)
	{
		chat(notice.getText(), notice.isProblem() ? ChatColorType.HIGHLIGHT : ChatColorType.NORMAL);
		if (notice.isProblem() && config.notifyOnProblems())
		{
			notifier.notify("Friendscape: " + notice.getText());
		}
	}

	private void chat(String text, ChatColorType color)
	{
		String message = new ChatMessageBuilder()
			.append(color)
			.append("[Friendscape] " + text)
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	/** A Tile or Tier the player's Team completed (SPEC 10.4): a chat line, and a toast with no sound. */
	private void celebrate(String text)
	{
		chat(text, ChatColorType.NORMAL);
		if (config.toastOnCompletion())
		{
			notifier.notify(COMPLETION_TOAST, "Friendscape: " + text);
		}
	}

	/**
	 * A mini board square's picture (SPEC 10.3): the item sprite, the boss icon RuneLite's hiscores
	 * use, or the skill icon. Nothing for a name this client does not know, so the Tile number shows.
	 */
	private void loadTileIcon(DiscoveryResponse.TileIcon icon, Consumer<BufferedImage> onLoaded)
	{
		if (icon.getKind() == null)
		{
			return;
		}
		switch (icon.getKind())
		{
			case DiscoveryResponse.TileIcon.ITEM:
				AsyncBufferedImage image = itemManager.getImage(icon.getItemId());
				onLoaded.accept(image);
				image.onLoaded(() -> onLoaded.accept(image));
				break;
			case DiscoveryResponse.TileIcon.KC:
				for (HiscoreSkill skill : HiscoreSkill.values())
				{
					if (skill.getName().equalsIgnoreCase(icon.getName()) && skill.getSpriteId() > 0)
					{
						spriteManager.getSpriteAsync(skill.getSpriteId(), 0, onLoaded);
						return;
					}
				}
				log.debug("No hiscores icon for {}", icon.getName());
				break;
			case DiscoveryResponse.TileIcon.XP:
				for (Skill skill : Skill.values())
				{
					if (skillSlug(skill).equals(icon.getSkill()))
					{
						onLoaded.accept(skillIconManager.getSkillImage(skill, true));
						return;
					}
				}
				log.debug("No skill icon for {}", icon.getSkill());
				break;
			default:
				break;
		}
	}

	/** Events left on this install (SPEC 10.3): nothing more is sent for them. */
	private Set<Integer> left()
	{
		return StoredEventNumbers.parse(config.leftEvents());
	}

	private void setLeft(int number, boolean leave)
	{
		Set<Integer> left = new HashSet<>(left());
		boolean changed = leave ? left.add(number) : left.remove(number);
		if (!changed)
		{
			return;
		}
		if (!leave)
		{
			// Back in: the next Reading carries every skill, as at login
			sendAllSkills = true;
		}
		configManager.setConfiguration(FriendscapeConfig.GROUP, FriendscapeConfig.LEFT_EVENTS, StoredEventNumbers.format(left));
	}

	/** Cancels every in-flight call; queued Submissions they carried are due again. */
	private void cancelAll()
	{
		api.cancelAll();
		SubmissionSender target = sender;
		if (target != null)
		{
			target.cancelled();
		}
	}

	private void resetReadings()
	{
		session.incrementAndGet();
		xpTracker.reset();
		sendAllSkills = true;
		pendingMoments = Set.of();
		momentReadingAt.set(null);
		lastResponse = null;
		discovery = null;
		// Nothing is watched until discovery answers again
		SubmissionDetector watching = detector;
		if (watching != null)
		{
			clientThread.invokeLater(() -> watching.setWatch(WatchList.EMPTY));
		}
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
		SubmissionSender queued = sender;
		List<EventCard> cards = found == null ? null : found.cards(left(),
			queued == null ? Map.of() : queued.queued(accountHash), Instant.now(), ZoneId.systemDefault());
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
			.closedBoards(StoredEventNumbers.parse(config.closedBoards()))
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
