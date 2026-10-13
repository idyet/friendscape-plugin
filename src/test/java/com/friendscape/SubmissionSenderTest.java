package com.friendscape;

import com.google.gson.Gson;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SubmissionSenderTest
{
	private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
	private static final long HASH = 7L;
	private static final Gson GSON = new Gson();

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private final List<ProblemNotices.Notice> told = new CopyOnWriteArrayList<>();
	private final AtomicInteger changes = new AtomicInteger();
	private volatile int answerCode = 200;
	private volatile String answer = "{\"events\":[{\"slug\":\"bingo\",\"status\":\"counted\"}]}";
	private SubmissionQueue queue;

	@Before
	public void setUp() throws IOException
	{
		queue = SubmissionQueue.open(folder.newFolder("submissions"), GSON);
	}

	private SubmissionSender sender()
	{
		Interceptor fake = chain -> new Response.Builder()
			.request(chain.request())
			.protocol(Protocol.HTTP_1_1)
			.code(answerCode)
			.message("fake")
			.body(ResponseBody.create(MediaType.get("application/json"), answer))
			.build();
		FriendscapeApi api = new FriendscapeApi(new OkHttpClient.Builder().addInterceptor(fake).build(), GSON,
			HttpUrl.get("https://friendscape.test/api/"), () -> true);
		return new SubmissionSender(queue, api, new SubmissionSender.Listener()
		{
			@Override
			public void tell(ProblemNotices.Notice notice)
			{
				told.add(notice);
			}

			@Override
			public void queueChanged()
			{
				changes.incrementAndGet();
			}
		});
	}

	private static QueuedSubmission entry(String id, Instant capturedAt)
	{
		return new QueuedSubmission(id, Long.toString(HASH), "Iron Man", "item", 11286, "Draconic visage", 1,
			capturedAt.toString(), List.of("bingo"));
	}

	/** Flushes, then waits until the send settled as {@code settled} says. */
	private void flush(SubmissionSender sender, BooleanSupplier settled) throws InterruptedException
	{
		sender.flush(HASH, new SubmissionSender.Context(Set.of(), Map.of("bingo", "Bingo"), true), NOW);
		for (int i = 0; i < 200 && !settled.getAsBoolean(); i++)
		{
			Thread.sleep(10);
		}
		assertTrue("settled", settled.getAsBoolean());
	}

	@Test
	public void sendsAndForgetsAnAnsweredSubmission() throws Exception
	{
		SubmissionSender sender = sender();
		sender.add(List.of(entry("a", NOW)), new byte[]{1});

		flush(sender, () -> !told.isEmpty());

		assertEquals(Map.of(), queue.waiting());
		assertEquals(List.of(new ProblemNotices.Notice("Draconic visage counted for Bingo.", false)), told);
		assertTrue(changes.get() >= 2);
	}

	@Test
	public void keepsASubmissionTheServerDidNotAnswer() throws Exception
	{
		answerCode = 502;
		SubmissionSender sender = sender();
		sender.add(List.of(entry("a", NOW)), new byte[]{1});

		flush(sender, () -> !queue.due(HASH, NOW.plus(SubmissionQueue.FIRST_RETRY)).isEmpty());

		assertEquals(Map.of("7", Map.of("bingo", 1)), queue.waiting());
		assertEquals("backing off", List.of(), queue.due(HASH, NOW));
		assertEquals(1, queue.due(HASH, NOW.plus(SubmissionQueue.FIRST_RETRY)).size());
		assertEquals(List.of(), told);
	}

	@Test
	public void dropsARefusedSubmissionAndSaysSo() throws Exception
	{
		answerCode = 400;
		SubmissionSender sender = sender();
		sender.add(List.of(entry("a", NOW)), new byte[]{1});

		flush(sender, () -> !told.isEmpty());

		assertEquals(Map.of(), queue.waiting());
		assertEquals(List.of(new ProblemNotices.Notice(
			"Friendscape refused Draconic visage. Upload a screenshot on the event page to claim it.", true)), told);
	}

	@Test
	public void tellsOfSubmissionsTheFullQueuePushesOut() throws Exception
	{
		SubmissionSender sender = sender();
		for (int i = 0; i <= SubmissionQueue.CAP; i++)
		{
			sender.add(List.of(entry("e" + i, NOW.plusSeconds(i))), new byte[]{1});
		}

		assertEquals(List.of(new ProblemNotices.Notice(
			"The send queue is full, so Draconic visage was taken out of it. Upload it on the event page.", true)), told);
		assertEquals(new EventCard.Queued(SubmissionQueue.CAP, 1), sender.queued(HASH).get("bingo"));
	}

	@Test
	public void prunesWhatNoDiscoveredEventCanCount() throws Exception
	{
		SubmissionSender sender = sender();
		sender.add(List.of(entry("a", NOW)), new byte[]{1});
		DiscoveryResponse discovery = GSON.fromJson("{\"events\":[{\"slug\":\"bingo\",\"phase\":\"ended\","
			+ "\"endsAt\":\"2026-11-03T11:00:00Z\",\"graceWindowSeconds\":3600}]}", DiscoveryResponse.class);

		sender.prune(HASH, discovery, NOW);

		assertEquals(Map.of(), queue.waiting());
		assertEquals(Map.of(), sender.queued(HASH));
	}

	@Test
	public void keepsWhatADiscoveredEventMayStillCount() throws Exception
	{
		SubmissionSender sender = sender();
		sender.add(List.of(entry("a", NOW)), new byte[]{1});
		DiscoveryResponse discovery = GSON.fromJson("{\"events\":[{\"slug\":\"bingo\",\"phase\":\"live\","
			+ "\"endsAt\":\"2026-11-04T12:00:00Z\",\"graceWindowSeconds\":3600}]}", DiscoveryResponse.class);

		sender.prune(HASH, discovery, NOW);

		assertEquals(new EventCard.Queued(1, 0), sender.queued(HASH).get("bingo"));
	}
}
