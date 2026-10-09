package com.friendscape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Value;

/**
 * Decides the chat lines for problems on discovered Events (SPEC 10.4): a held identity mismatch
 * (red, with the notifier) and removal from the Roster (quiet), each once per Event per session.
 * Safe to call from OkHttp threads.
 */
class ProblemNotices
{
	@Value
	static class Notice
	{
		String text;
		/** Shown in red, with the RuneLite notifier when that is on. */
		boolean problem;
	}

	private final Set<String> told = new HashSet<>();

	synchronized List<Notice> check(DiscoveryResponse discovery, Set<String> left)
	{
		List<Notice> notices = new ArrayList<>();
		if (discovery.getEvents() == null)
		{
			return notices;
		}
		for (DiscoveryResponse.DiscoveredEvent event : discovery.getEvents())
		{
			if (left.contains(event.getSlug()))
			{
				continue;
			}
			String entry = event.getEntry();
			if (DiscoveryResponse.HELD.equals(entry) && told.add(event.getSlug() + ":" + entry))
			{
				notices.add(new Notice("Your progress in " + event.getName()
					+ " is on hold while Friendscape checks this RSN's identity.", true));
			}
			else if (DiscoveryResponse.REMOVED.equals(entry) && told.add(event.getSlug() + ":" + entry))
			{
				notices.add(new Notice("You are no longer on the roster for " + event.getName() + ".", false));
			}
		}
		return notices;
	}

	/** A new session: every notice may show again. */
	synchronized void reset()
	{
		told.clear();
	}
}
