package com.friendscape;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/** In-game list of each Event with its status dot and the player's rank (SPEC 10.3). */
class FriendscapeOverlay extends OverlayPanel
{
	private final BooleanSupplier shown;
	private volatile List<EventCard> cards = List.of();

	FriendscapeOverlay(FriendscapePlugin plugin, BooleanSupplier shown)
	{
		super(plugin);
		this.shown = shown;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	void setCards(List<EventCard> cards)
	{
		this.cards = cards;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		List<EventCard> current = cards;
		if (!shown.getAsBoolean() || current.isEmpty())
		{
			return null;
		}
		for (EventCard card : current)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("● " + card.getName())
				.leftColor(card.getState().getColor())
				.right(card.getRank())
				.build());
		}
		return super.render(graphics);
	}
}
