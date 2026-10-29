package com.friendscape;

import net.runelite.client.chat.ChatColorType;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ChatLineTest
{
	private static final String PREFIX = "<col=f2b53a><shad=000000>[friendscape]</shad></col> ";

	@Test
	public void prefixIsLowercaseGoldWithAShadowAndTheTextKeepsItsColor()
	{
		assertEquals(PREFIX + "<colNORMAL>Counted",
			ChatLine.format("Counted", ChatColorType.NORMAL));
	}

	@Test
	public void problemTextStaysHighlighted()
	{
		assertEquals(PREFIX + "<colHIGHLIGHT>Name mismatch",
			ChatLine.format("Name mismatch", ChatColorType.HIGHLIGHT));
	}

	@Test
	public void textCannotInjectTags()
	{
		assertEquals(PREFIX + "<colNORMAL><lt>col=ff0000<gt>x",
			ChatLine.format("<col=ff0000>x", ChatColorType.NORMAL));
	}
}
