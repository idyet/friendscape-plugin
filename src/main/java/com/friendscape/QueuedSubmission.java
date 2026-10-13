package com.friendscape;

import java.util.List;
import lombok.Value;

/**
 * An item, clog or pet Submission waiting in the send queue (SPEC 10.5): its wire fields plus
 * what the plugin needs to keep or drop it. Stored as JSON, so add fields, never rename them.
 */
@Value
class QueuedSubmission
{
	/** The idempotency id: a resend gets the first answer back. */
	String id;
	/** The account that captured it, as text like on the wire; sent only while it is logged in. */
	String accountHash;
	String rsn;
	/** The server's kind: {@link SubmissionDetector.Kind#wire()}. */
	String kind;
	/** Null on a clog slot no watched item pins. */
	Integer itemId;
	/** For chat lines and notices. */
	String name;
	int quantity;
	/** ISO 8601. */
	String capturedAt;
	/** The Events that watched it; it leaves the queue once none of them can count it. */
	List<String> events;

	boolean isClogSlot()
	{
		return SubmissionDetector.Kind.CLOG_SLOT.wire().equals(kind);
	}
}
