package com.friendscape;

import com.google.gson.Gson;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class EventCardTest
{
	private static final Gson GSON = new Gson();
	private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

	/** One discovered Event: a Live Skill of the week unless {@code extra} overrides its fields. */
	private static DiscoveryResponse.DiscoveredEvent event(String extra)
	{
		String json = "{\"slug\":\"sotw\",\"name\":\"Slayer week\",\"format\":\"sotw\",\"phase\":\"live\","
			+ "\"startsAt\":\"2026-11-01T18:00:00.000Z\",\"endsAt\":\"2026-11-08T18:00:00.000Z\","
			+ "\"graceWindowSeconds\":3600,\"finalizedAt\":null,\"entry\":\"on_roster\","
			+ "\"teams\":[{\"name\":\"Team Bandos\",\"size\":3}],"
			+ "\"standing\":{\"gain\":3400,\"teams\":[{\"name\":\"Team Bandos\",\"solo\":false,\"rank\":2,\"of\":4,\"gain\":12340}]}"
			+ (extra.isEmpty() ? "" : "," + extra) + "}";
		return GSON.fromJson(json, DiscoveryResponse.DiscoveredEvent.class);
	}

	private static EventCard card(String extra)
	{
		return EventCard.of(event(extra), false, ZoneOffset.UTC);
	}

	@Test
	public void tracksALiveEventAndShowsTheTeamStandingAndOwnGain()
	{
		EventCard card = card("");

		assertEquals(CardState.TRACKING, card.getState());
		assertEquals("Tracking", card.getDetail());
		assertEquals(List.of("Team Bandos: 2nd of 4, +12,340 xp", "+3,400 xp since start"), card.getStanding());
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
		assertEquals(CardState.ATTENTION, card("\"entry\":\"held\"").getState());
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
		EventCard card = EventCard.of(event("\"entry\":\"held\""), true, ZoneOffset.UTC);

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
		EventCard card = EventCard.of(GSON.fromJson("{\"slug\":\"x\",\"name\":\"X\",\"phase\":\"live\"}",
			DiscoveryResponse.DiscoveredEvent.class), false, ZoneOffset.UTC);

		assertEquals(CardState.TRACKING, card.getState());
		assertTrue(card.getStanding().isEmpty());
	}
}
