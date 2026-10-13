package com.friendscape;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class SubmissionResponseTest
{
	private static final Map<String, String> NAMES = Map.of("a", "Bingo A", "b", "Bingo B");

	private static QueuedSubmission entry(String kind, String name)
	{
		return new QueuedSubmission("id", "7", "Iron Man", kind, 11286, name, 1, "2026-11-03T12:00:00Z", List.of("a", "b"));
	}

	private static List<ProblemNotices.Notice> notices(String events, String kind, boolean chatOnCounted)
	{
		SubmissionResponse response = new Gson().fromJson("{\"rsn\":\"Iron Man\",\"duplicate\":false,\"events\":["
			+ events + "]}", SubmissionResponse.class);
		return response.notices(entry(kind, "Draconic visage"), NAMES, chatOnCounted);
	}

	private static ProblemNotices.Notice quiet(String text)
	{
		return new ProblemNotices.Notice(text, false);
	}

	@Test
	public void namesTheEventItCountedFor()
	{
		assertEquals(List.of(quiet("Draconic visage counted for Bingo A.")),
			notices("{\"slug\":\"a\",\"status\":\"counted\"},{\"slug\":\"b\",\"status\":\"ended\"}", "item", true));
	}

	@Test
	public void givesOneLineNamingTheCountWhenSeveralEventsCountIt()
	{
		assertEquals(List.of(quiet("Draconic visage counted for 2 Events.")),
			notices("{\"slug\":\"a\",\"status\":\"counted\"},{\"slug\":\"b\",\"status\":\"counted\"}", "item", true));
	}

	@Test
	public void callsAClogSlotASlot()
	{
		assertEquals(List.of(quiet("New collection log slot (Draconic visage) counted for Bingo A.")),
			notices("{\"slug\":\"a\",\"status\":\"counted\"}", "clog_slot", true));
	}

	@Test
	public void keepsQuietOnCountedWhenTheSettingIsOff()
	{
		assertEquals(List.of(), notices("{\"slug\":\"a\",\"status\":\"counted\"}", "item", false));
	}

	@Test
	public void tellsOfReviewAndShowsARejectionReasonInRed()
	{
		assertEquals(List.of(
				quiet("Draconic visage is waiting for review in Bingo A."),
				new ProblemNotices.Notice("Draconic visage was rejected in Bingo B: Grace window has passed", true)),
			notices("{\"slug\":\"a\",\"status\":\"pending\"},"
				+ "{\"slug\":\"b\",\"status\":\"rejected\",\"reason\":\"Grace window has passed\"}", "item", false));
	}

	@Test
	public void leavesHeldAndNeedsEvidenceToTheirOwnNotices()
	{
		// Held shows from discovery; needs evidence was told loudly when the screenshot failed
		assertEquals(List.of(), notices("{\"slug\":\"a\",\"status\":\"held\"},{\"slug\":\"b\",\"status\":\"needs_evidence\"}",
			"item", true));
	}
}
