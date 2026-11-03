package com.friendscape;

import java.util.EnumSet;
import java.util.Set;
import net.runelite.api.WorldType;

/**
 * Decides whether progress on a world counts for Events. Leagues and other special worlds run on
 * separate characters or temporary progress, so nothing from them is ever sent. Hardcore Main
 * worlds are tracked, and their Readings carry the World bit (SPEC 5.7).
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

	/** The World bit (SPEC 5.7): a Hardcore Main world, where a character has one life. */
	static boolean isHardcore(Set<WorldType> worldTypes)
	{
		return worldTypes != null && worldTypes.contains(WorldType.HARDCORE_MAIN);
	}
}
