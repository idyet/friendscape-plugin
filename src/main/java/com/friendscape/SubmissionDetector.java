package com.friendscape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import lombok.Value;
import net.runelite.client.game.ItemStack;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Turns loot and chat lines into item, clog and pet Submissions (SPEC 10.2), keeping only those
 * some open Tile watches. Called on the client thread only.
 */
class SubmissionDetector
{
	/**
	 * How long a pet line waits for the line that names the pet. Both come in the same tick in
	 * practice; the slack covers a slow chat.
	 */
	static final int PET_NAME_TICKS = 3;

	/** The server's Submission kinds; pets are items. */
	enum Kind
	{
		ITEM,
		CLOG_SLOT;

		/** As the server spells it: {@code item}, {@code clog_slot}. */
		String wire()
		{
			return name().toLowerCase(Locale.ROOT);
		}
	}

	@Value
	static class Drop
	{
		Kind kind;
		/** Canonical; null on a clog slot whose name no watched item pins. */
		Integer itemId;
		/** For chat lines: the item's name. */
		String name;
		int quantity;
		/** The Events whose open Tiles watch it: the queue keeps it while any of them may count it. */
		Set<Integer> events;
	}

	private final IntUnaryOperator canonicalize;
	private final IntFunction<String> itemName;
	private WatchList watch = WatchList.EMPTY;
	/** Ticks left for a pet line's name to arrive; 0 when no pet is waiting for one. */
	private int petTicksLeft;
	private int tick;
	/** The tick each item id last came in loot: its own untradeable line is not a pet's name. */
	private final Map<Integer, Integer> lootedAt = new HashMap<>();

	/**
	 * @param canonicalize {@code ItemManager.canonicalize}: notes and placeholders to the item
	 * @param itemName an item's name, for the chat line when it counts
	 */
	SubmissionDetector(IntUnaryOperator canonicalize, IntFunction<String> itemName)
	{
		this.canonicalize = canonicalize;
		this.itemName = itemName;
	}

	void setWatch(WatchList watch)
	{
		this.watch = watch;
	}

	/**
	 * Whether {@code LootReceived} of this type is a drop to check: raids, chests, clues, minigames
	 * and pickpockets. NPC kills come through {@code ServerNpcLoot} instead, and loot from another
	 * player is not the player's own drop.
	 */
	static boolean countsLoot(LootRecordType type)
	{
		return type == LootRecordType.EVENT || type == LootRecordType.PICKPOCKET;
	}

	/** One watched Drop per item id in the loot, stacks of the same item added up. */
	List<Drop> loot(Collection<ItemStack> items)
	{
		Map<Integer, Integer> quantities = new LinkedHashMap<>();
		for (ItemStack stack : items)
		{
			quantities.merge(canonicalize.applyAsInt(stack.getId()), stack.getQuantity(), Integer::sum);
		}
		quantities.keySet().forEach(id -> lootedAt.put(id, tick));
		List<Drop> drops = new ArrayList<>();
		quantities.forEach((id, quantity) ->
		{
			Set<Integer> events = watch.eventsFor(id);
			if (!events.isEmpty())
			{
				drops.add(new Drop(Kind.ITEM, id, itemName.apply(id), quantity, events));
			}
		});
		return drops;
	}

	/** The watched Drops a game message reports: a named pet, a new clog slot, or none. */
	List<Drop> chat(String message)
	{
		DropMessages.Line line = DropMessages.parse(message);
		if (line == null)
		{
			return List.of();
		}
		if (line.getKind() == DropMessages.Kind.PET)
		{
			petTicksLeft = PET_NAME_TICKS;
			return List.of();
		}
		List<Drop> drops = new ArrayList<>();
		Integer id = watch.idForName(line.getName());
		if (petTicksLeft > 0 && id != null && !lootedLately(id))
		{
			// The pet line's name: the untradeable drop line, or the clog line when that comes first
			petTicksLeft = 0;
			drops.add(new Drop(Kind.ITEM, id, line.getName(), 1, watch.eventsFor(id)));
		}
		if (line.getKind() == DropMessages.Kind.CLOG_SLOT && !watch.clogSlotEvents().isEmpty())
		{
			drops.add(new Drop(Kind.CLOG_SLOT, id, line.getName(), 1, watch.clogSlotEvents()));
		}
		return drops;
	}

	void tick()
	{
		tick++;
		if (petTicksLeft > 0)
		{
			petTicksLeft--;
		}
		lootedAt.values().removeIf(at -> tick - at > PET_NAME_TICKS);
	}

	private boolean lootedLately(int id)
	{
		Integer at = lootedAt.get(id);
		return at != null && tick - at <= PET_NAME_TICKS;
	}
}
