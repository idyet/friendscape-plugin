package com.friendscape;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(FriendscapeConfig.GROUP)
public interface FriendscapeConfig extends Config
{
	String GROUP = "friendscape";
	String SEND_DATA = "sendData";
	/** Slugs of Events left on this install (see {@link LeftEvents}); never sent anywhere. */
	String LEFT_EVENTS = "leftEvents";
	String WARNING = "This plugin submits your username, account hash, drops, kill counts, XP and screenshots"
		+ " to a 3rd-party server (friendscape) not controlled or verified by the RuneLite Developers.";

	// Source of truth for all network traffic; the panel's "Turn on sending" flips this same key.
	@ConfigItem(
		keyName = SEND_DATA,
		name = "Send data to Friendscape",
		description = "Send your progress in Friendscape Events you are rostered in to the Friendscape server",
		warning = WARNING,
		position = 0
	)
	default boolean sendData()
	{
		return false;
	}

	@ConfigItem(
		keyName = "chatOnCounted",
		name = "Chat line when counted",
		description = "Print a chat line when a drop, collection log slot or pet counts for an Event",
		position = 1
	)
	default boolean chatOnCounted()
	{
		return true;
	}

	@ConfigItem(
		keyName = "toastOnCompletion",
		name = "Notify on Team completions",
		description = "Show a notification when your Team completes a Tile or reaches a Tier",
		position = 2
	)
	default boolean toastOnCompletion()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyOnProblems",
		name = "Notify on problems",
		description = "Use the RuneLite notifier when something needs your attention, such as progress on hold",
		position = 3
	)
	default boolean notifyOnProblems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlay",
		name = "Show overlay",
		description = "Show each Event with its status and your rank in game",
		position = 4
	)
	default boolean overlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = LEFT_EVENTS,
		name = "",
		description = "",
		hidden = true
	)
	default String leftEvents()
	{
		return "";
	}
}
