package org.lemurproject.lucindri.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import org.lemurproject.lucindri.searcher.parser.QueryParseException;
import org.lemurproject.lucindri.searcher.service.LucindriSearchService;
import org.lemurproject.lucindri.searcher.service.LucindriSearchService.SearchResult;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * A long-running HTTP/JSON front end over {@link LucindriSearchService} (TASK-0019): opens the index once
 * and stays warm so an agent can issue warm sub-second queries. Endpoints: {@code POST /search},
 * {@code POST /document}, {@code GET /healthz}. Uses the JDK's built-in {@link HttpServer} + Gson (no web
 * framework). Loopback dev target; auth deferred.
 */
public class LucindriServer {

	private final LucindriSearchService service;
	private final Gson gson = new Gson();
	private final HttpServer http;
	private final java.util.concurrent.ExecutorService executor;

	public LucindriServer(LucindriSearchService service, String host, int port, int threads) throws IOException {
		this.service = service;
		this.http = HttpServer.create(new InetSocketAddress(host, port), 0);
		// Daemon threads so a forgotten stop() never keeps the JVM alive; a user-supplied executor is not
		// shut down by HttpServer.stop(), so we own it and shut it down in stop().
		this.executor = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
			Thread t = new Thread(r, "lucindri-http");
			t.setDaemon(true);
			return t;
		});
		this.http.setExecutor(executor);
		register("/healthz", this::handleHealthz);
		register("/search", this::handleSearch);
		register("/document", this::handleDocument);
		register("/", this::handleNotFound); // JSON 404 for any unmatched route
	}

	/** Registers a handler with the access-log filter (request in, response out). */
	private HttpContext register(String path, HttpHandler handler) {
		HttpContext ctx = http.createContext(path, handler);
		ctx.getFilters().add(new AccessLogFilter());
		return ctx;
	}

	/** Logs every request as it arrives and its response status + duration as it leaves. */
	private static final class AccessLogFilter extends Filter {
		@Override
		public void doFilter(HttpExchange ex, Chain chain) throws IOException {
			long t0 = System.nanoTime();
			String who = ex.getRemoteAddress() != null ? ex.getRemoteAddress().getAddress().getHostAddress() : "?";
			System.out.println(now() + " req  " + ex.getRequestMethod() + " " + ex.getRequestURI() + " <- " + who);
			try {
				chain.doFilter(ex);
			} finally {
				long ms = (System.nanoTime() - t0) / 1_000_000;
				System.out.println(now() + " resp " + ex.getResponseCode() + " " + ex.getRequestMethod() + " "
						+ ex.getRequestURI() + " (" + ms + "ms)");
			}
		}

		@Override
		public String description() {
			return "access log";
		}
	}

	private static String now() {
		return java.time.LocalTime.now().toString();
	}

	public void start() {
		http.start();
	}

	/** The actual bound port (useful when constructed with port 0 for an ephemeral test port). */
	public int port() {
		return http.getAddress().getPort();
	}

	public void stop() {
		http.stop(0);
		executor.shutdownNow();
	}

	// --- handlers -------------------------------------------------------------------------------------

	private void handleHealthz(HttpExchange ex) throws IOException {
		if (requireMethod(ex, "GET")) {
			return;
		}
		Map<String, Object> ok = new LinkedHashMap<>();
		ok.put("ok", true);
		sendJson(ex, 200, ok);
	}

	private void handleSearch(HttpExchange ex) throws IOException {
		if (requireMethod(ex, "POST")) {
			return;
		}
		JsonObject body = readJson(ex);
		if (body == null) {
			sendError(ex, 400, "invalid JSON body");
			return;
		}
		JsonElement q = body.get("query");
		if (q == null || !q.isJsonPrimitive() || !q.getAsJsonPrimitive().isString()) {
			sendError(ex, 400, "missing required field: query (string)");
			return;
		}
		JsonElement c = body.get("count");
		if (c == null || !c.isJsonPrimitive() || !c.getAsJsonPrimitive().isNumber()) {
			sendError(ex, 400, "missing required field: count (positive integer)");
			return;
		}
		double cd = c.getAsDouble();
		if (Double.isNaN(cd) || Double.isInfinite(cd) || cd != Math.floor(cd) || cd < 1) {
			// Gson's getAsInt() would truncate 5.5 -> 5; validate integrality explicitly.
			sendError(ex, 400, "count must be a positive integer");
			return;
		}
		boolean summaries = false;
		JsonElement s = body.get("summaries");
		if (s != null && s.isJsonPrimitive() && s.getAsJsonPrimitive().isBoolean()) {
			summaries = s.getAsBoolean();
		}
		try {
			System.out.println(now() + " search query=" + q.getAsString() + " count=" + (int) cd
					+ " summaries=" + summaries);
			List<SearchResult> results = service.search(q.getAsString(), (int) cd, summaries);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("results", results); // SearchResult fields serialize directly; null summary is omitted
			sendJson(ex, 200, out);
		} catch (QueryParseException e) {
			sendError(ex, 400, e.getMessage()); // syntax error; the consumer self-corrects
		} catch (Exception e) {
			e.printStackTrace(); // log server-side; a 500 should never be silent
			sendError(ex, 500, "internal error: " + e);
		}
	}

	private void handleDocument(HttpExchange ex) throws IOException {
		if (requireMethod(ex, "POST")) {
			return;
		}
		JsonObject body = readJson(ex);
		if (body == null) {
			sendError(ex, 400, "invalid JSON body");
			return;
		}
		JsonElement d = body.get("docno");
		if (d == null || !d.isJsonPrimitive() || !d.getAsJsonPrimitive().isString()) {
			sendError(ex, 400, "missing required field: docno (string)");
			return;
		}
		try {
			String docno = d.getAsString();
			String fulltext = service.document(docno);
			if (fulltext == null) {
				sendError(ex, 404, "unknown docno");
				return;
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("docno", docno);
			out.put("fulltext", fulltext);
			sendJson(ex, 200, out);
		} catch (Exception e) {
			e.printStackTrace(); // log server-side; a 500 should never be silent
			sendError(ex, 500, "internal error: " + e);
		}
	}

	private void handleNotFound(HttpExchange ex) throws IOException {
		sendError(ex, 404, "not found");
	}

	// --- helpers --------------------------------------------------------------------------------------

	/** Returns true (and sends a 405) if the request method is not {@code method}. */
	private boolean requireMethod(HttpExchange ex, String method) throws IOException {
		if (method.equals(ex.getRequestMethod())) {
			return false;
		}
		ex.getResponseHeaders().set("Allow", method);
		sendError(ex, 405, "method not allowed; use " + method);
		return true;
	}

	private JsonObject readJson(HttpExchange ex) {
		try {
			String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			JsonElement el = new JsonParser().parse(body); // Gson 2.7-compatible
			return el.isJsonObject() ? el.getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private void sendError(HttpExchange ex, int status, String message) throws IOException {
		Map<String, Object> err = new LinkedHashMap<>();
		err.put("error", message);
		sendJson(ex, status, err);
	}

	private void sendJson(HttpExchange ex, int status, Object body) throws IOException {
		byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	// --- entry point ----------------------------------------------------------------------------------

	public static void main(String[] args) throws Exception {
		Map<String, String> flags = parseFlags(args);
		String index = flags.get("index");
		String portStr = flags.get("port");
		if (index == null || portStr == null) {
			System.err.println("Usage: LucindriServer --index <dir[,dir,...]> --port <n>");
			System.err.println("  [--host 127.0.0.1] [--rule dirichlet:2000] [--stemmer kstem]");
			System.err.println("  [--removeStopwords true] [--ignoreCase true] [--maxPassages 2] [--threads 8]");
			System.exit(2);
			return;
		}
		String host = flags.getOrDefault("host", "127.0.0.1");
		String rule = flags.get("rule"); // null -> service default (Dirichlet mu=2000)
		String stemmer = flags.getOrDefault("stemmer", "kstem");
		boolean removeStopwords = Boolean.parseBoolean(flags.getOrDefault("removeStopwords", "true"));
		boolean ignoreCase = Boolean.parseBoolean(flags.getOrDefault("ignoreCase", "true"));
		int maxPassages = Integer.parseInt(flags.getOrDefault("maxPassages", "2"));
		int port = Integer.parseInt(portStr);
		int threads = Integer.parseInt(flags.getOrDefault("threads",
				String.valueOf(Math.max(4, Runtime.getRuntime().availableProcessors()))));

		LucindriSearchService service = new LucindriSearchService(index, rule, stemmer, removeStopwords, ignoreCase,
				maxPassages);
		LucindriServer server = new LucindriServer(service, host, port, threads);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			server.stop();
			try {
				service.close();
			} catch (IOException ignored) {
			}
		}));
		server.start();
		System.out.println("Lucindri server: index=" + index + " rule=" + (rule == null ? "dirichlet:2000" : rule)
				+ " stemmer=" + stemmer + " removeStopwords=" + removeStopwords + " ignoreCase=" + ignoreCase
				+ " maxPassages=" + maxPassages + " threads=" + threads);
		System.out.println("listening on " + host + ":" + server.port());
	}

	/** Parses {@code --key value} pairs into a map. A flag with no following value maps to "true". */
	static Map<String, String> parseFlags(String[] args) {
		Map<String, String> flags = new HashMap<>();
		for (int i = 0; i < args.length; i++) {
			if (args[i].startsWith("--")) {
				String key = args[i].substring(2);
				if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
					flags.put(key, args[++i]);
				} else {
					flags.put(key, "true");
				}
			}
		}
		return flags;
	}
}
