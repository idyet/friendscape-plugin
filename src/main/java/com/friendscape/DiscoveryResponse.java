package com.friendscape;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
		/** The player's Submissions here still waiting for a screenshot; absent from older servers. */
		int needsEvidence;
		/** What Tiles open for the player's Teams look for; null when nothing is open. */
		Watch watch;
		/** A bingo Event's mini board with every Team's standing; null on a metric Event or older server. */
		Board board;
	}

	/** A bingo Event's board as the mini board shows it (SPEC 10.3): what the website shows. */
	@Value
	static class Board
	{
		/** Squares per side. */
		int size;
		/** Stored Tiles only; a position without one shows empty. */
		List<BoardTile> tiles;
		/** Every Team, in standings order. */
		List<BoardTeam> teams;

		/** The Tile at a number, or null for an empty position. */
		BoardTile tile(int number)
		{
			for (BoardTile tile : tiles == null ? List.<BoardTile>of() : tiles)
			{
				if (tile.getNumber() == number)
				{
					return tile;
				}
			}
			return null;
		}

		List<BoardTeam> teams()
		{
			return teams == null ? List.of() : teams;
		}

		/** The Teams the player is on, best first. */
		List<BoardTeam> mine()
		{
			List<BoardTeam> mine = new ArrayList<>();
			for (BoardTeam team : teams())
			{
				if (team.isMine())
				{
					mine.add(team);
				}
			}
			return mine;
		}

		/** Squares per side; 0 for a board too broken to draw. */
		int side()
		{
			return Math.max(0, size);
		}
	}

	@Value
	static class BoardTile
	{
		/** From 1: position + 1, as the website names it. */
		int number;
		String name;
		int tiers;
		/** Null shows the Tile number. */
		TileIcon icon;
	}

	/** The first Requirement's picture: an item sprite, a boss icon or a skill icon. */
	@Value
	static class TileIcon
	{
		static final String ITEM = "item";
		static final String KC = "kc";
		static final String XP = "xp";

		String kind;
		/** For {@code item}: a canonical item id. */
		int itemId;
		/** For {@code kc}: the activity as RuneLite's hiscores name it. */
		String name;
		/** For {@code xp}: the skill, e.g. {@code slayer}. */
		String skill;
	}

	@Value
	static class BoardTeam
	{
		int teamId;
		String name;
		int rank;
		/** How many Teams the Event ranks. */
		int of;
		int points;
		/** The player is on it now. */
		boolean mine;
		/** One per position, in order. */
		List<TeamTile> tiles;

		/** The highest Tier reached at a position, from 1; 0 when none is or out of range. */
		int tier(int position)
		{
			if (tiles == null || position >= tiles.size() || tiles.get(position).getTier() == null)
			{
				return 0;
			}
			return tiles.get(position).getTier();
		}
	}

	@Value
	static class TeamTile
	{
		static final String COMPLETE = "complete";
		static final String FREE = "free";
		static final String EMPTY = "empty";

		/** {@code empty}, {@code free}, {@code open} or {@code complete}. */
		String state;
		/** The highest Tier reached, from 1; null while none is. */
		Integer tier;
		/** 0 to 1: 1 when complete, else progress toward the next Tier. */
		double fill;
	}

	/**
	 * The open-Tile watch list (SPEC 10.3): the union over the player's Teams. Hidden Tiles are
	 * never in it, as the server redacts them.
	 */
	@Value
	static class Watch
	{
		List<WatchedItem> items;
		/** Some open Tile counts new collection log slots, whatever the item. */
		boolean clogSlots;
	}

	@Value
	static class WatchedItem
	{
		/** A canonical item id, as {@code ItemManager.canonicalize} gives it. */
		int id;
		/** The name chat lines use for it. */
		String name;
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

	/** The Events, empty when an older or broken answer has none. */
	List<DiscoveredEvent> events()
	{
		return events == null ? List.of() : events;
	}

	/** Event names by slug, for chat lines. */
	Map<String, String> eventNames()
	{
		Map<String, String> names = new HashMap<>();
		for (DiscoveredEvent event : events())
		{
			names.put(event.getSlug(), event.getName());
		}
		return names;
	}

	/**
	 * Until when each Event may still count a queued Submission: its end plus Grace window, or
	 * null while it has no end. An Event missing here no longer has a card.
	 */
	Map<String, Instant> keepUntil()
	{
		Map<String, Instant> keepUntil = new HashMap<>();
		for (DiscoveredEvent event : events())
		{
			Instant endsAt = parse(event, event.getEndsAt());
			keepUntil.put(event.getSlug(), endsAt == null ? null : endsAt.plusSeconds(event.getGraceWindowSeconds()));
		}
		return keepUntil;
	}

	/**
	 * One card per Event, less Finalized ones past their week: live first, then scheduled, ended,
	 * Finalized, and last those left or off the Roster, in the server's order within each.
	 * {@code queued} holds this install's Submissions by Event slug.
	 */
	List<EventCard> cards(Set<String> left, Map<String, EventCard.Queued> queued, Instant now, ZoneId zone)
	{
		List<DiscoveredEvent> ordered = new ArrayList<>(events());
		ordered.sort(Comparator.comparingInt(event -> cardOrder(event, left)));
		List<EventCard> cards = new ArrayList<>();
		for (DiscoveredEvent event : ordered)
		{
			Instant finalizedAt = parse(event, event.getFinalizedAt());
			if (finalizedAt != null && !now.isBefore(finalizedAt.plus(FINALIZED_VISIBLE)))
			{
				continue;
			}
			cards.add(EventCard.of(event, left.contains(event.getSlug()),
				queued.getOrDefault(event.getSlug(), EventCard.Queued.NONE), zone));
		}
		return cards;
	}

	private static int cardOrder(DiscoveredEvent event, Set<String> left)
	{
		if (left.contains(event.getSlug()) || REMOVED.equals(event.getEntry()))
		{
			return 4;
		}
		String phase = event.getPhase();
		if (SCHEDULED.equals(phase) || PUBLISHED.equals(phase))
		{
			return 1;
		}
		if (ENDED.equals(phase))
		{
			return 2;
		}
		return FINALIZED.equals(phase) ? 3 : 0;
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
