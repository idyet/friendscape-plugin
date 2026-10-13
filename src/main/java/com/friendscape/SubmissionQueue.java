package com.friendscape;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Item, clog and pet Submissions waiting to send (SPEC 10.5), on disk so they survive restarts:
 * {@code <id>.json} and its screenshot {@code <id>.png}. Every Submission goes through here and
 * leaves once the server answers, so a crash mid-send loses nothing. The JSON is written last,
 * so an submission exists only once complete. Thread-safe; does file IO, so never call it on the
 * client thread.
 */
@Slf4j
class SubmissionQueue
{
	static final int CAP = 20;
	static final Duration FIRST_RETRY = Duration.ofSeconds(30);
	private static final Duration LAST_RETRY = Duration.ofMinutes(15);
	private static final String JSON = ".json";
	private static final String PNG = ".png";

	private final File dir;
	private final Gson gson;
	/** By id, oldest capture first. */
	private final Map<String, QueuedSubmission> submissions = new LinkedHashMap<>();
	private final Map<String, Integer> failures = new HashMap<>();
	private final Map<String, Instant> retryAt = new HashMap<>();
	private final Set<String> inFlight = new HashSet<>();

	private SubmissionQueue(File dir, Gson gson)
	{
		this.dir = dir;
		this.gson = gson;
	}

	/** Loads what a previous run left, deleting orphaned and unreadable files. */
	static SubmissionQueue open(File dir, Gson gson) throws IOException
	{
		Files.createDirectories(dir.toPath());
		SubmissionQueue queue = new SubmissionQueue(dir, gson);
		queue.load();
		return queue;
	}

	private void load() throws IOException
	{
		File[] files = dir.listFiles();
		if (files == null)
		{
			throw new IOException("Cannot list " + dir);
		}
		List<QueuedSubmission> loaded = new ArrayList<>();
		Set<String> kept = new HashSet<>();
		for (File file : files)
		{
			if (file.getName().endsWith(JSON))
			{
				QueuedSubmission submission = read(file);
				if (submission != null)
				{
					loaded.add(submission);
					kept.add(file.getName());
					kept.add(submission.getId() + PNG);
				}
			}
		}
		for (File file : files)
		{
			if (!kept.contains(file.getName()))
			{
				log.debug("Removing orphaned queue file {}", file.getName());
				Files.deleteIfExists(file.toPath());
			}
		}
		loaded.sort(Comparator.comparing(SubmissionQueue::capturedAt));
		for (QueuedSubmission submission : loaded)
		{
			submissions.put(submission.getId(), submission);
		}
	}

