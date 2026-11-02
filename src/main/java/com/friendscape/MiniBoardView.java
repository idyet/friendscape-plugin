package com.friendscape;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * A bingo card's mini board (SPEC 10.3): a Team dropdown over one Team's grid. Cells show the Tile's
 * icon or number, green at the top Tier, amber with some Tier reached, a partial fill toward the next
 * Tier; hover names the Tile, its points and the next Tier's, a click opens it on the website.
 * Nothing on it is editable.
 */
class MiniBoardView extends JPanel
{
	/** Loads a Tile's picture, calling back on any thread, possibly more than once as it loads. */
	interface TileIcons
	{
		TileIcons NONE = (icon, onLoaded) -> { };

		void load(DiscoveryResponse.TileIcon icon, Consumer<BufferedImage> onLoaded);
	}

	/** What the board asks of the card. Called on the Swing thread. */
	interface Listener
	{
		void teamChosen(int teamNumber);

		void openTile(int teamNumber, int number);
	}

	/** What a card leaves for the grid: the side panel less its border and the card's padding. */
	private static final int WIDTH = PluginPanel.PANEL_WIDTH - 20 - 16;
	/** Item sprites are 36x32; larger squares only waste room. */
	private static final int MAX_CELL = 32;
	private static final int GAP = 2;
	/** Between the dropdown and the grid. */
	private static final int PICKER_GAP = 4;
	/** What a dropdown line leaves for text: the card width less the arrow button and insets. */
	static final int LABEL_WIDTH = WIDTH - 28;
	private static final String ELLIPSIS = "...";
	private static final Color FILL = new Color(ColorScheme.PROGRESS_COMPLETE_COLOR.getRed(),
		ColorScheme.PROGRESS_COMPLETE_COLOR.getGreen(), ColorScheme.PROGRESS_COMPLETE_COLOR.getBlue(), 110);

	/** Every Team in standings order with its points, the player's marked "(you)". */
	final JComboBox<DiscoveryResponse.BoardTeam> teamPicker = new JComboBox<>();
	final List<Cell> cells = new ArrayList<>();

	private final DiscoveryResponse.Board board;
	private final TileIcons icons;
	private final Listener listener;
	private final JPanel grid = new JPanel();
	private int shownTeam;

	MiniBoardView(DiscoveryResponse.Board board, int teamNumber, TileIcons icons, Listener listener)
	{
		this.board = board;
		this.icons = icons;
		this.listener = listener;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);

