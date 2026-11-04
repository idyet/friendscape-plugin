package com.friendscape;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DeathRecordTest
{
	private static final int TOTAL_LEVEL = 33;

	private final DeathRecord record = new DeathRecord();

	@Test
	public void isNotSyncedUntilTheTickAfterLogin()
	{
		assertFalse(record.synced());

		record.observe(0);
		assertFalse(record.synced());

		record.observe(0);
		assertTrue(record.synced());
	}

	@Test
	public void ignoresTheLoginTickValue()
	{
		record.observe(TOTAL_LEVEL);
		record.observe(0);

		assertFalse(record.dead());
	}

	@Test
	public void aDeadLoginReadsDeadButIsNoDeathTick()
	{
		record.observe(0);

		assertFalse(record.observe(TOTAL_LEVEL));
		assertTrue(record.dead());
	}

	@Test
	public void theRecordGoingSetIsTheDeathTick()
	{
		record.observe(0);
		record.observe(0);

		assertTrue(record.observe(TOTAL_LEVEL));
		assertTrue(record.dead());
	}

	@Test
	public void staysDeadWithoutAnotherDeathTick()
	{
		record.observe(0);
		record.observe(0);
		record.observe(TOTAL_LEVEL);

		assertFalse(record.observe(TOTAL_LEVEL));
		assertTrue(record.dead());
	}

	@Test
	public void aResetClearsTheRecordAndANewDeathIsADeathTickAgain()
	{
		record.observe(0);
		record.observe(0);
		record.observe(TOTAL_LEVEL);

		assertFalse(record.observe(0));
		assertFalse(record.dead());
		assertTrue(record.observe(TOTAL_LEVEL));
	}

	@Test
	public void forgettingWaitsForSyncAgain()
	{
		record.observe(0);
		record.observe(TOTAL_LEVEL);

		record.reset();

		assertFalse(record.synced());
		assertFalse(record.dead());
		record.observe(0);
		assertFalse(record.observe(TOTAL_LEVEL));
		assertTrue(record.dead());
	}
}
