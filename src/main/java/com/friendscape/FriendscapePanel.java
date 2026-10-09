package com.friendscape;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel: the header (name, sending toggle, connection status, "Playing as"), then one card per
 * discovered Event. Every change the user makes goes through {@code setSending}, which writes the
 * config item, or through {@link CardActions}, so the panel never holds its own copy of either.
 */
class FriendscapePanel extends PluginPanel
{
	/** What a card's buttons ask of the plugin. Called on the Swing thread. */
	interface CardActions
	{
		void leave(String slug);

		void rejoin(String slug);

		void openEventPage(String slug);
	}

	static final String LEAVE_CONFIRM = "Stop sending for %s from this install?\n\n"
		+ "Your progress and Roster entry stay, and the organizer can still track you through the hiscores. "
		+ "You can rejoin from this card at any time.";

	final JCheckBox sendingToggle = new JCheckBox("Send data to Friendscape");
	final JLabel statusDot = new JLabel("●");
	final JLabel statusText = new JLabel();
	final JLabel playingAs = new JLabel();
	final JLabel disclosure = new JLabel("<html>" + FriendscapeConfig.WARNING + "</html>");
	final JButton turnOnButton = new JButton("Turn on sending");
	final JLabel emptyState = new JLabel();
	final List<CardView> cardViews = new ArrayList<>();
	/** Asks before a Leave, given the Event name; replaced in tests. */
	Predicate<String> confirmLeave = this::askLeave;

	private final JPanel cards = new JPanel();
	private final CardActions actions;
	/** Runs each time the panel is opened. */
	private Runnable onOpen = () -> { };

	FriendscapePanel(Consumer<Boolean> setSending, CardActions actions)
	{
		this.actions = actions;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("friendscape");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);

		JPanel status = new JPanel(new BorderLayout(5, 0));
		status.add(statusDot, BorderLayout.WEST);
		status.add(statusText, BorderLayout.CENTER);
		status.setAlignmentX(LEFT_ALIGNMENT);

		statusText.setFont(FontManager.getRunescapeSmallFont());
		playingAs.setFont(FontManager.getRunescapeSmallFont());
		disclosure.setFont(disclosure.getFont().deriveFont(Font.PLAIN));
		disclosure.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		disclosure.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));

		for (JComponent c : new JComponent[]{title, sendingToggle, playingAs, disclosure, turnOnButton})
		{
			c.setAlignmentX(LEFT_ALIGNMENT);
		}

		header.add(title);
		header.add(sendingToggle);
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

		// ActionListener fires on user clicks only, not on setSelected, so update() never echoes back
		sendingToggle.addActionListener(e -> setSending.accept(sendingToggle.isSelected()));
		turnOnButton.addActionListener(e -> setSending.accept(true));

		update(PanelState.builder().build());
	}

	/** Must run on the Swing thread. */
	void update(PanelState state)
	{
		sendingToggle.setSelected(state.isSending());

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
		cards.removeAll();
		cardViews.clear();
		for (EventCard card : shown)
		{
			CardView view = new CardView(card);
			cardViews.add(view);
			cards.add(view);
		}

		revalidate();
		repaint();
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

	private boolean askLeave(String eventName)
	{
		return JOptionPane.showConfirmDialog(this, String.format(LEAVE_CONFIRM, eventName), "Leave " + eventName,
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
	}

	/** One Event card: name, state dot and detail, standing lines, then its links. */
	class CardView extends JPanel
	{
		final JLabel name = new JLabel();
		final JLabel detail = new JLabel();
		final JPanel standing = new JPanel();
		final JButton openButton = new JButton("Open event page");
		final JButton rejoinButton = new JButton("Rejoin");
		final JButton menuButton = new JButton("⋯");
		final JMenuItem leaveItem = new JMenuItem("Leave");

		CardView(EventCard card)
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

			JLabel dot = new JLabel("●");
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

			JPopupMenu menu = new JPopupMenu();
			menu.add(leaveItem);
			leaveItem.setVisible(!card.isLeft());
			menuButton.setVisible(!card.isLeft());
			menuButton.setToolTipText("More");
			menuButton.addActionListener(e -> menu.show(menuButton, 0, menuButton.getHeight()));
			leaveItem.addActionListener(e ->
			{
				if (confirmLeave.test(card.getName()))
				{
					actions.leave(card.getSlug());
				}
			});
			rejoinButton.setVisible(card.isLeft());
			rejoinButton.addActionListener(e -> actions.rejoin(card.getSlug()));
			openButton.addActionListener(e -> actions.openEventPage(card.getSlug()));

			JPanel links = new JPanel(new BorderLayout(5, 0));
			links.setOpaque(false);
			links.add(openButton, BorderLayout.WEST);
			links.add(rejoinButton, BorderLayout.EAST);
			links.setAlignmentX(LEFT_ALIGNMENT);
			links.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

			add(title);
			add(status);
			add(standing);
			add(links);
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