		List<DiscoveryResponse.BoardTeam> teams = board.teams();
		for (DiscoveryResponse.BoardTeam team : teams)
		{
			teamPicker.addItem(team);
		}
		teamPicker.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean isSelected, boolean cellHasFocus)
			{
				// The popup list narrows under a scrollbar, so fit to it once laid out
				int width = list.getWidth() > 0 ? Math.min(LABEL_WIDTH, list.getWidth() - 4) : LABEL_WIDTH;
				String text = value == null ? ""
					: label((DiscoveryResponse.BoardTeam) value, list.getFontMetrics(teamPicker.getFont()), width);
				Component line = super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
				// The player's own Teams in the brand orange; a highlighted row keeps the selection colours
				if (!isSelected && value != null)
				{
					line.setForeground(((DiscoveryResponse.BoardTeam) value).isMine()
						? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
				}
				return line;
			}
		});
		teamPicker.setFont(FontManager.getRunescapeSmallFont());
		teamPicker.setFocusable(false);
		teamPicker.setAlignmentX(LEFT_ALIGNMENT);
		// Full card width whatever the longest name, so a long one cannot widen the card
		Dimension pickerSize = new Dimension(WIDTH, teamPicker.getPreferredSize().height);
		teamPicker.setPreferredSize(pickerSize);
		teamPicker.setMaximumSize(pickerSize);
		// One Team has nothing to switch to
		boolean switchable = teams.size() > 1;
		teamPicker.setVisible(switchable);

		int size = board.side();
		int cell = cellSize(size);
		grid.setLayout(new GridLayout(size, size, GAP, GAP));
		grid.setOpaque(false);
		grid.setAlignmentX(LEFT_ALIGNMENT);
		Dimension gridSize = new Dimension(size * cell + (size - 1) * GAP, size * cell + (size - 1) * GAP);
		grid.setPreferredSize(gridSize);
		grid.setMaximumSize(gridSize);
		grid.setBorder(BorderFactory.createEmptyBorder());

		Component pickerGap = Box.createVerticalStrut(PICKER_GAP);
		pickerGap.setVisible(switchable);

		add(teamPicker);
		add(pickerGap);
		add(grid);
		showTeam(teamNumber);
		// Added after the first addItem and showTeam, whose selections are not picks
		teamPicker.addActionListener(e ->
		{
			DiscoveryResponse.BoardTeam picked = (DiscoveryResponse.BoardTeam) teamPicker.getSelectedItem();
			if (picked != null && picked.getTeam() != shownTeam)
			{
				showTeam(picked.getTeam());
				listener.teamChosen(shownTeam);
			}
		});
	}

	/** A dropdown line: the Team's name, marked when the player is on it, and its points. */
	static String label(DiscoveryResponse.BoardTeam team)
	{
		return (team.getName() == null ? "" : team.getName()) + suffix(team);
	}

	/** A dropdown line fitted to {@code width}: only the name is cut, so the points always show. */
	static String label(DiscoveryResponse.BoardTeam team, FontMetrics metrics, int width)
	{
		String name = team.getName() == null ? "" : team.getName();
		String suffix = suffix(team);
		if (metrics.stringWidth(name + suffix) <= width)
		{
			return name + suffix;
		}
		int keep = name.length();
		while (keep > 0 && metrics.stringWidth(name.substring(0, keep) + ELLIPSIS + suffix) > width)
		{
			keep--;
		}
		return name.substring(0, keep).trim() + ELLIPSIS + suffix;
	}

	private static String suffix(DiscoveryResponse.BoardTeam team)
	{
		return (team.isMine() ? " (you)" : "") + String.format(Locale.ENGLISH, ": %,d pts", team.getPoints());
	}

	/** The side of one square: as large as the card allows, up to a sprite's size. */
	static int cellSize(int size)
	{
		return Math.min(MAX_CELL, (WIDTH - (size - 1) * GAP) / Math.max(1, size));
	}

	/** The Team whose board is shown. */
	int shownTeam()
	{
		return shownTeam;
	}

	/** Shows a Team's board; an unknown Team shows the first. */
	void showTeam(int teamNumber)
	{
		List<DiscoveryResponse.BoardTeam> teams = board.teams();
		DiscoveryResponse.BoardTeam team = teams.isEmpty() ? null : teams.get(0);
		for (DiscoveryResponse.BoardTeam candidate : teams)
		{
			if (candidate.getTeam() == teamNumber)
			{
				team = candidate;
			}
		}
		shownTeam = team == null ? teamNumber : team.getTeam();
		teamPicker.setSelectedItem(team);

		grid.removeAll();
		cells.clear();
		int squares = board.side() * board.side();
		for (int position = 0; position < squares; position++)
		{
			List<DiscoveryResponse.TeamTile> tiles = team == null || team.getTiles() == null ? List.of() : team.getTiles();
			DiscoveryResponse.TeamTile state = position < tiles.size() ? tiles.get(position) : null;
			Cell cell = new Cell(board.tile(position + 1), state);
			cells.add(cell);
			grid.add(cell);
		}
		grid.revalidate();
		grid.repaint();
	}

	/** One square: the Tile's icon or number over its fill. */
	class Cell extends JComponent
	{
		final DiscoveryResponse.BoardTile tile;
		final DiscoveryResponse.TeamTile state;
		private volatile BufferedImage image;

		Cell(DiscoveryResponse.BoardTile tile, DiscoveryResponse.TeamTile state)
		{
			this.tile = tile;
			this.state = state;
			setToolTipText(tooltip());
			if (tile != null && tile.getIcon() != null)
			{
				icons.load(tile.getIcon(), loaded ->
				{
					image = loaded;
					SwingUtilities.invokeLater(this::repaint);
				});
			}
			if (opens())
			{
				setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				addMouseListener(new MouseAdapter()
				{
					@Override
					public void mouseClicked(MouseEvent e)
					{
						click();
					}
				});
			}
		}

		/** A stored Tile opens its detail; empty squares and the free centre have none. */
		boolean opens()
		{
			return tile != null && !isFree();
		}

		void click()
		{
			if (opens())
			{
				listener.openTile(shownTeam, tile.getNumber());
			}
		}

		private boolean isFree()
		{
			return state != null && DiscoveryResponse.TeamTile.FREE.equals(state.getState());
		}

		private boolean isDone()
		{
			return state != null && (DiscoveryResponse.TeamTile.COMPLETE.equals(state.getState()) || isFree());
		}

		/** Some Tier reached with more left; an older server sends no gain, so its Tiles read as done. */
		boolean partial()
		{
			return isDone() && !isFree() && state.getGain() != null;
		}

		/** Hover text: the Tile's name, the shown Team's points and what the next Tier is worth. */
		String tooltip()
		{
			if (isFree())
			{
				return "Free";
			}
			if (tile == null)
			{
				return null;
			}
			String name = tile.getName() == null ? "" : ": " + escape(tile.getName());
			return "<html>Tile " + tile.getNumber() + name + "<br>" + progress() + "</html>";
		}

		private String progress()
		{
			if (state == null || state.getPoints() == null || (!isDone() && state.getGain() == null))
			{
				return legacyProgress();
			}
			if (isDone() && !partial())
			{
				return String.format(Locale.ENGLISH, "Completed: %,d pts", state.getPoints());
			}
			int percent = (int) Math.floor(state.getFill() * 100);
			int gain = state.getGain();
			Integer tier = state.getTier();
			String next = tile.getTiers() <= 1 ? "complete" : "Tier " + (tier == null ? 1 : tier + 1);
			String toNext = String.format(Locale.ENGLISH, "%d%% to %s: +%,d pts", percent, next, gain);
			return tier == null ? toNext
				: String.format(Locale.ENGLISH, "Tier %d of %d: %,d pts<br>%s", tier, tile.getTiers(), state.getPoints(), toNext);
		}

		/** An older server's tooltip, without points: how far the Team is. */
		private String legacyProgress()
		{
			Integer tier = state == null ? null : state.getTier();
			String reached = tier != null && tile.getTiers() > 1 ? "Tier " + tier + " of " + tile.getTiers() : null;
			if (isDone())
			{
				return reached == null ? "Done" : reached + ", done";
			}
			int percent = state == null ? 0 : (int) Math.floor(state.getFill() * 100);
			return reached == null ? percent + "%" : reached + ", " + percent + "% to the next";
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				int w = getWidth();
				int h = getHeight();
				g2.setColor(tile == null && !isFree() ? ColorScheme.DARKER_GRAY_COLOR.darker() : ColorScheme.DARK_GRAY_COLOR);
				g2.fillRect(0, 0, w, h);
				if (partial())
				{
					g2.setColor(ColorScheme.PROGRESS_INPROGRESS_COLOR);
					g2.fillRect(0, 0, w, h);
				}
				else if (isDone())
				{
					g2.setColor(ColorScheme.PROGRESS_COMPLETE_COLOR);
					g2.fillRect(0, 0, w, h);
				}
				// The fill rises toward the next Tier on an open Tile and over a partial one's amber
				if (state != null && state.getFill() > 0 && (!isDone() || partial()))
				{
					int filled = (int) Math.round(h * Math.min(1, state.getFill()));
					g2.setColor(FILL);
					g2.fillRect(0, h - filled, w, filled);
				}
				BufferedImage shown = image;
				if (shown != null)
				{
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					double scale = Math.min(1, Math.min((double) (w - 2) / shown.getWidth(), (double) (h - 2) / shown.getHeight()));
					int iw = (int) (shown.getWidth() * scale);
					int ih = (int) (shown.getHeight() * scale);
					g2.drawImage(shown, (w - iw) / 2, (h - ih) / 2, iw, ih, null);
				}
				else if (tile != null)
				{
					g2.setFont(FontManager.getRunescapeSmallFont());
					g2.setColor(ColorScheme.TEXT_COLOR);
					String text = Integer.toString(tile.getNumber());
					FontMetrics metrics = g2.getFontMetrics();
					g2.drawString(text, (w - metrics.stringWidth(text)) / 2, (h + metrics.getAscent()) / 2 - 1);
				}
			}
			finally
			{
				g2.dispose();
			}
		}
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
