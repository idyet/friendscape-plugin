package com.friendscape;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/** The server's answer to a Reading: what it did for each Event the character is rostered in. */
@Slf4j
@Value
class ReadingsResponse
{
	/** Sent this long after an Event's start or end, so the server's clock is past it too. */
	static final Duration MOMENT_MARGIN = Duration.ofSeconds(2);
	/** Retry delay when the server has not reached a start or end this clock is past. */
	static final Duration MOMENT_RETRY = Duration.ofSeconds(10);

	private static final String NOT_STARTED = "not_started";
	private static final String COUNTED = "counted";

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
	 * When to send the next start or end Reading (every skill), or null when no Event has one to
	 * come: the start of an Event not started yet, or the end of one counting. Readings before start
	 * are ignored, so the start Reading is the first to count; the end Reading is the last.
	 */
	Instant nextMomentReading(Instant now)
	{
		if (events == null)
		{
			return null;
		}
		Instant next = null;
		for (EventReading event : events)
		{
			Instant moment = moment(event);
			if (moment == null)
			{
				continue;
			}
			// Already past here but not on the server: its clock is behind, so ask again shortly
			Instant at = moment.isAfter(now) ? moment.plus(MOMENT_MARGIN) : now.plus(MOMENT_RETRY);
			if (next == null || at.isBefore(next))
			{
				next = at;
			}
		}
		return next;
	}

	/** The moments this clock has passed, as of this answer: what a Reading sent now marks. */
	Set<Moment> momentsDue(Instant now)
	{
		Set<Moment> due = EnumSet.noneOf(Moment.class);
		if (events == null)
		{
			return due;
		}
		for (EventReading event : events)
		{
			Instant moment = moment(event);
			if (moment != null && !moment.isAfter(now))
			{
				due.add(NOT_STARTED.equals(event.getStatus()) ? Moment.START : Moment.END);
			}
		}
		return due;
	}

	/** The Event's next moment: its start while not started, its end while counting, else null. */
	private static Instant moment(EventReading event)
	{
		String at;
		if (NOT_STARTED.equals(event.getStatus()))
		{
			at = event.getStartsAt();
		}
		else if (COUNTED.equals(event.getStatus()))
		{
			at = event.getEndsAt();
		}
		else
		{
			return null;
		}
		if (at == null)
		{
			return null;
		}
		try
		{
			return Instant.parse(at);
		}
		catch (DateTimeParseException e)
		{
			log.warn("Ignoring Event {} with an unreadable moment {}", event.getSlug(), at);
			return null;
		}
	}
}
