package com.friendscape;

import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class LeftEventsTest
{
	@Test
	public void readsAndWritesTheStoredList()
	{
		assertEquals(Set.of(), LeftEvents.parse(null));
		assertEquals(Set.of(), LeftEvents.parse(""));
		assertEquals(Set.of("a", "b"), LeftEvents.parse("a,b"));
		assertEquals(Set.of("a", "b"), LeftEvents.parse(LeftEvents.format(Set.of("b", "a"))));
		assertEquals("a,b", LeftEvents.format(Set.of("b", "a")));
	}
}
