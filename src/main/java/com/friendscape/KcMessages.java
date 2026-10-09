/*
 * KILLCOUNT_PATTERN and RIFTS_CLOSED_PATTERN are lifted from RuneLite's ChatCommandsPlugin, and
 * CLUE_PATTERN is adapted from RuneLite's LootTrackerPlugin
 * (https://github.com/runelite/runelite, commit 42a6f17a6a2e8e478aa763890ecd0181a59dad38), under
 * this license:
 *
 * Copyright (c) 2017. l2-
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * Copyright (c) 2018, Psikoi <https://github.com/psikoi>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.friendscape;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;
import net.runelite.client.util.Text;

/**
 * Reads lifetime KC from game chat (SPEC 10.2): kill-count messages, clue completions and rifts
 * closed. Keeps the name the message uses: the server maps it through the alias table, so a new
 * boss needs no plugin update. Clue tiers and rifts take their hiscores name, which the alias
 * table also knows.
 */
final class KcMessages
{
	private static final Pattern KILLCOUNT_PATTERN = Pattern.compile("Your (?<pre>completion count for |subdued |completed )?(?:<col=[0-9a-f]{6}>)?(?<boss>.+?)(?:</col>)? (?<post>(?:(?:kill|harvest|lap|completion|success|Total Ticket) )?(?:count )?)is: ?(?:<col=[0-9a-f]{6}>|@.+?@)(?<kc>[0-9,]+)</col>");
	private static final Pattern RIFTS_CLOSED_PATTERN = Pattern.compile("Amount of Rifts you have closed: <col=ff0000>([0-9,]+)</col>\\.", Pattern.CASE_INSENSITIVE);
	/** Matched with tags removed. */
	private static final Pattern CLUE_PATTERN = Pattern.compile("You have completed ([0-9,]+) ([a-z]+) Treasure Trails?\\.");

	/** Leagues KC; never counts, and the server drops it too. */
	private static final String LEAGUES_SUFFIX = " (echo)";

	@Value
	static class Kc
	{
		/** The name the alias table knows it by in chat, e.g. {@code Barrows chest}. */
		String name;
		long count;
	}

	private KcMessages()
	{
	}

	/** The KC a game message reports, or null if it reports none (or Leagues KC). */
	static Kc parse(String message)
	{
		Matcher kill = KILLCOUNT_PATTERN.matcher(message);
		if (kill.find())
		{
			String boss = kill.group("boss");
			// "Your <x> is: N" with neither prefix nor "count" is no KC message
			boolean counted = !isEmpty(kill.group("pre")) || !isEmpty(kill.group("post"));
			if (!counted || boss.toLowerCase(Locale.ROOT).endsWith(LEAGUES_SUFFIX))
			{
				return null;
			}
			return new Kc(boss, count(kill.group("kc")));
		}

		Matcher rifts = RIFTS_CLOSED_PATTERN.matcher(message);
		if (rifts.find())
		{
			return new Kc("Rifts closed", count(rifts.group(1)));
		}

		Matcher clue = CLUE_PATTERN.matcher(Text.removeTags(message));
		if (clue.find())
		{
			return new Kc("Clue Scrolls (" + clue.group(2) + ")", count(clue.group(1)));
		}
		return null;
	}

	private static boolean isEmpty(String group)
	{
		return group == null || group.isEmpty();
	}

	private static long count(String digits)
	{
		return Long.parseLong(digits.replace(",", ""));
	}
}
