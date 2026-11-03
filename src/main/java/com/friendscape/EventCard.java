package com.friendscape;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.Value;
import lombok.With;
import net.runelite.client.util.QuantityFormatter;

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
	/** The full figure of an XP gain line, shown short, for the last standing line's tooltip; null otherwise. */
	String gainTooltip;
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
	/** The open Vote the card's picker shows; null unless Voting. */
	DiscoveryResponse.Vote vote;
	/** The Event is Finalized: nothing left to Leave or Rejoin (SPEC 10.3). */
	boolean finalized;
	/** The Codeword its overlay shows while live; null when there is none to show (SPEC 10.3). */
	String codeword;
	/** The Group the Event came from; null when none, or from an older server. */
	@With
	DiscoveryResponse.Group group;
	/** The Hardcore death skull on a standing line; null without one (SPEC 5.7). */
	Skull skull;
	/**
	 * A started metric Event's Leaderboard rows, drawn in place of the standing line when opened
	 * (SPEC 10.3); null when there is none to show, so the card offers no toggle.
	 */
	@With
	List<Row> leaderboard;

	/** A skull by the player's standing line (SPEC 5.7). */
	@Value
	static class Skull
	{
		/** The standing line it marks: the player's own gain, or their solo line. */
		int line;
		/** "Died 2 Nov 14:05. Showing best life. Current life: 340K." */
		String tooltip;
	}

	/** How a Leaderboard row is coloured: the player's own, anyone else's, or a removed member's. */
	enum Shade
	{
		MINE,
		OTHER,
		REMOVED
	}

	/**
	 * One Leaderboard row: {@code place}, {@code name} and {@code mark} on the left, of which only the
	 * name is cut to fit, and {@code value} right aligned. A gap row shows only "...".
	 */
	@Value
	static class Row
	{
		static final String GAP_TEXT = TextFit.ELLIPSIS;
		static final Row GAP = new Row(true, false, "", GAP_TEXT, "", "", null, Shade.OTHER, false);

		boolean gap;
		/** A contributor or own line, under its Team. */
		boolean indent;
		/** "1st ", or empty on an own line. */
		String place;
		String name;
		/** " (you)" on the player's Team, ":" on an own line, else empty. */
		String mark;
		/** "+1.2M xp" on a Team or solo row; no unit under a Team. */
		String value;
		/** Null when there is nothing more to say. */
		String tooltip;
		Shade shade;
		/** A Hardcore death skull by the value (SPEC 5.7). */
		boolean skull;

		/** The row as one line: "1st Red (you)  +1.2M xp". */
		String text()
		{
			return gap ? GAP_TEXT : place + name + mark + "  " + value;
		}
	}

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
		String gainTooltip = null;
		String rank = "";
		Skull skull = null;
		List<Row> leaderboard = null;
		if (bingo && started(event))
		{
			standing = bingoStanding(board);
			rank = bingoRank(board);
		}
		else if (hasStanding(event))
		{
			standing = standing(event);
			gainTooltip = gainTooltip(event);
			rank = rank(event.getStanding());
			skull = skull(event, zone);
		}
		if (!bingo && started(event) && event.getLeaderboard() != null && event.getLeaderboard().getTeams() != null)
		{
			leaderboard = leaderboard(event, zone);
		}
		boolean upload = (state == CardState.ATTENTION && event.getNeedsEvidence() > 0) || queued.getPushedOut() > 0;
		boolean report = state == CardState.ATTENTION && DiscoveryResponse.HELD.equals(event.getEntry());
		boolean live = DiscoveryResponse.LIVE.equals(event.getPhase()) && !left
			&& !DiscoveryResponse.REMOVED.equals(event.getEntry());
		return new EventCard(event.getNumber(), event.getName(), state, detail(event, state, zone), standing,
			gainTooltip, notes(queued), rank, board, bingo && !drawn(board) ? OPEN_BOARD : OPEN_EVENT_PAGE, upload, report, live,
			state == CardState.VOTING ? event.getVote() : null, DiscoveryResponse.FINALIZED.equals(event.getPhase()),
			live ? event.getCodeword() : null, event.getGroup(), skull, leaderboard);
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
		if (DiscoveryResponse.PUBLISHED.equals(phase) && event.getVote() != null)
		{
			return CardState.VOTING;
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
			case VOTING:
				Instant closesAt = DiscoveryResponse.parse(event, event.getVote().getClosesAt());
				return closesAt == null ? "Voting" : "Voting, closes " + STARTS.format(closesAt.atZone(zone));
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

	private static boolean tracksXp(DiscoveryResponse.DiscoveredEvent event)
	{
		return !"botw".equals(event.getFormat());
	}

	/** The player's own gain gets a line when on a real Team, not only solo. */
	private static boolean ownGainLine(DiscoveryResponse.Standing standing)
	{
		return standing.getTeams().stream().anyMatch(team -> !team.isSolo());
	}

	/** Only the own XP gain line shows short (SPEC 6.8), so only it has the full figure to show. */
	private static String gainTooltip(DiscoveryResponse.DiscoveredEvent event)
	{
		return tracksXp(event) && ownGainLine(event.getStanding()) ? gain(event.getStanding().getGain(), "xp") : null;
	}

	private static List<String> standing(DiscoveryResponse.DiscoveredEvent event)
	{
		String unit = tracksXp(event) ? "xp" : "kc";
		List<String> lines = new ArrayList<>();
		for (DiscoveryResponse.TeamStanding team : event.getStanding().getTeams())
		{
			String place = ordinal(team.getRank()) + " of " + team.getOf() + ", " + gain(team.getGain(), unit);
			lines.add(team.isSolo() ? place : team.getName() + ": " + place);
		}
		if (ownGainLine(event.getStanding()))
		{
			long gain = event.getStanding().getGain();
			lines.add((tracksXp(event) ? shortGain(gain) : gain(gain, unit)) + " since start");
		}
		return lines;
	}

	/** On the own gain line when there is one, else on the solo line, which comes first. */
	private static Skull skull(DiscoveryResponse.DiscoveredEvent event, ZoneId zone)
	{
		DiscoveryResponse.Standing standing = event.getStanding();
		DiscoveryResponse.Skull skull = standing.getSkull();
		if (skull == null)
		{
			return null;
		}
		return new Skull(ownGainLine(standing) ? standing.getTeams().size() : 0, skullText(event, skull, zone));
	}

	/** "Died 2 Nov 14:05. Showing best life. Current life: 340K." (SPEC 5.7); XP short per SPEC 6.8. */
	private static String skullText(DiscoveryResponse.DiscoveredEvent event, DiscoveryResponse.Skull skull, ZoneId zone)
	{
		Instant at = DiscoveryResponse.parse(event, skull.getAt());
		String when = at == null ? "" : " " + STARTS.format(at.atZone(zone));
		String death = DiscoveryResponse.Skull.TRANSFERRED.equals(skull.getKind())
			? "Transferred" + when + ". Final is best life."
			: "Died" + when + ". Showing best life.";
		return death + " Current life: " + amount(event, skull.getCurrent()) + ".";
	}

	/** The Leaderboard in standings order: each Team, its contributors and own line under it, "..." at a gap. */
	private static List<Row> leaderboard(DiscoveryResponse.DiscoveredEvent event, ZoneId zone)
	{
		String unit = " " + (tracksXp(event) ? "xp" : "kc");
		List<Row> rows = new ArrayList<>();
		for (DiscoveryResponse.LeaderboardTeam team : event.getLeaderboard().getTeams())
		{
			if (team.isGap())
			{
				rows.add(Row.GAP);
			}
			rows.add(new Row(false, false, ordinal(team.getRank()) + " ", orEmpty(team.getName()),
				team.isMine() && !team.isSolo() ? " (you)" : "", signed(event, team.getGain()) + unit,
				tooltip(event, team.getGain(), false, team.getSkull(), zone), team.isMine() ? Shade.MINE : Shade.OTHER,
				team.getSkull() != null));
			if (team.getMembers() != null)
			{
				for (DiscoveryResponse.LeaderboardMember member : team.getMembers())
				{
					Shade shade = member.isRemoved() ? Shade.REMOVED : member.isMine() ? Shade.MINE : Shade.OTHER;
					rows.add(new Row(false, true, ordinal(member.getRank()) + " ", orEmpty(member.getRsn()), "",
						signed(event, member.getGain()), tooltip(event, member.getGain(), member.isRemoved(),
						member.getSkull(), zone), shade, member.getSkull() != null));
				}
			}
			DiscoveryResponse.LeaderboardOwn own = team.getOwn();
			if (own != null)
			{
				rows.add(new Row(false, true, "", orEmpty(own.getRsn()), ":", signed(event, own.getGain()) + " ("
					+ ordinal(own.getRank()) + " of " + own.getOf() + ")", tooltip(event, own.getGain(), false,
					own.getSkull(), zone), Shade.MINE, own.getSkull() != null));
			}
		}
		return rows;
	}

	/** The full XP figure, why a member is greyed and the skull's text, each when it applies; else null. */
	private static String tooltip(DiscoveryResponse.DiscoveredEvent event, long gain, boolean removed,
		DiscoveryResponse.Skull skull, ZoneId zone)
	{
		List<String> parts = new ArrayList<>();
		if (tracksXp(event))
		{
			parts.add(gain(gain, "xp"));
		}
		if (removed)
		{
			parts.add("Removed from the roster");
		}
		if (skull != null)
		{
			parts.add(skullText(event, skull, zone));
		}
		return parts.isEmpty() ? null : String.join(". ", parts);
	}

	private static String orEmpty(String name)
	{
		return name == null ? "" : name;
	}

	/** A gain with its sign: XP short (SPEC 6.8), KC in full. */
	private static String signed(DiscoveryResponse.DiscoveredEvent event, long gain)
	{
		return (gain < 0 ? "" : "+") + amount(event, gain);
	}

	/** XP short (SPEC 6.8), KC in full. */
	private static String amount(DiscoveryResponse.DiscoveredEvent event, long amount)
	{
		return tracksXp(event) ? shortAmount(amount) : String.format(Locale.ENGLISH, "%,d", amount);
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

	/** RuneLite's stack form, "+13M xp". */
	private static String shortGain(long gain)
	{
		return (gain < 0 ? "" : "+") + shortAmount(gain) + " xp";
	}

	/** "13M"; one skill's gain fits an int, the clamp is only defensive. */
	private static String shortAmount(long amount)
	{
		int clamped = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, amount));
		return QuantityFormatter.quantityToRSDecimalStack(clamped);
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
