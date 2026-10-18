package com.friendscape;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class ReadingsResponseTest
{
	private static final Gson GSON = new Gson();
	private static final Instant NOW = Instant.parse("2026-11-01T17:00:00Z");

	private static ReadingsResponse parse(String json)
	{
		return GSON.fromJson(json, ReadingsResponse.class);
	}

	@Test
	public void readsEachEventStatus()
	{
		ReadingsResponse response = parse("{\"rsn\":\"Iron Man\",\"events\":["
			+ "{\"number\":123456,\"status\":\"counted\",\"startsAt\":\"2026-11-01T00:00:00.000Z\",\"endsAt\":null}]}");

		assertEquals(123456, response.getEvents().get(0).getNumber());
		assertEquals("counted", response.getEvents().get(0).getStatus());
	}

	@Test
	public void schedulesTheStartReadingAtTheEarliestStartToCome()
	{
		ReadingsResponse response = parse("{\"events\":["
			+ "{\"number\":222222,\"status\":\"not_started\",\"startsAt\":\"2026-11-02T18:00:00.000Z\"},"
			+ "{\"number\":111111,\"status\":\"not_started\",\"startsAt\":\"2026-11-01T18:00:00.000Z\"},"
			+ "{\"number\":333333,\"status\":\"counted\",\"startsAt\":\"2026-10-01T18:00:00.000Z\"}]}");

		assertEquals(Instant.parse("2026-11-01T18:00:02Z"), response.nextMomentReading(NOW));
	}

	@Test
	public void schedulesNoStartReadingForAnEventWithoutAStart()
	{
		ReadingsResponse response = parse("{\"events\":[{\"number\":111111,\"status\":\"not_started\",\"startsAt\":null}]}");

		assertNull(response.nextMomentReading(NOW));
	}

	@Test
	public void retriesShortlyWhenTheServerHasNotReachedAStartThisClockHasPassed()
	{
		ReadingsResponse response = parse("{\"events\":["
			+ "{\"number\":111111,\"status\":\"not_started\",\"startsAt\":\"2026-11-01T16:59:59.000Z\"}]}");

		assertEquals(NOW.plusSeconds(10), response.nextMomentReading(NOW));
	}

	@Test
	public void schedulesTheEndReadingAtTheEndOfACountedEvent()
	{
		ReadingsResponse response = parse("{\"events\":["
			+ "{\"number\":111111,\"status\":\"counted\",\"startsAt\":\"2026-10-01T18:00:00.000Z\",\"endsAt\":\"2026-11-01T18:00:00.000Z\"},"
			+ "{\"number\":222222,\"status\":\"held\",\"startsAt\":\"2026-10-01T18:00:00.000Z\",\"endsAt\":\"2026-11-01T17:30:00.000Z\"},"
			+ "{\"number\":333333,\"status\":\"ended\",\"startsAt\":\"2026-10-01T18:00:00.000Z\",\"endsAt\":\"2026-11-01T16:00:00.000Z\"}]}");

		assertEquals(Instant.parse("2026-11-01T18:00:02Z"), response.nextMomentReading(NOW));
	}

	@Test
	public void schedulesWhicheverMomentComesFirst()
	{
		ReadingsResponse response = parse("{\"events\":["
			+ "{\"number\":111111,\"status\":\"not_started\",\"startsAt\":\"2026-11-01T19:00:00.000Z\"},"
			+ "{\"number\":222222,\"status\":\"counted\",\"endsAt\":\"2026-11-01T18:00:00.000Z\"}]}");

		assertEquals(Instant.parse("2026-11-01T18:00:02Z"), response.nextMomentReading(NOW));
	}

	@Test
	public void retriesShortlyWhenTheServerHasNotReachedAnEndThisClockHasPassed()
	{
		ReadingsResponse response = parse("{\"events\":["
			+ "{\"number\":111111,\"status\":\"counted\",\"endsAt\":\"2026-11-01T16:59:59.000Z\"}]}");

		assertEquals(NOW.plusSeconds(10), response.nextMomentReading(NOW));
	}

	@Test
	public void marksTheMomentsThisClockHasPassed()
	{
		ReadingsResponse response = parse("{\"events\":["
			+ "{\"number\":111111,\"status\":\"not_started\",\"startsAt\":\"2026-11-01T17:00:00.000Z\"},"
			+ "{\"number\":222222,\"status\":\"counted\",\"endsAt\":\"2026-11-01T18:00:00.000Z\"}]}");

		assertEquals(EnumSet.of(Moment.START), response.momentsDue(NOW));
		assertEquals(EnumSet.of(Moment.START, Moment.END), response.momentsDue(Instant.parse("2026-11-01T18:00:02Z")));
	}

	@Test
	public void marksNothingBeforeAnyMoment()
	{
		ReadingsResponse response = parse("{\"events\":["
			+ "{\"number\":111111,\"status\":\"not_started\",\"startsAt\":\"2026-11-01T18:00:00.000Z\"},"
			+ "{\"number\":222222,\"status\":\"ended\",\"endsAt\":\"2026-11-01T16:00:00.000Z\"}]}");

		assertEquals(Set.of(), response.momentsDue(NOW));
		assertEquals(Set.of(), parse("{}").momentsDue(NOW));
	}

	@Test
	public void schedulesNothingForAnEmptyResponse()
	{
		assertNull(parse("{\"rsn\":\"Nobody\",\"events\":[]}").nextMomentReading(NOW));
		assertNull(parse("{}").nextMomentReading(NOW));
	}

	@Test
	public void schedulesNothingForAnEventLeftOnThisInstall()
	{
		ReadingsResponse response = parse("{\"events\":["
			+ "{\"number\":100001,\"status\":\"not_started\",\"startsAt\":\"2026-11-01T18:00:00.000Z\"},"
			+ "{\"number\":100002,\"status\":\"not_started\",\"startsAt\":\"2026-11-02T18:00:00.000Z\"}]}");

		assertEquals(Instant.parse("2026-11-02T18:00:02Z"), response.without(Set.of(100001)).nextMomentReading(NOW));
		assertEquals(java.util.Map.of(100001, "not_started", 100002, "not_started"), response.statuses());
	}
}
