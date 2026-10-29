package com.friendscape;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/**
 * Painted stand-ins for Unicode symbols. RuneLite's RuneScape fonts lack glyphs like U+25CF and
 * U+22EF, which render as a missing-glyph box, so the panel draws them instead. Each paints in its
 * component's foreground color, so {@code setForeground} still recolors it.
 */
final class Glyphs
{
	private Glyphs()
	{
	}

	/** A filled circle: the state and connection dot. */
	static Icon dot()
	{
		return new Painted(8, 8, (g, x, y) -> g.fillOval(x, y, 8, 8));
	}

	/** Three horizontal dots: the card's "More" menu. */
	static Icon more()
	{
		return new Painted(14, 4, (g, x, y) ->
		{
			for (int i = 0; i < 3; i++)
			{
				g.fillOval(x + i * 5, y, 4, 4);
			}
		});
	}

	/** Two headers each over an indented line: the panel's By Group toggle. */
	static Icon groups()
	{
		return new Painted(12, 12, (g, x, y) ->
		{
			for (int i = 0; i < 2; i++)
			{
				g.fillRect(x, y + i * 7, 12, 2);
				g.fillRect(x + 4, y + i * 7 + 3, 8, 2);
			}
		});
	}

	/** A small triangle pointing down when open, right when collapsed: a Group header's state. */
	static Icon chevron(boolean open)
	{
		return new Painted(8, 8, (g, x, y) ->
		{
			if (open)
			{
				g.fillPolygon(new int[]{x, x + 8, x + 4}, new int[]{y + 2, y + 2, y + 6}, 3);
			}
			else
			{
				g.fillPolygon(new int[]{x + 2, x + 6, x + 2}, new int[]{y, y + 4, y + 8}, 3);
			}
		});
	}

	private interface Painter
	{
		void paint(Graphics2D g, int x, int y);
	}

	private static final class Painted implements Icon
	{
		private final int width;
		private final int height;
		private final Painter painter;

		Painted(int width, int height, Painter painter)
		{
			this.width = width;
			this.height = height;
			this.painter = painter;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(c.isEnabled() ? c.getForeground() : c.getForeground().darker());
				painter.paint(g2, x, y);
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override
		public int getIconWidth()
		{
			return width;
		}

		@Override
		public int getIconHeight()
		{
			return height;
		}
	}
}
