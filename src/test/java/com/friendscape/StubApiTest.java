package com.friendscape;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StubApiTest
{
	private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
	private static final Gson GSON = new Gson();

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final List<String> logged = new CopyOnWriteArrayList<>();
	private final HttpClient http = HttpClient.newHttpClient();
	private Path dir;
	private StubApi stub;
	private String root;
	private String base;

	@Before
	public void start() throws IOException
	{
		dir = folder.getRoot().toPath();
		Files.createDirectories(dir.resolve("scenarios"));
		write("discovery.json", "{\"events\":[{\"number\":100001,\"name\":\"Showcase\",\"format\":\"sotw\",\"phase\":\"live\","
			+ "\"startsAt\":\"{{now-2h}}\",\"endsAt\":\"{{now+3d}}\",\"entry\":\"on_roster\"}]}");
		write("scenarios/other.json", "{\"events\":[{\"number\":100002,\"name\":\"Other\",\"phase\":\"live\"}]}");
		stub = new StubApi(dir, Clock.fixed(NOW, ZoneOffset.UTC), logged::add);
		root = "http://localhost:" + stub.start(0) + "/";
		base = root + "api/";
	}

	@After
	public void stop()
	{
		stub.stop();
	}

	private void write(String name, String json) throws IOException
	{
		Files.write(dir.resolve(name), json.getBytes(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> get(String path) throws IOException, InterruptedException
	{
		return getRoot("api/" + path);
	}

	/** A path off the stub's root rather than the API base, as the scenario switch is. */
	private HttpResponse<String> getRoot(String path) throws IOException, InterruptedException
	{
		return http.send(HttpRequest.newBuilder(URI.create(root + path)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException
	{
		return http.send(HttpRequest.newBuilder(URI.create(base + path))
			.header("Content-Type", "application/json")
			.header("X-Friendscape-Protocol", "2")
			.header("X-Friendscape-Version", "0.1.3")
			.POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private DiscoveryResponse discover() throws IOException, InterruptedException
	{
		HttpResponse<String> response = post("v1/plugin/discovery", "{\"rsn\":\"Iron Man\",\"accountHash\":\"42\"}");
		assertEquals(200, response.statusCode());
		return GSON.fromJson(response.body(), DiscoveryResponse.class);
	}

	@Test
	public void resolvesTimeTokensAgainstNowAndLeavesFixedTimes()
	{
		assertEquals("\"2026-11-03T10:00:00Z\" \"2026-11-06T12:00:00Z\" \"2026-11-03T12:00:30Z\" \"2026-11-03T11:45:00Z\" "
				+ "\"2026-11-03T12:00:00Z\" \"2026-01-01T00:00:00Z\"",
			StubApi.resolve("\"{{now-2h}}\" \"{{now+3d}}\" \"{{now+30s}}\" \"{{now-15m}}\" \"{{now}}\" \"2026-01-01T00:00:00Z\"",
				NOW, "Iron Man"));
	}

	@Test
	public void resolvesTheRsnToken()
	{
		assertEquals("{\"name\":\"Iron Man\"}", StubApi.resolve("{\"name\":\"{{rsn}}\"}", NOW, "Iron Man"));
	}

	@Test
	public void discoveryEchoesTheRsnAndServesTheFileWithTimesResolved() throws Exception
	{
		DiscoveryResponse discovery = discover();

		assertEquals("Iron Man", discovery.getRsn());
		assertEquals(1, discovery.getEvents().size());
		assertEquals("2026-11-03T10:00:00Z", discovery.getEvents().get(0).getStartsAt());
		assertEquals("2026-11-06T12:00:00Z", discovery.getEvents().get(0).getEndsAt());
	}

	@Test
	public void discoveryRereadsTheFileOnEveryRequest() throws Exception
	{
		discover();
		write("discovery.json", "{\"events\":[]}");

		assertTrue(discover().getEvents().isEmpty());
	}

	@Test
	public void switchesScenarioLiveAndBackToShowcase() throws Exception
	{
		assertEquals(200, getRoot("_stub/scenario/other").statusCode());
		assertEquals(100002, discover().getEvents().get(0).getNumber());

		assertEquals(200, getRoot("_stub/scenario/showcase").statusCode());
		assertEquals(100001, discover().getEvents().get(0).getNumber());
	}

	@Test
	public void refusesAnUnknownScenarioAndKeepsTheCurrentOne() throws Exception
	{
		assertEquals(404, getRoot("_stub/scenario/nope").statusCode());
		assertEquals(404, getRoot("_stub/scenario/..%2Fdiscovery").statusCode());

		assertEquals(100001, discover().getEvents().get(0).getNumber());
	}

	@Test
	public void healthAnswersJson() throws Exception
	{
		HttpResponse<String> response = get("health");

		assertEquals(200, response.statusCode());
		assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
	}

	@Test
	public void readingsCountForEveryLiveEventOfTheScenario() throws Exception
	{
		HttpResponse<String> response = post("v1/plugin/readings",
			"{\"rsn\":\"Iron Man\",\"accountHash\":\"42\",\"hardcore\":true,\"xp\":{\"attack\":100}}");

		assertEquals(200, response.statusCode());
		ReadingsResponse readings = GSON.fromJson(response.body(), ReadingsResponse.class);
		assertEquals(ReadingsResponse.COUNTED, readings.statuses().get(100001));
	}

	@Test
	public void submissionsAndReportsAccept() throws Exception
	{
		HttpResponse<String> submission = http.send(HttpRequest.newBuilder(URI.create(base + "v1/plugin/submissions"))
			.header("Content-Type", "multipart/form-data; boundary=x")
			.POST(HttpRequest.BodyPublishers.ofString("--x--")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, submission.statusCode());
		assertTrue(GSON.fromJson(submission.body(), SubmissionResponse.class).getEvents().isEmpty());

		HttpResponse<String> report = post("v1/plugin/report", "{\"rsn\":\"Iron Man\",\"accountHash\":\"42\",\"number\":100001}");
		assertEquals(200, report.statusCode());
		assertTrue(GSON.fromJson(report.body(), FriendscapeApi.ReportAnswer.class).getMessage().startsWith("[stub]"));
	}

	@Test
	public void refusesAVoteOnAnEventWithoutOne() throws Exception
	{
		HttpResponse<String> response = post("v1/plugin/vote",
			"{\"rsn\":\"Iron Man\",\"accountHash\":\"42\",\"number\":100001,\"candidate\":1}");

		assertEquals(404, response.statusCode());
		assertTrue(GSON.fromJson(response.body(), FriendscapeApi.ErrorAnswer.class).getError().startsWith("[stub]"));
	}

	@Test
	public void votesOnAnEventWithAVoteComeBackWithTheChoice() throws Exception
	{
		write("discovery.json", "{\"events\":[{\"number\":100001,\"vote\":{\"open\":true,\"candidates\":[]}}]}");

		HttpResponse<String> response = post("v1/plugin/vote",
			"{\"rsn\":\"Iron Man\",\"accountHash\":\"42\",\"number\":100001,\"candidate\":3}");

		FriendscapeApi.VoteAnswer answer = GSON.fromJson(response.body(), FriendscapeApi.VoteAnswer.class);
		assertEquals(Integer.valueOf(3), answer.getVote().getChoice());
	}

	@Test
	public void anythingElseIsNotFound() throws Exception
	{
		assertEquals(404, get("v1/plugin/nope").statusCode());
		assertEquals(404, get("nope").statusCode());
	}

	@Test
	public void logsEachRequestWithItsHeaders() throws Exception
	{
		post("v1/plugin/report", "{\"number\":1}");

		assertEquals(List.of("[stub] /api/v1/plugin/report protocol=2 version=0.1.3"), logged);
	}

	@Test
	public void logsDiscoveryIdentity() throws Exception
	{
		discover();

		assertEquals(List.of("[stub] /api/v1/plugin/discovery protocol=2 version=0.1.3 rsn=Iron Man accountHash=42"),
			logged);
	}

	@Test
	public void logsEachReadingMetricWithItsHardcoreBit() throws Exception
	{
		post("v1/plugin/readings", "{\"rsn\":\"Iron Man\",\"accountHash\":\"42\",\"hardcore\":false,"
			+ "\"xp\":{\"attack\":100,\"slayer\":5},\"kcChat\":{\"Zulrah\":3}}");

		assertEquals(List.of("[stub] /api/v1/plugin/readings protocol=2 version=0.1.3 "
			+ "attack=100 hardcore=false, slayer=5 hardcore=false, Zulrah=3 hardcore=false"), logged);
	}

	@Test
	public void logsMissingHeadersAsNone() throws Exception
	{
		get("health");

		assertEquals(List.of("[stub] /api/health protocol=none version=none"), logged);
	}

	@Test
	public void shippedShowcaseAndScenariosParse() throws IOException
	{
		Path shipped = Paths.get("src/test/resources/stub");
		StubApi real = new StubApi(shipped, Clock.fixed(NOW, ZoneOffset.UTC), logged::add);
		DiscoveryResponse showcase = real.discovery("Iron Man");
		assertEquals(5, showcase.getEvents().size());
		assertEquals("died", showcase.getEvents().get(0).getStanding().getSkull().getKind());
		assertEquals("transferred", showcase.getEvents().get(1).getStanding().getSkull().getKind());
		assertNull(showcase.getEvents().get(2).getStanding().getSkull());
		assertTrue(showcase.getEvents().get(3).getNotice().isProblem());
		assertEquals("[stub] Plain notice, problem false.", showcase.getEvents().get(4).getNotice().getText());

		assertTrue(real.scenario("skull-appears"));
		assertEquals("died", real.discovery("Iron Man").getEvents().get(2).getStanding().getSkull().getKind());
		assertTrue(real.scenario("skull-clears"));
		assertNull(real.discovery("Iron Man").getEvents().get(0).getStanding().getSkull());
		assertTrue(real.scenario("showcase"));
	}
}
