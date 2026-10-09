package com.friendscape;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/** The server's answer to a Reading: what it did for each Event the character is rostered in. */
@Slf4j
@Value
class ReadingsResponse
{
	/** Sent this long after an Event's start, so the server's clock is past it too. */
	static final Duration START_MARGIN = Duration.ofSeconds(2);
	/** Retry delay when the server still says not started though this clock is past the start. */
	static final Duration START_RETRY = Duration.ofSeconds(10);

	String rsn;
	/** Empty when the character is on no roster: the Readings were dropped. */
	List<EventReading> events;

	@Value
	static class EventReading
	{
		String slug;
		/** {@code counted}, {@code not_started}, {@code ended}, {@code held} or {@code not_on_roster}. */
		String status;
		String startsAt;
		String endsAt;
	}

	/**
	 * When to send the start Reading (every skill) for the Events not started yet, or null when none
	 * has a start. Readings before start are ignored, so the start Reading is the first to count.
	 */
	Instant nextStartReading(Instant now)
	{
		if (events == null)
		{
			return null;
		}
		Instant next = null;
		for (EventReading event : events)
		{
			if (!"not_started".equals(event.getStatus()) || event.getStartsAt() == null)
			{
				continue;
			}
			Instant startsAt;
			try
			{
				startsAt = Instant.parse(event.getStartsAt());
			}
			catch (DateTimeParseException e)
			{
				log.warn("Ignoring Event {} with an unreadable start {}", event.getSlug(), event.getStartsAt());
				continue;
			}
			// Already past here but not on the server: its clock is behind, so ask again shortly
			Instant at = startsAt.isAfter(now) ? startsAt.plus(START_MARGIN) : now.plus(START_RETRY);
			if (next == null || at.isBefore(next))
			{
				next = at;
			}
		}
		return next;
	}
}
