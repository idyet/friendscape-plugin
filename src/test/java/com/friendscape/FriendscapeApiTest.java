package com.friendscape;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class FriendscapeApiTest
{
	private static final HttpUrl BASE = HttpUrl.get("https://friendscape.test/api/");

	private final List<String> requestedUrls = new CopyOnWriteArrayList<>();
	private final AtomicReference<String> requestThread = new AtomicReference<>();
	private final AtomicBoolean permitted = new AtomicBoolean(true);

	private final List<String> requestBodies = new CopyOnWriteArrayList<>();

	private OkHttpClient clientAnswering(int code)
	{
		return clientAnswering(code, "{\"status\":\"ok\"}");
	}

	private OkHttpClient clientAnswering(int code, String json)
	{
		Interceptor fake = chain ->
		{
			requestedUrls.add(chain.request().url().toString());
			if (chain.request().body() != null)
			{
				Buffer buffer = new Buffer();
				chain.request().body().writeTo(buffer);
				requestBodies.add(buffer.readUtf8());
			}
			requestThread.set(Thread.currentThread().getName());
			return new Response.Builder()
				.request(chain.request())
				.protocol(Protocol.HTTP_1_1)
				.code(code)
				.message("fake")
				.body(ResponseBody.create(MediaType.get("application/json"), json))
				.build();
		};
		return new OkHttpClient.Builder().addInterceptor(fake).build();
	}

	private FriendscapeApi api(OkHttpClient http)
	{
		return new FriendscapeApi(http, new Gson(), BASE, permitted::get);
	}

	private Boolean checkHealth(FriendscapeApi api) throws InterruptedException
	{
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Boolean> result = new AtomicReference<>();
		api.checkHealth(up ->
		{
			result.set(up);
			done.countDown();
		});
		done.await(5, TimeUnit.SECONDS);
		return result.get();
	}

	@Test
	public void healthCheckReportsServerUp() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(200));

		assertEquals(Boolean.TRUE, checkHealth(api));
		assertEquals(List.of("https://friendscape.test/api/health"), requestedUrls);
	}

	@Test
	public void healthCheckReportsServerDownOnErrorStatus() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(503));

		assertEquals(Boolean.FALSE, checkHealth(api));
	}

	@Test
	public void nothingIsSentWhileNotPermitted() throws InterruptedException
	{
		permitted.set(false);
		FriendscapeApi api = api(clientAnswering(200));

		AtomicBoolean called = new AtomicBoolean();
		api.checkHealth(up -> called.set(true));
		Thread.sleep(200);

		assertTrue(requestedUrls.isEmpty());
		assertTrue("callback must not fire for a refused call", !called.get());
	}

	@Test
	public void requestRunsOffTheCallingThread() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(200));

		checkHealth(api);

		assertNotEquals(Thread.currentThread().getName(), requestThread.get());
	}

	/** Blocks inside the interceptor until released, then answers 200 or proceeds to the (cancelled) network. */
	private OkHttpClient clientBlockingUntil(CountDownLatch entered, CountDownLatch release, boolean fakeResponse)
	{
		Interceptor slow = chain ->
		{
			entered.countDown();
			try
			{
				release.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
			if (!fakeResponse)
			{
				// proceed() checks for cancellation before touching the network
				return chain.proceed(chain.request());
			}
			return new Response.Builder()
				.request(chain.request())
				.protocol(Protocol.HTTP_1_1)
				.code(200)
				.message("fake")
				.body(ResponseBody.create(MediaType.get("application/json"), "{}"))
				.build();
		};
		return new OkHttpClient.Builder().addInterceptor(slow).build();
	}

	@Test
	public void cancelAllStopsInFlightCallsAndDropsTheirResults() throws InterruptedException
	{
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		FriendscapeApi api = api(clientBlockingUntil(entered, release, false));

		AtomicReference<Boolean> result = new AtomicReference<>();
		api.checkHealth(result::set);
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		api.cancelAll();
		release.countDown();
		Thread.sleep(300);

		assertNull("a cancelled call reports nothing", result.get());
	}

	@Test
	public void resultIsDroppedWhenSendingTurnsOffMidFlight() throws InterruptedException
	{
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		FriendscapeApi api = api(clientBlockingUntil(entered, release, true));

		AtomicReference<Boolean> result = new AtomicReference<>();
		api.checkHealth(result::set);
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		permitted.set(false);
		release.countDown();
		Thread.sleep(300);

		assertNull("a response arriving after sending turned off is dropped", result.get());
	}

	private static JsonElement json(String text)
	{
		return new Gson().fromJson(text, JsonElement.class);
	}

	private ReadingsResponse sendReadings(FriendscapeApi api, Map<String, Long> xp) throws InterruptedException
	{
		return sendReadings(api, xp, Set.of());
	}

	private ReadingsResponse sendReadings(FriendscapeApi api, Map<String, Long> xp, Set<Moment> moments)
		throws InterruptedException
	{
		return sendReadings(api, xp, moments, Set.of());
	}

	private ReadingsResponse sendReadings(FriendscapeApi api, Map<String, Long> xp, Set<Moment> moments,
		Set<Integer> muted) throws InterruptedException
	{
		return sendReadings(api, xp, Map.of(), moments, muted);
	}

	private ReadingsResponse sendReadings(FriendscapeApi api, Map<String, Long> xp, Map<String, Long> kcChat,
		Set<Moment> moments, Set<Integer> muted) throws InterruptedException
	{
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<ReadingsResponse> result = new AtomicReference<>();
		api.sendReadings("Iron Man", -4611686018427387904L, xp, kcChat, moments, muted, response ->
		{
			result.set(response);
			done.countDown();
		});
		done.await(2, TimeUnit.SECONDS);
		return result.get();
	}

	@Test
	public void readingsCarryRsnAccountHashAsTextAndAbsoluteXp() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(200, "{\"rsn\":\"Iron Man\",\"events\":[]}"));

		sendReadings(api, Map.of("overall", 4_600_000_000L));

		assertEquals(List.of("https://friendscape.test/api/v1/plugin/readings"), requestedUrls);
		assertEquals(
			json("{\"rsn\":\"Iron Man\",\"accountHash\":\"-4611686018427387904\",\"xp\":{\"overall\":4600000000}}"),
			json(requestBodies.get(0)));
	}

	@Test
	public void kcReadingsCarryTheChatNameAndNoXp() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(200, "{\"rsn\":\"Iron Man\",\"events\":[]}"));

		sendReadings(api, Map.of(), Map.of("Barrows chest", 45L), Set.of(), Set.of());

		assertEquals(
			json("{\"rsn\":\"Iron Man\",\"accountHash\":\"-4611686018427387904\",\"kcChat\":{\"Barrows chest\":45}}"),
			json(requestBodies.get(0)));
	}

	@Test
	public void startAndEndReadingsAreMarked() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(200, "{\"rsn\":\"Iron Man\",\"events\":[]}"));

		sendReadings(api, Map.of("overall", 1L), EnumSet.of(Moment.START, Moment.END));

		assertEquals(
			json("{\"rsn\":\"Iron Man\",\"accountHash\":\"-4611686018427387904\",\"xp\":{\"overall\":1},"
				+ "\"atStart\":true,\"atEnd\":true}"),
			json(requestBodies.get(0)));
	}

	@Test
	public void readingsAnswerWithEachEventStatus() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(200,
			"{\"rsn\":\"Iron Man\",\"events\":[{\"number\":123456,\"status\":\"ended\",\"startsAt\":null,\"endsAt\":null}]}"));

		ReadingsResponse response = sendReadings(api, Map.of("attack", 1L));

		assertEquals("ended", response.getEvents().get(0).getStatus());
	}

	@Test
	public void aRefusedReadingIsDroppedNotRetried() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(400, "{\"error\":\"Not a valid RSN\"}"));

		assertNull(sendReadings(api, Map.of("attack", 1L)));
		assertEquals(1, requestedUrls.size());
	}

	@Test
	public void readingsNameTheEventsLeftOnThisInstall() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(200, "{\"rsn\":\"Iron Man\",\"events\":[]}"));

		sendReadings(api, Map.of("overall", 1L), Set.of(), new java.util.TreeSet<>(Set.of(654321, 123456)));

		assertEquals(
			json("{\"rsn\":\"Iron Man\",\"accountHash\":\"-4611686018427387904\",\"xp\":{\"overall\":1},"
				+ "\"muted\":[123456,654321]}"),
			json(requestBodies.get(0)));
	}

	/** Discovery's answer, or the reachability it reported: true reachable, false unreachable. */
	private final AtomicReference<DiscoveryResponse> discovered = new AtomicReference<>();
	private final AtomicReference<Boolean> reachable = new AtomicReference<>();

	private void discover(FriendscapeApi api) throws InterruptedException
	{
		CountDownLatch done = new CountDownLatch(1);
		api.discover("Iron Man", 7L, response ->
		{
			discovered.set(response);
			done.countDown();
		}, up ->
		{
			reachable.set(up);
			done.countDown();
		});
		done.await(2, TimeUnit.SECONDS);
		// The reachability callback can come just after the answer
		Thread.sleep(50);
	}

	@Test
	public void discoveryCarriesTheIdentityAndAnswersTheEvents() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(200, "{\"rsn\":\"Iron Man\",\"events\":[{\"number\":123456,"
			+ "\"standing\":{\"gain\":5,\"teams\":[{\"name\":\"Iron Man\",\"solo\":true,\"rank\":1,\"of\":2,\"gain\":5}]}}]}"));

		discover(api);

		assertEquals(List.of("https://friendscape.test/api/v1/plugin/discovery"), requestedUrls);
		assertEquals(json("{\"rsn\":\"Iron Man\",\"accountHash\":\"7\"}"), json(requestBodies.get(0)));
		assertEquals(5, discovered.get().getEvents().get(0).getStanding().getGain());
		assertEquals(Boolean.TRUE, reachable.get());
	}

	@Test
	public void discoveryReportsTheServerUnreachableOnAServerError() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(502, "bad gateway"));

		discover(api);

		assertNull(discovered.get());
		assertEquals(Boolean.FALSE, reachable.get());
	}

	@Test
	public void aRefusedDiscoveryStillMeansTheServerIsUp() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(400, "{\"error\":\"Not a valid RSN\"}"));

		discover(api);

		assertNull(discovered.get());
		assertEquals(Boolean.TRUE, reachable.get());
	}

	private String report(FriendscapeApi api) throws InterruptedException
	{
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<String> told = new AtomicReference<>();
		api.report("Iron Man", 7L, 123456, message ->
		{
			told.set(message);
			done.countDown();
		});
		done.await(2, TimeUnit.SECONDS);
		return told.get();
	}

	@Test
	public void aReportCarriesTheIdentityAndCardAndTellsTheServersAnswer() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(200, "{\"status\":\"already_reported\",\"message\":\"Already reported\"}"));

		assertEquals("Already reported", report(api));
		assertEquals(List.of("https://friendscape.test/api/v1/plugin/report"), requestedUrls);
		assertEquals(json("{\"rsn\":\"Iron Man\",\"accountHash\":\"7\",\"number\":123456}"),
			json(requestBodies.get(0)));
	}

	@Test
	public void aReportExplainsARefusalOrFailure() throws InterruptedException
	{
		assertEquals(FriendscapeApi.NOTHING_TO_REPORT, report(api(clientAnswering(404, "{}"))));
		assertEquals(FriendscapeApi.TOO_MANY_REPORTS, report(api(clientAnswering(429, "{}"))));
		assertEquals(FriendscapeApi.REPORT_FAILED, report(api(clientAnswering(502, "bad gateway"))));
	}

	/** What a vote heard: {@code choice <n>} for an answer, else the message for the player. */
	private String vote(FriendscapeApi api) throws InterruptedException
	{
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<String> heard = new AtomicReference<>();
		api.vote("Iron Man", 7L, 123456, 2, answered ->
		{
			heard.set("choice " + answered.getChoice());
			done.countDown();
		}, message ->
		{
			heard.set(message);
			done.countDown();
		});
		done.await(2, TimeUnit.SECONDS);
		return heard.get();
	}

	@Test
	public void aVoteCarriesTheIdentityCardAndCandidateAndAnswersTheVote() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(200, "{\"vote\":{\"closesAt\":\"2026-11-05T18:00:00.000Z\","
			+ "\"open\":false,\"candidates\":[],\"choice\":2}}"));

		assertEquals("choice 2", vote(api));
		assertEquals(List.of("https://friendscape.test/api/v1/plugin/vote"), requestedUrls);
		assertEquals(json("{\"rsn\":\"Iron Man\",\"accountHash\":\"7\",\"number\":123456,\"candidate\":2}"),
			json(requestBodies.get(0)));
	}

	@Test
	public void aRefusedVoteTellsTheServersReason() throws InterruptedException
	{
		assertEquals("On Cooldown this week", vote(api(clientAnswering(409, "{\"error\":\"On Cooldown this week\"}"))));
		assertEquals(FriendscapeApi.VOTE_FAILED, vote(api(clientAnswering(502, "bad gateway"))));
		assertEquals(FriendscapeApi.VOTE_FAILED, vote(api(clientAnswering(200, "{}"))));
	}

	private static final QueuedSubmission VISAGE = new QueuedSubmission("3f1c2a9e", "-4611686018427387904",
		"Iron Man", "item", 11286, "Draconic visage", 2, "2026-11-03T12:00:00Z", List.of(123456));

	/** What the send's callback heard: the answer, {@code refused:<code>} or {@code failed}. */
	private Object sendSubmission(FriendscapeApi api, QueuedSubmission entry, byte[] png) throws InterruptedException
	{
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Object> result = new AtomicReference<>();
		api.sendSubmission(entry, png, Set.of(654321), new FriendscapeApi.SubmissionCallback()
		{
			@Override
			public void answered(SubmissionResponse response)
			{
				result.set(response);
				done.countDown();
			}

			@Override
			public void refused(int code)
			{
				result.set("refused:" + code);
				done.countDown();
			}

			@Override
			public void failed()
			{
				result.set("failed");
				done.countDown();
			}
		});
		done.await(2, TimeUnit.SECONDS);
		return result.get();
	}

	/** A multipart text field's value, or null when absent. */
	private static String formField(String body, String name)
	{
		Matcher field = Pattern.compile("name=\"" + name + "\"\r\n(?:[^\r\n]+\r\n)*\r\n([^\r\n]*)\r\n").matcher(body);
		return field.find() ? field.group(1) : null;
	}

	@Test
	public void submissionsSendTheFieldsAndScreenshotAsMultipart() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(200,
			"{\"rsn\":\"Iron Man\",\"duplicate\":false,\"events\":[{\"number\":123456,\"status\":\"counted\"}]}"));

		Object answer = sendSubmission(api, VISAGE, new byte[]{1, 2, 3});

		assertEquals(List.of("https://friendscape.test/api/v1/plugin/submissions"), requestedUrls);
		String body = requestBodies.get(0);
		assertEquals("Iron Man", formField(body, "rsn"));
		assertEquals("-4611686018427387904", formField(body, "accountHash"));
		assertEquals("3f1c2a9e", formField(body, "idempotencyId"));
		assertEquals("2026-11-03T12:00:00Z", formField(body, "capturedAt"));
		assertEquals("item", formField(body, "kind"));
		assertEquals("11286", formField(body, "itemId"));
		assertEquals("2", formField(body, "quantity"));
		assertEquals("[654321]", formField(body, "muted"));
		assertTrue(body.contains("name=\"screenshot\"; filename=\"screenshot.png\""));
		assertTrue(body.contains("Content-Type: image/png"));
		assertEquals("counted", ((SubmissionResponse) answer).getEvents().get(0).getStatus());
	}

	@Test
	public void aSubmissionWithoutScreenshotOrItemIdLeavesThemOut() throws InterruptedException
	{
		FriendscapeApi api = api(clientAnswering(200, "{\"events\":[]}"));
		QueuedSubmission slot = new QueuedSubmission("x", "7", "Iron Man", "clog_slot", null, "Dossier", 1,
			"2026-11-03T12:00:00Z", List.of(123456));

		sendSubmission(api, slot, null);

		assertFalse(requestBodies.get(0).contains("screenshot"));
		assertNull(formField(requestBodies.get(0), "itemId"));
		assertEquals("clog_slot", formField(requestBodies.get(0), "kind"));
	}

	@Test
	public void aRefusedSubmissionIsToldApartFromAFailedOne() throws InterruptedException
	{
		assertEquals("refused:400", sendSubmission(api(clientAnswering(400, "{}")), VISAGE, null));
		// No screenshot storage yet, the server down, or a rate limit: worth a retry
		assertEquals("failed", sendSubmission(api(clientAnswering(503, "{}")), VISAGE, null));
		assertEquals("failed", sendSubmission(api(clientAnswering(429, "{}")), VISAGE, null));
		Interceptor down = chain ->
		{
			throw new IOException("unreachable");
		};
		assertEquals("failed", sendSubmission(api(new OkHttpClient.Builder().addInterceptor(down).build()), VISAGE, null));
	}

	@Test
	public void eventPagesLiveBesideTheApi()
	{
		assertEquals("https://friendscape.test/e/123456", FriendscapeApi.eventPage(BASE, 123456));
	}

	@Test
	public void uploadAndTilePagesHangOffTheEventPage()
	{
		assertEquals("https://friendscape.test/e/123456/upload", FriendscapeApi.uploadPage(BASE, 123456));
		assertEquals("https://friendscape.test/e/123456/t/3", FriendscapeApi.teamPage(BASE, 123456, 3));
		assertEquals("https://friendscape.test/e/123456/t/3?tile=12", FriendscapeApi.tilePage(BASE, 123456, 3, 12));
	}
}
