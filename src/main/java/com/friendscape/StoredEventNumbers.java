package com.friendscape;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A stored set of Event numbers, such as those left on this install: comma-separated. Anything
 * that is not an Event number, such as a slug stored before Events were numbered, is dropped.
 */
final class StoredEventNumbers
{
	/** Six digits, never a leading zero: 100000 to 999999. */
	private static final Pattern EVENT_NUMBER = Pattern.compile("[1-9][0-9]{5}");

	private StoredEventNumbers()
	{
	}

	/** True for a number in 100000 to 999999. */
	static boolean isEventNumber(Integer number)
	{
		return number != null && number >= 100000 && number <= 999999;
	}

	static Set<Integer> parse(String stored)
	{
		if (stored == null || stored.isEmpty())
		{
			return Set.of();
		}
		return Arrays.stream(stored.split(","))
			.filter(s -> EVENT_NUMBER.matcher(s).matches())
			.map(Integer::valueOf)
			.collect(Collectors.toUnmodifiableSet());
	}

	static String format(Set<Integer> numbers)
	{
		return new TreeSet<>(numbers).stream().map(String::valueOf).collect(Collectors.joining(","));
	}
}
