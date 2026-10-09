package com.friendscape;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.Value;

/** One Event card as the panel and overlay show it (SPEC 10.3): state, detail line and standing. */
@Value
class EventCard
{
	private static final DateTimeFormatter STARTS = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);

	String slug;
	String name;
	CardState state;
	/** The line under the name: what the state means for the player. */
	String detail;
	/** The standing line, one per Team, then the player's own gain when on a real Team. */
	List<String> standing;
	/** The best Team's rank for the overlay, e.g. {@code 2nd/4}; empty without a standing. */
	String rank;

	boolean isLeft()
	{
		return state == CardState.LEFT;
	}

	static EventCard of(DiscoveryResponse.DiscoveredEvent event, boolean left, ZoneId zone)
	{
		CardState state = state(event, left);
		boolean ranked = hasStanding(event);
		return new EventCard(event.getSlug(), event.getName(), state, detail(event, state, zone),
			ranked ? standing(event) : List.of(), ranked ? rank(event.getStanding()) : "");
	}

	private static CardState state(DiscoveryResponse.DiscoveredEvent event, boolean left)
	{
		String phase = event.getPhase();
		if (left)
		{
			return CardState.LEFT;
		}
		if (DiscoveryResponse.REMOVED.equals(event.getEntry()))
		{
			return CardState.NOT_ON_ROSTER;
		}
		if (DiscoveryResponse.FINALIZED.equals(phase))
		{
			return CardState.FINAL;
		}
		if (DiscoveryResponse.ENDED.equals(phase))
		{
			return CardState.ENDED;
		}
		if (DiscoveryResponse.HELD.equals(event.getEntry()))
		{
			return CardState.ATTENTION;
		}
		if (DiscoveryResponse.PUBLISHED.equals(phase))
		{
			return CardState.NOT_SCHEDULED;
		}
		return CardState.TRACKING;
	}

	private static String detail(DiscoveryResponse.DiscoveredEvent event, CardState state, ZoneId zone)
	{
		switch (state)
		{
			case LEFT:
				return "Left on this install, nothing is sent";
			case NOT_ON_ROSTER:
				return "No longer on the roster";
			case FINAL:
				return "Final standing";
			case ENDED:
				return "Ended, results provisional";
			case ATTENTION:
				return "On hold while Friendscape checks this RSN";
			case NOT_SCHEDULED:
				return "Not scheduled yet";
			default:
				Instant startsAt = DiscoveryResponse.parse(event, event.getStartsAt());
				if (DiscoveryResponse.SCHEDULED.equals(event.getPhase()) && startsAt != null)
				{
					return "Starts " + STARTS.format(startsAt.atZone(zone));
				}
				return "Tracking";
		}
	}

	/** Standings mean something once the Event has started. */
	private static boolean hasStanding(DiscoveryResponse.DiscoveredEvent event)
	{
		String phase = event.getPhase();
		return event.getStanding() != null && event.getStanding().getTeams() != null
			&& (DiscoveryResponse.LIVE.equals(phase) || DiscoveryResponse.ENDED.equals(phase)
			|| DiscoveryResponse.FINALIZED.equals(phase));
	}

	private static List<String> standing(DiscoveryResponse.DiscoveredEvent event)
	{
		String unit = "botw".equals(event.getFormat()) ? "kc" : "xp";
		List<String> lines = new ArrayList<>();
		boolean onRealTeam = false;
		for (DiscoveryResponse.TeamStanding team : event.getStanding().getTeams())
		{
			String place = ordinal(team.getRank()) + " of " + team.getOf() + ", " + gain(team.getGain(), unit);
			lines.add(team.isSolo() ? place : team.getName() + ": " + place);
			onRealTeam |= !team.isSolo();
		}
		if (onRealTeam)
		{
			lines.add(gain(event.getStanding().getGain(), unit) + " since start");
		}
		return lines;
	}

	private static String rank(DiscoveryResponse.Standing standing)
	{
		if (standing.getTeams().isEmpty())
		{
			return "";
		}
		DiscoveryResponse.TeamStanding best = standing.getTeams().get(0);
		return ordinal(best.getRank()) + "/" + best.getOf();
	}

	private static String gain(long gain, String unit)
	{
		return String.format(Locale.ENGLISH, "+%,d %s", gain, unit);
	}

	/** 1st, 2nd, 3rd, 4th, ..., 11th, 12th, 13th, 21st. */
	static String ordinal(int n)
	{
		int lastTwo = n % 100;
		if (lastTwo >= 11 && lastTwo <= 13)
		{
			return n + "th";
		}
		switch (n % 10)
		{
			case 1:
				return n + "st";
			case 2:
				return n + "nd";
			case 3:
				return n + "rd";
			default:
				return n + "th";
		}
	}
}
