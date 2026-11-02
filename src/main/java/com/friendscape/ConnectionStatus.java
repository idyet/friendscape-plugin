package com.friendscape;

import java.awt.Color;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.client.ui.ColorScheme;

@Getter
@AllArgsConstructor
enum ConnectionStatus
{
	OFF("Sending is off", ColorScheme.LIGHT_GRAY_COLOR),
	LOGGED_OUT("Log in to start tracking", ColorScheme.LIGHT_GRAY_COLOR),
	WORLD_NOT_TRACKED("Not tracked on Leagues or special worlds", ColorScheme.PROGRESS_INPROGRESS_COLOR),
	CONNECTING("Connecting", ColorScheme.LIGHT_GRAY_COLOR),
	CONNECTED("Connected", ColorScheme.PROGRESS_COMPLETE_COLOR),
	UNREACHABLE("Server unreachable, retrying", ColorScheme.LIGHT_GRAY_COLOR),
	/** A DNS filter or router answered for the server: a problem the player can fix, not a passing outage. */
	BLOCKED("Blocked by your network", ColorScheme.PROGRESS_ERROR_COLOR);

	private final String text;
	private final Color color;
}
