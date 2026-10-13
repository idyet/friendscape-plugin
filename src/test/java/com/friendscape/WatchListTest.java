package com.friendscape;

import com.google.gson.Gson;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class WatchListTest
{
	private static final Gson GSON = new Gson();

	/** A Live bingo watching {@code watch}, unless {@code extra} overrides its fields. */
	private static String event(String slug, String watch, String extra)
	{
		return "{\"slug\":\"" + slug + "\",\"name\":\"Bingo " + slug + "\",\"format\":\"bingo\",\"phase\":\"live\","
			+ "\"entry\":\"on_roster\",\"watch\":" + watch + (extra.isEmpty() ? "" : "," + extra) + "}";
	}

	private static WatchList watch(Set<String> left, String... events)
	{
		DiscoveryResponse discovery = GSON.fromJson("{\"rsn\":\"Iron Man\",\"events\":["
			+ String.join(",", events) + "]}", DiscoveryResponse.class);
		return WatchList.of(discovery, left);
	}

	private static final String VISAGE = "{\"items\":[{\"id\":11286,\"name\":\"Draconic visage\"}],\"clogSlots\":false}";
	private static final String SNAKELING = "{\"items\":[{\"id\":12921,\"name\":\"Pet snakeling\"}],\"clogSlots\":true}";

	@Test
	public void namesTheEventsAnItemIsWatchedIn()
	{
		WatchList list = watch(Set.of(), event("a", VISAGE, ""), event("b", VISAGE, ""), event("c", SNAKELING, ""));

		assertEquals(Set.of("a", "b"), list.eventsFor(11286));
		assertEquals(Set.of(), list.eventsFor(4151));
		assertEquals(Set.of("c"), list.clogSlotEvents());
	}

	@Test
	public void findsAWatchedItemByTheNameChatUses()
	{
		WatchList list = watch(Set.of(), event("a", SNAKELING, ""));

		assertEquals(Integer.valueOf(12921), list.idForName("pet snakeling"));
		assertNull(list.idForName("Pet kraken"));
	}

	@Test
	public void cannotPinANameTwoWatchedIdsShare()
	{
		WatchList list = watch(Set.of(),
			event("a", "{\"items\":[{\"id\":1,\"name\":\"Dossier\"},{\"id\":2,\"name\":\"Dossier\"}]}", ""));

		assertNull(list.idForName("Dossier"));
	}

	@Test
	public void watchesOnlyLiveEventsStillOnTheRosterAndNotLeft()
	{
		WatchList list = watch(Set.of("left"),
			event("left", VISAGE, ""),
			event("removed", VISAGE, "\"entry\":\"removed\""),
			event("scheduled", VISAGE, "\"phase\":\"scheduled\""),
			event("ended", VISAGE, "\"phase\":\"ended\""),
			event("held", VISAGE, "\"entry\":\"held\""));

		// A held entry's Submissions are kept, and count if the identity check settles in its favour
		assertEquals(Set.of("held"), list.eventsFor(11286));
	}

	@Test
	public void watchesNothingWithoutAWatchFromTheServer()
	{
		WatchList list = watch(Set.of(), "{\"slug\":\"a\",\"phase\":\"live\",\"entry\":\"on_roster\"}");

		assertEquals(Set.of(), list.eventsFor(11286));
		assertEquals(Set.of(), list.clogSlotEvents());
		assertEquals(Set.of(), WatchList.of(null, Set.of()).clogSlotEvents());
	}
}
