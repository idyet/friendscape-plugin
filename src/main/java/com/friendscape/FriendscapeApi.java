package com.friendscape;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The only way out of the client. Every call is refused unless {@code permitted} says yes at
 * enqueue time, runs on OkHttp's dispatcher threads (never the client thread), and has its result
 * dropped if permission was withdrawn or the call cancelled while it was in flight.
 */
@Slf4j
class FriendscapeApi
{
	private final OkHttpClient http;
	private final HttpUrl base;
	private final BooleanSupplier permitted;
	private final Set<Call> inFlight = ConcurrentHashMap.newKeySet();

	FriendscapeApi(OkHttpClient http, HttpUrl base, BooleanSupplier permitted)
	{
		this.http = http;
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
