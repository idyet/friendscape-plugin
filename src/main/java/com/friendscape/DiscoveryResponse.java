package com.friendscape;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * The server's answer to discovery: every Event that rosters the logged-in character, with its
 * phase, the player's Roster entry state and standing. The plugin's cards come from it alone.
 */
@Slf4j
@Value
class DiscoveryResponse
{
	/** A Finalized Event's card goes this long after Finalize, as the server stops listing it. */
	static final Duration FINALIZED_VISIBLE = Duration.ofDays(7);
	/**
	 * After an Event's end, how long a Reading may still be its end Reading: the server takes one up
	 * to a minute after end, plus the plugin's retries while the server's clock lags.
	 */
	static final Duration END_READING_WINDOW = Duration.ofMinutes(2);

	static final String LIVE = "live";
	static final String SCHEDULED = "scheduled";
	static final String PUBLISHED = "published";
	static final String ENDED = "ended";
	static final String FINALIZED = "finalized";
	static final String HELD = "held";
	static final String REMOVED = "removed";

	String rsn;
	/** Empty when no Roster has the character: the empty state. */
	List<DiscoveredEvent> events;

	@Value
	static class DiscoveredEvent
	{
		String slug;
		String name;
		/** {@code bingo}, {@code sotw} or {@code botw}. */
		String format;
		/** {@code published}, {@code scheduled}, {@code live}, {@code ended} or {@code finalized}. */
		String phase;
		String startsAt;
		String endsAt;
		int graceWindowSeconds;
		String finalizedAt;
		/** {@code on_roster}, {@code held} or {@code removed}. */
		String entry;
		List<TeamSize> teams;
		/** Null without a metric, or while no Team counts the entry. */
		Standing standing;
	}

	@Value
	static class TeamSize
	{
		String name;
		int size;
	}

	@Value
	static class Standing
	{
		/** The player's own gain since Baseline. */
		long gain;
		/** Each of the player's Teams, best first. */
		List<TeamStanding> teams;
	}

	@Value
	static class TeamStanding
	{
		/** The Team name, or for a Team of one its player's RSN. */
		String name;
		boolean solo;
		int rank;
		/** How many Teams the Event ranks. */
		int of;
		long gain;
	}

	private List<DiscoveredEvent> events()
	{
		return events == null ? List.of() : events;
	}

	/** One card per Event in the server's order, less Finalized ones past their week. */
	List<EventCard> cards(Set<String> left, Instant now, ZoneId zone)
	{
		List<EventCard> cards = new ArrayList<>();
		for (DiscoveredEvent event : events())
		{
			Instant finalizedAt = parse(event, event.getFinalizedAt());
			if (finalizedAt != null && !now.isBefore(finalizedAt.plus(FINALIZED_VISIBLE)))
			{
				continue;
			}
			cards.add(EventCard.of(event, left.contains(event.getSlug()), zone));
		}
		return cards;
	}

	/**
	 * Whether a Reading could count anywhere: some Event not left, still on its Roster, and not over
	 * (an Event that just ended still takes its end Reading). A held entry wants them too: its
	 * Readings settle the identity check.
	 */
	boolean wantsReadings(Set<String> left, Instant now)
	{
		for (DiscoveredEvent event : events())
		{
			if (left.contains(event.getSlug()) || REMOVED.equals(event.getEntry())
				|| FINALIZED.equals(event.getPhase()))
			{
				continue;
			}
			if (ENDED.equals(event.getPhase()))
			{
				Instant endsAt = parse(event, event.getEndsAt());
				if (endsAt == null || !now.isBefore(endsAt.plus(END_READING_WINDOW)))
				{
					continue;
				}
			}
			return true;
		}
		return false;
	}

	/** The discovered Events the player left: what a Reading names as muted. Sorted, for the wire. */
	Set<String> muted(Set<String> left)
	{
		Set<String> muted = new TreeSet<>();
		for (DiscoveredEvent event : events())
		{
			if (left.contains(event.getSlug()))
			{
				muted.add(event.getSlug());
			}
		}
		return muted;
	}

	/**
	 * The Events not left, with the status a Reading would answer for each: what schedules the start
	 * and end Readings, so an idle player still sends the start Reading of an Event Scheduled after
	 * login. Mirrors core {@code readingStatus}.
	 */
	ReadingsResponse asReadings(Set<String> left)
	{
		List<ReadingsResponse.EventReading> readings = new ArrayList<>();
		for (DiscoveredEvent event : events())
		{
			if (!left.contains(event.getSlug()))
			{
				readings.add(new ReadingsResponse.EventReading(event.getSlug(), readingStatus(event),
					event.getStartsAt(), event.getEndsAt()));
			}
		}
		return new ReadingsResponse(rsn, readings);
	}

	private static String readingStatus(DiscoveredEvent event)
	{
		if (REMOVED.equals(event.getEntry()))
		{
			return ReadingsResponse.NOT_ON_ROSTER;
		}
		if (ENDED.equals(event.getPhase()) || FINALIZED.equals(event.getPhase()))
		{
			return ReadingsResponse.ENDED;
		}
		if (HELD.equals(event.getEntry()))
		{
			return ReadingsResponse.HELD;
		}
		return LIVE.equals(event.getPhase()) ? ReadingsResponse.COUNTED : ReadingsResponse.NOT_STARTED;
	}

	/** An ISO 8601 time from the server, or null when absent or unreadable (logged). */
	static Instant parse(DiscoveredEvent event, String at)
	{
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
			log.warn("Ignoring Event {} with an unreadable time {}", event.getSlug(), at);
			return null;
		}
	}
}
