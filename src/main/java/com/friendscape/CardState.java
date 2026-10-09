package com.friendscape;

import java.awt.Color;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.client.ui.ColorScheme;

/** What an Event card says about the player's part in it (SPEC 10.3), with its dot colour. */
@Getter
@AllArgsConstructor
enum CardState
{
	TRACKING(ColorScheme.PROGRESS_COMPLETE_COLOR),
	/** An identity mismatch is held: progress counts nowhere until it is settled. */
	ATTENTION(ColorScheme.PROGRESS_INPROGRESS_COLOR),
	/** Taken off the Roster mid-Event. */
	NOT_ON_ROSTER(new Color(0x4D9DE0)),
	/** Muted on this install; the server is never told. */
	LEFT(ColorScheme.MEDIUM_GRAY_COLOR),
	/** Published with no start and end set yet. */
	NOT_SCHEDULED(ColorScheme.LIGHT_GRAY_COLOR),
	ENDED(ColorScheme.LIGHT_GRAY_COLOR),
	FINAL(ColorScheme.BRAND_ORANGE);

	private final Color color;
}
