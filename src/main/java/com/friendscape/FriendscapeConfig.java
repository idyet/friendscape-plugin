package com.friendscape;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(FriendscapeConfig.GROUP)
public interface FriendscapeConfig extends Config
{
	String GROUP = "friendscape";
	String SEND_DATA = "sendData";
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
}
