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
	private final FriendscapePanel panel = new FriendscapePanel(sendingRequests::add);

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
}
