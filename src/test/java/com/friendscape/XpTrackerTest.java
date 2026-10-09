package com.friendscape;

import java.time.Instant;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class XpTrackerTest
{
	private static final Instant LOGIN = Instant.parse("2026-11-03T00:00:00Z");

	private final XpTracker tracker = new XpTracker();

	private static Instant after(int seconds)
	{
		return LOGIN.plusSeconds(seconds);
	}

	@Test
	public void sendsEverySkillAndTheirSumAsOverallAtLogin()
	{
		Map<String, Long> sent = tracker.takeAll(Map.of("attack", 100L, "slayer", 2_000L), LOGIN);

		assertEquals(Map.of("attack", 100L, "slayer", 2_000L, "overall", 2_100L), sent);
	}

	@Test
	public void ignoresStatChangesBeforeTheLoginReading()
	{
		tracker.observe("attack", 500L);

		assertFalse(tracker.due(after(120)));
	}

	@Test
	public void sendsOnlyChangedSkillsPlusOverall()
	{
		tracker.takeAll(Map.of("attack", 100L, "slayer", 2_000L), LOGIN);

		tracker.observe("slayer", 2_050L);

		assertEquals(Map.of("slayer", 2_050L, "overall", 2_150L), tracker.takeChanged(after(60)));
	}

	@Test
	public void ignoresAnUnchangedValue()
	{
		// Boosts and drains fire StatChanged with the same XP
		tracker.takeAll(Map.of("attack", 100L), LOGIN);

		tracker.observe("attack", 100L);

		assertFalse(tracker.due(after(120)));
		assertTrue(tracker.takeChanged(after(120)).isEmpty());
	}

	@Test
	public void sendsAtMostOncePerMinuteWhileXpChanges()
	{
		tracker.takeAll(Map.of("attack", 100L), LOGIN);

		tracker.observe("attack", 150L);
		assertFalse(tracker.due(after(59)));
		assertTrue(tracker.due(after(60)));

		tracker.takeChanged(after(60));
		tracker.observe("attack", 200L);
		assertFalse(tracker.due(after(119)));
		assertTrue(tracker.due(after(120)));
	}

	@Test
	public void changesNothingAfterSendingThem()
	{
		tracker.takeAll(Map.of("attack", 100L), LOGIN);
		tracker.observe("attack", 150L);

		tracker.takeChanged(after(60));

		assertFalse(tracker.due(after(600)));
	}

	@Test
	public void forgetsEverythingOnReset()
	{
		tracker.takeAll(Map.of("attack", 100L), LOGIN);
		tracker.observe("attack", 150L);

		tracker.reset();
		tracker.observe("attack", 200L);

		assertTrue(tracker.takeChanged(after(600)).isEmpty());
	}
}
