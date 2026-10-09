package com.friendscape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class KcMessagesTest
{
	private static void assertKc(String name, long count, String message)
	{
		assertEquals(new KcMessages.Kc(name, count), KcMessages.parse(message));
	}

	@Test
	public void readsABossKillCount()
	{
		assertKc("Zulrah", 1234, "Your Zulrah kill count is: <col=ff0000>1,234</col>.");
	}

	@Test
	public void keepsTheChatNameForTheServerToMap()
	{
		assertKc("Barrows chest", 45, "Your Barrows chest count is: <col=ff0000>45</col>.");
		assertKc("Kree'arra", 7, "Your Kree'arra kill count is: <col=ff0000>7</col>.");
		assertKc("Gauntlet", 12, "Your Gauntlet completion count is: <col=ff0000>12</col>.");
	}

	@Test
	public void readsCompletedAndSubduedCounts()
	{
		assertKc("Chambers of Xeric Challenge Mode", 3,
			"Your completed Chambers of Xeric Challenge Mode count is: <col=ff0000>3</col>.");
		assertKc("Theatre of Blood: Entry Mode", 2,
			"Your completed Theatre of Blood: Entry Mode count is: <col=ff0000>2</col>.");
		assertKc("Wintertodt", 500, "Your subdued Wintertodt count is: <col=ff0000>500</col>.");
	}

	@Test
	public void readsAColouredBossName()
	{
		assertKc("Lunar Chest", 9, "Your <col=ff0000>Lunar Chest</col> count is: <col=ff0000>9</col>.");
	}

	@Test
	public void readsHarvestCounts()
	{
		assertKc("herbiboar", 31, "Your herbiboar harvest count is: <col=ff0000>31</col>.");
	}

	@Test
	public void readsClueTiers()
	{
		assertKc("Clue Scrolls (hard)", 112, "You have completed <col=ff0000>112</col> hard Treasure Trails.");
		assertKc("Clue Scrolls (beginner)", 1, "You have completed <col=ff0000>1</col> beginner Treasure Trail.");
	}

	@Test
	public void readsRiftsClosed()
	{
		assertKc("Rifts closed", 88, "Amount of rifts you have closed: <col=ff0000>88</col>.");
	}

	@Test
	public void dropsLeaguesEchoKc()
	{
		assertNull(KcMessages.parse("Your Zulrah (Echo) kill count is: <col=ff0000>3</col>."));
		assertNull(KcMessages.parse("Your <col=a53fff>Corrupted Hunllef (Echo)</col> kill count is: <col=ff3045>31</col>"));
		assertNull(KcMessages.parse("Your <col=6800bf>Kalphite Queen (Echo)</col> kill count is:<col=e00a19>1</col>"));
	}

	@Test
	public void ignoresOtherMessages()
	{
		assertNull(KcMessages.parse("Fight duration: <col=ff0000>1:02</col>. Personal best: 0:58"));
		assertNull(KcMessages.parse("Your reward is: <col=ff0000>1</col>."));
		assertNull(KcMessages.parse("Welcome to Old School RuneScape."));
	}
}
