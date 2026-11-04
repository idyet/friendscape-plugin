package com.friendscape;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spike for 186 check 7, never merged: logs every event that could mark a Hardcore death or the
 * Reset choice to client.log, each line prefixed {@code [capture]}. {@code ::mark <label>} splits steps.
 */
@PluginDescriptor(name = "Friendscape death capture", developerPlugin = true)
public class DeathCapturePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(DeathCapturePlugin.class);

	// Player-authored chat: never a signal, and other players' words stay out of the log
	private static final Set<ChatMessageType> PLAYER_CHAT = EnumSet.of(ChatMessageType.PUBLICCHAT,
		ChatMessageType.MODCHAT, ChatMessageType.PRIVATECHAT, ChatMessageType.PRIVATECHATOUT,
		ChatMessageType.MODPRIVATECHAT, ChatMessageType.FRIENDSCHAT, ChatMessageType.CLAN_CHAT,
		ChatMessageType.CLAN_GUEST_CHAT, ChatMessageType.CLAN_GIM_CHAT, ChatMessageType.AUTOTYPER,
		ChatMessageType.MODAUTOTYPER);

	private static final int MAX_CHILDREN = 512;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	private int[] lastVarps;
	private final Map<Integer, Integer> lastVarbits = new HashMap<>();
	private final Map<Integer, Integer> lastVarcInts = new HashMap<>();

	@Override
	protected void startUp()
	{
		lastVarps = null;
		lastVarbits.clear();
		lastVarcInts.clear();
		log.info("[capture] start");
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (event.getCommand().equals("mark"))
		{
			log.info("[capture] --- MARK {} ---", String.join(" ", event.getArguments()));
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		log.info("[capture] state {} world {} types {} hash {}", event.getGameState(), client.getWorld(),
			client.getWorldType(), client.getAccountHash());
		// Varps reload on login: start the diff afresh so the reload is not read as changes
		lastVarps = null;
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Player local = client.getLocalPlayer();
		if (event.getActor() == local)
		{
			log.info("[capture] local ActorDeath");
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!PLAYER_CHAT.contains(event.getType()))
		{
			log.info("[capture] chat {} '{}'", event.getType(), event.getMessage());
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() != -1)
		{
			Integer old = lastVarbits.put(event.getVarbitId(), event.getValue());
			log.info("[capture] varbit {} {} -> {}", event.getVarbitId(), old, event.getValue());
			return;
		}
		int varp = event.getVarpId();
		int[] varps = client.getVarps();
		if (lastVarps == null || lastVarps.length != varps.length)
		{
			lastVarps = varps.clone();
			log.info("[capture] varp {} ? -> {}", varp, event.getValue());
			return;
		}
		log.info("[capture] varp {} {} -> {}", varp, lastVarps[varp], event.getValue());
		lastVarps[varp] = varps[varp];
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		int value = client.getVarcIntValue(event.getIndex());
		Integer old = lastVarcInts.put(event.getIndex(), value);
		log.info("[capture] varc {} {} -> {}", event.getIndex(), old, value);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		int group = event.getGroupId();
		log.info("[capture] widget loaded {}", group);
		// Text is often filled by a script after load: read it once the tick settles
		clientThread.invokeLater(() -> dumpText(group));
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		log.info("[capture] widget closed {} modal {} unload {}", event.getGroupId(), event.getModalMode(),
			event.isUnload());
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		log.info("[capture] click '{}' '{}' action {} id {} param1 {} param0 {}", event.getMenuOption(),
			event.getMenuTarget(), event.getMenuAction(), event.getId(), event.getParam1(), event.getParam0());
	}

	private void dumpText(int group)
	{
		for (int child = 0; child < MAX_CHILDREN; child++)
		{
			Widget widget = client.getWidget(group, child);
			if (widget == null)
			{
				continue;
			}
			logText(group, child, -1, widget);
			Widget[] dynamic = widget.getDynamicChildren();
			for (int i = 0; dynamic != null && i < dynamic.length; i++)
			{
				logText(group, child, i, dynamic[i]);
			}
		}
	}

	private static void logText(int group, int child, int index, Widget widget)
	{
		String text = widget.getText();
		if (text != null && !text.isBlank())
		{
			log.info("[capture] text {}:{}{} '{}'", group, child, index < 0 ? "" : "[" + index + "]", text);
		}
	}
}
