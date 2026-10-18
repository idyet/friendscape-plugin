package com.friendscape;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a Submission could count for (SPEC 10.3): only drops that some open Tile watches are sent.
 * Built from discovery, so it is as fresh as the last poll. Immutable.
 */
final class WatchList
{
	static final WatchList EMPTY = new WatchList(Map.of(), Map.of(), Set.of());

	/** Event numbers by watched item id. */
	private final Map<Integer, Set<Integer>> eventsById;
	/** Item id by lower-case name; null where two watched ids share the name. */
	private final Map<String, Integer> idByName;
	private final Set<Integer> clogSlotEvents;

	private WatchList(Map<Integer, Set<Integer>> eventsById, Map<String, Integer> idByName, Set<Integer> clogSlotEvents)
	{
		this.eventsById = eventsById;
		this.idByName = idByName;
		this.clogSlotEvents = clogSlotEvents;
	}

	/**
	 * The watch of every Live Event still on its Roster and not left. A held entry still watches:
	 * its Submissions are kept and count if the identity check settles in the player's favour.
	 */
	static WatchList of(DiscoveryResponse discovery, Set<Integer> left)
	{
		if (discovery == null)
		{
			return EMPTY;
		}
		Map<Integer, Set<Integer>> eventsById = new HashMap<>();
		Map<String, Integer> idByName = new HashMap<>();
		Set<Integer> clogSlotEvents = new HashSet<>();
		for (DiscoveryResponse.DiscoveredEvent event : discovery.events())
		{
			DiscoveryResponse.Watch watch = event.getWatch();
			if (watch == null || left.contains(event.getNumber())
				|| !DiscoveryResponse.LIVE.equals(event.getPhase())
				|| DiscoveryResponse.REMOVED.equals(event.getEntry()))
			{
				continue;
			}
			if (watch.isClogSlots())
			{
				clogSlotEvents.add(event.getNumber());
			}
			if (watch.getItems() == null)
			{
				continue;
			}
			for (DiscoveryResponse.WatchedItem item : watch.getItems())
			{
				eventsById.computeIfAbsent(item.getId(), id -> new HashSet<>()).add(event.getNumber());
				if (item.getName() != null)
				{
					String key = key(item.getName());
					Integer id = item.getId();
					boolean clash = idByName.containsKey(key) && !id.equals(idByName.get(key));
					idByName.put(key, clash ? null : id);
				}
			}
		}
		return new WatchList(eventsById, idByName, clogSlotEvents);
	}

	/** The Events watching this canonical item id; empty when none does. */
	Set<Integer> eventsFor(int itemId)
	{
		return eventsById.getOrDefault(itemId, Set.of());
	}

	/** The watched id a chat line's item name means, or null when none or more than one. */
	Integer idForName(String name)
	{
		return idByName.get(key(name));
	}

	/** The Events where some open Tile counts any new collection log slot. */
	Set<Integer> clogSlotEvents()
	{
		return clogSlotEvents;
	}

	private static String key(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT);
	}
}
