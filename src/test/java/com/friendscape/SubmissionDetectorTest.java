package com.friendscape;

import com.google.gson.Gson;
import java.util.List;
import java.util.Set;
import net.runelite.client.game.ItemStack;
import net.runelite.http.api.loottracker.LootRecordType;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class SubmissionDetectorTest
{
	private static final int VISAGE = 11286;
	private static final int NOTED_VISAGE = 11287;
	private static final int SNAKELING = 12921;

	private SubmissionDetector detector;

	@Before
	public void setUp()
	{
		// Notes canonicalize to the item, as ItemManager does
		detector = new SubmissionDetector(id -> id == NOTED_VISAGE ? VISAGE : id, id -> "Item " + id);
		watch("{\"items\":[{\"id\":11286,\"name\":\"Draconic visage\"},{\"id\":12921,\"name\":\"Pet snakeling\"}],"
			+ "\"clogSlots\":false}");
	}

	private void watch(String watch)
	{
		String json = "{\"events\":[{\"slug\":\"bingo\",\"phase\":\"live\",\"entry\":\"on_roster\",\"watch\":" + watch + "}]}";
		detector.setWatch(WatchList.of(new Gson().fromJson(json, DiscoveryResponse.class), Set.of()));
	}

	private static SubmissionDetector.Drop item(int id, String name, int quantity)
	{
		return new SubmissionDetector.Drop(SubmissionDetector.Kind.ITEM, id, name, quantity, Set.of("bingo"));
	}

	@Test
	public void sendsOnlyWatchedItemsFromLoot()
	{
		List<SubmissionDetector.Drop> drops = detector.loot(List.of(new ItemStack(526, 1), new ItemStack(VISAGE, 1)));

		assertEquals(List.of(item(VISAGE, "Item 11286", 1)), drops);
	}

	@Test
	public void canonicalizesIdsAndAddsUpStacksOfOneItem()
	{
		List<SubmissionDetector.Drop> drops = detector.loot(List.of(new ItemStack(NOTED_VISAGE, 2), new ItemStack(VISAGE, 1)));

		assertEquals(List.of(item(VISAGE, "Item 11286", 3)), drops);
	}

	@Test
	public void namesAPetFromTheLineAfterThePetLine()
	{
		assertEquals(List.of(), detector.chat("You have a funny feeling like you're being followed."));

		assertEquals(List.of(item(SNAKELING, "Pet snakeling", 1)), detector.chat("Untradeable drop: Pet snakeling"));
		// The collection log line for the same pet is not a second pet
		assertEquals(List.of(), detector.chat("New item added to your collection log: Pet snakeling"));
	}

	@Test
	public void doesNotTakeAnItemTheLootAlreadySentForThePet()
	{
		watch("{\"items\":[{\"id\":11286,\"name\":\"Draconic visage\"},{\"id\":12921,\"name\":\"Pet snakeling\"}],"
			+ "\"clogSlots\":false}");
		detector.loot(List.of(new ItemStack(VISAGE, 1)));
		detector.chat("You have a funny feeling like you're being followed.");

		// The visage's own untradeable line is not the pet's, and the pet line still waits for its name
		assertEquals(List.of(), detector.chat("Untradeable drop: Draconic visage"));
		assertEquals(List.of(item(SNAKELING, "Pet snakeling", 1)), detector.chat("Untradeable drop: Pet snakeling"));
	}

	@Test
	public void forgetsThePetLineAfterAFewTicks()
	{
		detector.chat("You have a funny feeling like you're being followed.");
		for (int i = 0; i < SubmissionDetector.PET_NAME_TICKS; i++)
		{
			detector.tick();
		}

		assertEquals(List.of(), detector.chat("Untradeable drop: Pet snakeling"));
	}

	@Test
	public void ignoresAnUntradeableDropWithoutAPetLine()
	{
		// Untradeable drops land on the floor and come through loot
		assertEquals(List.of(), detector.chat("Untradeable drop: Pet snakeling"));
	}

	@Test
	public void sendsANewClogSlotOnlyWhereSomeTileCountsSlots()
	{
		assertEquals(List.of(), detector.chat("New item added to your collection log: Dragon pickaxe"));

		watch("{\"items\":[{\"id\":11286,\"name\":\"Draconic visage\"}],\"clogSlots\":true}");

		assertEquals(List.of(new SubmissionDetector.Drop(SubmissionDetector.Kind.CLOG_SLOT, null, "Dragon pickaxe", 1,
				Set.of("bingo"))),
			detector.chat("New item added to your collection log: Dragon pickaxe"));
		assertEquals(List.of(new SubmissionDetector.Drop(SubmissionDetector.Kind.CLOG_SLOT, VISAGE, "Draconic visage", 1,
				Set.of("bingo"))),
			detector.chat("New item added to your collection log: Draconic visage"));
	}

	@Test
	public void takesLootFromChestsAndPickpocketsButNotKillsCoveredElsewhere()
	{
		assertTrue(SubmissionDetector.countsLoot(LootRecordType.EVENT));
		assertTrue(SubmissionDetector.countsLoot(LootRecordType.PICKPOCKET));
		// NPC kills come through ServerNpcLoot; player kills are not the player's own drops
		assertFalse(SubmissionDetector.countsLoot(LootRecordType.NPC));
		assertFalse(SubmissionDetector.countsLoot(LootRecordType.PLAYER));
		assertFalse(SubmissionDetector.countsLoot(LootRecordType.UNKNOWN));
	}
}
