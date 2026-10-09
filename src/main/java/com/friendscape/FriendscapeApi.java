package com.friendscape;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The only way out of the client. Every call is refused unless {@code permitted} says yes at
 * enqueue time, runs on OkHttp's dispatcher threads (never the client thread), and has its result
 * dropped if permission was withdrawn or the call cancelled while it was in flight.
 */
@Slf4j
class FriendscapeApi
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl base;
	private final BooleanSupplier permitted;
	private final Set<Call> inFlight = ConcurrentHashMap.newKeySet();

	FriendscapeApi(OkHttpClient http, Gson gson, HttpUrl base, BooleanSupplier permitted)
	{
		this.http = http;
		this.gson = gson;
		this.base = base;
		this.permitted = permitted;
	}

	/**
	 * Reports whether the server answered its health route. Called back on an OkHttp thread, and
	 * not at all if the call was refused or cancelled.
	 */
	void checkHealth(Consumer<Boolean> onResult)
	{
		Request request = new Request.Builder().url(base.resolve("health")).get().build();
		enqueue(request, response -> onResult.accept(response.isSuccessful()), error -> onResult.accept(false));
	}

	/**
	 * Asks which Events roster the character, with each one's phase, Roster entry state and
	 * standing. {@code onReachable} hears whether the server answered at all: a refusal (4xx) is an
	 * answer, a server error or failed connection is not; {@code onResult} hears only a successful
	 * answer. Both come back on an OkHttp thread, and neither for a call refused here or cancelled.
	 */
	void discover(String rsn, long accountHash, Consumer<DiscoveryResponse> onResult, Consumer<Boolean> onReachable)
	{
		Request request = new Request.Builder()
			.url(base.resolve("v1/plugin/discovery"))
			.post(RequestBody.create(JSON, gson.toJson(identity(rsn, accountHash))))
			.build();
		enqueue(request, response ->
		{
			onReachable.accept(response.code() < 500);
			DiscoveryResponse parsed = parse(response, DiscoveryResponse.class);
			if (parsed != null)
			{
				onResult.accept(parsed);
			}
		}, error -> onReachable.accept(false));
	}

	/** The website's page for an Event: {@code /e/<slug>} on the API's host. */
	static String eventPage(HttpUrl base, String slug)
	{
		return base.newBuilder().encodedPath("/e/").addPathSegment(slug).build().toString();
	}

	private static Map<String, Object> identity(String rsn, long accountHash)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("rsn", rsn);
		// A Java long does not fit a JSON number, so the hash travels as text
		body.put("accountHash", Long.toString(accountHash));
		return body;
	}

	/**
	 * Sends absolute skill XP by skill slug and lifetime KC by the name chat uses for it (the server
	 * maps names to activities), either of which may be empty. Marked as the start or end Reading
	 * for {@code moments}.
	 * Readings are never queued or retried: a failed send is dropped, since the next Reading carries
	 * a total that covers it. {@code muted} names the Events left on this install. The server's
	 * answer comes back on an OkHttp thread; nothing comes back for a failed or refused send.
	 */
	void sendReadings(String rsn, long accountHash, Map<String, Long> xp, Map<String, Long> kcChat,
		Set<Moment> moments, Set<String> muted, Consumer<ReadingsResponse> onResult)
	{
		Map<String, Object> body = identity(rsn, accountHash);
		if (!xp.isEmpty())
		{
			body.put("xp", xp);
		}
		if (!kcChat.isEmpty())
		{
			body.put("kcChat", kcChat);
		}
		if (moments.contains(Moment.START))
		{
			body.put("atStart", true);
		}
		if (moments.contains(Moment.END))
		{
			body.put("atEnd", true);
		}
		if (!muted.isEmpty())
		{
			// Named on each Reading only: the server captures nothing for these and stores no Leave
			body.put("muted", muted);
		}
		Request request = new Request.Builder()
			.url(base.resolve("v1/plugin/readings"))
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		enqueue(request, response ->
		{
			ReadingsResponse parsed = parse(response, ReadingsResponse.class);
			if (parsed != null)
			{
				onResult.accept(parsed);
			}
		}, error -> log.debug("Reading dropped: {}", error.getMessage()));
	}

	/** The JSON body of a successful response, or null (logged) for an error status or bad body. */
	private <T> T parse(Response response, Class<T> type)
	{
		ResponseBody body = response.body();
		if (!response.isSuccessful() || body == null)
		{
			log.debug("{} {} answered {}", response.request().method(), response.request().url(), response.code());
			return null;
		}
		try
		{
			return gson.fromJson(body.charStream(), type);
		}
		catch (JsonParseException e)
		{
			log.warn("Unreadable answer from {}", response.request().url(), e);
			return null;
		}
	}

	/** Cancels every in-flight call; their callbacks never fire. */
	void cancelAll()
	{
		// Remove one by one: clear() could drop a call enqueued mid-loop without cancelling it
		for (Call call : inFlight)
		{
			call.cancel();
			inFlight.remove(call);
		}
	}

	private void enqueue(Request request, Consumer<Response> onResponse, Consumer<IOException> onFailure)
	{
		if (!permitted.getAsBoolean())
		{
			log.debug("Refused {} {}: sending is off or this world is not tracked", request.method(), request.url());
			return;
		}

		Call call = http.newCall(request);
		inFlight.add(call);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				if (claimResult(call))
				{
					log.debug("{} {} failed", request.method(), request.url(), e);
					onFailure.accept(e);
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					if (claimResult(call))
					{
						onResponse.accept(response);
					}
				}
			}
		});
	}

	/** Removes the call from the in-flight set; true if its result should still be delivered. */
	private boolean claimResult(Call call)
	{
		inFlight.remove(call);
		return !call.isCanceled() && permitted.getAsBoolean();
	}
}
