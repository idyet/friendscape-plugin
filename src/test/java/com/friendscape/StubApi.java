package com.friendscape;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A fake Friendscape API for running the plugin locally (issue 187): serves a discovery file that
 * can show every card state, including ones the real server cannot drive yet, and logs what the
 * plugin sends. Test sources only, so it never reaches the Plugin Hub jar.
 * <p>
 * Discovery serves {@code discovery.json} (the showcase), or {@code scenarios/<name>.json} after
 * {@code GET /_stub/scenario/<name>}, re-read on every request. {@code {{now}}}, {@code {{now-2h}}}
 * and {@code {{now+3d}}} (units s, m, h, d) resolve at serve time, {@code {{rsn}}} to the
 * logged-in RSN.
 */
class StubApi
{
	private static final String SHOWCASE = "showcase";
	private static final String PORT_PROPERTY = "friendscape.stubPort";
	private static final String DIR_PROPERTY = "friendscape.stubDir";
	private static final int DEFAULT_PORT = 8787;

	private static final Pattern TOKEN = Pattern.compile("\\{\\{(?:now(?:([+-])(\\d+)([smhd]))?|(rsn))\\}\\}");
	private static final Pattern SCENARIO = Pattern.compile("/_stub/scenario/([a-z0-9-]+)");
	private static final String API = "/api/";
	private static final Gson GSON = new Gson();

	private final Path dir;
	private final Clock clock;
	private final Consumer<String> log;
	private volatile String scenario = SHOWCASE;
	private HttpServer server;

	StubApi(Path dir, Clock clock, Consumer<String> log)
	{
		this.dir = dir;
		this.clock = clock;
		this.log = log;
	}

	/** Starts the stub on {@code localhost:port}, 0 for any free port; returns the port bound. */
	int start(int port) throws IOException
	{
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		server.createContext("/", this::handle);
		server.start();
		return server.getAddress().getPort();
	}

	void stop()
	{
		server.stop(0);
	}

	/** Switches discovery to the named scenario; false, keeping the current one, when it has no file. */
	boolean scenario(String name)
	{
		if (!Files.isRegularFile(file(name)))
		{
			return false;
		}
		scenario = name;
		return true;
	}

	private Path file(String name)
	{
		return SHOWCASE.equals(name) ? dir.resolve("discovery.json") : dir.resolve("scenarios").resolve(name + ".json");
	}

	/** The current scenario's discovery for {@code rsn}, its tokens resolved now. */
	DiscoveryResponse discovery(String rsn) throws IOException
	{
		return GSON.fromJson(discoveryJson(rsn), DiscoveryResponse.class);
	}

	private JsonObject discoveryJson(String rsn) throws IOException
	{
		String template = new String(Files.readAllBytes(file(scenario)), StandardCharsets.UTF_8);
		JsonObject json = GSON.fromJson(resolve(template, clock.instant(), rsn), JsonObject.class);
		json.addProperty("rsn", rsn);
		return json;
	}

	/** {@code template} with each time token as an ISO instant off {@code now}, and {@code {{rsn}}} as {@code rsn}. */
	static String resolve(String template, Instant now, String rsn)
	{
		Matcher token = TOKEN.matcher(template);
		StringBuffer out = new StringBuffer();
		while (token.find())
		{
			String value;
			if (token.group(4) != null)
			{
				value = rsn;
			}
			else
			{
				Instant at = now.truncatedTo(ChronoUnit.SECONDS);
				if (token.group(1) != null)
				{
					Duration offset = Duration.of(Long.parseLong(token.group(2)), unit(token.group(3)));
					at = "+".equals(token.group(1)) ? at.plus(offset) : at.minus(offset);
				}
				value = at.toString();
			}
			token.appendReplacement(out, Matcher.quoteReplacement(value));
		}
		token.appendTail(out);
		return out.toString();
	}

	private static ChronoUnit unit(String letter)
	{
		switch (letter)
		{
			case "s":
				return ChronoUnit.SECONDS;
			case "m":
				return ChronoUnit.MINUTES;
			case "h":
				return ChronoUnit.HOURS;
			default:
				return ChronoUnit.DAYS;
		}
	}

	/** A status and JSON body to answer with. */
	private static final class Reply
	{
		final int code;
		final JsonElement body;

		Reply(int code, JsonElement body)
		{
			this.code = code;
			this.body = body;
		}
	}

	private static final Reply NOT_FOUND = new Reply(404, field("error", "[stub] Not found"));

	private void handle(HttpExchange exchange) throws IOException
	{
		// HttpExchange is only AutoCloseable from JDK 18; the Plugin Hub builds with 11
		try
		{
			String path = exchange.getRequestURI().getRawPath();
			String body = read(exchange.getRequestBody());
			List<String> line = new ArrayList<>();
			line.add("[stub] " + path);
			line.add("protocol=" + header(exchange, FriendscapeApi.PROTOCOL_HEADER));
			line.add("version=" + header(exchange, FriendscapeApi.VERSION_HEADER));
			Reply reply;
			try
			{
				reply = route(path, body, line);
			}
			// A broken scenario file or request: answer and log it rather than drop the connection
			catch (IOException | RuntimeException e)
			{
				line.add("failed: " + e);
				reply = new Reply(500, field("error", "[stub] " + e.getMessage()));
			}
			// Logged before answering, so the line is out by the time the plugin hears back
			log.accept(String.join(" ", line));
			send(exchange, reply);
		}
		finally
		{
			exchange.close();
		}
	}

