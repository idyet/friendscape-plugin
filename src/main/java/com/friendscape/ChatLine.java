package com.friendscape;

import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;

/**
 * A plugin chat line (SPEC 10.4): a lowercase {@code [friendscape]} prefix in the crown's gold with a
 * black shadow so it reads on the opaque chatbox, then the text in the player's chat color.
 */
final class ChatLine
{
	private static final String PREFIX = "<col=f2b53a><shad=000000>[friendscape]</shad></col> ";

	private ChatLine()
	{
	}

	/** {@code text} is escaped, so it cannot add tags of its own. */
	static String format(String text, ChatColorType color)
	{
		return PREFIX + new ChatMessageBuilder()
			.append(color)
			.append(text)
			.build();
	}
}
