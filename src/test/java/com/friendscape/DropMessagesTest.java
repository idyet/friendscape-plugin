package com.friendscape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class DropMessagesTest
{
	@Test
	public void readsANewCollectionLogSlot()
	{
		assertEquals(new DropMessages.Line(DropMessages.Kind.CLOG_SLOT, "Dragon pickaxe"),
			DropMessages.parse("New item added to your collection log: <col=ef1020>Dragon pickaxe</col>"));
	}

	@Test
	public void readsAnUntradeableDrop()
	{
		assertEquals(new DropMessages.Line(DropMessages.Kind.UNTRADEABLE, "Pet snakeling"),
			DropMessages.parse("Untradeable drop: Pet snakeling"));
	}

	@Test
	public void readsEachWayAPetArrives()
	{
		DropMessages.Line pet = new DropMessages.Line(DropMessages.Kind.PET, null);
		assertEquals(pet, DropMessages.parse("You have a funny feeling like you're being followed."));
		assertEquals(pet, DropMessages.parse("<col=ef20ff>You feel something weird sneaking into your backpack.</col>"));
	}

	@Test
	public void ignoresADuplicatePetThatNeverArrived()
	{
		assertNull(DropMessages.parse("You have a funny feeling like you would have been followed..."));
	}

	@Test
	public void ignoresOtherLines()
	{
		assertNull(DropMessages.parse("Your Zulrah kill count is: <col=ff0000>12</col>."));
		assertNull(DropMessages.parse("New item added to your collection log: "));
	}
}
