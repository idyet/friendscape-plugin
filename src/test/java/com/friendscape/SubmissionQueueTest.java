package com.friendscape;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SubmissionQueueTest
{
	private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
	private static final long HASH = 7L;
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private File dir;
	private SubmissionQueue queue;

	@Before
	public void setUp() throws IOException
	{
		dir = new File(folder.getRoot(), "submissions");
		queue = open();
	}

	private SubmissionQueue open() throws IOException
	{
		return SubmissionQueue.open(dir, new Gson());
	}

	private static QueuedSubmission entry(String id, Instant capturedAt, String... events)
	{
		return new QueuedSubmission(id, Long.toString(HASH), "Iron Man", "item", 11286, "Draconic visage", 1,
			capturedAt.toString(), List.of(events));
	}

	@Test
	public void keepsAnEntryAndItsScreenshotAcrossRestarts() throws IOException
	{
		queue.add(entry("a", NOW, "bingo"), PNG);

		SubmissionQueue reopened = open();

		assertEquals(List.of(entry("a", NOW, "bingo")), reopened.due(HASH, NOW));
		assertArrayEquals(PNG, reopened.screenshot("a"));
	}

	@Test
	public void keepsAnEntryWhoseScreenshotFailed() throws IOException
	{
		queue.add(entry("a", NOW, "bingo"), null);

		assertEquals(1, open().due(HASH, NOW).size());
		assertNull(queue.screenshot("a"));
	}

	@Test
	public void dropsTheOldestPastTheCap() throws IOException
	{
		for (int i = 0; i < SubmissionQueue.CAP; i++)
		{
			assertEquals(List.of(), queue.add(entry("e" + i, NOW.plusSeconds(i), "bingo"), PNG));
		}

		List<QueuedSubmission> dropped = queue.add(entry("new", NOW.plusSeconds(100), "bingo"), PNG);

		assertEquals(List.of(entry("e0", NOW, "bingo")), dropped);
		assertEquals(SubmissionQueue.CAP, open().due(HASH, NOW.plusSeconds(100)).size());
		assertFalse(new File(dir, "e0.png").exists());
	}

	@Test
	public void sendsOnlyTheLoggedInAccountsEntries() throws IOException
	{
		queue.add(new QueuedSubmission("other", "8", "Alt", "item", 1, "x", 1, NOW.toString(), List.of("bingo")), PNG);

		assertEquals(List.of(), queue.due(HASH, NOW));
	}

	@Test
	public void backsOffAfterEachFailedSend() throws IOException
	{
		queue.add(entry("a", NOW, "bingo"), PNG);

		assertEquals(1, queue.takeDue(HASH, NOW).size());
		assertEquals("in flight, so a second flush takes nothing", List.of(), queue.takeDue(HASH, NOW));
		queue.failed("a", NOW);
		assertEquals(List.of(), queue.due(HASH, NOW.plus(SubmissionQueue.FIRST_RETRY).minusSeconds(1)));
		assertEquals(1, queue.due(HASH, NOW.plus(SubmissionQueue.FIRST_RETRY)).size());

		queue.takeDue(HASH, NOW.plus(SubmissionQueue.FIRST_RETRY));
		queue.failed("a", NOW);
		assertEquals("doubled", List.of(), queue.due(HASH, NOW.plus(SubmissionQueue.FIRST_RETRY)));
		assertEquals(1, queue.due(HASH, NOW.plus(SubmissionQueue.FIRST_RETRY.multipliedBy(2))).size());
	}

	@Test
	public void sendsAgainACallCutOffBeforeItAnswered() throws IOException
	{
		queue.add(entry("a", NOW, "bingo"), PNG);
		queue.takeDue(HASH, NOW);

		queue.clearInFlight();

		assertEquals(1, queue.due(HASH, NOW).size());
	}

	@Test
	public void releasesASendThatNeverLeftWithoutBackoff() throws IOException
	{
		queue.add(entry("a", NOW, "bingo"), PNG);
		queue.takeDue(HASH, NOW);

		queue.release("a");

		assertEquals(1, queue.due(HASH, NOW).size());
	}

	@Test
	public void removesAnAnsweredEntryAndItsScreenshot() throws IOException
	{
		queue.add(entry("a", NOW, "bingo"), PNG);

		queue.remove("a");

		assertEquals(List.of(), open().due(HASH, NOW));
		assertEquals(0, dir.listFiles().length);
	}

	@Test
	public void prunesEntriesWhoseEventsAreGoneOrPastTheirGraceWindow() throws IOException
	{
		queue.add(entry("open", NOW, "live", "over"), PNG);
		queue.add(entry("over", NOW, "over"), PNG);
		queue.add(entry("gone", NOW, "deleted"), PNG);
		queue.add(entry("unscheduled", NOW, "no-end"), PNG);
		queue.add(new QueuedSubmission("alt", "8", "Alt", "item", 1, "x", 1, NOW.toString(), List.of("deleted")), PNG);

		Map<String, Instant> keepUntil = new java.util.HashMap<>();
		keepUntil.put("live", NOW.plus(Duration.ofDays(1)));
		keepUntil.put("over", NOW.minusSeconds(1));
		keepUntil.put("no-end", null);
		List<QueuedSubmission> pruned = queue.prune(HASH, keepUntil, NOW);

		assertEquals(Set.of("over", "gone"), Set.of(pruned.get(0).getId(), pruned.get(1).getId()));
		assertEquals(Set.of("open", "unscheduled"), Set.of(queue.due(HASH, NOW).get(0).getId(), queue.due(HASH, NOW).get(1).getId()));
		assertEquals("another account's entries wait for its own discovery", 1, queue.due(8L, NOW).size());
	}

	@Test
	public void countsWhatIsWaitingPerEvent() throws IOException
	{
		queue.add(entry("a", NOW, "bingo", "other"), PNG);
		queue.add(entry("b", NOW, "bingo"), PNG);

		queue.add(new QueuedSubmission("alt", "8", "Alt", "item", 1, "x", 1, NOW.toString(), List.of("bingo")), PNG);

		assertEquals(Map.of("7", Map.of("bingo", 2, "other", 1), "8", Map.of("bingo", 1)), queue.waiting());
	}

	@Test
	public void removesOrphanedFilesAtStart() throws IOException
	{
		queue.add(entry("a", NOW, "bingo"), PNG);
		Files.write(new File(dir, "orphan.png").toPath(), PNG);
		Files.write(new File(dir, "half.json.tmp").toPath(), new byte[]{'{'});
		Files.write(new File(dir, "broken.json").toPath(), "not json".getBytes());

		SubmissionQueue reopened = open();

		assertEquals(1, reopened.due(HASH, NOW).size());
		assertEquals(Set.of("a.json", "a.png"), Set.of(dir.list()));
		assertTrue(new File(dir, "a.png").exists());
	}
}
