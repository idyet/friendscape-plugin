package com.friendscape;

import java.io.IOException;
import java.util.List;
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

	private OkHttpClient clientAnswering(int code)
	{
		Interceptor fake = chain ->
		{
			requestedUrls.add(chain.request().url().toString());
			requestThread.set(Thread.currentThread().getName());
			return new Response.Builder()
				.request(chain.request())
				.protocol(Protocol.HTTP_1_1)
				.code(code)
				.message("fake")
				.body(ResponseBody.create(MediaType.get("application/json"), "{\"status\":\"ok\"}"))
				.build();
		};
		return new OkHttpClient.Builder().addInterceptor(fake).build();
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
		FriendscapeApi api = new FriendscapeApi(clientAnswering(200), BASE, permitted::get);

		assertEquals(Boolean.TRUE, checkHealth(api));
		assertEquals(List.of("https://friendscape.test/api/health"), requestedUrls);
	}

	@Test
	public void healthCheckReportsServerDownOnErrorStatus() throws InterruptedException
	{
		FriendscapeApi api = new FriendscapeApi(clientAnswering(503), BASE, permitted::get);

		assertEquals(Boolean.FALSE, checkHealth(api));
	}

	@Test
	public void nothingIsSentWhileNotPermitted() throws InterruptedException
	{
		permitted.set(false);
		FriendscapeApi api = new FriendscapeApi(clientAnswering(200), BASE, permitted::get);

		AtomicBoolean called = new AtomicBoolean();
		api.checkHealth(up -> called.set(true));
		Thread.sleep(200);

		assertTrue(requestedUrls.isEmpty());
		assertTrue("callback must not fire for a refused call", !called.get());
	}

	@Test
	public void requestRunsOffTheCallingThread() throws InterruptedException
	{
		FriendscapeApi api = new FriendscapeApi(clientAnswering(200), BASE, permitted::get);

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
		FriendscapeApi api = new FriendscapeApi(clientBlockingUntil(entered, release, false), BASE, permitted::get);

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
		FriendscapeApi api = new FriendscapeApi(clientBlockingUntil(entered, release, true), BASE, permitted::get);

		AtomicReference<Boolean> result = new AtomicReference<>();
		api.checkHealth(result::set);
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		permitted.set(false);
		release.countDown();
		Thread.sleep(300);

		assertNull("a response arriving after sending turned off is dropped", result.get());
	}
}
