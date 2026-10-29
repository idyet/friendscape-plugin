package com.friendscape;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import org.junit.Test;

public class CodewordOverlaysTest
{
	private final List<String> added = new ArrayList<>();
	private final List<String> removed = new ArrayList<>();
	private final CodewordOverlays overlays = new CodewordOverlays(
		overlay -> added.add(overlay.getName()), overlay -> removed.add(overlay.getName()),
		number -> new CodewordOverlay(null, number, () -> true, new FriendscapeConfig()
		{
		}));

	private static EventCard card(int number, boolean live, String codeword)
	{
		return new EventCard(number, "Event " + number, live ? CardState.TRACKING : CardState.ENDED, "", List.of(),
			null, List.of(), "", null, EventCard.OPEN_EVENT_PAGE, false, false, live, null, false, codeword, null);
	}

	private static Map<Integer, String> codewords(Object... pairs)
	{
		Map<Integer, String> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			map.put((Integer) pairs[i], (String) pairs[i + 1]);
		}
		return map;
	}

	@Test
	public void wantsOneOverlayPerCardWithACodewordNotHidden()
	{
		List<EventCard> cards = List.of(card(111111, true, "Mithril"), card(222222, true, null),
			card(333333, true, "Adamant"), card(444444, true, "Rune"));

		assertEquals(codewords(111111, "Mithril", 444444, "Rune"), CodewordOverlays.wanted(cards, Set.of(333333)));
	}

	@Test
	public void addsAnOverlayNamedByEventNumberAndRemovesItOnceNotWanted()
	{
		overlays.show(codewords(111111, "Mithril", 222222, "Rune"));
		assertEquals(List.of("friendscape-codeword-111111", "friendscape-codeword-222222"), added);

		overlays.show(codewords(222222, "Rune"));
		assertEquals(List.of("friendscape-codeword-111111"), removed);

		overlays.show(Map.of());
		assertEquals(List.of("friendscape-codeword-111111", "friendscape-codeword-222222"), removed);
	}

	@Test
	public void keepsAnOverlayAcrossUpdatesAndFollowsANewCodeword()
	{
		overlays.show(codewords(111111, "Mithril"));
		CodewordOverlay first = overlays.overlay(111111);

		overlays.show(codewords(111111, "Adamant"));

		assertEquals(1, added.size());
		assertSame(first, overlays.overlay(111111));
		assertEquals("Adamant", first.getCodeword());
	}

	@Test
	public void closeRemovesEveryOverlayAndAddsNoneAfter()
	{
		overlays.show(codewords(111111, "Mithril", 222222, "Rune"));

		overlays.close();

		assertEquals(2, removed.size());
		overlays.show(codewords(111111, "Mithril"));
		assertEquals(2, added.size());
	}

	@Test
	public void timestampsInUtcToTheMinute()
	{
		assertEquals("2026-11-03 12:05 UTC", CodewordOverlay.timestamp(Instant.parse("2026-11-03T12:05:59.900Z")));
	}
}
