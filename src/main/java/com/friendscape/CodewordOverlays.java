package com.friendscape;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import net.runelite.client.ui.overlay.Overlay;

/**
 * The Codeword overlays in game (SPEC 10.3): one per live card with a Codeword the player has not
 * hidden. Adds and removes them as cards come and go; called from any thread.
 */
class CodewordOverlays
{
	private final Consumer<Overlay> add;
	private final Consumer<Overlay> remove;
	private final IntFunction<CodewordOverlay> create;
	/** By Event number. */
	private final Map<Integer, CodewordOverlay> shown = new HashMap<>();
	/** Set by {@link #close}: a late refresh must not add overlays back once the plugin stops. */
	private boolean closed;

	CodewordOverlays(Consumer<Overlay> add, Consumer<Overlay> remove, IntFunction<CodewordOverlay> create)
	{
		this.add = add;
		this.remove = remove;
		this.create = create;
	}

	/** Codewords by Event number, in card order: each card that carries one, less those hidden. */
	static Map<Integer, String> wanted(List<EventCard> cards, Set<Integer> hidden)
	{
		Map<Integer, String> wanted = new LinkedHashMap<>();
		for (EventCard card : cards)
		{
			if (card.getCodeword() != null && !hidden.contains(card.getNumber()))
			{
				wanted.put(card.getNumber(), card.getCodeword());
			}
		}
		return wanted;
	}

	/** Shows exactly these Codewords, keeping each overlay that stays so its position holds. */
	synchronized void show(Map<Integer, String> codewords)
	{
		if (closed)
		{
			return;
		}
		for (Iterator<Map.Entry<Integer, CodewordOverlay>> it = shown.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<Integer, CodewordOverlay> entry = it.next();
			if (!codewords.containsKey(entry.getKey()))
			{
				remove.accept(entry.getValue());
				it.remove();
			}
		}
		codewords.forEach((number, codeword) ->
		{
			CodewordOverlay overlay = shown.get(number);
			if (overlay == null)
			{
				overlay = create.apply(number);
				overlay.setCodeword(codeword);
				shown.put(number, overlay);
				add.accept(overlay);
			}
			else
			{
				overlay.setCodeword(codeword);
			}
		});
	}

	/** Removes every overlay for good. */
	synchronized void close()
	{
		show(Map.of());
		closed = true;
	}

	/** The overlay showing an Event's Codeword, or null. */
	synchronized CodewordOverlay overlay(int number)
	{
		return shown.get(number);
	}
}
