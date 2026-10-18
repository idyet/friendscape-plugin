package com.friendscape;

import com.google.gson.Gson;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

	private static String event(int number, String phase, String entry, String extra)
	{
		return "{\"number\":" + number + ",\"name\":\"Event " + number + "\",\"format\":\"sotw\",\"phase\":\"" + phase + "\","
			+ "\"startsAt\":\"2026-11-01T18:00:00.000Z\",\"endsAt\":\"2026-11-08T18:00:00.000Z\","
			+ "\"entry\":\"" + entry + "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
	}

	private static DiscoveryResponse parse(String... events)
	{
		return GSON.fromJson("{\"rsn\":\"Iron Man\",\"events\":[" + String.join(",", events) + "]}",
			DiscoveryResponse.class);
	}

	private static List<Integer> numbers(List<EventCard> cards)
	{
		return cards.stream().map(EventCard::getNumber).collect(Collectors.toList());
	}

	@Test
	public void makesOneCardPerEventInServerOrder()
	{
		DiscoveryResponse response = parse(event(222222, "live", "on_roster", ""), event(111111, "scheduled", "on_roster", ""));

		assertEquals(List.of(222222, 111111), numbers(response.cards(Set.of(), Map.of(), NOW, ZoneOffset.UTC)));
	}

	@Test
	public void ordersCardsLiveThenScheduledThenFinishedThenLeft()
	{
		int left = 100001;
		int finalized = 100002;
		int removed = 100003;
		int ended = 100004;
		int unscheduled = 100005;
		int soon = 100006;
		int live = 100007;
		int held = 100008;
		DiscoveryResponse response = parse(
			event(left, "live", "on_roster", ""),
			event(finalized, "finalized", "on_roster", "\"finalizedAt\":\"2026-11-02T12:00:00.000Z\""),
			event(removed, "live", "removed", ""),
			event(ended, "ended", "on_roster", ""),
			event(unscheduled, "published", "on_roster", ""),
			event(soon, "scheduled", "on_roster", ""),
			event(live, "live", "on_roster", ""),
			event(held, "live", "held", ""));

		assertEquals(List.of(live, held, unscheduled, soon, ended, finalized, left, removed),
			numbers(response.cards(Set.of(left), Map.of(), NOW, ZoneOffset.UTC)));
	}

	@Test
	public void removesAFinalizedCardSevenDaysAfterFinalize()
	{
		DiscoveryResponse response = parse(
			event(111111, "finalized", "on_roster", "\"finalizedAt\":\"2026-10-28T12:00:01.000Z\""),
			event(222222, "finalized", "on_roster", "\"finalizedAt\":\"2026-10-27T12:00:00.000Z\""));

		assertEquals(List.of(111111), numbers(response.cards(Set.of(), Map.of(), NOW, ZoneOffset.UTC)));
	}

	@Test
	public void marksLeftCards()
	{
		DiscoveryResponse response = parse(event(111111, "live", "on_roster", ""), event(222222, "live", "on_roster", ""));

		List<EventCard> cards = response.cards(Set.of(222222), Map.of(), NOW, ZoneOffset.UTC);

		assertEquals(CardState.TRACKING, cards.get(0).getState());
		assertEquals(CardState.LEFT, cards.get(1).getState());
	}

	@Test
	public void isEmptyWithNoEvents()
	{
		assertTrue(parse().cards(Set.of(), Map.of(), NOW, ZoneOffset.UTC).isEmpty());
		assertTrue(GSON.fromJson("{}", DiscoveryResponse.class).cards(Set.of(), Map.of(), NOW, ZoneOffset.UTC).isEmpty());
	}

	@Test
	public void wantsReadingsWhileAnEventNotLeftCanStillTakeThem()
	{
		assertTrue(parse(event(111111, "live", "on_roster", "")).wantsReadings(Set.of(), NOW));
		assertTrue(parse(event(111111, "scheduled", "on_roster", "")).wantsReadings(Set.of(), NOW));
		// A held claimant's Readings settle the name-reuse check
		assertTrue(parse(event(111111, "live", "held", "")).wantsReadings(Set.of(), NOW));
		assertTrue(parse(event(111111, "published", "on_roster", "")).wantsReadings(Set.of(), NOW));
	}

	@Test
	public void wantsNoReadingsWhenEveryEventIsLeftEndedOrRemoved()
	{
		assertFalse(parse().wantsReadings(Set.of(), NOW));
		assertFalse(parse(event(111111, "live", "on_roster", "")).wantsReadings(Set.of(111111), NOW));
		assertFalse(parse(event(111111, "live", "removed", "")).wantsReadings(Set.of(), NOW));
		assertFalse(parse(event(111111, "ended", "on_roster", "\"endsAt\":\"2026-11-03T11:57:00.000Z\""))
			.wantsReadings(Set.of(), NOW));
		assertFalse(parse(event(111111, "finalized", "on_roster", "")).wantsReadings(Set.of(), NOW));
	}

	@Test
	public void stillWantsTheEndReadingJustAfterEnd()
	{
		DiscoveryResponse response = parse(event(111111, "ended", "on_roster", "\"endsAt\":\"2026-11-03T11:59:00.000Z\""));

		assertTrue(response.wantsReadings(Set.of(), NOW));
	}

	@Test
	public void namesTheLeftEventsItFound()
	{
		DiscoveryResponse response = parse(event(111111, "live", "on_roster", ""), event(222222, "live", "on_roster", ""));

		assertEquals(Set.of(222222), response.muted(Set.of(222222, 333333)));
	}

	@Test
	public void schedulesTheStartReadingOfAnEventScheduledAfterLogin()
	{
		DiscoveryResponse response = parse(
			event(111111, "scheduled", "on_roster", "\"startsAt\":\"2026-11-04T18:00:00.000Z\""),
			event(222222, "scheduled", "on_roster", "\"startsAt\":\"2026-11-03T18:00:00.000Z\""));

		ReadingsResponse readings = response.asReadings(Set.of(222222));

		assertEquals(Instant.parse("2026-11-04T18:00:02Z"), readings.nextMomentReading(NOW));
	}

	@Test
	public void givesEachEventTheStatusAReadingWouldAnswer()
	{
		DiscoveryResponse response = parse(
			event(111111, "live", "on_roster", ""),
			event(222222, "scheduled", "on_roster", ""),
			event(333333, "ended", "held", ""),
			event(444444, "live", "held", ""),
			event(555555, "live", "removed", ""),
			event(666666, "finalized", "on_roster", ""));

		List<String> statuses = response.asReadings(Set.of()).getEvents().stream()
			.map(ReadingsResponse.EventReading::getStatus).collect(Collectors.toList());

		assertEquals(List.of("counted", "not_started", "ended", "held", "not_on_roster", "ended"), statuses);
	}
}
