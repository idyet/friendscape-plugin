package com.friendscape;

import java.util.EnumSet;
import net.runelite.api.WorldType;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WorldFilterTest
{
	@Test
	public void tracksNormalWorlds()
	{
		assertTrue(WorldFilter.isTracked(EnumSet.noneOf(WorldType.class)));
		assertTrue(WorldFilter.isTracked(EnumSet.of(WorldType.MEMBERS)));
		assertTrue(WorldFilter.isTracked(EnumSet.of(WorldType.MEMBERS, WorldType.SKILL_TOTAL)));
		assertTrue(WorldFilter.isTracked(EnumSet.of(WorldType.MEMBERS, WorldType.PVP, WorldType.HIGH_RISK)));
	}

	@Test
	public void excludesLeagues()
	{
		assertFalse(WorldFilter.isTracked(EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL)));
	}

	@Test
	public void excludesSpecialWorlds()
	{
		for (WorldType type : new WorldType[]{
			WorldType.DEADMAN,
			WorldType.FRESH_START_WORLD,
			WorldType.TOURNAMENT_WORLD,
			WorldType.BETA_WORLD,
			WorldType.NOSAVE_MODE,
			WorldType.QUEST_SPEEDRUNNING,
			WorldType.PVP_ARENA,
			WorldType.LAST_MAN_STANDING,
		})
		{
			assertFalse(type.name(), WorldFilter.isTracked(EnumSet.of(WorldType.MEMBERS, type)));
		}
	}

	@Test
	public void excludesUnknownWorld()
	{
		assertFalse(WorldFilter.isTracked(null));
	}
}
