package com.friendscape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the Tiles and Tiers the player's Teams completed since the last discovery (SPEC 10.4), for
 * a chat line and a toast each. A board seen for the first time this session only sets the mark,
 * so logging in never replays old completions. Safe to call from OkHttp threads.
 */
class TeamCompletions
{
	/** The Tier each Team last had per position, by Event number and Team id. */
	private final Map<String, int[]> seen = new HashMap<>();

	synchronized List<String> check(DiscoveryResponse discovery, Set<Integer> left)
	{
		List<String> told = new ArrayList<>();
		for (DiscoveryResponse.DiscoveredEvent event : discovery.events())
		{
			DiscoveryResponse.Board board = event.getBoard();
			if (board == null || left.contains(event.getNumber()))
			{
				continue;
			}
			for (DiscoveryResponse.BoardTeam team : board.mine())
			{
				int[] tiers = new int[board.side() * board.side()];
				for (int position = 0; position < tiers.length; position++)
				{
					tiers[position] = team.tier(position);
				}
				int[] before = seen.put(event.getNumber() + ":" + team.getTeamId(), tiers);
				if (before == null || before.length != tiers.length)
				{
					continue;
				}
				for (int position = 0; position < tiers.length; position++)
				{
					DiscoveryResponse.BoardTile tile = board.tile(position + 1);
					if (tile != null && tiers[position] > before[position])
					{
						told.add(text(event, team, tile, tiers[position]));
					}
				}
			}
		}
		return told;
	}

	private static String text(DiscoveryResponse.DiscoveredEvent event, DiscoveryResponse.BoardTeam team,
		DiscoveryResponse.BoardTile tile, int tier)
	{
		String where = "Tile " + tile.getNumber() + " (" + tile.getName() + ") in " + event.getName() + ".";
		return tile.getTiers() > 1
			? team.getName() + " reached Tier " + tier + " of " + tile.getTiers() + " on " + where
			: team.getName() + " completed " + where;
	}

	/** A new session: boards are seen afresh. */
	synchronized void reset()
	{
		seen.clear();
	}
}
