package com.friendscape;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Decides which absolute skill XP to send (SPEC 5.2): every skill at login, then the skills that
 * changed, at most once per {@link #SEND_INTERVAL} while XP changes. Each send adds {@code overall}
 * as the sum of every skill. Keys are the server's skill slugs, e.g. {@code attack}.
 */
class XpTracker
{
	static final Duration SEND_INTERVAL = Duration.ofSeconds(60);
	static final String OVERALL = "overall";

	private final Map<String, Long> known = new HashMap<>();
	private final Map<String, Long> changed = new HashMap<>();
	private Instant lastSent;

	/**
	 * Records a skill's XP from {@code StatChanged}. Ignored until {@link #takeAll} has primed the
	 * tracker, since the login Reading covers everything before it.
	 */
	synchronized void observe(String skill, long xp)
	{
		if (lastSent == null)
		{
			return;
		}
		Long before = known.put(skill, xp);
		if (before == null || before != xp)
		{
			changed.put(skill, xp);
		}
	}

	/** Whether changed skills are waiting and the last send was at least a minute ago. */
	synchronized boolean due(Instant now)
	{
		return !changed.isEmpty() && lastSent != null && !now.isBefore(lastSent.plus(SEND_INTERVAL));
	}

	/** Every skill, for the login or start Reading. Primes the tracker and clears what changed. */
	synchronized Map<String, Long> takeAll(Map<String, Long> skills, Instant now)
	{
		known.clear();
		known.putAll(skills);
		changed.clear();
		lastSent = now;
		return withOverall(new HashMap<>(known));
	}

	/** The skills changed since the last send, empty if none; the logout Reading ignores the minute. */
	synchronized Map<String, Long> takeChanged(Instant now)
	{
		if (changed.isEmpty())
		{
			return Map.of();
		}
		Map<String, Long> sent = withOverall(new HashMap<>(changed));
		changed.clear();
		lastSent = now;
		return sent;
	}

	/** Forgets everything: the next Reading must be a {@link #takeAll}. */
	synchronized void reset()
	{
		known.clear();
		changed.clear();
		lastSent = null;
	}

	private Map<String, Long> withOverall(Map<String, Long> skills)
	{
		long overall = 0;
		for (long xp : known.values())
		{
			overall += xp;
		}
		skills.put(OVERALL, overall);
		return skills;
	}
}
