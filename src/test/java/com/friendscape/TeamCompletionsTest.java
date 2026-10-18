package com.friendscape;

import com.google.gson.Gson;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TeamCompletionsTest
{
	private static final Gson GSON = new Gson();

	private final TeamCompletions completions = new TeamCompletions();

	/**
	 * A Live bingo Event with Tile 1 (one Tier) and Tile 2 (three Tiers); Red is the player's Team,
	 * Blue a rival. Each tier array gives the Tier reached per position, 0 for none.
	 */
	private static DiscoveryResponse discovery(int[] red, int[] blue)
	{
		String json = "{\"events\":[{\"number\":123456,\"name\":\"Autumn bingo\",\"format\":\"bingo\","
			+ "\"phase\":\"live\",\"entry\":\"on_roster\",\"board\":{\"size\":2,\"tiles\":["
			+ "{\"number\":1,\"name\":\"Blood shards\",\"tiers\":1},"
			+ "{\"number\":2,\"name\":\"Vorkath\",\"tiers\":3}],\"teams\":["
			+ team(1, "Red", true, red) + "," + team(2, "Blue", false, blue) + "]}}]}";
		return GSON.fromJson(json, DiscoveryResponse.class);
	}

	private static String team(int id, String name, boolean mine, int[] tiers)
	{
		StringBuilder tiles = new StringBuilder();
		for (int i = 0; i < 4; i++)
		{
			int tier = i < tiers.length ? tiers[i] : 0;
			tiles.append(i == 0 ? "" : ",").append("{\"state\":\"open\",\"tier\":")
				.append(tier == 0 ? "null" : tier).append(",\"fill\":0}");
		}
		return "{\"teamId\":" + id + ",\"name\":\"" + name + "\",\"rank\":1,\"of\":2,\"points\":0,"
			+ "\"mine\":" + mine + ",\"tiles\":[" + tiles + "]}";
	}

	@Test
	public void saysNothingOnTheFirstLookAtABoard()
	{
		assertTrue(completions.check(discovery(new int[]{1, 2}, new int[]{}), Set.of()).isEmpty());
	}

	@Test
	public void namesATileTheTeamCompleted()
	{
		completions.check(discovery(new int[]{}, new int[]{}), Set.of());

		List<String> told = completions.check(discovery(new int[]{1}, new int[]{}), Set.of());

		assertEquals(List.of("Red completed Tile 1 (Blood shards) in Autumn bingo."), told);
	}

	@Test
	public void namesATierReachedOnATileWithSeveral()
	{
		completions.check(discovery(new int[]{0, 1}, new int[]{}), Set.of());

		List<String> told = completions.check(discovery(new int[]{0, 2}, new int[]{}), Set.of());

		assertEquals(List.of("Red reached Tier 2 of 3 on Tile 2 (Vorkath) in Autumn bingo."), told);
	}

	@Test
	public void namesOnlyTheTopTierWhenSeveralCameAtOnce()
	{
		completions.check(discovery(new int[]{}, new int[]{}), Set.of());

		List<String> told = completions.check(discovery(new int[]{0, 3}, new int[]{}), Set.of());

		assertEquals(List.of("Red reached Tier 3 of 3 on Tile 2 (Vorkath) in Autumn bingo."), told);
	}

	@Test
	public void ignoresRivalTeamsAndReversals()
	{
		completions.check(discovery(new int[]{1}, new int[]{}), Set.of());

		assertTrue(completions.check(discovery(new int[]{0}, new int[]{1, 3}), Set.of()).isEmpty());
	}

	@Test
	public void saysNothingForALeftEvent()
	{
		completions.check(discovery(new int[]{}, new int[]{}), Set.of());

		assertTrue(completions.check(discovery(new int[]{1}, new int[]{}), Set.of(123456)).isEmpty());
	}

	@Test
	public void startsOverAfterAReset()
	{
		completions.check(discovery(new int[]{}, new int[]{}), Set.of());
		completions.reset();

		assertTrue(completions.check(discovery(new int[]{1}, new int[]{}), Set.of()).isEmpty());
	}
}
