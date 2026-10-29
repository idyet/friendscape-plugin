package com.friendscape;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class FriendscapePanelTest
{
	private final List<Boolean> sendingRequests = new ArrayList<>();
	private final List<String> actions = new ArrayList<>();
	private final FriendscapePanel panel = new FriendscapePanel(sendingRequests::add, new FriendscapePanel.CardActions()
	{
		@Override
		public void leave(int number)
		{
			actions.add("leave " + number);
		}

		@Override
		public void rejoin(int number)
		{
			actions.add("rejoin " + number);
		}

		@Override
		public void openEventPage(int number)
		{
			actions.add("open " + number);
		}

		@Override
		public void openUploadPage(int number)
		{
			actions.add("upload " + number);
		}

		@Override
		public void report(int number)
		{
			actions.add("report " + number);
		}

		@Override
		public void openBoard(int number, int teamNumber)
		{
			actions.add("board " + number + " team " + teamNumber);
		}

		@Override
		public void openTile(int number, int teamNumber, int tile)
		{
			actions.add("tile " + number + " team " + teamNumber + " #" + tile);
		}

		@Override
		public void setBoardOpen(int number, boolean open)
		{
			actions.add((open ? "show board " : "hide board ") + number);
		}

		@Override
		public void vote(int number, int candidate)
		{
			actions.add("vote " + number + " #" + candidate);
		}

		@Override
		public void setStandingsHidden(int number, boolean hidden)
		{
			actions.add((hidden ? "hide standings " : "show standings ") + number);
		}

		@Override
		public void setCodewordHidden(int number, boolean hidden)
		{
			actions.add((hidden ? "hide codeword " : "show codeword ") + number);
		}

		@Override
		public void setByGroup(boolean byGroup)
		{
			actions.add(byGroup ? "by group" : "flat");
		}

		@Override
		public void setGroupCollapsed(int key, boolean collapsed)
		{
			actions.add((collapsed ? "collapse " : "expand ") + key);
		}
	}, MiniBoardView.TileIcons.NONE);

	private static EventCard card(int number, CardState state, List<String> standing)
	{
		boolean live = state == CardState.TRACKING || state == CardState.ATTENTION;
		return new EventCard(number, "Event " + number, state, "detail " + number, standing, null, List.of(), "", null,
			EventCard.OPEN_EVENT_PAGE, false, false, live, null, false, null, null);
	}

	private static PanelState discovered(EventCard... cards)
	{
		return PanelState.builder().sending(true).rsn("Iron Man").status(ConnectionStatus.CONNECTED)
			.cards(List.of(cards)).build();
	}

	private static final DiscoveryResponse.Group IRON = new DiscoveryResponse.Group(300000, "Iron clan");
	private static final DiscoveryResponse.Group BANDOS = new DiscoveryResponse.Group(200000, "Bandos boys");

	private static EventCard grouped(int number, CardState state, DiscoveryResponse.Group group)
	{
		return card(number, state, List.of()).withGroup(group);
	}

	private static PanelState byGroup(Set<Integer> collapsed, EventCard... cards)
	{
		return discovered(cards).toBuilder().byGroup(true).collapsedGroups(collapsed).build();
	}

	/** The cards list as shown: a header's name, or a card's Event number. */
	private List<String> shownOrder()
	{
		List<String> shown = new ArrayList<>();
		for (Component c : panel.cards.getComponents())
		{
			if (c instanceof FriendscapePanel.GroupHeader)
			{
				shown.add(((FriendscapePanel.GroupHeader) c).name.getText());
			}
			else if (c instanceof FriendscapePanel.CardView && c.isVisible())
			{
				shown.add(((FriendscapePanel.CardView) c).name.getText());
			}
		}
		return shown;
	}

	@Test
	public void theGroupToggleSwitchesBetweenFlatAndByGroup()
	{
		panel.update(discovered(card(111111, CardState.TRACKING, List.of())));
		assertTrue(panel.groupToggle.isVisible());
		assertEquals("Show by Group", panel.groupToggle.getToolTipText());
		panel.groupToggle.doClick();

		panel.update(byGroup(Set.of(), card(111111, CardState.TRACKING, List.of())));
		assertEquals("Show as one list", panel.groupToggle.getToolTipText());
		panel.groupToggle.doClick();

		assertEquals(List.of("by group", "flat"), actions);
	}

	@Test
	public void flatShowsNoGroupHeaders()
	{
		panel.update(discovered(grouped(111111, CardState.TRACKING, IRON), card(222222, CardState.ENDED, List.of())));

		assertEquals(List.of("Event 111111", "Event 222222"), shownOrder());
	}

	@Test
	public void byGroupShowsAHeaderPerGroupWithItsCardsBeneathAndOtherEventsLast()
	{
		panel.update(byGroup(Set.of(), card(111111, CardState.TRACKING, List.of()),
			grouped(222222, CardState.TRACKING, IRON), grouped(333333, CardState.TRACKING, BANDOS),
			grouped(444444, CardState.ENDED, IRON)));

		assertEquals(List.of("Bandos boys", "Event 333333", "Iron clan", "Event 222222", "Event 444444",
			CardGroups.OTHER_EVENTS, "Event 111111"), shownOrder());
		// Expanded: no count or dot, which would only repeat the cards beneath
		FriendscapePanel.GroupHeader header = panel.groupHeaders.get(0);
		assertFalse(header.count.isVisible());
		assertFalse(header.dot.isVisible());
	}

	@Test
	public void aCollapsedHeaderHidesItsCardsAndShowsTheirCountAndMostUrgentState()
	{
		panel.update(byGroup(Set.of(IRON.getNumber(), CardGroups.OTHER_KEY), card(111111, CardState.ENDED, List.of()),
			grouped(222222, CardState.TRACKING, IRON), grouped(333333, CardState.TRACKING, BANDOS),
			grouped(444444, CardState.ATTENTION, IRON)));

		assertEquals(List.of("Bandos boys", "Event 333333", "Iron clan", CardGroups.OTHER_EVENTS), shownOrder());
		FriendscapePanel.GroupHeader iron = panel.groupHeaders.get(1);
		assertEquals("2", iron.count.getText());
		assertTrue(iron.dot.isVisible());
		assertEquals(CardState.ATTENTION.getColor(), iron.dot.getForeground());
		// Nothing there needs action: no dot
		FriendscapePanel.GroupHeader other = panel.groupHeaders.get(2);
		assertEquals("1", other.count.getText());
		assertFalse(other.dot.isVisible());
	}

	@Test
	public void clickingAHeaderAsksToCollapseOrExpandIt()
	{
		panel.update(byGroup(Set.of(CardGroups.OTHER_KEY), card(111111, CardState.ENDED, List.of()),
			grouped(222222, CardState.TRACKING, IRON)));

		panel.groupHeaders.get(0).doClick();
		panel.groupHeaders.get(1).doClick();

		assertEquals(List.of("collapse 300000", "expand 0"), actions);
	}

	@Test
	public void showsDisclosureAndTurnOnButtonWhileSendingIsOff()
	{
		panel.update(PanelState.builder().sending(false).build());

		assertTrue(panel.disclosure.isVisible());
		assertTrue(panel.turnOnButton.isVisible());
	}

	@Test
	public void hidesDisclosureWhileSendingIsOn()
	{
		panel.update(PanelState.builder().sending(true).status(ConnectionStatus.CONNECTED).build());

		assertFalse(panel.disclosure.isVisible());
		assertFalse(panel.turnOnButton.isVisible());
	}

	@Test
	public void hasNoSendingCheckbox()
	{
		panel.update(PanelState.builder().sending(true).build());

		assertFalse(contains(panel, JCheckBox.class));
	}

	private static boolean contains(Container container, Class<?> type)
	{
		for (Component child : container.getComponents())
		{
			if (type.isInstance(child) || (child instanceof Container && contains((Container) child, type)))
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void turnOnButtonRequestsSendingOn()
	{
		panel.update(PanelState.builder().sending(false).build());

		panel.turnOnButton.doClick();

		assertEquals(List.of(true), sendingRequests);
	}

	@Test
	public void showsPlayingAsOnceTheRsnIsKnown()
	{
		panel.update(PanelState.builder().sending(true).build());
		assertFalse(panel.playingAs.isVisible());

		panel.update(PanelState.builder().sending(true).rsn("Zezima").build());
		assertTrue(panel.playingAs.isVisible());
		assertEquals("Playing as Zezima", panel.playingAs.getText());
	}

	@Test
	public void explainsWhenTheWorldIsNotTracked()
	{
		panel.update(PanelState.builder().sending(true).status(ConnectionStatus.WORLD_NOT_TRACKED).build());

		assertEquals(ConnectionStatus.WORLD_NOT_TRACKED.getText(), panel.statusText.getText());
	}

	@Test
	public void showsNoCardsAndNoEmptyStateBeforeDiscoveryAnswers()
	{
		panel.update(PanelState.builder().sending(true).rsn("Iron Man").build());

		assertTrue(panel.cardViews.isEmpty());
		assertFalse(panel.emptyState.isVisible());
	}

	@Test
	public void showsTheEmptyStateWhenOnNoRoster()
	{
		panel.update(discovered());

		assertTrue(panel.emptyState.isVisible());
		assertTrue(panel.emptyState.getText().contains("No Events yet. Ask your organizer to add Iron Man."));
	}

	@Test
	public void showsOneCardPerEventWithItsDetailAndStanding()
	{
		panel.update(discovered(card(111111, CardState.TRACKING, List.of("1st of 2, +5 xp")),
			card(222222, CardState.ENDED, List.of())));

		assertFalse(panel.emptyState.isVisible());
		assertEquals(2, panel.cardViews.size());
		FriendscapePanel.CardView first = panel.cardViews.get(0);
		assertEquals("Event 111111", first.name.getText());
		assertEquals("detail 111111", first.detail.getText());
		assertEquals(List.of("1st of 2, +5 xp"), first.standingTexts());
	}

	@Test
	public void showsACardsNotesBelowItsStanding()
	{
		panel.update(discovered(new EventCard(111111, "Event 111111", CardState.ATTENTION, "1 item needs a screenshot",
			List.of("1st of 2, +5 xp"), null, List.of("3 waiting to send"), "", null, EventCard.OPEN_EVENT_PAGE, true, false, true, null, false, null, null)));

		assertEquals(List.of("1st of 2, +5 xp", "3 waiting to send"), panel.cardViews.get(0).standingTexts());
	}

	@Test
	public void showsTheFullXpGainOnHoverOverTheShortOwnGainLine()
	{
		panel.update(discovered(new EventCard(111111, "Event 111111", CardState.TRACKING, "Tracking",
			List.of("Bandos: 1st of 2, +13,100,000 xp", "+13M xp since start"), "+13,034,431 xp",
			List.of("3 waiting to send"), "", null, EventCard.OPEN_EVENT_PAGE, false, false, true, null, false, null,
			null)));

		Component[] lines = panel.cardViews.get(0).standing.getComponents();
		assertNull(((JLabel) lines[0]).getToolTipText());
		assertEquals("+13,034,431 xp", ((JLabel) lines[1]).getToolTipText());
		assertNull(((JLabel) lines[2]).getToolTipText());
	}

	@Test
	public void linksToTheUploadPageWhileScreenshotsAreNeeded()
	{
		panel.update(discovered(new EventCard(111111, "Event 111111", CardState.ATTENTION, "1 item needs a screenshot",
			List.of(), null, List.of(), "", null, EventCard.OPEN_EVENT_PAGE, true, false, true, null, false, null, null)));
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		assertTrue(view.uploadButton.isVisible());
		view.uploadButton.doClick();

		assertEquals(List.of("upload 111111"), actions);
		assertFalse(panel.cardViews.isEmpty());
		panel.update(discovered(card(222222, CardState.TRACKING, List.of())));
		assertFalse(panel.cardViews.get(0).uploadButton.isVisible());
	}

	@Test
	public void offersReportOnAHeldCardOnce()
	{
		panel.update(discovered(new EventCard(111111, "Event 111111", CardState.ATTENTION,
			"On hold while Friendscape checks this RSN", List.of(), null, List.of(), "", null, EventCard.OPEN_EVENT_PAGE,
			false, true, true, null, false, null, null)));
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		assertTrue(view.reportButton.isVisible());
		view.reportButton.doClick();
		view.reportButton.doClick();

		assertEquals(List.of("report 111111"), actions);
		panel.update(discovered(card(222222, CardState.TRACKING, List.of())));
		assertFalse(panel.cardViews.get(0).reportButton.isVisible());
	}

	/** A 2x2 board: Tile 1 and the free centre stand in for the rest; Red (id 1) is the player's. */
	private static EventCard bingoCard()
	{
		String json = "{\"size\":2,\"tiles\":[{\"number\":1,\"name\":\"Blood shards\",\"tiers\":1},"
			+ "{\"number\":2,\"name\":\"Vorkath\",\"tiers\":3}],\"teams\":["
			+ "{\"team\":2,\"name\":\"Blue\",\"rank\":1,\"of\":2,\"points\":9,\"mine\":false,\"tiles\":["
			+ "{\"state\":\"complete\",\"tier\":1,\"fill\":1},{\"state\":\"open\",\"tier\":null,\"fill\":0},"
			+ "{\"state\":\"empty\",\"tier\":null,\"fill\":0},{\"state\":\"empty\",\"tier\":null,\"fill\":0}]},"
			+ "{\"team\":1,\"name\":\"Red\",\"rank\":2,\"of\":2,\"points\":0,\"mine\":true,\"tiles\":["
			+ "{\"state\":\"open\",\"tier\":null,\"fill\":0.5},{\"state\":\"open\",\"tier\":1,\"fill\":0.25},"
			+ "{\"state\":\"empty\",\"tier\":null,\"fill\":0},{\"state\":\"empty\",\"tier\":null,\"fill\":0}]}]}";
		DiscoveryResponse.Board board = new com.google.gson.Gson().fromJson(json, DiscoveryResponse.Board.class);
		return new EventCard(222222, "Autumn bingo", CardState.TRACKING, "Tracking", List.of("Red: 2nd of 2, 0 pts"),
			null, List.of(), "2nd/2", board, EventCard.OPEN_EVENT_PAGE, false, false, true, null, false, null, null);
	}

	@Test
	public void showsThePlayersTeamBoardFirstWithAChipPerTeam()
	{
		panel.update(discovered(bingoCard()));
		MiniBoardView board = panel.cardViews.get(0).board;

		assertTrue(board.isVisible());
		assertEquals(1, board.shownTeam());
		assertEquals(2, board.chips.size());
		assertTrue(board.chips.get(1).isSelected());
		assertEquals(4, board.cells.size());
		assertEquals("<html>Tile 1: Blood shards<br>50%</html>", board.cells.get(0).tooltip());
		assertEquals("<html>Tile 2: Vorkath<br>Tier 1 of 3, 25% to the next</html>", board.cells.get(1).tooltip());
		assertEquals("Red: 2nd of 2, 0 pts", board.standing.getText());
	}

	@Test
	public void opensThePlayersTeamBoardWhenTheBoardIsTooLargeToDraw()
	{
		EventCard small = bingoCard();
		DiscoveryResponse.Board large = new DiscoveryResponse.Board(8, small.getBoard().getTiles(), small.getBoard().getTeams());
		panel.update(discovered(new EventCard(222222, "Autumn bingo", CardState.TRACKING, "Tracking", List.of(), null, List.of(),
			"", large, EventCard.OPEN_BOARD, false, false, true, null, false, null, null)));
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		assertEquals(null, view.board);
		view.openButton.doClick();

		assertEquals(List.of("board 222222 team 1"), actions);
	}

	@Test
	public void opensTheEventPageBesideAMiniBoard()
	{
		panel.update(discovered(bingoCard()));

		panel.cardViews.get(0).openButton.doClick();

		assertEquals(List.of("open 222222"), actions);
	}

	@Test
	public void aChipSwitchesTeamsAndTheChoiceSurvivesAnUpdate()
	{
		panel.update(discovered(bingoCard()));

		panel.cardViews.get(0).board.chips.get(0).doClick();
		assertEquals("<html>Tile 1: Blood shards<br>Done</html>", panel.cardViews.get(0).board.cells.get(0).tooltip());
		assertEquals("Blue: 1st of 2, 9 pts", panel.cardViews.get(0).board.standing.getText());

		panel.update(discovered(bingoCard()));
		assertEquals(2, panel.cardViews.get(0).board.shownTeam());
	}

	@Test
	public void aClickOnATileOpensItForTheShownTeamButNotOnAnEmptySquare()
	{
		panel.update(discovered(bingoCard()));
		MiniBoardView board = panel.cardViews.get(0).board;

		board.cells.get(1).click();
		board.cells.get(3).click();

		assertEquals(List.of("tile 222222 team 1 #2"), actions);
		assertFalse(board.cells.get(3).opens());
	}

	@Test
	public void theBoardCollapsesAndRemembersPerEvent()
	{
		panel.update(discovered(bingoCard()));
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		view.boardToggle.doClick();

		assertFalse(view.board.isVisible());
		assertEquals("Show board", view.boardToggle.getText());
		assertEquals(List.of("hide board 222222"), actions);

		panel.update(PanelState.builder().sending(true).rsn("Iron Man").cards(List.of(bingoCard()))
			.closedBoards(java.util.Set.of(222222)).build());
		assertFalse(panel.cardViews.get(0).board.isVisible());
	}

	@Test
	public void aMetricCardHasNoBoard()
	{
		panel.update(discovered(card(111111, CardState.TRACKING, List.of())));

		assertEquals(null, panel.cardViews.get(0).board);
		assertFalse(panel.cardViews.get(0).boardToggle.isVisible());
	}

	@Test
	public void leavesOnlyAfterTheConfirm()
	{
		panel.update(discovered(card(111111, CardState.TRACKING, List.of())));
		List<String> asked = new ArrayList<>();

		panel.confirmLeave = name ->
		{
			asked.add(name);
			return false;
		};
		panel.cardViews.get(0).leaveItem.doClick();
		assertTrue(actions.isEmpty());

		panel.confirmLeave = name -> true;
		panel.cardViews.get(0).leaveItem.doClick();

		assertEquals(List.of("Event 111111"), asked);
		assertEquals(List.of("leave 111111"), actions);
	}

	@Test
	public void aLeftCardOffersRejoinInsteadOfLeave()
	{
		panel.update(discovered(card(111111, CardState.LEFT, List.of())));
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		assertTrue(view.rejoinButton.isVisible());
		assertFalse(view.leaveItem.isVisible());

		view.rejoinButton.doClick();

		assertEquals(List.of("rejoin 111111"), actions);
	}

	private static EventCard finalizedCard(CardState state)
	{
		return new EventCard(111111, "Event 111111", state, "Final", List.of(), null, List.of(), "", null,
			EventCard.OPEN_EVENT_PAGE, false, false, false, null, true, null, null);
	}

	@Test
	public void aFinalizedCardHasNoLeaveAndNoMenu()
	{
		panel.update(discovered(finalizedCard(CardState.FINAL)));
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		assertFalse(view.leaveItem.isVisible());
		assertFalse(view.menuButton.isVisible());
		assertFalse(view.rejoinButton.isVisible());
	}

	@Test
	public void aLeftFinalizedCardHasNoRejoin()
	{
		panel.update(discovered(finalizedCard(CardState.LEFT)));
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		assertFalse(view.rejoinButton.isVisible());
		assertFalse(view.menuButton.isVisible());
	}

	@Test
	public void anEndedCardKeepsLeave()
	{
		panel.update(discovered(card(111111, CardState.ENDED, List.of())));
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		assertTrue(view.leaveItem.isVisible());
		assertTrue(view.menuButton.isVisible());
	}

	private static EventCard withCodeword(EventCard card, String codeword)
	{
		return new EventCard(card.getNumber(), card.getName(), card.getState(), card.getDetail(), card.getStanding(),
			card.getGainTooltip(), card.getNotes(), card.getRank(), card.getBoard(), card.getLink(), card.isUpload(), card.isReport(),
			card.isLive(), card.getVote(), card.isFinalized(), codeword, card.getGroup());
	}

	@Test
	public void aLiveCardOffersToHideItsStandingsAndCodeword()
	{
		panel.update(discovered(withCodeword(card(111111, CardState.TRACKING, List.of()), "Mithril")));
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		assertTrue(view.standingsItem.isVisible());
		assertEquals("Hide standings", view.standingsItem.getText());
		assertTrue(view.codewordItem.isVisible());
		assertEquals("Hide codeword", view.codewordItem.getText());

		view.standingsItem.doClick();
		view.codewordItem.doClick();

		assertEquals(List.of("hide standings 111111", "hide codeword 111111"), actions);
	}

	@Test
	public void aHiddenChoiceOffersToShowAgain()
	{
		panel.update(PanelState.builder().sending(true).rsn("Iron Man").status(ConnectionStatus.CONNECTED)
			.cards(List.of(withCodeword(card(111111, CardState.TRACKING, List.of()), "Mithril")))
			.hiddenStandings(Set.of(111111)).hiddenCodewords(Set.of(111111)).build());
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		assertEquals("Show standings", view.standingsItem.getText());
		assertEquals("Show codeword", view.codewordItem.getText());

		view.standingsItem.doClick();
		view.codewordItem.doClick();

		assertEquals(List.of("show standings 111111", "show codeword 111111"), actions);
	}

	@Test
	public void offersTheCodewordItemOnlyWithACodeword()
	{
		panel.update(discovered(card(111111, CardState.TRACKING, List.of())));
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		assertTrue(view.standingsItem.isVisible());
		assertFalse(view.codewordItem.isVisible());
	}

	@Test
	public void offersNoOverlayItemsOnceNotLive()
	{
		for (EventCard card : List.of(card(111111, CardState.ENDED, List.of()), card(111111, CardState.LEFT, List.of()),
			finalizedCard(CardState.FINAL)))
		{
			panel.update(discovered(card));
			FriendscapePanel.CardView view = panel.cardViews.get(0);

			assertFalse(view.standingsItem.isVisible());
			assertFalse(view.codewordItem.isVisible());
		}
	}

	@Test
	public void opensTheEventPage()
	{
		panel.update(discovered(card(111111, CardState.TRACKING, List.of())));

		panel.cardViews.get(0).openButton.doClick();

		assertEquals(List.of("open 111111"), actions);
	}

	@Test
	public void hidesCardsWhileSendingIsOff()
	{
		panel.update(discovered(card(111111, CardState.TRACKING, List.of())));
		panel.update(PanelState.builder().sending(false).rsn("Iron Man").cards(List.of(card(111111, CardState.TRACKING, List.of()))).build());

		assertTrue(panel.cardViews.isEmpty());
		assertFalse(panel.emptyState.isVisible());
	}

	/** A Voting card: Fishing (2 votes, the player's choice unless {@code choice} says otherwise), Mining On Cooldown, Cooking. */
	private static EventCard votingCard(boolean any, String choice)
	{
		String json = "{\"closesAt\":\"2026-11-05T18:00:00.000Z\",\"open\":" + any + ",\"candidates\":["
			+ "{\"id\":1,\"name\":\"Fishing\",\"count\":2,\"cooling\":false},"
			+ "{\"id\":2,\"name\":\"Mining\"" + (any ? "" : ",\"count\":0") + ",\"cooling\":true},"
			+ "{\"id\":3,\"name\":\"Cooking\"" + (any ? "" : ",\"count\":0") + ",\"cooling\":false}],"
			+ "\"choice\":" + choice + "}";
		DiscoveryResponse.Vote vote = new com.google.gson.Gson().fromJson(json, DiscoveryResponse.Vote.class);
		return new EventCard(333333, "Skill of the week #1", CardState.VOTING, "Voting, closes 5 Nov 18:00", List.of(),
			null, List.of(), "", null, EventCard.OPEN_EVENT_PAGE, false, false, false, vote, false, null, null);
	}

	@Test
	public void listsACandidateListBallotWithCountsTheChoiceAndCooldownsDisabled()
	{
		panel.update(discovered(votingCard(false, "1")));
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		assertEquals(List.of("Fishing: 2 (your vote)", "Mining (On Cooldown)", "Cooking: 0"), view.ballotTexts());
		assertTrue(view.ballot.get(0).isSelected());
		assertFalse(view.ballot.get(1).isEnabled());
		assertTrue(view.ballot.get(2).isEnabled());
		assertTrue(view.voteNote.isVisible());
		assertEquals("Cast in game, website changes blocked", view.voteNote.getText());
		assertFalse("the choice is already cast", view.voteButton.isEnabled());

		view.ballot.get(2).doClick();
		assertTrue(view.voteButton.isEnabled());
		view.voteButton.doClick();

		assertEquals(List.of("vote 333333 #3"), actions);
	}

	@Test
	public void offersNoNoteBeforeThePlayerVotes()
	{
		panel.update(discovered(votingCard(false, "null")));
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		assertFalse(view.voteNote.isVisible());
		assertFalse(view.voteButton.isEnabled());
		assertEquals("Vote", view.voteButton.getText());
	}

	@Test
	public void keepsAPickAcrossTheMinutelyRefresh()
	{
		panel.update(discovered(votingCard(false, "null")));
		panel.cardViews.get(0).ballot.get(2).doClick();

		panel.update(discovered(votingCard(false, "null")));

		assertTrue(panel.cardViews.get(0).ballot.get(2).isSelected());
		assertTrue(panel.cardViews.get(0).voteButton.isEnabled());
	}

	@Test
	public void offersAnAnyBallotAsTheTopVotedAndADropdown()
	{
		panel.update(discovered(votingCard(true, "3")));
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		assertTrue(view.ballot.isEmpty());
		assertEquals(List.of("Fishing: 2"), view.tallyTexts());
		assertEquals(3, view.picker.getItemCount());
		assertEquals(3, ((DiscoveryResponse.VoteCandidate) view.picker.getSelectedItem()).getId());
		assertFalse(view.voteButton.isEnabled());

		view.picker.setSelectedIndex(1);
		assertEquals("an On Cooldown Candidate cannot be picked", 3,
			((DiscoveryResponse.VoteCandidate) view.picker.getSelectedItem()).getId());

		view.picker.setSelectedIndex(0);
		view.voteButton.doClick();
		assertEquals(List.of("vote 333333 #1"), actions);
	}

	@Test
	public void showsNoBallotOnAnyOtherCard()
	{
		panel.update(discovered(card(111111, CardState.TRACKING, List.of())));

		assertFalse(panel.cardViews.get(0).voteButton.isVisible());
		assertTrue(panel.cardViews.get(0).ballot.isEmpty());
	}
}
