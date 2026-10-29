package com.friendscape;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FriendscapeOverlayTest
{
	private static EventCard card(int number, boolean live)
	{
		return new EventCard(number, "Event " + number, live ? CardState.TRACKING : CardState.ENDED, "", List.of(),
			List.of(), "", null, EventCard.OPEN_EVENT_PAGE, false, false, live, null, false, null, null);
	}

	private static List<Integer> numbers(List<EventCard> cards)
	{
		return cards.stream().map(EventCard::getNumber).collect(Collectors.toList());
	}

	@Test
	public void listsLiveEventsNotHidden()
	{
		List<EventCard> cards = List.of(card(111111, true), card(222222, false), card(333333, true), card(444444, true));

		assertEquals(List.of(111111, 444444), numbers(FriendscapeOverlay.rows(cards, Set.of(333333))));
	}

	@Test
	public void listsNothingWithEveryLiveEventHidden()
	{
		assertEquals(List.of(), FriendscapeOverlay.rows(List.of(card(111111, true)), Set.of(111111)));
	}
}
