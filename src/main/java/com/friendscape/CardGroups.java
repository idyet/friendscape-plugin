package com.friendscape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.Value;

/**
 * The panel's By Group view (SPEC 10.3): one section per Group, alphabetical by name ignoring case,
 * each holding its cards in flat order, then "Other events" for cards with no Group.
 */
final class CardGroups
{
	static final String OTHER_EVENTS = "Other events";
	/** Where "Other events" is stored among collapsed Group numbers; no Group number is 0. */
	static final int OTHER_KEY = 0;

	private CardGroups()
	{
	}

	@Value
	static class Section
	{
		/** The Group number, or {@link #OTHER_KEY}. */
		int key;
		String name;
		List<EventCard> cards;

		/**
		 * The state a collapsed header's dot shows, so collapsing never hides something needing action:
		 * Attention, then Voting with a vote not yet cast, then Live (Tracking); null when no card is in
		 * any of them.
		 */
		CardState urgent()
		{
			if (cards.stream().anyMatch(card -> card.getState() == CardState.ATTENTION))
			{
				return CardState.ATTENTION;
			}
			// A cast vote asks nothing more of the player
			if (cards.stream().anyMatch(card -> card.getState() == CardState.VOTING
				&& card.getVote() != null && card.getVote().getChoice() == null))
			{
				return CardState.VOTING;
			}
			return cards.stream().anyMatch(EventCard::isLive) ? CardState.TRACKING : null;
		}
	}

	/** The sections for {@code cards}, which come in flat order. */
	static List<Section> of(List<EventCard> cards)
	{
		Map<Integer, Section> groups = new LinkedHashMap<>();
		List<EventCard> other = new ArrayList<>();
		for (EventCard card : cards)
		{
			DiscoveryResponse.Group group = card.getGroup();
			if (group == null)
			{
				other.add(card);
				continue;
			}
			groups.computeIfAbsent(group.getNumber(), number -> new Section(number, group.getName(), new ArrayList<>()))
				.getCards().add(card);
		}
		List<Section> sections = new ArrayList<>(groups.values());
		sections.sort(Comparator.comparing(Section::getName, String.CASE_INSENSITIVE_ORDER));
		if (!other.isEmpty())
		{
			sections.add(new Section(OTHER_KEY, OTHER_EVENTS, other));
		}
		return sections;
	}

	/** The stored collapsed headers: Group numbers (see {@link StoredEventNumbers}) and {@link #OTHER_KEY}. */
	static Set<Integer> parseCollapsed(String stored)
	{
		Set<Integer> collapsed = new TreeSet<>(StoredEventNumbers.parse(stored));
		if (stored != null && Arrays.asList(stored.split(",")).contains(String.valueOf(OTHER_KEY)))
		{
			collapsed.add(OTHER_KEY);
		}
		return Set.copyOf(collapsed);
	}

	static String formatCollapsed(Set<Integer> collapsed)
	{
		return StoredEventNumbers.format(collapsed);
	}
}
