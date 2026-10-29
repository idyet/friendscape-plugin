package com.friendscape;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/** In-game list of each live Event, its name colored by status, with the player's rank (SPEC 10.3). */
class FriendscapeOverlay extends OverlayPanel
{
	private final BooleanSupplier shown;
	private volatile List<EventCard> rows = List.of();

	FriendscapeOverlay(FriendscapePlugin plugin, BooleanSupplier shown)
	{
		super(plugin);
		this.shown = shown;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	/** The cards, less those whose standings the player hid from the card's menu. */
	void setCards(List<EventCard> cards, Set<Integer> hidden)
	{
		this.rows = rows(cards, hidden);
	}

	/** The Live cards not hidden from the standings overlay (SPEC 10.3), in card order. */
	static List<EventCard> rows(List<EventCard> cards, Set<Integer> hidden)
	{
		List<EventCard> rows = new ArrayList<>();
		for (EventCard card : cards)
		{
			if (card.isLive() && !hidden.contains(card.getNumber()))
			{
				rows.add(card);
			}
		}
		return rows;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		List<EventCard> current = rows;
		if (!shown.getAsBoolean() || current.isEmpty())
		{
			return null;
		}
		for (EventCard card : current)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(card.getName())
				.leftColor(card.getState().getColor())
				.right(card.getRank())
				.build());
		}
		return super.render(graphics);
	}
}
