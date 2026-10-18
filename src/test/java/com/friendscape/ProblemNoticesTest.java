package com.friendscape;

import com.google.gson.Gson;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ProblemNoticesTest
{
	private static final Gson GSON = new Gson();

	private final ProblemNotices notices = new ProblemNotices();

	/** Pairs of Event number and Roster entry state. */
	private static DiscoveryResponse discovery(Object... numberAndEntry)
	{
		StringBuilder events = new StringBuilder();
		for (int i = 0; i < numberAndEntry.length; i += 2)
		{
			events.append(i == 0 ? "" : ",").append("{\"number\":").append(numberAndEntry[i])
				.append(",\"name\":\"Event ").append(numberAndEntry[i])
				.append("\",\"phase\":\"live\",\"entry\":\"").append(numberAndEntry[i + 1]).append("\"}");
		}
		return GSON.fromJson("{\"events\":[" + events + "]}", DiscoveryResponse.class);
	}

	@Test
	public void warnsOfAHeldMismatchOncePerSessionInRedWithTheNotifier()
	{
		List<ProblemNotices.Notice> first = notices.check(discovery(111111, "held"), Set.of());
		List<ProblemNotices.Notice> again = notices.check(discovery(111111, "held"), Set.of());

		assertEquals(1, first.size());
		assertTrue(first.get(0).isProblem());
		assertTrue(first.get(0).getText().contains("Event 111111"));
		assertTrue(again.isEmpty());
	}

	@Test
	public void saysNotOnRosterOncePerSessionQuietly()
	{
		List<ProblemNotices.Notice> first = notices.check(discovery(111111, "removed"), Set.of());

		assertEquals(1, first.size());
		assertEquals(false, first.get(0).isProblem());
		assertTrue(notices.check(discovery(111111, "removed"), Set.of()).isEmpty());
	}

	@Test
	public void saysNothingForTrackedOrLeftEvents()
	{
		assertTrue(notices.check(discovery(111111, "on_roster", 222222, "held"), Set.of(222222)).isEmpty());
	}

	@Test
	public void warnsAgainInANewSession()
	{
		notices.check(discovery(111111, "held"), Set.of());

		notices.reset();

		assertEquals(1, notices.check(discovery(111111, "held"), Set.of()).size());
	}

	@Test
	public void warnsPerEvent()
	{
		notices.check(discovery(111111, "held"), Set.of());

		assertEquals(1, notices.check(discovery(111111, "held", 222222, "held"), Set.of()).size());
	}
}
