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

	private static DiscoveryResponse discovery(String... slugAndEntry)
	{
		StringBuilder events = new StringBuilder();
		for (int i = 0; i < slugAndEntry.length; i += 2)
		{
			events.append(i == 0 ? "" : ",").append("{\"slug\":\"").append(slugAndEntry[i])
				.append("\",\"name\":\"Event ").append(slugAndEntry[i])
				.append("\",\"phase\":\"live\",\"entry\":\"").append(slugAndEntry[i + 1]).append("\"}");
		}
		return GSON.fromJson("{\"events\":[" + events + "]}", DiscoveryResponse.class);
	}

	@Test
	public void warnsOfAHeldMismatchOncePerSessionInRedWithTheNotifier()
	{
		List<ProblemNotices.Notice> first = notices.check(discovery("a", "held"), Set.of());
		List<ProblemNotices.Notice> again = notices.check(discovery("a", "held"), Set.of());

		assertEquals(1, first.size());
		assertTrue(first.get(0).isProblem());
		assertTrue(first.get(0).getText().contains("Event a"));
		assertTrue(again.isEmpty());
	}

	@Test
	public void saysNotOnRosterOncePerSessionQuietly()
	{
		List<ProblemNotices.Notice> first = notices.check(discovery("a", "removed"), Set.of());

		assertEquals(1, first.size());
		assertEquals(false, first.get(0).isProblem());
		assertTrue(notices.check(discovery("a", "removed"), Set.of()).isEmpty());
	}

	@Test
	public void saysNothingForTrackedOrLeftEvents()
	{
		assertTrue(notices.check(discovery("a", "on_roster", "b", "held"), Set.of("b")).isEmpty());
	}

	@Test
	public void warnsAgainInANewSession()
	{
		notices.check(discovery("a", "held"), Set.of());

		notices.reset();

		assertEquals(1, notices.check(discovery("a", "held"), Set.of()).size());
	}

	@Test
	public void warnsPerEvent()
	{
		notices.check(discovery("a", "held"), Set.of());

		assertEquals(1, notices.check(discovery("a", "held", "b", "held"), Set.of()).size());
	}
}
