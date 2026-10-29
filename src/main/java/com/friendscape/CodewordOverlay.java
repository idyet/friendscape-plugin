package com.friendscape;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * One Live Event's Codeword in game, so a screenshot taken for a manual upload carries it (SPEC
 * 10.3): the Codeword, then the local clock in UTC. Named by Event number, so a dragged position
 * sticks to its Event.
 */
class CodewordOverlay extends OverlayPanel
{
	private static final DateTimeFormatter TIMESTAMP =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);
	/** The space between the Codeword and the timestamp. */
	private static final int GAP = 10;

	private final int number;
	private final BooleanSupplier shown;
	private final BooleanSupplier timestamped;
	private volatile String codeword = "";

	CodewordOverlay(Plugin plugin, int number, BooleanSupplier shown, BooleanSupplier timestamped)
	{
		super(plugin);
		this.number = number;
		this.shown = shown;
		this.timestamped = timestamped;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public String getName()
	{
		return "friendscape-codeword-" + number;
	}

	String getCodeword()
	{
		return codeword;
	}

	void setCodeword(String codeword)
	{
		this.codeword = codeword;
	}

	/** The overlay's clock: UTC to the minute, e.g. {@code 2026-11-03 12:05 UTC}. */
	static String timestamp(Instant now)
	{
		return TIMESTAMP.format(now);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!shown.getAsBoolean())
		{
			return null;
		}
		String current = codeword;
		String time = timestamped.getAsBoolean() ? timestamp(Instant.now()) : "";
		// Wide enough for one line: the default panel width would wrap a long Codeword
		FontMetrics metrics = graphics.getFontMetrics();
		int width = metrics.stringWidth(current) + (time.isEmpty() ? 0 : GAP + metrics.stringWidth(time))
			+ 2 * ComponentConstants.STANDARD_BORDER;
		panelComponent.setPreferredSize(new Dimension(width, 0));
		panelComponent.getChildren().add(LineComponent.builder()
			.left(current)
			.right(time)
			.build());
		return super.render(graphics);
	}
}
