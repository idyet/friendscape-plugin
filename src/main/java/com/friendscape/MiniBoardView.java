package com.friendscape;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * A bingo card's mini board (SPEC 10.3): Team chips over one Team's grid. Cells show the Tile's
 * icon or number, green when done, a partial fill toward the next Tier; hover names the Tile and
 * its progress, a click opens it on the website. Nothing on it is editable.
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
		void teamChosen(int teamId);

		void openTile(int teamId, int number);
	}

	/** What a card leaves for the grid: the side panel less its border and the card's padding. */
	private static final int WIDTH = PluginPanel.PANEL_WIDTH - 20 - 16;
	/** Item sprites are 36x32; larger squares only waste room. */
	private static final int MAX_CELL = 32;
	private static final int GAP = 2;
	private static final Color FILL = new Color(ColorScheme.PROGRESS_COMPLETE_COLOR.getRed(),
		ColorScheme.PROGRESS_COMPLETE_COLOR.getGreen(), ColorScheme.PROGRESS_COMPLETE_COLOR.getBlue(), 110);

	final List<JToggleButton> chips = new ArrayList<>();
	/** The shown Team's place, as chips switch standing with the board. */
	final JLabel standing = new JLabel();
	final List<Cell> cells = new ArrayList<>();

	private final DiscoveryResponse.Board board;
	private final TileIcons icons;
	private final Listener listener;
	private final JPanel grid = new JPanel();
	private int shownTeam;

	MiniBoardView(DiscoveryResponse.Board board, int teamId, TileIcons icons, Listener listener)
	{
		this.board = board;
		this.icons = icons;
		this.listener = listener;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);

		JPanel chipRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
		chipRow.setOpaque(false);
		chipRow.setAlignmentX(LEFT_ALIGNMENT);
		for (DiscoveryResponse.BoardTeam team : board.teams())
		{
			JToggleButton chip = new JToggleButton(team.getName());
			chip.setFont(FontManager.getRunescapeSmallFont());
			chip.setFocusable(false);
			chip.setMargin(new Insets(1, 4, 1, 4));
			chip.addActionListener(e ->
			{
				showTeam(team.getTeamId());
				listener.teamChosen(team.getTeamId());
			});
			chips.add(chip);
			chipRow.add(chip);
		}
		// One Team has nothing to switch to
		chipRow.setVisible(chips.size() > 1);

		int size = board.side();
		int cell = cellSize(size);
		grid.setLayout(new GridLayout(size, size, GAP, GAP));
		grid.setOpaque(false);
		grid.setAlignmentX(LEFT_ALIGNMENT);
		Dimension gridSize = new Dimension(size * cell + (size - 1) * GAP, size * cell + (size - 1) * GAP);
		grid.setPreferredSize(gridSize);
		grid.setMaximumSize(gridSize);
		grid.setBorder(BorderFactory.createEmptyBorder());

		standing.setFont(FontManager.getRunescapeSmallFont());
		standing.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		standing.setAlignmentX(LEFT_ALIGNMENT);
		standing.setVisible(chips.size() > 1);

		add(chipRow);
		add(standing);
		add(grid);
		showTeam(teamId);
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
	void showTeam(int teamId)
	{
		List<DiscoveryResponse.BoardTeam> teams = board.teams();
		DiscoveryResponse.BoardTeam team = teams.isEmpty() ? null : teams.get(0);
		for (DiscoveryResponse.BoardTeam candidate : teams)
		{
			if (candidate.getTeamId() == teamId)
			{
				team = candidate;
			}
		}
		shownTeam = team == null ? teamId : team.getTeamId();
		standing.setText(team == null ? "" : team.getName() + ": " + EventCard.bingoPlace(team));
		for (int i = 0; i < chips.size(); i++)
		{
			chips.get(i).setSelected(teams.get(i).getTeamId() == shownTeam);
		}

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

		/** Hover text: the Tile's name and how far the shown Team is. */
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
				if (isDone())
				{
					g2.setColor(ColorScheme.PROGRESS_COMPLETE_COLOR);
					g2.fillRect(0, 0, w, h);
				}
				else if (state != null && state.getFill() > 0)
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
