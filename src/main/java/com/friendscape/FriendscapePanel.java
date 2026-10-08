package com.friendscape;

import java.awt.BorderLayout;
import java.awt.Font;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel. For now only the header: name, sending toggle, connection status and "Playing as".
 * Every change the user makes goes through {@code setSending}, which writes the config item, so
 * the panel never holds its own copy of the opt-in.
 */
class FriendscapePanel extends PluginPanel
{
	final JCheckBox sendingToggle = new JCheckBox("Send data to Friendscape");
	final JLabel statusDot = new JLabel("●");
	final JLabel statusText = new JLabel();
	final JLabel playingAs = new JLabel();
	final JLabel disclosure = new JLabel("<html>" + FriendscapeConfig.WARNING + "</html>");
	final JButton turnOnButton = new JButton("Turn on sending");

	FriendscapePanel(Consumer<Boolean> setSending)
	{
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

		revalidate();
		repaint();
	}
}
