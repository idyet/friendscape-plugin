package com.friendscape;

import com.google.gson.Gson;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ProblemNoticesTest
{
	private static final Gson GSON = new Gson();
	private static final String RESTART_TEXT = "Restart RuneLite to update Friendscape and take part in Event 111111.";
	private static final String RESTART = "{\"text\":\"" + RESTART_TEXT + "\",\"problem\":true}";

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

	/** One Live Event carrying the server's {@code notice} JSON. */
	private static DiscoveryResponse withNotice(int number, String entry, String notice)
	{
		return GSON.fromJson("{\"events\":[{\"number\":" + number + ",\"name\":\"Event " + number
			+ "\",\"phase\":\"live\",\"entry\":\"" + entry + "\",\"notice\":" + notice + "}]}", DiscoveryResponse.class);
	}

	private static DiscoveryResponse withNotice(int number, String notice)
	{
		return withNotice(number, "on_roster", notice);
	}

	@Test
	public void showsAServerNoticeOncePerEventPerSession()
	{
		List<ProblemNotices.Notice> first = notices.check(withNotice(111111, RESTART), Set.of());

		assertEquals(List.of(new ProblemNotices.Notice(RESTART_TEXT, true)), first);
		assertTrue(notices.check(withNotice(111111, RESTART), Set.of()).isEmpty());
		assertEquals(1, notices.check(withNotice(222222, RESTART), Set.of()).size());

		notices.reset();

		assertEquals(1, notices.check(withNotice(111111, RESTART), Set.of()).size());
	}

	@Test
	public void showsAServerNoticeQuietlyWhenNotAProblem()
	{
		List<ProblemNotices.Notice> told = notices.check(withNotice(111111, "{\"text\":\"Hello.\"}"), Set.of());

		assertEquals(List.of(new ProblemNotices.Notice("Hello.", false)), told);
	}

	@Test
	public void showsNoServerNoticeWhenAbsentTextlessOrLeft()
	{
		assertTrue(notices.check(withNotice(111111, "null"), Set.of()).isEmpty());
		assertTrue(notices.check(withNotice(111111, "{\"problem\":true}"), Set.of()).isEmpty());
		assertTrue(notices.check(withNotice(111111, RESTART), Set.of(111111)).isEmpty());
	}

	@Test
	public void showsAServerNoticeBesideAHeldMismatch()
	{
		assertEquals(2, notices.check(withNotice(111111, "held", RESTART), Set.of()).size());
	}

	@Test
	public void warnsOfABlockingNetworkOncePerSessionInRedNamingTheHost()
	{
		ProblemNotices.Notice first = notices.blocked("dev.friendscape.cc");

		assertEquals("Blocked by your network. Allow dev.friendscape.cc in your DNS filter or router.", first.getText());
		assertTrue(first.isProblem());
		assertNull(notices.blocked("dev.friendscape.cc"));

		notices.reset();

		assertNotNull(notices.blocked("dev.friendscape.cc"));
	}
}