	private QueuedSubmission read(File file)
	{
		try
		{
			QueuedSubmission submission = gson.fromJson(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), QueuedSubmission.class);
			if (submission != null && file.getName().equals(submission.getId() + JSON) && capturedAt(submission) != Instant.MIN)
			{
				return submission;
			}
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("Unreadable queued Submission {}", file.getName(), e);
		}
		return null;
	}

	/**
	 * Keeps a Submission until the server answers; {@code png} is null when capture failed. Past
	 * the cap the oldest go to make room, and are returned for a notice to upload them by hand.
	 */
	synchronized List<QueuedSubmission> add(QueuedSubmission submission, byte[] png) throws IOException
	{
		if (png != null)
		{
			write(new File(dir, submission.getId() + PNG), png);
		}
		write(new File(dir, submission.getId() + JSON), gson.toJson(submission).getBytes(StandardCharsets.UTF_8));
		submissions.put(submission.getId(), submission);
		List<QueuedSubmission> dropped = new ArrayList<>();
		while (submissions.size() > CAP)
		{
			QueuedSubmission oldest = submissions.values().stream().min(Comparator.comparing(SubmissionQueue::capturedAt)).orElseThrow();
			remove(oldest.getId());
			dropped.add(oldest);
		}
		return dropped;
	}

	/** Entries of this account due a send now, oldest first; none already in flight. */
	synchronized List<QueuedSubmission> due(long accountHash, Instant now)
	{
		String hash = Long.toString(accountHash);
		List<QueuedSubmission> due = new ArrayList<>();
		for (QueuedSubmission submission : submissions.values())
		{
			Instant at = retryAt.get(submission.getId());
			if (hash.equals(submission.getAccountHash()) && !inFlight.contains(submission.getId()) && (at == null || !now.isBefore(at)))
			{
				due.add(submission);
			}
		}
		return due;
	}

	/** The screenshot to send with an submission, or null without one. */
	synchronized byte[] screenshot(String id) throws IOException
	{
		File png = new File(dir, id + PNG);
		return png.exists() ? Files.readAllBytes(png.toPath()) : null;
	}

	/**
	 * Takes this account's due Submissions to send, marking them in flight in the same step, so
	 * two flushes never send one twice.
	 */
	synchronized List<QueuedSubmission> takeDue(long accountHash, Instant now)
	{
		List<QueuedSubmission> due = due(accountHash, now);
		due.forEach(submission -> inFlight.add(submission.getId()));
		return due;
	}

	/** A send that never left (sending turned off meanwhile): due again, without backoff. */
	synchronized void release(String id)
	{
		inFlight.remove(id);
	}

	/** No answer: retried after a backoff that doubles each time, up to {@link #LAST_RETRY}. */
	synchronized void failed(String id, Instant now)
	{
		inFlight.remove(id);
		if (!submissions.containsKey(id))
		{
			return;
		}
		int failed = failures.merge(id, 1, Integer::sum);
		Duration wait = FIRST_RETRY.multipliedBy(1L << Math.min(failed - 1, 10));
		retryAt.put(id, now.plus(wait.compareTo(LAST_RETRY) > 0 ? LAST_RETRY : wait));
	}

	/** Answered, pruned or pushed out: the submission and its screenshot go. */
	synchronized void remove(String id)
	{
		submissions.remove(id);
		failures.remove(id);
		retryAt.remove(id);
		inFlight.remove(id);
		try
		{
			Files.deleteIfExists(new File(dir, id + JSON).toPath());
			Files.deleteIfExists(new File(dir, id + PNG).toPath());
		}
		catch (IOException e)
		{
			// Left behind, the next start removes it as an orphan
			log.warn("Could not delete queued Submission {}", id, e);
		}
	}

	/**
	 * Drops this account's submissions no Event can count any more: each of its Events gone from
	 * discovery (card removed) or past {@code keepUntil} (its end plus Grace window; null while it
	 * has no end). Another account's submissions wait for that account's own discovery.
	 */
	synchronized List<QueuedSubmission> prune(long accountHash, Map<String, Instant> keepUntil, Instant now)
	{
		String hash = Long.toString(accountHash);
		List<QueuedSubmission> pruned = new ArrayList<>();
		for (QueuedSubmission submission : new ArrayList<>(submissions.values()))
		{
			if (!hash.equals(submission.getAccountHash()))
			{
				continue;
			}
			boolean wanted = false;
			for (String slug : submission.getEvents())
			{
				Instant until = keepUntil.get(slug);
				wanted |= keepUntil.containsKey(slug) && (until == null || now.isBefore(until));
			}
			if (!wanted)
			{
				remove(submission.getId());
				pruned.add(submission);
			}
		}
		return pruned;
	}

	/** How many submissions wait per Event slug, by account hash: the cards' "waiting to send". */
	synchronized Map<String, Map<String, Integer>> waiting()
	{
		Map<String, Map<String, Integer>> waiting = new HashMap<>();
		for (QueuedSubmission submission : submissions.values())
		{
			Map<String, Integer> bySlug = waiting.computeIfAbsent(submission.getAccountHash(), hash -> new TreeMap<>());
			submission.getEvents().forEach(slug -> bySlug.merge(slug, 1, Integer::sum));
		}
		return waiting;
	}

	/** Calls cut off before they answered (logout, hop, sending off): due again at once. */
	synchronized void clearInFlight()
	{
		inFlight.clear();
	}

	private static void write(File file, byte[] bytes) throws IOException
	{
		File temp = new File(file.getParentFile(), file.getName() + ".tmp");
		Files.write(temp.toPath(), bytes);
		Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Its capture time, or {@link Instant#MIN} when unreadable. */
	private static Instant capturedAt(QueuedSubmission submission)
	{
		if (submission.getCapturedAt() == null)
		{
			return Instant.MIN;
		}
		try
		{
			return Instant.parse(submission.getCapturedAt());
		}
		catch (DateTimeParseException e)
		{
			// Hand-edited or from a broken write: load() deletes it
			return Instant.MIN;
		}
	}
}
