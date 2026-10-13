package com.friendscape;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;
import net.runelite.client.util.Text;

/**
 * Reads the chat lines behind clog and pet Submissions (SPEC 10.2). They name items, never ids,
 * and each depends on a game setting: the collection log chat notification, and for pets
 * "Untradeable loot notifications", whose line names the pet after the pet line.
 */
final class DropMessages
{
	enum Kind
	{
		/** A new collection log slot, by item name. */
		CLOG_SLOT,
		/** An untradeable item dropped, by name: names a pet just after {@link #PET}. */
		UNTRADEABLE,
		/** A pet arrived, following or in the backpack; the line does not say which. */
		PET
	}

	@Value
	static class Line
	{
		Kind kind;
		/** Null for {@link Kind#PET}. */
		String name;
	}

	private static final Pattern CLOG = Pattern.compile("New item added to your collection log: (.+)");
	private static final Pattern UNTRADEABLE = Pattern.compile("Untradeable drop: (.+)");
	// A duplicate pet ("would have been followed...") never arrives, so it is not a drop
	private static final String PET_FOLLOWING = "You have a funny feeling like you're being followed.";
	private static final String PET_BACKPACK = "You feel something weird sneaking into your backpack.";

	private DropMessages()
	{
	}

	/** The drop a game message reports, or null for any other line. */
	static Line parse(String message)
	{
		String text = Text.removeTags(message).trim();
		if (text.equals(PET_FOLLOWING) || text.equals(PET_BACKPACK))
		{
			return new Line(Kind.PET, null);
		}
		Matcher clog = CLOG.matcher(text);
		if (clog.matches())
		{
			return new Line(Kind.CLOG_SLOT, clog.group(1).trim());
		}
		Matcher untradeable = UNTRADEABLE.matcher(text);
		if (untradeable.matches())
		{
			return new Line(Kind.UNTRADEABLE, untradeable.group(1).trim());
		}
		return null;
	}
}