	/** The answer to {@code path}, adding what the log line should show to {@code line}. */
	private Reply route(String path, String body, List<String> line) throws IOException
	{
		Matcher switching = SCENARIO.matcher(path);
		if (switching.matches())
		{
			String name = switching.group(1);
			boolean found = scenario(name);
			line.add(found ? "scenario=" + name : "no scenario " + name);
			return found ? new Reply(200, field("status", "scenario " + name))
				: new Reply(404, field("error", "[stub] No scenario " + name));
		}
		if (!path.startsWith(API))
		{
			return NOT_FOUND;
		}
		switch (path.substring(API.length()))
		{
			case "health":
				return new Reply(200, field("status", "ok"));
			case "v1/plugin/discovery":
			{
				JsonObject identity = GSON.fromJson(body, JsonObject.class);
				String rsn = text(identity, "rsn");
				line.add("rsn=" + rsn);
				line.add("accountHash=" + text(identity, "accountHash"));
				line.add("accountType=" + text(identity, "accountType"));
				line.add("hardcore=" + text(identity, "hardcore"));
				return new Reply(200, discoveryJson(rsn));
			}
			case "v1/plugin/readings":
			{
				JsonObject reading = GSON.fromJson(body, JsonObject.class);
				line.add(metrics(reading));
				// Statuses matching discovery's, or the plugin would rediscover after every Reading
				return new Reply(200, GSON.toJsonTree(discovery(text(reading, "rsn")).asReadings(Set.of())));
			}
			case "v1/plugin/submissions":
				return new Reply(200, GSON.toJsonTree(new SubmissionResponse(null, false, List.of())));
			case "v1/plugin/report":
				return new Reply(200, GSON.toJsonTree(new FriendscapeApi.ReportAnswer("reported", "[stub] Report received.")));
			case "v1/plugin/vote":
				return vote(GSON.fromJson(body, JsonObject.class));
			default:
				return NOT_FOUND;
		}
	}

	/** Each XP and KC metric of a Reading with the Reading's World bit, as the server would count it. */
	private static String metrics(JsonObject reading)
	{
		String hardcore = " hardcore=" + text(reading, "hardcore");
		List<String> metrics = new ArrayList<>();
		for (String group : List.of("xp", "kcChat"))
		{
			if (reading.has(group))
			{
				for (Map.Entry<String, JsonElement> metric : reading.getAsJsonObject(group).entrySet())
				{
					metrics.add(metric.getKey() + "=" + metric.getValue().getAsString() + hardcore);
				}
			}
		}
		return String.join(", ", metrics);
	}

	/** The voted Event's Vote with the choice taken; refused when the scenario gives that Event none. */
	private Reply vote(JsonObject ballot) throws IOException
	{
		JsonObject discovery = discoveryJson(text(ballot, "rsn"));
		for (JsonElement event : discovery.getAsJsonArray("events"))
		{
			JsonObject found = event.getAsJsonObject();
			if (found.get("number").getAsInt() == ballot.get("number").getAsInt() && found.has("vote"))
			{
				JsonObject vote = found.getAsJsonObject("vote");
				vote.add("choice", ballot.get("candidate"));
				JsonObject answer = new JsonObject();
				answer.add("vote", vote);
				return new Reply(200, answer);
			}
		}
		return new Reply(404, field("error", "[stub] No Vote on this Event."));
	}

	private static String text(JsonObject json, String field)
	{
		JsonElement value = json == null ? null : json.get(field);
		return value == null || value.isJsonNull() ? null : value.getAsString();
	}

	private static String header(HttpExchange exchange, String name)
	{
		String value = exchange.getRequestHeaders().getFirst(name);
		return value == null ? "none" : value;
	}

	private static JsonObject field(String name, String value)
	{
		JsonObject json = new JsonObject();
		json.add(name, new JsonPrimitive(value));
		return json;
	}

	private static String read(InputStream in) throws IOException
	{
		return new String(in.readAllBytes(), StandardCharsets.UTF_8);
	}

	private static void send(HttpExchange exchange, Reply reply) throws IOException
	{
		byte[] bytes = GSON.toJson(reply.body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(reply.code, bytes.length);
		try (OutputStream out = exchange.getResponseBody())
		{
			out.write(bytes);
		}
	}

	/**
	 * {@code ./gradlew runStub}: starts the stub on {@value #DEFAULT_PORT} (or {@code -Dfriendscape.stubPort}),
	 * points the plugin at it and launches RuneLite in the same JVM.
	 */
	public static void main(String[] args) throws Exception
	{
		Path dir = Paths.get(System.getProperty(DIR_PROPERTY, "src/test/resources/stub"));
		Logger logger = LoggerFactory.getLogger(StubApi.class);
		StubApi stub = new StubApi(dir, Clock.systemUTC(), logger::info);
		int port = stub.start(Integer.getInteger(PORT_PROPERTY, DEFAULT_PORT));
		System.setProperty(FriendscapePlugin.API_BASE_PROPERTY, "http://localhost:" + port + API);
		logger.info("[stub] serving {} on port {}", dir.toAbsolutePath(), port);
		FriendscapePluginTest.main(args);
	}
}
