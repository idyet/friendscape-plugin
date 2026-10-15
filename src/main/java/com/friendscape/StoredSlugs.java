package com.friendscape;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** A stored set of Event slugs, such as those left on this install: comma-separated (slugs have no commas). */
final class StoredSlugs
{
	private StoredSlugs()
	{
	}

	static Set<String> parse(String stored)
	{
		if (stored == null || stored.isEmpty())
		{
			return Set.of();
		}
		return Arrays.stream(stored.split(",")).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
	}

	static String format(Set<String> slugs)
	{
		return String.join(",", new TreeSet<>(slugs));
	}
}
