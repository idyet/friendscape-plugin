package com.friendscape;

import com.google.gson.Gson;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
		assertEquals(List.of("Team Bandos: 2nd of 4, +12,340 xp", "+3,400 xp since start"), card.getStanding());
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
}
