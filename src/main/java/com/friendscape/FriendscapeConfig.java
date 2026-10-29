package com.friendscape;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(FriendscapeConfig.GROUP)
public interface FriendscapeConfig extends Config
{
	String GROUP = "friendscape";
	String SEND_DATA = "sendData";
	/** Numbers of Events left on this install (see {@link StoredEventNumbers}); never sent anywhere. */
	String LEFT_EVENTS = "leftEvents";
	/** Numbers of Events whose mini board the player closed (see {@link StoredEventNumbers}). */
	String CLOSED_BOARDS = "closedBoards";
	/** Numbers of Live Events left out of the standings overlay (see {@link StoredEventNumbers}). */
	String HIDDEN_STANDINGS = "hiddenStandings";
	/** Numbers of Live Events whose Codeword overlay the player hid (see {@link StoredEventNumbers}). */
	String HIDDEN_CODEWORDS = "hiddenCodewords";
	/** The panel shows cards under a header per Group (SPEC 10.3), the default; off is one flat list. */
	String BY_GROUP = "byGroup";
	/** Group numbers whose panel header the player collapsed (see {@link CardGroups#parseCollapsed}). */
	String COLLAPSED_GROUPS = "collapsedGroups";
	/** Master switch for every Codeword overlay. */
	String CODEWORD_OVERLAYS = "codewordOverlays";
	String CODEWORD_TIMESTAMP = "codewordTimestamp";
	String CODEWORD_COLOR = "codewordColor";
	String TIMESTAMP_COLOR = "timestampColor";
	String KEEP_FINALIZED_DAYS = "keepFinalizedDays";
	/** The server stops listing a Finalized Event 7 days after Finalize, which caps Keep finalized Events. */
	int MAX_KEEP_FINALIZED_DAYS = 7;
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
		name = "Chat message when counted",
		description = "Print a chat message when a drop, collection log slot or pet counts for an Event",
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
		description = "Show a notification when something needs your attention, such as progress on hold",
		position = 3
	)
	default boolean notifyOnProblems()
	{
		return true;
	}

	// keyName predates the Codeword overlays; renaming it would turn the overlay back on for players who hid it
	@ConfigItem(
		keyName = "overlay",
		name = "Show standings overlay",
		description = "Show each Live Event with its status and your rank in game",
		position = 4
	)
	default boolean standingsOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = CODEWORD_OVERLAYS,
		name = "Show codeword overlays",
		description = "Show each Live bingo Event's Codeword in game, so screenshots for manual uploads carry it",
		position = 5
	)
	default boolean codewordOverlays()
	{
		return true;
	}

	@ConfigItem(
		keyName = CODEWORD_TIMESTAMP,
		name = "Show timestamp on codeword overlays",
		description = "Show the current time in UTC next to each Codeword",
		position = 6
	)
	default boolean codewordTimestamp()
	{
		return true;
	}

	@ConfigItem(
		keyName = CODEWORD_COLOR,
		name = "Codeword color",
		description = "The color of the Codeword on codeword overlays",
		position = 7
	)
	default Color codewordColor()
	{
		return new Color(0x00FF6A);
	}

	@ConfigItem(
		keyName = TIMESTAMP_COLOR,
		name = "Timestamp color",
		description = "The color of the timestamp on codeword overlays",
		position = 8
	)
	default Color timestampColor()
	{
		return Color.WHITE;
	}

	@Range(min = 0, max = MAX_KEEP_FINALIZED_DAYS)
	@ConfigItem(
		keyName = KEEP_FINALIZED_DAYS,
		name = "Keep finalized Events (days)",
		description = "How many days a Finalized Event's card stays in the panel. 0 removes it on Finalize",
		position = 9
	)
	default int keepFinalizedDays()
	{
		return 1;
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

	@ConfigItem(
		keyName = CLOSED_BOARDS,
		name = "",
		description = "",
		hidden = true
	)
	default String closedBoards()
	{
		return "";
	}

	@ConfigItem(
		keyName = HIDDEN_STANDINGS,
		name = "",
		description = "",
		hidden = true
	)
	default String hiddenStandings()
	{
		return "";
	}

	@ConfigItem(
		keyName = HIDDEN_CODEWORDS,
		name = "",
		description = "",
		hidden = true
	)
	default String hiddenCodewords()
	{
		return "";
	}

	@ConfigItem(
		keyName = BY_GROUP,
		name = "",
		description = "",
		hidden = true
	)
	default boolean byGroup()
	{
		return true;
	}

	@ConfigItem(
		keyName = COLLAPSED_GROUPS,
		name = "",
		description = "",
		hidden = true
	)
	default String collapsedGroups()
	{
		return "";
	}
}
