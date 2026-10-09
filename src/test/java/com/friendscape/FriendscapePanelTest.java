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
		public void leave(String slug)
		{
			actions.add("leave " + slug);
		}

		@Override
		public void rejoin(String slug)
		{
			actions.add("rejoin " + slug);
		}

		@Override
		public void openEventPage(String slug)
		{
			actions.add("open " + slug);
		}
	});

	private static EventCard card(String slug, CardState state, List<String> standing)
	{
		return new EventCard(slug, "Event " + slug, state, "detail " + slug, standing, "");
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
		panel.update(discovered(card("a", CardState.TRACKING, List.of("1st of 2, +5 xp")),
			card("b", CardState.ENDED, List.of())));

		assertFalse(panel.emptyState.isVisible());
		assertEquals(2, panel.cardViews.size());
		FriendscapePanel.CardView first = panel.cardViews.get(0);
		assertEquals("Event a", first.name.getText());
		assertEquals("detail a", first.detail.getText());
		assertEquals(List.of("1st of 2, +5 xp"), first.standingTexts());
	}

	@Test
	public void leavesOnlyAfterTheConfirm()
	{
		panel.update(discovered(card("a", CardState.TRACKING, List.of())));
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

		assertEquals(List.of("Event a"), asked);
		assertEquals(List.of("leave a"), actions);
	}

	@Test
	public void aLeftCardOffersRejoinInsteadOfLeave()
	{
		panel.update(discovered(card("a", CardState.LEFT, List.of())));
		FriendscapePanel.CardView view = panel.cardViews.get(0);

		assertTrue(view.rejoinButton.isVisible());
		assertFalse(view.leaveItem.isVisible());

		view.rejoinButton.doClick();

		assertEquals(List.of("rejoin a"), actions);
	}

	@Test
	public void opensTheEventPage()
	{
		panel.update(discovered(card("a", CardState.TRACKING, List.of())));

		panel.cardViews.get(0).openButton.doClick();

		assertEquals(List.of("open a"), actions);
	}

	@Test
	public void hidesCardsWhileSendingIsOff()
	{
		panel.update(discovered(card("a", CardState.TRACKING, List.of())));
		panel.update(PanelState.builder().sending(false).rsn("Iron Man").cards(List.of(card("a", CardState.TRACKING, List.of()))).build());

		assertTrue(panel.cardViews.isEmpty());
		assertFalse(panel.emptyState.isVisible());
	}
}
