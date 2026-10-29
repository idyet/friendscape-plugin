package com.friendscape;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class CardGroupsTest
{
	/** A Voting card carries a Vote not yet cast. */
	private static EventCard card(int number, CardState state, DiscoveryResponse.Group group)
	{
		return state == CardState.VOTING ? voting(number, null, group) : withVote(number, state, null, group);
	}

	private static EventCard voting(int number, Integer choice, DiscoveryResponse.Group group)
	{
		return withVote(number, CardState.VOTING,
			new DiscoveryResponse.Vote("2026-11-05T18:00:00Z", false, List.of(), choice), group);
	}

	private static EventCard withVote(int number, CardState state, DiscoveryResponse.Vote vote,
		DiscoveryResponse.Group group)
	{
		boolean live = state == CardState.TRACKING || state == CardState.ATTENTION;
		return new EventCard(number, "Event " + number, state, "", List.of(), null, List.of(), "", null,
			EventCard.OPEN_EVENT_PAGE, false, false, live, vote, false, null, group);
	}

	private static final DiscoveryResponse.Group IRON = new DiscoveryResponse.Group(300000, "iron clan");
	private static final DiscoveryResponse.Group BANDOS = new DiscoveryResponse.Group(200000, "Bandos boys");
	private static final DiscoveryResponse.Group ZULRAH = new DiscoveryResponse.Group(100000, "Zulrah");

	private static List<Integer> numbers(CardGroups.Section section)
	{
		return section.getCards().stream().map(EventCard::getNumber).collect(Collectors.toList());
	}

	@Test
	public void ordersGroupsByNameIgnoringCaseWithOtherEventsLast()
	{
		List<CardGroups.Section> sections = CardGroups.of(List.of(
			card(111111, CardState.TRACKING, null),
			card(222222, CardState.TRACKING, ZULRAH),
			card(333333, CardState.TRACKING, IRON),
			card(444444, CardState.ENDED, BANDOS),
			card(555555, CardState.LEFT, IRON),
			card(666666, CardState.FINAL, IRON)));

		assertEquals(List.of("Bandos boys", "iron clan", "Zulrah", CardGroups.OTHER_EVENTS),
			sections.stream().map(CardGroups.Section::getName).collect(Collectors.toList()));
		assertEquals(List.of(200000, 300000, 100000, CardGroups.OTHER_KEY),
			sections.stream().map(CardGroups.Section::getKey).collect(Collectors.toList()));
		// Within a Group, the flat order the cards came in
		assertEquals(List.of(333333, 555555, 666666), numbers(sections.get(1)));
		assertEquals(List.of(111111), numbers(sections.get(3)));
	}

	@Test
	public void putsEveryCardUnderOtherEventsWhenNoneHasAGroup()
	{
		List<CardGroups.Section> sections = CardGroups.of(List.of(
			card(111111, CardState.TRACKING, null), card(222222, CardState.ENDED, null)));

		assertEquals(1, sections.size());
		assertEquals(CardGroups.OTHER_EVENTS, sections.get(0).getName());
		assertEquals(List.of(111111, 222222), numbers(sections.get(0)));
	}

	@Test
	public void showsAHeaderForASingleGroup()
	{
		List<CardGroups.Section> sections = CardGroups.of(List.of(card(111111, CardState.TRACKING, IRON)));

		assertEquals(1, sections.size());
		assertEquals("iron clan", sections.get(0).getName());
	}

	@Test
	public void namesTheMostUrgentStateAttentionThenVotingThenLive()
	{
		assertEquals(CardState.ATTENTION, CardGroups.of(List.of(card(1, CardState.TRACKING, IRON),
			card(2, CardState.VOTING, IRON), card(3, CardState.ATTENTION, IRON))).get(0).urgent());
		assertEquals(CardState.VOTING, CardGroups.of(List.of(card(1, CardState.TRACKING, IRON),
			card(2, CardState.VOTING, IRON))).get(0).urgent());
		assertEquals(CardState.TRACKING, CardGroups.of(List.of(card(1, CardState.ENDED, IRON),
			card(2, CardState.TRACKING, IRON))).get(0).urgent());
		// Scheduled shows Tracking too, but nothing runs yet
		EventCard scheduled = new EventCard(1, "Event 1", CardState.TRACKING, "", List.of(), null, List.of(), "", null,
			EventCard.OPEN_EVENT_PAGE, false, false, false, null, false, null, IRON);
		assertNull(CardGroups.of(List.of(scheduled, card(2, CardState.LEFT, IRON))).get(0).urgent());
	}

	@Test
	public void aVoteAlreadyCastNeedsNoDot()
	{
		assertNull(CardGroups.of(List.of(voting(1, 2, IRON), card(2, CardState.ENDED, IRON))).get(0).urgent());
		assertEquals(CardState.VOTING, CardGroups.of(List.of(voting(1, 2, IRON), voting(2, null, IRON))).get(0).urgent());
		// Cast, but something else still runs
		assertEquals(CardState.TRACKING, CardGroups.of(List.of(voting(1, 2, IRON), card(2, CardState.TRACKING, IRON)))
			.get(0).urgent());
	}

	@Test
	public void readsCollapsedGroupsWithTheReservedOtherEventsKey()
	{
		assertEquals(Set.of(100000, CardGroups.OTHER_KEY), CardGroups.parseCollapsed("0,100000,junk,12"));
		assertEquals(Set.of(), CardGroups.parseCollapsed(""));
		assertEquals("0,100000", CardGroups.formatCollapsed(Set.of(100000, CardGroups.OTHER_KEY)));
	}
}
