package com.friendscape;

import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
	}, MiniBoardView.TileIcons.NONE);

	private static EventCard card(int number, CardState state, List<String> standing)
	{
		return new EventCard(number, "Event " + number, state, "detail " + number, standing, List.of(), "", null,
			EventCard.OPEN_EVENT_PAGE, false, false, true, null);
	}

	private static PanelState discovered(EventCard... cards)
	{
		return PanelState.builder().sending(true).rsn("Iron Man").status(ConnectionStatus.CONNECTED)
			.cards(List.of(cards)).build();
	}

	@Test
	public void showsDisclosureAndTurnOnButtonWhileSendingIsOff()
	{
		panel.update(PanelState.builder().sending(false).build());

		assertTrue(panel.disclosure.isVisible());
		assertTrue(panel.turnOnButton.isVisible());
		assertFalse(panel.sendingToggle.isSelected());
	}

	@Test
	public void hidesDisclosureWhileSendingIsOn()
	{
		panel.update(PanelState.builder().sending(true).status(ConnectionStatus.CONNECTED).build());

		assertFalse(panel.disclosure.isVisible());
		assertFalse(panel.turnOnButton.isVisible());
		assertTrue(panel.sendingToggle.isSelected());
	}

	@Test
	public void turnOnButtonRequestsSendingOn()
	{
		panel.update(PanelState.builder().sending(false).build());

		panel.turnOnButton.doClick();

		assertEquals(List.of(true), sendingRequests);
	}

	@Test
	public void toggleRequestsTheFlippedValue()
	{
		panel.update(PanelState.builder().sending(true).build());

		panel.sendingToggle.doClick();

		assertEquals(List.of(false), sendingRequests);
	}

	@Test
	public void updateDoesNotEchoBackAsARequest()
	{
		panel.update(PanelState.builder().sending(true).build());
		panel.update(PanelState.builder().sending(false).build());

		assertTrue(sendingRequests.isEmpty());
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
			List.of("1st of 2, +5 xp"), List.of("3 waiting to send"), "", null, EventCard.OPEN_EVENT_PAGE, true, false, true, null)));

		assertEquals(List.of("1st of 2, +5 xp", "3 waiting to send"), panel.cardViews.get(0).standingTexts());
	}

	@Test
	public void linksToTheUploadPageWhileScreenshotsAreNeeded()
	{
		panel.update(discovered(new EventCard(111111, "Event 111111", CardState.ATTENTION, "1 item needs a screenshot",
			List.of(), List.of(), "", null, EventCard.OPEN_EVENT_PAGE, true, false, true, null)));
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
			"On hold while Friendscape checks this RSN", List.of(), List.of(), "", null, EventCard.OPEN_EVENT_PAGE,
			false, true, true, null)));
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
			List.of(), "2nd/2", board, EventCard.OPEN_EVENT_PAGE, false, false, true, null);
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
		panel.update(discovered(new EventCard(222222, "Autumn bingo", CardState.TRACKING, "Tracking", List.of(), List.of(),
			"", large, EventCard.OPEN_BOARD, false, false, true, null)));
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
			List.of(), "", null, EventCard.OPEN_EVENT_PAGE, false, false, false, vote);
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
