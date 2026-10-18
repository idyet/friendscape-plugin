package com.friendscape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Value;

/**
 * The server's answer to an item, clog or pet Submission: one outcome per Event that rosters the
 * player. Any answer settles the Submission, so the queue drops it.
 */
@Value
class SubmissionResponse
{
	static final String COUNTED = "counted";
	static final String PENDING = "pending";
	static final String REJECTED = "rejected";

	String rsn;
	/** A resend of an id already taken: the outcomes are the stored ones. */
	boolean duplicate;
	List<EventOutcome> events;

	@Value
	static class EventOutcome
	{
		int number;
		/**
		 * {@code counted}, {@code needs_evidence}, {@code pending}, {@code held}, {@code rejected},
		 * or a refusal that stored nothing: {@code not_started}, {@code ended}, {@code not_on_roster}.
		 */
		String status;
		/** The public reason for a rejection. */
		String reason;
	}

	/**
	 * The chat lines for this answer (SPEC 10.4): one for counting, naming how many Events when
	 * several count it, one per review and one per rejection with its reason. Held shows through
	 * discovery, needs evidence when the screenshot failed.
	 *
	 * @param eventNames Event names by Event number, from discovery; "Event <number>" stands in for an unknown one
	 */
	List<ProblemNotices.Notice> notices(QueuedSubmission submission, Map<Integer, String> eventNames, boolean chatOnCounted)
	{
		String what = submission.isClogSlot()
			? "New collection log slot (" + submission.getName() + ")" : submission.getName();
		List<String> counted = new ArrayList<>();
		List<ProblemNotices.Notice> notices = new ArrayList<>();
		for (EventOutcome outcome : events == null ? List.<EventOutcome>of() : events)
		{
			String event = eventNames.getOrDefault(outcome.getNumber(), "Event " + outcome.getNumber());
			switch (outcome.getStatus())
			{
				case COUNTED:
					counted.add(event);
					break;
				case PENDING:
					notices.add(new ProblemNotices.Notice(what + " is waiting for review in " + event + ".", false));
					break;
				case REJECTED:
					notices.add(new ProblemNotices.Notice(what + " was rejected in " + event + ": " + outcome.getReason(), true));
					break;
				default:
					break;
			}
		}
		if (chatOnCounted && !counted.isEmpty())
		{
			String where = counted.size() == 1 ? counted.get(0) : counted.size() + " Events";
			notices.add(0, new ProblemNotices.Notice(what + " counted for " + where + ".", false));
		}
		return notices;
	}
}
