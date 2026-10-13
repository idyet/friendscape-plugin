package com.friendscape;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends item, clog and pet Submissions through the disk queue (SPEC 10.5) and tells the player
 * how each went (SPEC 10.4). Every call does file IO, so run it off the client thread; answers
 * arrive on OkHttp threads. The cards read {@link #queued} without touching the disk.
 */
@Slf4j
class SubmissionSender
{
	interface Listener
	{
		/** A chat line, red with the notifier for a problem. */
		void tell(ProblemNotices.Notice notice);

		/** What waits to send changed: refresh the cards. */
		void queueChanged();
	}

	/** What a send needs from the plugin's state at the time. */
	@Value
	static class Context
	{
		/** The discovered Events left on this install. */
		Set<String> muted;
		/** Event names by slug, for the chat lines. */
		Map<String, String> eventNames;
		boolean chatOnCounted;
	}

	private final SubmissionQueue queue;
	private final FriendscapeApi api;
	private final Listener listener;
	/** Pushed out of the full queue this session, by account hash and then Event slug. */
	private final Map<String, Map<String, Integer>> pushedOut = new ConcurrentHashMap<>();
	/** The queue's counts as of its last change, by account hash and then Event slug. */
	private volatile Map<String, Map<String, Integer>> waiting;

	SubmissionSender(SubmissionQueue queue, FriendscapeApi api, Listener listener)
	{
		this.queue = queue;
		this.api = api;
		this.listener = listener;
		this.waiting = queue.waiting();
	}

	/**
	 * Keeps new Submissions that share one screenshot ({@code png} null when capture failed). Any
	 * the full queue pushes out are told about and noted on their cards.
	 */
	void add(List<QueuedSubmission> submissions, byte[] png) throws IOException
	{
		for (QueuedSubmission submission : submissions)
		{
			for (QueuedSubmission removed : queue.add(submission, png))
			{
				Map<String, Integer> bySlug = pushedOut.computeIfAbsent(removed.getAccountHash(), hash -> new ConcurrentHashMap<>());
				removed.getEvents().forEach(slug -> bySlug.merge(slug, 1, Integer::sum));
				listener.tell(new ProblemNotices.Notice("The send queue is full, so " + removed.getName()
					+ " was taken out of it. Upload it on the event page.", true));
			}
		}
		changed();
	}

	/** Sends every Submission of this account that is due, each settled by its answer. */
	void flush(long accountHash, Context context, Instant now)
	{
		for (QueuedSubmission submission : queue.takeDue(accountHash, now))
		{
			byte[] png;
			try
			{
				png = queue.screenshot(submission.getId());
			}
			catch (IOException e)
			{
				log.warn("Could not read the screenshot of queued Submission {}", submission.getId(), e);
				queue.failed(submission.getId(), now);
				continue;
			}
			boolean sent = api.sendSubmission(submission, png, context.getMuted(), new FriendscapeApi.SubmissionCallback()
			{
				@Override
				public void answered(SubmissionResponse response)
				{
					queue.remove(submission.getId());
					changed();
					response.notices(submission, context.getEventNames(), context.isChatOnCounted()).forEach(listener::tell);
				}

				@Override
				public void refused(int code)
				{
					log.warn("Submission {} refused with {}", submission.getId(), code);
					queue.remove(submission.getId());
					changed();
					listener.tell(new ProblemNotices.Notice("Friendscape refused " + submission.getName()
						+ ". Upload a screenshot on the event page to claim it.", true));
				}

				@Override
				public void failed()
				{
					// Backoff from the flush, not the answer: a slow failure waits no longer
					queue.failed(submission.getId(), now);
				}
			});
			if (!sent)
			{
				queue.release(submission.getId());
			}
		}
	}

	/**
	 * Drops this account's Submissions no discovered Event can count any more: gone from
	 * discovery (card removed) or past end plus Grace window.
	 */
	void prune(long accountHash, DiscoveryResponse discovery, Instant now)
	{
		if (!queue.prune(accountHash, discovery.keepUntil(), now).isEmpty())
		{
			changed();
		}
	}

	/** Calls cut off before they answered: due again at the next flush. */
	void cancelled()
	{
		queue.clearInFlight();
	}

	/** This account's queued and pushed-out Submissions by Event slug, for the cards. */
	Map<String, EventCard.Queued> queued(long accountHash)
	{
		String hash = Long.toString(accountHash);
		Map<String, Integer> waitingHere = waiting.getOrDefault(hash, Map.of());
		Map<String, Integer> pushedOutHere = pushedOut.getOrDefault(hash, Map.of());
		Map<String, EventCard.Queued> queued = new HashMap<>();
		waitingHere.forEach((slug, count) -> queued.put(slug, new EventCard.Queued(count, pushedOutHere.getOrDefault(slug, 0))));
		pushedOutHere.forEach((slug, count) -> queued.putIfAbsent(slug, new EventCard.Queued(0, count)));
		return queued;
	}

	private void changed()
	{
		waiting = queue.waiting();
		listener.queueChanged();
	}
}
