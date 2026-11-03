package com.friendscape;

import com.google.gson.Gson;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class EventCardTest
{
	private static final Gson GSON = new Gson();
	private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

	/** One discovered Event: a Live Skill of the week unless {@code extra} overrides its fields. */
	private static DiscoveryResponse.DiscoveredEvent event(String extra)
	{
		String json = "{\"number\":123456,\"name\":\"Slayer week\",\"format\":\"sotw\",\"phase\":\"live\","
			+ "\"startsAt\":\"2026-11-01T18:00:00.000Z\",\"endsAt\":\"2026-11-08T18:00:00.000Z\","
			+ "\"graceWindowSeconds\":3600,\"finalizedAt\":null,\"entry\":\"on_roster\","
			+ "\"teams\":[{\"name\":\"Team Bandos\",\"size\":3}],"
			+ "\"standing\":{\"gain\":3400,\"teams\":[{\"name\":\"Team Bandos\",\"solo\":false,\"rank\":2,\"of\":4,\"gain\":12340}]}"
			+ (extra.isEmpty() ? "" : "," + extra) + "}";
		return GSON.fromJson(json, DiscoveryResponse.DiscoveredEvent.class);
	}

	private static EventCard card(String extra)
	{
		return EventCard.of(event(extra), false, EventCard.Queued.NONE, ZoneOffset.UTC);
	}

	@Test
	public void tracksALiveEventAndShowsTheTeamStandingAndOwnGain()
	{
		EventCard card = card("");

		assertEquals(123456, card.getNumber());
		assertEquals(CardState.TRACKING, card.getState());
		assertEquals("Tracking", card.getDetail());
		assertEquals(List.of("Team Bandos: 2nd of 4, +12,340 xp", "+3400 xp since start"), card.getStanding());
	}

	@Test
	public void isLiveOnlyWhileTheEventIsLiveAndStillSending()
	{
		assertTrue(card("").isLive());
		assertTrue(card("\"entry\":\"held\"").isLive());
		assertFalse(card("\"phase\":\"scheduled\"").isLive());
		assertFalse(card("\"phase\":\"ended\"").isLive());
		assertFalse(card("\"entry\":\"removed\"").isLive());
		assertFalse(EventCard.of(event(""), true, EventCard.Queued.NONE, ZoneOffset.UTC).isLive());
	}

	@Test
	public void carriesTheCodewordOnlyWhileLiveAndStillSending()
	{
		String codeword = "\"codeword\":\"Mithril\"";
		assertEquals("Mithril", card(codeword).getCodeword());
		assertEquals("Mithril", card(codeword + ",\"entry\":\"held\"").getCodeword());
		assertNull(card("").getCodeword());
		assertNull(card("\"codeword\":null").getCodeword());
		assertNull(card(codeword + ",\"phase\":\"ended\"").getCodeword());
		assertNull(card(codeword + ",\"entry\":\"removed\"").getCodeword());
		assertNull(EventCard.of(event(codeword), true, EventCard.Queued.NONE, ZoneOffset.UTC).getCodeword());
	}

	@Test
	public void showsASoloStandingAsRankAndGainInKcForABossOfTheWeek()
	{
		EventCard card = card("\"format\":\"botw\",\"standing\":{\"gain\":51,"
			+ "\"teams\":[{\"name\":\"Iron Man\",\"solo\":true,\"rank\":1,\"of\":12,\"gain\":51}]}");

		assertEquals(List.of("1st of 12, +51 kc"), card.getStanding());
	}

	@Test
	public void listsEveryTeamOfASharedMember()
	{
		EventCard card = card("\"standing\":{\"gain\":7,\"teams\":["
			+ "{\"name\":\"Zamorak\",\"solo\":false,\"rank\":1,\"of\":3,\"gain\":8},"
			+ "{\"name\":\"Bandos\",\"solo\":false,\"rank\":3,\"of\":3,\"gain\":7}]}");

		assertEquals(List.of("Zamorak: 1st of 3, +8 xp", "Bandos: 3rd of 3, +7 xp", "+7 xp since start"),
			card.getStanding());
	}

	/** A Live Event whose player gained {@code gain} in {@code format} on Team Bandos. */
	private static EventCard gained(String format, long gain)
	{
		return card("\"format\":\"" + format + "\",\"standing\":{\"gain\":" + gain + ",\"teams\":["
			+ "{\"name\":\"Team Bandos\",\"solo\":false,\"rank\":2,\"of\":4,\"gain\":12340}]}");
	}

	@Test
	public void showsTheOwnXpGainInRuneScapeShortFormWithTheFullFigureInTheTooltip()
	{
		assertEquals("+3400 xp since start", gained("sotw", 3400).getStanding().get(1));
		assertEquals("+3,400 xp", gained("sotw", 3400).getGainTooltip());
		assertEquals("+34K xp since start", gained("sotw", 34_000).getStanding().get(1));
		assertEquals("+150.9K xp since start", gained("sotw", 150_987).getStanding().get(1));
		assertEquals("+150,987 xp", gained("sotw", 150_987).getGainTooltip());
		assertEquals("+13M xp since start", gained("sotw", 13_034_431).getStanding().get(1));
		assertEquals("+13,034,431 xp", gained("sotw", 13_034_431).getGainTooltip());
	}

	@Test
	public void clampsAnXpGainBeyondAnInt()
	{
		EventCard card = gained("sotw", 3_000_000_000L);

		assertEquals("+2.1B xp since start", card.getStanding().get(1));
		assertEquals("+3,000,000,000 xp", card.getGainTooltip());
	}

	@Test
	public void keepsTheFullFigureForAKcGain()
	{
		EventCard card = gained("botw", 150_987);

		assertEquals("+150,987 kc since start", card.getStanding().get(1));
		assertNull(card.getGainTooltip());
	}

	private static final String DIED = "\"skull\":{\"kind\":\"died\",\"at\":\"2026-11-02T14:05:00.000Z\","
		+ "\"best\":1204000,\"current\":340000}";

	/** A Hardcore Main standing, on Team Bandos unless {@code solo}, with {@code skull} added. */
	private static EventCard hardcore(boolean solo, String skull)
	{
		String team = solo
			? "{\"name\":\"Iron Man\",\"solo\":true,\"rank\":1,\"of\":12,\"gain\":1204000}"
			: "{\"name\":\"Team Bandos\",\"solo\":false,\"rank\":2,\"of\":4,\"gain\":3000000}";
		return card("\"standing\":{\"gain\":1204000,\"teams\":[" + team + "]" + (skull.isEmpty() ? "" : "," + skull)
			+ "}");
	}

	@Test
	public void putsTheSkullOnTheOwnGainLineWithTheCurrentLifeInItsTooltip()
	{
		EventCard card = hardcore(false, DIED);

		assertEquals(List.of("Team Bandos: 2nd of 4, +3,000,000 xp", "+1.2M xp since start"),
			card.getStanding());
		assertEquals(1, card.getSkull().getLine());
		assertEquals("Died 2 Nov 14:05. Showing best life. Current life: 340K.", card.getSkull().getTooltip());
	}

	@Test
	public void putsTheSkullOnASoloStandingLine()
	{
		EventCard card = hardcore(true, DIED);

		assertEquals(List.of("1st of 12, +1,204,000 xp"), card.getStanding());
		assertEquals(0, card.getSkull().getLine());
	}

	@Test
	public void saysTransferredForATransferredCharacter()
	{
		EventCard card = hardcore(true, DIED.replace("died", "transferred"));

		assertEquals("Transferred 2 Nov 14:05. Final is best life. Current life: 340K.", card.getSkull().getTooltip());
	}

	@Test
	public void showsTheCurrentLifeInFullForKc()
	{
		EventCard card = card("\"format\":\"botw\",\"standing\":{\"gain\":51,"
			+ "\"teams\":[{\"name\":\"Iron Man\",\"solo\":true,\"rank\":1,\"of\":12,\"gain\":51}],"
			+ "\"skull\":{\"kind\":\"died\",\"at\":\"2026-11-02T14:05:00.000Z\",\"best\":51,\"current\":1204}}");

		assertEquals("Died 2 Nov 14:05. Showing best life. Current life: 1,204.", card.getSkull().getTooltip());
	}

	@Test
	public void leavesTheTimeOutOfAnUnreadableDeath()
	{
		EventCard card = hardcore(true, DIED.replace("2026-11-02T14:05:00.000Z", "soon"));

		assertEquals("Died. Showing best life. Current life: 340K.", card.getSkull().getTooltip());
	}

	@Test
	public void showsNoSkullWithoutTheSkullFields()
	{
		assertNull(hardcore(true, "").getSkull());
		assertNull(hardcore(true, "\"skull\":null").getSkull());
		assertNull(card("").getSkull());
		assertNull(card("\"phase\":\"scheduled\"," + "\"standing\":{\"gain\":0,\"teams\":[]," + DIED + "}").getSkull());
	}

	@Test
	public void hasNoGainTooltipWithoutAnOwnGainLine()
	{
		assertNull(card("\"standing\":{\"gain\":51,"
			+ "\"teams\":[{\"name\":\"Iron Man\",\"solo\":true,\"rank\":1,\"of\":12,\"gain\":51}]}").getGainTooltip());
		assertNull(card("\"phase\":\"scheduled\"").getGainTooltip());
	}

	@Test
	public void ordinalsHandleTheTeens()
	{
		assertEquals("11th", EventCard.ordinal(11));
		assertEquals("12th", EventCard.ordinal(12));
		assertEquals("13th", EventCard.ordinal(13));
		assertEquals("21st", EventCard.ordinal(21));
		assertEquals("22nd", EventCard.ordinal(22));
		assertEquals("103rd", EventCard.ordinal(103));
	}

	@Test
	public void showsWhenAScheduledEventStartsAndNoStandingYet()
	{
		EventCard card = card("\"phase\":\"scheduled\"");

		assertEquals(CardState.TRACKING, card.getState());
		assertEquals("Starts 1 Nov 18:00", card.getDetail());
		assertTrue(card.getStanding().isEmpty());
	}

	@Test
	public void saysNotScheduledYetForAPublishedEvent()
	{
		EventCard card = card("\"phase\":\"published\",\"startsAt\":null,\"endsAt\":null,\"standing\":null");

		assertEquals(CardState.NOT_SCHEDULED, card.getState());
		assertTrue(card.getStanding().isEmpty());
	}

	@Test
	public void needsAttentionWhileAnIdentityMismatchIsHeld()
	{
		EventCard held = card("\"entry\":\"held\"");
		assertEquals(CardState.ATTENTION, held.getState());
		assertTrue(held.isReport());
		assertFalse(card("\"entry\":\"on_roster\"").isReport());
	}

	@Test
	public void stillNeedsAttentionWhenHeldAfterEnd()
	{
		assertEquals(CardState.ATTENTION, card("\"entry\":\"held\",\"phase\":\"ended\"").getState());
	}

	@Test
	public void saysNotOnRosterWhenRemovedMidEvent()
	{
		assertEquals(CardState.NOT_ON_ROSTER, card("\"entry\":\"removed\"").getState());
	}

	@Test
	public void saysEndedResultsProvisionalAndKeepsTheStanding()
	{
		EventCard card = card("\"phase\":\"ended\"");

		assertEquals(CardState.ENDED, card.getState());
		assertEquals("Ended, results provisional", card.getDetail());
		assertEquals(2, card.getStanding().size());
	}

	@Test
	public void showsTheFinalStandingOnceFinalized()
	{
		EventCard card = card("\"phase\":\"finalized\",\"finalizedAt\":\"2026-11-09T10:00:00.000Z\"");

		assertEquals(CardState.FINAL, card.getState());
		assertEquals("Final standing", card.getDetail());
		assertEquals(2, card.getStanding().size());
	}

	@Test
	public void greysALeftEventWhateverItsPhase()
	{
		EventCard card = EventCard.of(event("\"entry\":\"held\""), true, EventCard.Queued.NONE, ZoneOffset.UTC);

		assertEquals(CardState.LEFT, card.getState());
		assertTrue(card.isLeft());
	}

	@Test
	public void ranksForTheOverlayFromTheBestTeam()
	{
		assertEquals("2nd/4", card("").getRank());
		assertEquals("", card("\"standing\":null").getRank());
		assertEquals("", card("\"phase\":\"scheduled\"").getRank());
	}

	@Test
	public void survivesFieldsMissingFromAnOlderServer()
	{
		EventCard card = EventCard.of(GSON.fromJson("{\"number\":654321,\"name\":\"X\",\"phase\":\"live\"}",
			DiscoveryResponse.DiscoveredEvent.class), false, EventCard.Queued.NONE, ZoneOffset.UTC);

		assertEquals(CardState.TRACKING, card.getState());
		assertTrue(card.getStanding().isEmpty());
	}

	@Test
	public void asksForAScreenshotWhileItemsNeedEvidence()
	{
		EventCard card = card("\"needsEvidence\":2");

		assertEquals(CardState.ATTENTION, card.getState());
		assertEquals("2 items need a screenshot", card.getDetail());
		assertTrue(card.isUpload());
		assertEquals(List.of(), card.getNotes());
	}

	@Test
	public void keepsAskingForEvidenceAfterEnd()
	{
		// Uploads stay open through the Grace window and review
		EventCard card = card("\"phase\":\"ended\",\"needsEvidence\":1");

		assertEquals(CardState.ATTENTION, card.getState());
		assertEquals("1 item needs a screenshot", card.getDetail());
	}

	@Test
	public void showsWhatWaitsToSendAndWhatTheFullQueuePushedOut()
	{
		EventCard card = EventCard.of(event(""), false, new EventCard.Queued(3, 1), ZoneOffset.UTC);

		assertEquals(CardState.TRACKING, card.getState());
		assertEquals(List.of("3 waiting to send", "1 taken out of the full send queue: upload it on the event page"),
			card.getNotes());
		assertTrue(card.isUpload());
		assertEquals(List.of(), card("").getNotes());
		assertFalse(card("").isUpload());
	}

	/** A Live bingo Event whose board is {@code size} squares a side, with Red the player's Team. */
	private static EventCard bingo(int size, String extra)
	{
		StringBuilder tiles = new StringBuilder();
		for (int i = 0; i < size * size; i++)
		{
			tiles.append(i == 0 ? "" : ",").append("{\"state\":\"open\",\"tier\":null,\"fill\":0}");
		}
		return card("\"format\":\"bingo\",\"standing\":null,\"board\":{\"size\":" + size + ",\"tiles\":[],\"teams\":["
			+ "{\"team\":2,\"name\":\"Blue\",\"rank\":1,\"of\":3,\"points\":1200,\"mine\":false,\"tiles\":[" + tiles + "]},"
			+ "{\"team\":1,\"name\":\"Red\",\"rank\":2,\"of\":3,\"points\":340,\"mine\":true,\"tiles\":[" + tiles + "]}]}"
			+ (extra.isEmpty() ? "" : "," + extra));
	}

	@Test
	public void showsTheBingoStandingOfThePlayersTeamInPoints()
	{
		EventCard card = bingo(5, "");

		assertEquals(List.of("Red: 2nd of 3, 340 pts"), card.getStanding());
		assertEquals("2nd/3", card.getRank());
		assertEquals(5, card.miniBoard().getSize());
		assertEquals("Open event page", card.getLink());
	}

	@Test
	public void showsNoBingoStandingBeforeStartButTheBoardAlready()
	{
		EventCard card = bingo(5, "\"phase\":\"scheduled\"");

		assertTrue(card.getStanding().isEmpty());
		assertEquals("", card.getRank());
		assertEquals(5, card.miniBoard().getSize());
	}

	@Test
	public void leavesABoardLargerThanSevenToTheWebsite()
	{
		EventCard card = bingo(8, "");

		assertNull(card.miniBoard());
		assertEquals(List.of("Red: 2nd of 3, 340 pts"), card.getStanding());
		assertEquals("Open board", card.getLink());
	}

	@Test
	public void linksAMetricEventToItsPage()
	{
		assertNull(card("").getBoard());
		assertEquals("Open event page", card("").getLink());
	}

	private static final String VOTE = "\"phase\":\"published\",\"standing\":null,"
		+ "\"vote\":{\"closesAt\":\"2026-11-05T18:00:00.000Z\",\"open\":false,"
		+ "\"candidates\":[{\"id\":1,\"name\":\"Fishing\",\"count\":0,\"cooling\":false}],\"choice\":null}";

	@Test
	public void votesWhileASeriesEventsVoteIsOpen()
	{
		EventCard card = card(VOTE);

		assertEquals(CardState.VOTING, card.getState());
		assertEquals("Voting, closes 5 Nov 18:00", card.getDetail());
		assertEquals("Fishing", card.getVote().candidates().get(0).getName());
	}

	@Test
	public void offersNoVoteOnceLeft()
	{
		EventCard card = EventCard.of(event(VOTE), true, EventCard.Queued.NONE, ZoneOffset.UTC);

		assertEquals(CardState.LEFT, card.getState());
		assertNull(card.getVote());
	}

	/** A Leaderboard Team row as discovery sends it, {@code extra} closing it, such as its members. */
	private static String boardTeam(String name, boolean solo, int rank, long gain, boolean mine, boolean gap,
		String extra)
	{
		return "{\"name\":\"" + name + "\",\"solo\":" + solo + ",\"rank\":" + rank + ",\"of\":9,\"gain\":" + gain
			+ ",\"mine\":" + mine + ",\"gap\":" + gap + (extra.isEmpty() ? ",\"skull\":null,\"members\":[],\"own\":null" : extra)
			+ "}";
	}

	private static String member(String rsn, int rank, long gain, boolean mine, boolean removed)
	{
		return "{\"rsn\":\"" + rsn + "\",\"rank\":" + rank + ",\"gain\":" + gain + ",\"mine\":" + mine
			+ ",\"removed\":" + removed + ",\"skull\":null}";
	}

	private static final String SOLO_BOARD = "\"leaderboard\":{\"teams\":["
		+ boardTeam("Zezima", true, 1, 5_000_000, false, false, "") + ","
		+ boardTeam("Lynx Titan", true, 2, 4_100_000, false, false, "") + ","
		+ boardTeam("Woox", true, 3, 3_000_000, false, false, "") + ","
		+ boardTeam("B0aty", true, 6, 1_500_000, false, true, "") + ","
		+ boardTeam("Iron Man", true, 7, 1_204_000, true, false, ",\"members\":[],\"own\":null," + DIED) + ","
		+ boardTeam("Settled", true, 8, 900_000, false, false, "") + "]}";

	private static List<String> texts(List<EventCard.Row> rows)
	{
		List<String> texts = new ArrayList<>();
		for (EventCard.Row row : rows)
		{
			texts.add(row.text());
		}
		return texts;
	}

	@Test
	public void drawsASoloLeaderboardWithItsGapAndThePlayersRowInOrange()
	{
		List<EventCard.Row> rows = card(SOLO_BOARD).getLeaderboard();

		assertEquals(List.of("1st Zezima  +5M xp", "2nd Lynx Titan  +4.1M xp", "3rd Woox  +3M xp", "...",
			"6th B0aty  +1.5M xp", "7th Iron Man  +1.2M xp", "8th Settled  +900K xp"), texts(rows));
		assertTrue(rows.get(3).isGap());
		assertEquals(EventCard.Shade.MINE, rows.get(5).getShade());
		assertEquals(EventCard.Shade.OTHER, rows.get(0).getShade());
		assertFalse(rows.get(0).isIndent());
	}

	@Test
	public void putsTheFullXpFigureAndTheSkullInALeaderboardRowsTooltip()
	{
		List<EventCard.Row> rows = card(SOLO_BOARD).getLeaderboard();

		assertEquals("+5,000,000 xp", rows.get(0).getTooltip());
		assertFalse(rows.get(0).isSkull());
		assertTrue(rows.get(5).isSkull());
		assertEquals("+1,204,000 xp. Died 2 Nov 14:05. Showing best life. Current life: 340K.", rows.get(5).getTooltip());
	}

	private static final String TEAM_BOARD = "\"format\":\"botw\",\"leaderboard\":{\"teams\":["
		+ boardTeam("Zamorak", false, 1, 300, false, false, ",\"skull\":null,\"members\":["
			+ member("Alice", 1, 120, false, false) + "," + member("Bob", 2, 100, false, true) + ","
			+ member("Carl", 3, 80, false, false) + "],\"own\":null") + ","
		+ boardTeam("Bandos", false, 5, 120, true, true, ",\"skull\":null,\"members\":["
			+ member("Dee", 1, 60, false, false) + "," + member("Eve", 2, 40, false, false) + ","
			+ member("Fay", 3, 10, false, false) + "],\"own\":{\"rsn\":\"Iron Man\",\"rank\":7,\"of\":9,\"gain\":5,"
			+ "\"skull\":{\"kind\":\"died\",\"at\":\"2026-11-02T14:05:00.000Z\",\"best\":5,\"current\":1204}}") + "]}";

	@Test
	public void drawsATeamLeaderboardWithIndentedContributorsAndTheOwnLine()
	{
		List<EventCard.Row> rows = card(TEAM_BOARD).getLeaderboard();

		assertEquals(List.of("1st Zamorak  +300 kc", "1st Alice  +120", "2nd Bob  +100", "3rd Carl  +80", "...",
			"5th Bandos (you)  +120 kc", "1st Dee  +60", "2nd Eve  +40", "3rd Fay  +10", "Iron Man:  +5 (7th of 9)"),
			texts(rows));
		assertFalse(rows.get(0).isIndent());
		assertTrue(rows.get(1).isIndent());
		assertTrue(rows.get(9).isIndent());
		assertEquals(EventCard.Shade.MINE, rows.get(5).getShade());
		assertEquals(EventCard.Shade.OTHER, rows.get(6).getShade());
		assertEquals(EventCard.Shade.MINE, rows.get(9).getShade());
	}

	@Test
	public void greysARemovedContributorAndSaysWhy()
	{
		EventCard.Row bob = card(TEAM_BOARD).getLeaderboard().get(2);

		assertEquals(EventCard.Shade.REMOVED, bob.getShade());
		assertEquals("Removed from the roster", bob.getTooltip());
	}

	@Test
	public void keepsKcInFullWithNoTooltipButTheSkulls()
	{
		List<EventCard.Row> rows = card(TEAM_BOARD).getLeaderboard();

		assertNull(rows.get(0).getTooltip());
		assertNull(rows.get(1).getTooltip());
		assertTrue(rows.get(9).isSkull());
		assertEquals("Died 2 Nov 14:05. Showing best life. Current life: 1,204.", rows.get(9).getTooltip());
	}

	@Test
	public void showsContributorXpShortWithoutTheUnit()
	{
		List<EventCard.Row> rows = card(TEAM_BOARD.replace("\"format\":\"botw\"", "\"format\":\"sotw\"")
			.replace("\"gain\":120,\"mine\":false", "\"gain\":500000,\"mine\":false")).getLeaderboard();

		assertEquals("1st Alice  +500K", rows.get(1).text());
		assertEquals("+500,000 xp", rows.get(1).getTooltip());
	}

	@Test
	public void hasALeaderboardOnlyOnceAMetricEventHasStartedAndTheServerSendsOne()
	{
		assertNotNull(card(SOLO_BOARD).getLeaderboard());
		assertNotNull(card(SOLO_BOARD + ",\"phase\":\"ended\"").getLeaderboard());
		assertNotNull(card(SOLO_BOARD + ",\"phase\":\"finalized\"").getLeaderboard());
		assertNotNull(EventCard.of(event(SOLO_BOARD), true, EventCard.Queued.NONE, ZoneOffset.UTC).getLeaderboard());
		assertNull(card(SOLO_BOARD + ",\"phase\":\"scheduled\"").getLeaderboard());
		assertNull(card("").getLeaderboard());
		assertNull(bingo(5, SOLO_BOARD).getLeaderboard());
	}

	@Test
	public void skipsNullLeaderboardEntriesRatherThanFailTheCard()
	{
		List<EventCard.Row> rows = card("\"leaderboard\":{\"teams\":[null,"
			+ boardTeam("Zamorak", false, 1, 300, false, false, ",\"skull\":null,\"members\":[null,"
			+ member("Alice", 1, 120, false, false) + "],\"own\":null") + "]}").getLeaderboard();

		assertEquals(List.of("1st Zamorak  +300 xp", "1st Alice  +120"), texts(rows));
	}
}
