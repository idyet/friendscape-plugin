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
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import static org.junit.Assert.assertEquals;
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
		Set<String> muted) throws InterruptedException
	{
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<ReadingsResponse> result = new AtomicReference<>();
		api.sendReadings("Iron Man", -4611686018427387904L, xp, moments, muted, response ->
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
			"{\"rsn\":\"Iron Man\",\"events\":[{\"slug\":\"sotw\",\"status\":\"ended\",\"startsAt\":null,\"endsAt\":null}]}"));

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

		sendReadings(api, Map.of("overall", 1L), Set.of(), new java.util.TreeSet<>(Set.of("b", "a")));

		assertEquals(
			json("{\"rsn\":\"Iron Man\",\"accountHash\":\"-4611686018427387904\",\"xp\":{\"overall\":1},"
				+ "\"muted\":[\"a\",\"b\"]}"),
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
		FriendscapeApi api = api(clientAnswering(200, "{\"rsn\":\"Iron Man\",\"events\":[{\"slug\":\"sotw\","
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

	@Test
	public void eventPagesLiveBesideTheApi()
	{
		assertEquals("https://friendscape.test/e/sotw-slayer", FriendscapeApi.eventPage(BASE, "sotw-slayer"));
		assertEquals("https://friendscape.test/e/a%20b", FriendscapeApi.eventPage(BASE, "a b"));
	}
}
