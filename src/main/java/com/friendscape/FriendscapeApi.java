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
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
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
	private static final MediaType PNG = MediaType.get("image/png");

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

	/** The website's page for an Event: {@code /e/<number>} on the API's host. */
	static String eventPage(HttpUrl base, int number)
	{
		return base.newBuilder().encodedPath("/e/").addPathSegment(Integer.toString(number)).build().toString();
	}

	/** The website's upload page for an Event, for needs-evidence and manual uploads. */
	static String uploadPage(HttpUrl base, int number)
	{
		return base.newBuilder().encodedPath("/e/").addPathSegment(Integer.toString(number)).addPathSegment("upload").build().toString();
	}

	/** A Team's board page: {@code /e/<number>/t/<team>}. */
	static String teamPage(HttpUrl base, int number, int teamId)
	{
		return teamPageUrl(base, number, teamId).build().toString();
	}

	/** One Tile's detail on a Team's board page: {@code /e/<number>/t/<team>?tile=<n>}. */
	static String tilePage(HttpUrl base, int number, int teamId, int tile)
	{
		return teamPageUrl(base, number, teamId).addQueryParameter("tile", Integer.toString(tile)).build().toString();
	}

	private static HttpUrl.Builder teamPageUrl(HttpUrl base, int number, int teamId)
	{
		return base.newBuilder().encodedPath("/e/").addPathSegment(Integer.toString(number)).addPathSegment("t")
			.addPathSegment(Integer.toString(teamId)).query(null);
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
	 * a total that covers it. {@code muted} holds the numbers of the Events left on this install. The server's
	 * answer comes back on an OkHttp thread; nothing comes back for a failed or refused send.
	 */
	void sendReadings(String rsn, long accountHash, Map<String, Long> xp, Map<String, Long> kcChat,
		Set<Moment> moments, Set<Integer> muted, Consumer<ReadingsResponse> onResult)
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

	static final String REPORT_FAILED = "Could not report to Friendscape. Try again later.";
	static final String NOTHING_TO_REPORT = "Nothing held to report: it may be settled already.";
	static final String TOO_MANY_REPORTS = "Too many reports from here. Try again in an hour.";

	/** The server's answer to a report: {@code {"status", "message"}}. */
	@Value
	static class ReportAnswer
	{
		String status;
		String message;
	}

	/**
	 * "Report to Friendscape" on a held card (SPEC 10.3): asks the admin to look at the identity
	 * mismatch over {@code rsn}, from the card of Event {@code number}. {@code onMessage} hears what to tell
	 * the player, on an OkHttp thread; nothing for a call refused here or cancelled.
	 */
	void report(String rsn, long accountHash, int number, Consumer<String> onMessage)
	{
		Map<String, Object> body = identity(rsn, accountHash);
		body.put("number", number);
		Request request = new Request.Builder()
			.url(base.resolve("v1/plugin/report"))
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		enqueue(request, response ->
		{
			if (response.code() == 404)
			{
				onMessage.accept(NOTHING_TO_REPORT);
				return;
			}
			if (response.code() == 429)
			{
				onMessage.accept(TOO_MANY_REPORTS);
				return;
			}
			ReportAnswer answer = parse(response, ReportAnswer.class);
			onMessage.accept(answer == null || answer.getMessage() == null ? REPORT_FAILED : answer.getMessage());
		}, error -> onMessage.accept(REPORT_FAILED));
	}

	/** How a Submission send ended; called back on an OkHttp thread, not at all if refused here or cancelled. */
	interface SubmissionCallback
	{
		/** The server took it: any outcome settles it. */
		void answered(SubmissionResponse response);

		/** The server refused it for good (a 4xx other than 408 and 429): resending would not help. */
		void refused(int code);

		/** No answer, a timeout, rate limit, server error or unreadable body: worth a retry. */
		void failed();
	}

	/**
	 * Sends an item, clog or pet Submission (SPEC 5.4) as multipart: the queued Submission's fields and
	 * the screenshot, which is left out when capture failed. {@code muted} holds the numbers of the Events
	 * left on this install. False when refused here, as sending is off: the callback never fires.
	 */
	boolean sendSubmission(QueuedSubmission submission, byte[] png, Set<Integer> muted, SubmissionCallback callback)
	{
		MultipartBody.Builder body = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("rsn", submission.getRsn())
			.addFormDataPart("accountHash", submission.getAccountHash())
			.addFormDataPart("idempotencyId", submission.getId())
			.addFormDataPart("capturedAt", submission.getCapturedAt())
			.addFormDataPart("kind", submission.getKind())
			.addFormDataPart("quantity", Integer.toString(submission.getQuantity()));
		if (submission.getItemId() != null)
		{
			body.addFormDataPart("itemId", Integer.toString(submission.getItemId()));
		}
		if (!muted.isEmpty())
		{
			body.addFormDataPart("muted", gson.toJson(muted));
		}
		if (png != null)
		{
			body.addFormDataPart("screenshot", "screenshot.png", RequestBody.create(PNG, png));
		}
		Request request = new Request.Builder()
			.url(base.resolve("v1/plugin/submissions"))
			.post(body.build())
			.build();
		return enqueue(request, response ->
		{
			int code = response.code();
			// A timeout or rate limit is passing; any other 4xx would fail again
			if (code >= 400 && code < 500 && code != 408 && code != 429)
			{
				callback.refused(code);
				return;
			}
			SubmissionResponse parsed = parse(response, SubmissionResponse.class);
			if (parsed == null)
			{
				callback.failed();
			}
			else
			{
				callback.answered(parsed);
			}
		}, error -> callback.failed());
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

	/** False when refused here: sending is off, logged out or the world is not tracked. */
	private boolean enqueue(Request request, Consumer<Response> onResponse, Consumer<IOException> onFailure)
	{
		if (!permitted.getAsBoolean())
		{
			log.debug("Refused {} {}: sending is off or this world is not tracked", request.method(), request.url());
			return false;
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
		return true;
	}

	/** Removes the call from the in-flight set; true if its result should still be delivered. */
	private boolean claimResult(Call call)
	{
		inFlight.remove(call);
		return !call.isCanceled() && permitted.getAsBoolean();
	}
}
