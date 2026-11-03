package com.friendscape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Value;

/**
 * Decides the chat lines for problems on discovered Events (SPEC 10.4): a held identity mismatch
 * (red, with the notifier), removal from the Roster (quiet) and a server notice (red with the
 * notifier when the server marks it a problem), each once per Event per session, and a network
 * blocking the server (red, with the notifier), once per session. Safe to call from OkHttp threads.
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

	/** Told's key for a network block; Event keys are {@code number:entry} and {@code number:notice}. */
	private static final String BLOCKED = "blocked";

	private final Set<String> told = new HashSet<>();

	synchronized List<Notice> check(DiscoveryResponse discovery, Set<Integer> left)
	{
		List<Notice> notices = new ArrayList<>();
		if (discovery.getEvents() == null)
		{
			return notices;
		}
		for (DiscoveryResponse.DiscoveredEvent event : discovery.getEvents())
		{
			if (left.contains(event.getNumber()))
			{
				continue;
			}
			String entry = event.getEntry();
			if (DiscoveryResponse.HELD.equals(entry) && told.add(event.getNumber() + ":" + entry))
			{
				notices.add(new Notice("Your progress in " + event.getName()
					+ " is on hold while Friendscape checks this RSN's identity.", true));
			}
			else if (DiscoveryResponse.REMOVED.equals(entry) && told.add(event.getNumber() + ":" + entry))
			{
				notices.add(new Notice("You are no longer on the roster for " + event.getName() + ".", false));
			}
			Notice notice = event.getNotice();
			if (notice != null && notice.getText() != null && told.add(event.getNumber() + ":notice"))
			{
				notices.add(notice);
			}
		}
		return notices;
	}

	/** The line for a network blocking the API's {@code host}; null when already told this session. */
	synchronized Notice blocked(String host)
	{
		if (!told.add(BLOCKED))
		{
			return null;
		}
		return new Notice("Blocked by your network. Allow " + host + " in your DNS filter or router.", true);
	}

	/** A new session: every notice may show again. */
	synchronized void reset()
	{
		told.clear();
	}
}
