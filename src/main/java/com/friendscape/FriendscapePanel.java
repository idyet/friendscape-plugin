package com.friendscape;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JToggleButton;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel: the header (name, connection status, "Playing as", "Turn on sending"), then one card per
 * discovered Event. Every change the user makes goes through {@code setSending}, which writes the
 * config item, or through {@link CardActions}, so the panel never holds its own copy of either.
 */
class FriendscapePanel extends PluginPanel
{
	/** What a card's buttons ask of the plugin. Called on the Swing thread. */
	interface CardActions
	{
		void leave(int number);

		void rejoin(int number);

		void openEventPage(int number);

		/** The website's upload page, for screenshots the server still needs. */
		void openUploadPage(int number);

		/** "Report to Friendscape": asks the admin to look at the card's held identity mismatch. */
		void report(int number);

		/** A Team's board page, for a board too large for the card. */
		void openBoard(int number, int teamNumber);

		/** One Tile's detail on a Team's board page. */
		void openTile(int number, int teamNumber, int tile);

		/** Remembers whether a card's mini board is open, per Event. */
		void setBoardOpen(int number, boolean open);

		/** Votes from a Voting card for a Candidate, by its Ballot place (SPEC 4.9). */
		void vote(int number, int candidate);

		/** Leaves a Live Event out of the standings overlay, or brings it back (SPEC 10.3). */
		void setStandingsHidden(int number, boolean hidden);

		/** Hides a Live Event's Codeword overlay, or brings it back (SPEC 10.3). */
		void setCodewordHidden(int number, boolean hidden);

		/** Switches the cards between one flat list and a header per Group (SPEC 10.3). */
		void setByGroup(boolean byGroup);

		/** Collapses a Group header or expands it, by Group number or {@link CardGroups#OTHER_KEY}. */
		void setGroupCollapsed(int key, boolean collapsed);
	}

	/** The space between a card's blocks, such as above its links. */
	private static final int CARD_GAP = 6;

	static final String VOTE_NOTE = "Cast in game, website changes blocked";

	static final String LEAVE_CONFIRM = "Stop sending for %s from this install?\n\n"
		+ "Your progress and Roster entry stay, and the organizer can still track you through the hiscores. "
		+ "You can rejoin from this card at any time.";

	final JLabel statusDot = new JLabel(Glyphs.dot());
	final JLabel statusText = new JLabel();
	final JLabel playingAs = new JLabel();
	final JLabel disclosure = new JLabel("<html>" + FriendscapeConfig.WARNING + "</html>");
	final JButton turnOnButton = new JButton("Turn on sending");
	final JLabel emptyState = new JLabel();
	final JToggleButton groupToggle = new JToggleButton(Glyphs.groups());
	final List<CardView> cardViews = new ArrayList<>();
	/** By Group only: one per section, in panel order. */
	final List<GroupHeader> groupHeaders = new ArrayList<>();
	/** Asks before a Leave, given the Event name; replaced in tests. */
	Predicate<String> confirmLeave = this::askLeave;

	/** The cards list, headers included. */
	final JPanel cards = new JPanel();
	private final CardActions actions;
	private final MiniBoardView.TileIcons icons;
	/** The Team each mini board shows, by Event number; kept across updates, not across restarts. */
	private final Map<Integer, Integer> shownTeams = new HashMap<>();
	/** The Candidate picked on each Voting card but not yet cast, so the minutely refresh keeps it. */
	private final Map<Integer, Integer> picks = new HashMap<>();
	/** Runs each time the panel is opened. */
	private Runnable onOpen = () -> { };
	/** What the last update showed, so the toggle asks for the other view. */
	private boolean byGroup;

