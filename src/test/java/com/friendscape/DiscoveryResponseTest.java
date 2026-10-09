package com.friendscape;

import com.google.gson.Gson;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DiscoveryResponseTest
{
	private static final Gson GSON = new Gson();
	private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

	private static String event(String slug, String phase, String entry, String extra)
	{
		return "{\"slug\":\"" + slug + "\",\"name\":\"" + slug + "\",\"format\":\"sotw\",\"phase\":\"" + phase + "\","
			+ "\"startsAt\":\"2026-11-01T18:00:00.000Z\",\"endsAt\":\"2026-11-08T18:00:00.000Z\","
			+ "\"entry\":\"" + entry + "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
	}

	private static DiscoveryResponse parse(String... events)
	{
		return GSON.fromJson("{\"rsn\":\"Iron Man\",\"events\":[" + String.join(",", events) + "]}",
			DiscoveryResponse.class);
	}

	private static List<String> slugs(List<EventCard> cards)
	{
		return cards.stream().map(EventCard::getSlug).collect(Collectors.toList());
	}

	@Test
	public void makesOneCardPerEventInServerOrder()
	{
		DiscoveryResponse response = parse(event("b", "live", "on_roster", ""), event("a", "scheduled", "on_roster", ""));

		assertEquals(List.of("b", "a"), slugs(response.cards(Set.of(), NOW, ZoneOffset.UTC)));
	}

	@Test
	public void removesAFinalizedCardSevenDaysAfterFinalize()
	{
		DiscoveryResponse response = parse(
			event("recent", "finalized", "on_roster", "\"finalizedAt\":\"2026-10-28T12:00:01.000Z\""),
			event("old", "finalized", "on_roster", "\"finalizedAt\":\"2026-10-27T12:00:00.000Z\""));

		assertEquals(List.of("recent"), slugs(response.cards(Set.of(), NOW, ZoneOffset.UTC)));
	}

	@Test
	public void marksLeftCards()
	{
		DiscoveryResponse response = parse(event("a", "live", "on_roster", ""), event("b", "live", "on_roster", ""));

		List<EventCard> cards = response.cards(Set.of("b"), NOW, ZoneOffset.UTC);

		assertEquals(CardState.TRACKING, cards.get(0).getState());
		assertEquals(CardState.LEFT, cards.get(1).getState());
	}

	@Test
	public void isEmptyWithNoEvents()
	{
		assertTrue(parse().cards(Set.of(), NOW, ZoneOffset.UTC).isEmpty());
		assertTrue(GSON.fromJson("{}", DiscoveryResponse.class).cards(Set.of(), NOW, ZoneOffset.UTC).isEmpty());
	}

	@Test
	public void wantsReadingsWhileAnEventNotLeftCanStillTakeThem()
	{
		assertTrue(parse(event("a", "live", "on_roster", "")).wantsReadings(Set.of(), NOW));
		assertTrue(parse(event("a", "scheduled", "on_roster", "")).wantsReadings(Set.of(), NOW));
		// A held claimant's Readings settle the name-reuse check
		assertTrue(parse(event("a", "live", "held", "")).wantsReadings(Set.of(), NOW));
		assertTrue(parse(event("a", "published", "on_roster", "")).wantsReadings(Set.of(), NOW));
	}

	@Test
	public void wantsNoReadingsWhenEveryEventIsLeftEndedOrRemoved()
	{
		assertFalse(parse().wantsReadings(Set.of(), NOW));
		assertFalse(parse(event("a", "live", "on_roster", "")).wantsReadings(Set.of("a"), NOW));
		assertFalse(parse(event("a", "live", "removed", "")).wantsReadings(Set.of(), NOW));
		assertFalse(parse(event("a", "ended", "on_roster", "\"endsAt\":\"2026-11-03T11:57:00.000Z\""))
			.wantsReadings(Set.of(), NOW));
		assertFalse(parse(event("a", "finalized", "on_roster", "")).wantsReadings(Set.of(), NOW));
	}

	@Test
	public void stillWantsTheEndReadingJustAfterEnd()
	{
		DiscoveryResponse response = parse(event("a", "ended", "on_roster", "\"endsAt\":\"2026-11-03T11:59:00.000Z\""));

		assertTrue(response.wantsReadings(Set.of(), NOW));
	}

	@Test
	public void namesTheLeftEventsItFound()
	{
		DiscoveryResponse response = parse(event("a", "live", "on_roster", ""), event("b", "live", "on_roster", ""));

		assertEquals(Set.of("b"), response.muted(Set.of("b", "gone")));
	}

	@Test
	public void schedulesTheStartReadingOfAnEventScheduledAfterLogin()
	{
		DiscoveryResponse response = parse(
			event("later", "scheduled", "on_roster", "\"startsAt\":\"2026-11-04T18:00:00.000Z\""),
			event("left", "scheduled", "on_roster", "\"startsAt\":\"2026-11-03T18:00:00.000Z\""));

		ReadingsResponse readings = response.asReadings(Set.of("left"));

		assertEquals(Instant.parse("2026-11-04T18:00:02Z"), readings.nextMomentReading(NOW));
	}

	@Test
	public void givesEachEventTheStatusAReadingWouldAnswer()
	{
		DiscoveryResponse response = parse(
			event("a", "live", "on_roster", ""),
			event("b", "scheduled", "on_roster", ""),
			event("c", "ended", "held", ""),
			event("d", "live", "held", ""),
			event("e", "live", "removed", ""),
			event("f", "finalized", "on_roster", ""));

		List<String> statuses = response.asReadings(Set.of()).getEvents().stream()
			.map(ReadingsResponse.EventReading::getStatus).collect(Collectors.toList());

		assertEquals(List.of("counted", "not_started", "ended", "held", "not_on_roster", "ended"), statuses);
	}
}
