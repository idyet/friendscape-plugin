package com.friendscape;

import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class StoredSlugsTest
{
	@Test
	public void readsAndWritesTheStoredList()
	{
		assertEquals(Set.of(), StoredSlugs.parse(null));
		assertEquals(Set.of(), StoredSlugs.parse(""));
		assertEquals(Set.of("a", "b"), StoredSlugs.parse("a,b"));
		assertEquals(Set.of("a", "b"), StoredSlugs.parse(StoredSlugs.format(Set.of("b", "a"))));
		assertEquals("a,b", StoredSlugs.format(Set.of("b", "a")));
	}
}