	FriendscapePanel(Consumer<Boolean> setSending, CardActions actions, MiniBoardView.TileIcons icons)
	{
		this.actions = actions;
		this.icons = icons;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("friendscape");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		groupToggle.setFocusable(false);
		groupToggle.addActionListener(e -> actions.setByGroup(!byGroup));
		JPanel titleRow = new JPanel(new BorderLayout(5, 0));
		titleRow.add(title, BorderLayout.CENTER);
		titleRow.add(groupToggle, BorderLayout.EAST);

		JPanel status = new JPanel(new BorderLayout(5, 0));
		status.add(statusDot, BorderLayout.WEST);
		status.add(statusText, BorderLayout.CENTER);
		status.setAlignmentX(LEFT_ALIGNMENT);

		statusText.setFont(FontManager.getRunescapeSmallFont());
		playingAs.setFont(FontManager.getRunescapeSmallFont());
		disclosure.setFont(disclosure.getFont().deriveFont(Font.PLAIN));
		disclosure.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		disclosure.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));

		for (JComponent c : new JComponent[]{titleRow, playingAs, disclosure, turnOnButton})
		{
			c.setAlignmentX(LEFT_ALIGNMENT);
		}

		header.add(titleRow);
		header.add(status);
		header.add(playingAs);
		header.add(disclosure);
		header.add(turnOnButton);
		add(header, BorderLayout.NORTH);

		cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
		cards.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
		emptyState.setFont(FontManager.getRunescapeSmallFont());
		emptyState.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		emptyState.setAlignmentX(LEFT_ALIGNMENT);
		JPanel body = new JPanel(new BorderLayout());
		body.add(emptyState, BorderLayout.NORTH);
		body.add(cards, BorderLayout.CENTER);
		add(body, BorderLayout.CENTER);

		turnOnButton.addActionListener(e -> setSending.accept(true));

		update(PanelState.builder().build());
	}

	/** Must run on the Swing thread. */
	void update(PanelState state)
	{
		statusDot.setForeground(state.getStatus().getColor());
		statusText.setText(state.getStatus().getText());

		playingAs.setVisible(state.getRsn() != null);
		playingAs.setText(state.getRsn() == null ? "" : "Playing as " + state.getRsn());

		disclosure.setVisible(!state.isSending());
		turnOnButton.setVisible(!state.isSending());

		// Cards come from discovery, which needs sending on: hide what could be stale
		List<EventCard> shown = state.isSending() && state.getCards() != null ? state.getCards() : List.of();
		emptyState.setVisible(state.isSending() && state.getCards() != null && shown.isEmpty());
		emptyState.setText("<html>No Events yet. Ask your organizer to add " + state.getRsn() + ".</html>");
		byGroup = state.isByGroup();
		groupToggle.setVisible(!shown.isEmpty());
		groupToggle.setSelected(byGroup);
		groupToggle.setToolTipText(byGroup ? "Show as one list" : "Show by Group");
		cards.removeAll();
		cardViews.clear();
		groupHeaders.clear();
		if (byGroup)
		{
			for (CardGroups.Section section : CardGroups.of(shown))
			{
				boolean collapsed = state.getCollapsedGroups().contains(section.getKey());
				GroupHeader header = new GroupHeader(section, collapsed);
				groupHeaders.add(header);
				cards.add(header);
				if (!collapsed)
				{
					section.getCards().forEach(card -> addCard(card, state));
				}
			}
		}
		else
		{
			shown.forEach(card -> addCard(card, state));
		}

		revalidate();
		repaint();
	}

	private void addCard(EventCard card, PanelState state)
	{
		CardView view = new CardView(card, !state.getClosedBoards().contains(card.getNumber()),
			state.getHiddenStandings().contains(card.getNumber()), state.getHiddenCodewords().contains(card.getNumber()));
		cardViews.add(view);
		cards.add(view);
	}

	void setOnOpen(Runnable onOpen)
	{
		this.onOpen = onOpen;
	}

	@Override
	public void onActivate()
	{
		onOpen.run();
	}

	/** A Candidate list row: "Fishing: 2", the player's choice marked, an On Cooldown one with no count. */
	static String ballotLine(DiscoveryResponse.VoteCandidate candidate, Integer choice)
	{
		if (candidate.isCooling())
		{
			return candidate.toString();
		}
		String line = candidate.getName() + ": " + candidate.votes();
		return choice != null && choice == candidate.getId() ? line + " (your vote)" : line;
	}

	/** Whether the Ballot holds the Candidate and it is not On Cooldown. */
	private static boolean votable(DiscoveryResponse.Vote vote, int id)
	{
		for (DiscoveryResponse.VoteCandidate candidate : vote.candidates())
		{
			if (candidate.getId() == id)
			{
				return !candidate.isCooling();
			}
		}
		return false;
	}

	/** A pick worth casting: votable and not the vote already cast. */
	private static boolean castable(DiscoveryResponse.Vote vote, Integer picked)
	{
		return picked != null && votable(vote, picked) && !picked.equals(vote.getChoice());
	}

	/** The Any dropdown: every Candidate in Ballot order; an On Cooldown one is never selected. */
	private static DefaultComboBoxModel<DiscoveryResponse.VoteCandidate> pickerModel(DiscoveryResponse.Vote vote,
		Integer picked)
	{
		DefaultComboBoxModel<DiscoveryResponse.VoteCandidate> model = new DefaultComboBoxModel<>()
		{
			@Override
			public void setSelectedItem(Object item)
			{
				if (!cooling(item))
				{
					super.setSelectedItem(item);
				}
			}
		};
		DiscoveryResponse.VoteCandidate selected = null;
		for (DiscoveryResponse.VoteCandidate candidate : vote.candidates())
		{
			model.addElement(candidate);
			if (picked != null && picked == candidate.getId())
			{
				selected = candidate;
			}
		}
		// addElement selects the first; nothing shows picked until the player picks
		model.setSelectedItem(selected);
		return model;
	}

	private static boolean cooling(Object item)
	{
		return item instanceof DiscoveryResponse.VoteCandidate && ((DiscoveryResponse.VoteCandidate) item).isCooling();
	}

	private static JLabel smallLabel(String text, Color color)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		return label;
	}

	/** The player's first Team, else the leader: what a mini board shows until a chip is chosen. */
	private static int defaultTeam(DiscoveryResponse.Board board)
	{
		List<DiscoveryResponse.BoardTeam> mine = board.mine();
		List<DiscoveryResponse.BoardTeam> teams = mine.isEmpty() ? board.teams() : mine;
		return teams.isEmpty() ? 0 : teams.get(0).getTeam();
	}

	private boolean askLeave(String eventName)
	{
		return JOptionPane.showConfirmDialog(this, String.format(LEAVE_CONFIRM, eventName), "Leave " + eventName,
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
	}

	/**
	 * A By Group header: the Group name, and while collapsed its card count and a dot for its most
	 * urgent card (SPEC 10.3). Clicking collapses or expands it.
	 */
	class GroupHeader extends JButton
	{
		final JLabel name = new JLabel();
		final JLabel count = new JLabel();
		final JLabel dot = new JLabel(Glyphs.dot());

		GroupHeader(CardGroups.Section section, boolean collapsed)
		{
			setLayout(new BorderLayout(5, 0));
			setBorder(BorderFactory.createEmptyBorder(4, 2, 6, 2));
			setContentAreaFilled(false);
			setFocusable(false);
			setAlignmentX(LEFT_ALIGNMENT);

			JLabel arrow = new JLabel(Glyphs.chevron(!collapsed));
			arrow.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			name.setText(section.getName());
			name.setFont(FontManager.getRunescapeBoldFont());
			name.setForeground(ColorScheme.TEXT_COLOR);
			count.setText(String.valueOf(section.getCards().size()));
			count.setFont(FontManager.getRunescapeSmallFont());
			count.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			count.setVisible(collapsed);
			CardState urgent = section.urgent();
			dot.setVisible(collapsed && urgent != null);
			if (urgent != null)
			{
				dot.setForeground(urgent.getColor());
			}

			JPanel summary = new JPanel(new BorderLayout(5, 0));
			summary.setOpaque(false);
			summary.add(count, BorderLayout.WEST);
			summary.add(dot, BorderLayout.EAST);
			add(arrow, BorderLayout.WEST);
			add(name, BorderLayout.CENTER);
			add(summary, BorderLayout.EAST);
			// Full width in the cards' BoxLayout, at its own height
			setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
			addActionListener(e -> actions.setGroupCollapsed(section.getKey(), !collapsed));
		}
	}

	/** One Event card: name, state dot and detail, standing lines, then its links. */
	class CardView extends JPanel
	{
		final JLabel name = new JLabel();
		final JLabel detail = new JLabel();
		final JPanel standing = new JPanel();
		final JButton openButton = new JButton();
		final JButton uploadButton = new JButton("Open event page to upload");
		final JButton reportButton = new JButton("Report to Friendscape");
		final JButton boardToggle = new JButton();
		/** Null without a mini board. */
		final MiniBoardView board;
		final JButton rejoinButton = new JButton("Rejoin");
		final JButton menuButton = new JButton(Glyphs.more());
		final JMenuItem leaveItem = new JMenuItem("Leave");
		final JMenuItem standingsItem = new JMenuItem();
		final JMenuItem codewordItem = new JMenuItem();
		/** Candidate list mode: one radio per Candidate; empty otherwise. */
		final List<JRadioButton> ballot = new ArrayList<>();
		/** Any mode: the Any tally lines above the picker. */
		final JPanel tally = new JPanel();
		/** Any mode: every Candidate; null otherwise. */
		final JComboBox<DiscoveryResponse.VoteCandidate> picker;
		final JButton voteButton = new JButton("Vote");
		final JLabel voteNote = new JLabel(VOTE_NOTE);

		CardView(EventCard card, boolean boardOpen, boolean standingsHidden, boolean codewordHidden)
		{
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 6, 0, ColorScheme.DARK_GRAY_COLOR),
				BorderFactory.createEmptyBorder(8, 8, 8, 8)));
			setAlignmentX(LEFT_ALIGNMENT);

			JPanel title = new JPanel(new BorderLayout(5, 0));
			title.setOpaque(false);
			name.setText(card.getName());
			name.setFont(FontManager.getRunescapeBoldFont());
			name.setForeground(card.isLeft() ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.TEXT_COLOR);
			title.add(name, BorderLayout.CENTER);
			title.add(menuButton, BorderLayout.EAST);
			title.setAlignmentX(LEFT_ALIGNMENT);

			JLabel dot = new JLabel(Glyphs.dot());
			dot.setForeground(card.getState().getColor());
			detail.setText(card.getDetail());
			detail.setFont(FontManager.getRunescapeSmallFont());
			JPanel status = new JPanel(new BorderLayout(5, 0));
			status.setOpaque(false);
			status.add(dot, BorderLayout.WEST);
			status.add(detail, BorderLayout.CENTER);
			status.setAlignmentX(LEFT_ALIGNMENT);

			standing.setLayout(new BoxLayout(standing, BoxLayout.Y_AXIS));
			standing.setOpaque(false);
			standing.setAlignmentX(LEFT_ALIGNMENT);
			for (String line : card.getStanding())
			{
				JLabel label = new JLabel(line);
				label.setFont(FontManager.getRunescapeSmallFont());
				label.setForeground(card.isLeft() ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
				standing.add(label);
			}
			for (String line : card.getNotes())
			{
				JLabel label = new JLabel(line);
				label.setFont(FontManager.getRunescapeSmallFont());
				label.setForeground(CardState.ATTENTION.getColor());
				standing.add(label);
			}

			JPopupMenu menu = new JPopupMenu();
			menu.add(standingsItem);
			menu.add(codewordItem);
			menu.add(leaveItem);
			// Overlay choices only mean something while the Event is in the overlays
			standingsItem.setVisible(card.isLive());
			standingsItem.setText(standingsHidden ? "Show standings" : "Hide standings");
			standingsItem.addActionListener(e -> actions.setStandingsHidden(card.getNumber(), !standingsHidden));
			codewordItem.setVisible(card.getCodeword() != null);
			codewordItem.setText(codewordHidden ? "Show codeword" : "Hide codeword");
			codewordItem.addActionListener(e -> actions.setCodewordHidden(card.getNumber(), !codewordHidden));
			// A Finalized Event has nothing left to mute
			leaveItem.setVisible(!card.isLeft() && !card.isFinalized());
			menuButton.setVisible(standingsItem.isVisible() || codewordItem.isVisible() || leaveItem.isVisible());
			menuButton.setToolTipText("More");
			menuButton.addActionListener(e -> menu.show(menuButton, 0, menuButton.getHeight()));
			leaveItem.addActionListener(e ->
			{
				if (confirmLeave.test(card.getName()))
				{
					actions.leave(card.getNumber());
				}
			});
			rejoinButton.setVisible(card.isLeft() && !card.isFinalized());
			rejoinButton.addActionListener(e -> actions.rejoin(card.getNumber()));
			openButton.setText(card.getLink());
			openButton.addActionListener(e ->
			{
				if (card.getBoard() != null && card.miniBoard() == null)
				{
					actions.openBoard(card.getNumber(), defaultTeam(card.getBoard()));
				}
				else
				{
					actions.openEventPage(card.getNumber());
				}
			});
			uploadButton.setVisible(card.isUpload());
			uploadButton.setAlignmentX(LEFT_ALIGNMENT);
			uploadButton.addActionListener(e -> actions.openUploadPage(card.getNumber()));
			reportButton.setVisible(card.isReport());
			reportButton.setAlignmentX(LEFT_ALIGNMENT);
			reportButton.setToolTipText("Tell Friendscape this RSN is yours; the answer comes in chat");
			reportButton.addActionListener(e ->
			{
				// Once per card: a repeat within a day posts nothing anyway
				reportButton.setEnabled(false);
				actions.report(card.getNumber());
			});

			JPanel ballotPanel = new JPanel();
			ballotPanel.setLayout(new BoxLayout(ballotPanel, BoxLayout.Y_AXIS));
			ballotPanel.setOpaque(false);
			ballotPanel.setAlignmentX(LEFT_ALIGNMENT);
			picker = card.getVote() != null && card.getVote().isOpen() ? new JComboBox<>() : null;
			showBallot(card, ballotPanel);

			board = card.miniBoard() == null ? null : miniBoard(card);
			boardToggle.setVisible(board != null);
			boardToggle.setAlignmentX(LEFT_ALIGNMENT);
			boardToggle.setFocusable(false);
			showBoard(boardOpen);
			boardToggle.addActionListener(e ->
			{
				boolean open = !board.isVisible();
				showBoard(open);
				actions.setBoardOpen(card.getNumber(), open);
			});

			JPanel links = new JPanel(new BorderLayout(5, 0));
			links.setOpaque(false);
			links.add(openButton, BorderLayout.WEST);
			links.add(rejoinButton, BorderLayout.EAST);
			links.setAlignmentX(LEFT_ALIGNMENT);
			links.setBorder(BorderFactory.createEmptyBorder(CARD_GAP, 0, 0, 0));

			add(title);
			add(status);
			add(standing);
			add(ballotPanel);
			add(uploadButton);
			add(reportButton);
			add(boardToggle);
			if (board != null)
			{
				board.setBorder(BorderFactory.createEmptyBorder(CARD_GAP, 0, 0, 0));
				add(board);
			}
			add(links);
		}

		/** The Ballot of a Voting card (SPEC 10.3); hides the Vote button on any other card. */
		private void showBallot(EventCard card, JPanel ballotPanel)
		{
			DiscoveryResponse.Vote vote = card.getVote();
			voteButton.setVisible(vote != null);
			voteNote.setVisible(vote != null && vote.getChoice() != null);
			if (vote == null)
			{
				return;
			}
			int number = card.getNumber();
			Integer remembered = picks.get(number);
			if (remembered != null && remembered.equals(vote.getChoice()))
			{
				// Cast: the card now marks it as the choice
				picks.remove(number);
				remembered = null;
			}
			Integer picked = remembered != null && votable(vote, remembered) ? remembered : vote.getChoice();
			if (vote.isOpen())
			{
				tally.setLayout(new BoxLayout(tally, BoxLayout.Y_AXIS));
				tally.setOpaque(false);
				tally.setAlignmentX(LEFT_ALIGNMENT);
				for (String line : vote.tally())
				{
					tally.add(smallLabel(line, ColorScheme.LIGHT_GRAY_COLOR));
				}
				ballotPanel.add(tally);
				picker.setModel(pickerModel(vote, picked));
				picker.setRenderer(new DefaultListCellRenderer()
				{
					@Override
					public Component getListCellRendererComponent(JList<?> list, Object value, int index,
						boolean isSelected, boolean cellHasFocus)
					{
						Component shown = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
						shown.setEnabled(!cooling(value));
						return shown;
					}
				});
				picker.setAlignmentX(LEFT_ALIGNMENT);
				picker.addActionListener(e ->
				{
					DiscoveryResponse.VoteCandidate chosen = (DiscoveryResponse.VoteCandidate) picker.getSelectedItem();
					pick(card, chosen == null ? null : chosen.getId());
				});
				ballotPanel.add(picker);
			}
			else
			{
				ButtonGroup group = new ButtonGroup();
				for (DiscoveryResponse.VoteCandidate candidate : vote.candidates())
				{
					JRadioButton row = new JRadioButton(ballotLine(candidate, vote.getChoice()));
					row.setFont(FontManager.getRunescapeSmallFont());
					row.setOpaque(false);
					row.setAlignmentX(LEFT_ALIGNMENT);
					row.setEnabled(!candidate.isCooling());
					row.setSelected(picked != null && picked == candidate.getId());
					row.addActionListener(e -> pick(card, candidate.getId()));
					group.add(row);
					ballot.add(row);
					ballotPanel.add(row);
				}
			}
			voteButton.setText(vote.getChoice() == null ? "Vote" : "Change vote");
			voteButton.setAlignmentX(LEFT_ALIGNMENT);
			voteButton.setEnabled(castable(vote, picked));
			voteButton.addActionListener(e ->
			{
				Integer chosen = picks.getOrDefault(number, vote.getChoice());
				if (castable(vote, chosen))
				{
					// Until the answer refreshes the card: one vote per click
					voteButton.setEnabled(false);
					actions.vote(number, chosen);
				}
			});
			voteNote.setFont(FontManager.getRunescapeSmallFont());
			voteNote.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			voteNote.setAlignmentX(LEFT_ALIGNMENT);
			ballotPanel.add(Box.createVerticalStrut(CARD_GAP));
			ballotPanel.add(voteButton);
			ballotPanel.add(voteNote);
		}

		private void pick(EventCard card, Integer candidate)
		{
			if (candidate == null)
			{
				picks.remove(card.getNumber());
			}
			else
			{
				picks.put(card.getNumber(), candidate);
			}
			voteButton.setEnabled(castable(card.getVote(), candidate));
		}

		private MiniBoardView miniBoard(EventCard card)
		{
			int number = card.getNumber();
			Integer remembered = shownTeams.get(number);
			int teamNumber = remembered != null ? remembered : defaultTeam(card.miniBoard());
			return new MiniBoardView(card.miniBoard(), teamNumber, icons, new MiniBoardView.Listener()
			{
				@Override
				public void teamChosen(int chosen)
				{
					shownTeams.put(number, chosen);
				}

				@Override
				public void openTile(int shownTeam, int tile)
				{
					actions.openTile(number, shownTeam, tile);
				}
			});
		}

		private void showBoard(boolean open)
		{
			if (board != null)
			{
				board.setVisible(open);
				boardToggle.setText(open ? "Hide board" : "Show board");
				revalidate();
			}
		}

		List<String> ballotTexts()
		{
			List<String> texts = new ArrayList<>();
			for (JRadioButton row : ballot)
			{
				texts.add(row.getText());
			}
			return texts;
		}

		List<String> tallyTexts()
		{
			List<String> texts = new ArrayList<>();
			for (Component c : tally.getComponents())
			{
				texts.add(((JLabel) c).getText());
			}
			return texts;
		}

		List<String> standingTexts()
		{
			List<String> texts = new ArrayList<>();
			for (Component c : standing.getComponents())
			{
				texts.add(((JLabel) c).getText());
			}
			return texts;
		}
	}
}
