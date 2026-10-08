package com.friendscape;

import java.util.EnumSet;
import java.util.Set;
import net.runelite.api.WorldType;

/**
 * Decides whether progress on a world counts for Events. Leagues and other special worlds run on
 * separate characters or temporary progress, so nothing from them is ever sent.
 */
final class WorldFilter
{
	private static final Set<WorldType> EXCLUDED = EnumSet.of(
		WorldType.SEASONAL,
		WorldType.DEADMAN,
		WorldType.FRESH_START_WORLD,
		WorldType.TOURNAMENT_WORLD,
		WorldType.BETA_WORLD,
		WorldType.NOSAVE_MODE,
		WorldType.QUEST_SPEEDRUNNING,
		WorldType.PVP_ARENA,
		WorldType.LAST_MAN_STANDING
	);

	private WorldFilter()
	{
	}

	static boolean isTracked(Set<WorldType> worldTypes)
	{
		if (worldTypes == null)
		{
			return false;
		}
		for (WorldType type : worldTypes)
		{
			if (EXCLUDED.contains(type))
			{
				return false;
			}
		}
		return true;
	}
}
