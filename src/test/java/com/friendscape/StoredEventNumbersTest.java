package com.friendscape;

import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class StoredEventNumbersTest
{
	@Test
	public void readsAndWritesTheStoredList()
	{
		assertEquals(Set.of(), StoredEventNumbers.parse(null));
		assertEquals(Set.of(), StoredEventNumbers.parse(""));
		assertEquals(Set.of(123456, 654321), StoredEventNumbers.parse("123456,654321"));
		assertEquals(Set.of(123456, 654321), StoredEventNumbers.parse(StoredEventNumbers.format(Set.of(654321, 123456))));
		assertEquals("123456,654321", StoredEventNumbers.format(Set.of(654321, 123456)));
	}

	@Test
	public void dropsStaleSlugsAndAnythingElseNotAnEventNumber()
	{
		assertEquals(Set.of(), StoredEventNumbers.parse("my-bingo-ab12,botw-7"));
		assertEquals(Set.of(123456, 999999),
			StoredEventNumbers.parse("my-bingo-ab12,123456,099999,12345,1234567,1e5,+123456, 123456,999999,"));
	}

	@Test
	public void knowsAnEventNumber()
	{
		assertEquals(true, StoredEventNumbers.isEventNumber(100000));
		assertEquals(true, StoredEventNumbers.isEventNumber(999999));
		assertEquals(false, StoredEventNumbers.isEventNumber(99999));
		assertEquals(false, StoredEventNumbers.isEventNumber(1000000));
		assertEquals(false, StoredEventNumbers.isEventNumber(null));
	}

	@Test
	public void prunesToTheKeptNumbers()
	{
		assertEquals("123456", StoredEventNumbers.prune("123456,654321", Set.of(123456, 111111)));
		assertEquals("", StoredEventNumbers.prune("123456", Set.of()));
		assertEquals("", StoredEventNumbers.prune("", Set.of(123456)));
	}
}
