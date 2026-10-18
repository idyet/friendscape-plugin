package com.friendscape;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.Value;

/** One Event card as the panel shows it, and the overlay while live (SPEC 10.3): state, detail line and standing. */
@Value
class EventCard
{
	private static final DateTimeFormatter STARTS = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);
	/** Larger boards show only the standing line and "Open board" (SPEC 10.3). */
	static final int MAX_MINI_BOARD = 7;
	static final String OPEN_BOARD = "Open board";
	static final String OPEN_EVENT_PAGE = "Open event page";

	/** The Event number. */
	int number;
	String name;
	CardState state;
	/** The line under the name: what the state means for the player. */
	String detail;
	/** The standing line, one per Team, then the player's own gain when on a real Team. */
	List<String> standing;
	/** Lines that ask something of the player: uploads, and Submissions queued or pushed out. */
	List<String> notes;
	/** The best Team's rank for the overlay, e.g. {@code 2nd/4}; empty without a standing. */
	String rank;
	/** A bingo Event's board; null on a metric Event. */
	DiscoveryResponse.Board board;
	/** The label of the link to the website: "Open board" for a board too large to draw, else "Open event page". */
	String link;
	/** Something here waits for a screenshot upload on the website. */
	boolean upload;
	/** An identity mismatch is held here: the card offers "Report to Friendscape" (SPEC 10.3). */
	boolean report;
	/** Live, still on the Roster and not left: the only cards the overlay shows. */
	boolean live;

	/** This install's Submissions for the Event (SPEC 10.5): what the server does not know of. */
	@Value
	static class Queued
	{
		static final Queued NONE = new Queued(0, 0);

		/** Still in the send queue. */
		int waiting;
		/** Pushed out of the full queue this session, for the player to upload by hand. */
		int pushedOut;
	}

	boolean isLeft()
	{
		return state == CardState.LEFT;
	}

	static EventCard of(DiscoveryResponse.DiscoveredEvent event, boolean left, Queued queued, ZoneId zone)
	{
		CardState state = state(event, left);
		DiscoveryResponse.Board board = event.getBoard();
		boolean bingo = board != null;
		List<String> standing = List.of();
		String rank = "";
		if (bingo && started(event))
		{
			standing = bingoStanding(board);
			rank = bingoRank(board);
		}
		else if (hasStanding(event))
		{
			standing = standing(event);
			rank = rank(event.getStanding());
		}
		boolean upload = (state == CardState.ATTENTION && event.getNeedsEvidence() > 0) || queued.getPushedOut() > 0;
		boolean report = state == CardState.ATTENTION && DiscoveryResponse.HELD.equals(event.getEntry());
		boolean live = DiscoveryResponse.LIVE.equals(event.getPhase()) && !left
			&& !DiscoveryResponse.REMOVED.equals(event.getEntry());
		return new EventCard(event.getNumber(), event.getName(), state, detail(event, state, zone), standing,
			notes(queued), rank, board, bingo && !drawn(board) ? OPEN_BOARD : OPEN_EVENT_PAGE, upload, report, live);
	}

	/** The mini board draws up to 7x7; larger ones are left to the website. */
	static boolean drawn(DiscoveryResponse.Board board)
	{
		return board != null && board.side() > 0 && board.side() <= MAX_MINI_BOARD;
	}

	/** The board the card draws, or null. */
	DiscoveryResponse.Board miniBoard()
	{
		return drawn(board) ? board : null;
	}

	/** One line per Team the player is on: "Red: 2nd of 3, 340 pts". */
	private static List<String> bingoStanding(DiscoveryResponse.Board board)
	{
		List<String> lines = new ArrayList<>();
		for (DiscoveryResponse.BoardTeam team : board.mine())
		{
			lines.add(team.getName() + ": " + bingoPlace(team));
		}
		return lines;
	}

	/** The best of the player's Teams; Teams come in standings order. */
	private static String bingoRank(DiscoveryResponse.Board board)
	{
		List<DiscoveryResponse.BoardTeam> mine = board.mine();
		return mine.isEmpty() ? "" : ordinal(mine.get(0).getRank()) + "/" + mine.get(0).getOf();
	}

	/** A bingo Team's place: "2nd of 3, 340 pts". */
	static String bingoPlace(DiscoveryResponse.BoardTeam team)
	{
		return String.format(Locale.ENGLISH, "%s of %d, %,d pts", ordinal(team.getRank()), team.getOf(), team.getPoints());
	}

	private static List<String> notes(Queued queued)
	{
		List<String> notes = new ArrayList<>();
		if (queued.getWaiting() > 0)
		{
			notes.add(queued.getWaiting() + " waiting to send");
		}
		if (queued.getPushedOut() > 0)
		{
			notes.add(queued.getPushedOut() + " taken out of the full send queue: upload "
				+ (queued.getPushedOut() == 1 ? "it" : "them") + " on the event page");
		}
		return notes;
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
		// Still worth attention after end: a mismatch settled during review lets the progress count,
		// and needs-evidence uploads stay open until Finalize
		if (DiscoveryResponse.HELD.equals(event.getEntry()) || event.getNeedsEvidence() > 0)
		{
			return CardState.ATTENTION;
		}
		if (DiscoveryResponse.ENDED.equals(phase))
		{
			return CardState.ENDED;
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
				if (DiscoveryResponse.HELD.equals(event.getEntry()))
				{
					return "On hold while Friendscape checks this RSN";
				}
				int items = event.getNeedsEvidence();
				return items == 1 ? "1 item needs a screenshot" : items + " items need a screenshot";
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
		return event.getStanding() != null && event.getStanding().getTeams() != null && started(event);
	}

	private static boolean started(DiscoveryResponse.DiscoveredEvent event)
	{
		String phase = event.getPhase();
		return DiscoveryResponse.LIVE.equals(phase) || DiscoveryResponse.ENDED.equals(phase)
			|| DiscoveryResponse.FINALIZED.equals(phase);
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
