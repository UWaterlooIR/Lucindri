package org.lemurproject.lucindri.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.similarities.LMDirichletSimilarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.analyzer.EnglishAnalyzerConfigurable;
import org.lemurproject.lucindri.searcher.service.LucindriSearchService;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Black-box HTTP tests for {@link LucindriServer} (TASK-0019): inline mini-index, ephemeral port. */
public class LucindriServerTest {

	@TempDir
	Path dir;

	private LucindriSearchService service;
	private LucindriServer server;
	private String base;
	private final HttpClient client = HttpClient.newHttpClient();
	private final Gson gson = new Gson();

	@BeforeEach
	void setUp() throws Exception {
		Path index = dir.resolve("index");
		EnglishAnalyzerConfigurable an = new EnglishAnalyzerConfigurable();
		an.setLowercase(true);
		an.setStopwordRemoval(true);
		an.setStemmer(EnglishAnalyzerConfigurable.StemmerType.KSTEM);

		FieldType textType = new FieldType();
		textType.setTokenized(true);
		textType.setStored(true);
		textType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
		IndexWriterConfig cfg = new IndexWriterConfig(an);
		cfg.setSimilarity(new LMDirichletSimilarity());
		String[][] docs = {
				{ "d1", "the cat sat on the mat. a dog ran in the park." },
				{ "d2", "the fish swam in the lake. a bird flew away." },
				{ "wsj-90-01-01", "cat and dog played. the cat chased the dog again." },
		};
		try (Directory d = FSDirectory.open(index); IndexWriter w = new IndexWriter(d, cfg)) {
			for (String[] doc : docs) {
				Document ld = new Document();
				ld.add(new StringField("externalId", doc[0], Field.Store.YES));
				ld.add(new Field("fulltext", doc[1], textType));
				w.addDocument(ld);
			}
		}

		service = new LucindriSearchService(index.toString(), "dirichlet:2000", "kstem", true, true, 2);
		server = new LucindriServer(service, "127.0.0.1", 0, 4);
		server.start();
		base = "http://127.0.0.1:" + server.port();
	}

	@AfterEach
	void tearDown() throws IOException {
		if (server != null) {
			server.stop();
		}
		if (service != null) {
			service.close();
		}
	}

	// --- helpers --------------------------------------------------------------------------------------

	private HttpResponse<String> post(String path, String json) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json)).build();
		return client.send(req, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).GET().build();
		return client.send(req, HttpResponse.BodyHandlers.ofString());
	}

	private JsonObject json(HttpResponse<String> r) {
		return new com.google.gson.JsonParser().parse(r.body()).getAsJsonObject();
	}

	// --- tests ----------------------------------------------------------------------------------------

	@Test
	void healthz() throws Exception {
		HttpResponse<String> r = get("/healthz");
		assertEquals(200, r.statusCode());
		assertTrue(json(r).get("ok").getAsBoolean());
	}

	@Test
	void searchReturnsRankedResults() throws Exception {
		HttpResponse<String> r = post("/search", "{\"query\":\"#combine(\\\"cat\\\")\",\"count\":5}");
		assertEquals(200, r.statusCode());
		JsonArray results = json(r).getAsJsonArray("results");
		assertTrue(results.size() >= 1 && results.size() <= 5);
		double prev = Double.POSITIVE_INFINITY;
		for (int i = 0; i < results.size(); i++) {
			JsonObject res = results.get(i).getAsJsonObject();
			assertFalse(res.get("docno").getAsString().isEmpty());
			double score = res.get("score").getAsDouble();
			assertTrue(score <= prev, "non-increasing scores");
			prev = score;
			assertFalse(res.has("summary"), "no summary when not requested");
		}
	}

	@Test
	void determinism() throws Exception {
		String body = "{\"query\":\"#combine(\\\"cat\\\")\",\"count\":5}";
		assertEquals(post("/search", body).body(), post("/search", body).body());
	}

	@Test
	void summariesRequested_everyResultHasNonEmptySummary() throws Exception {
		HttpResponse<String> r = post("/search", "{\"query\":\"#combine(\\\"cat\\\")\",\"count\":5,\"summaries\":true}");
		assertEquals(200, r.statusCode());
		JsonArray results = json(r).getAsJsonArray("results");
		assertTrue(results.size() >= 1);
		for (int i = 0; i < results.size(); i++) {
			JsonObject res = results.get(i).getAsJsonObject();
			assertTrue(res.has("summary") && !res.get("summary").getAsString().isEmpty(),
					"non-empty summary for " + res.get("docno"));
		}
	}

	@Test
	void malformedQueryIs400() throws Exception {
		HttpResponse<String> r = post("/search", "{\"query\":\"#combine(\",\"count\":5}");
		assertEquals(400, r.statusCode());
		assertFalse(json(r).get("error").getAsString().isEmpty());
	}

	@Test
	void missingQueryIs400_and_nonIntegerCountIs400() throws Exception {
		assertEquals(400, post("/search", "{\"count\":5}").statusCode());
		assertEquals(400, post("/search", "{\"query\":\"#combine(\\\"cat\\\")\",\"count\":5.5}").statusCode());
	}

	@Test
	void documentRoundTrip_and404() throws Exception {
		// discover a real docno from search, then fetch it
		JsonArray results = json(post("/search", "{\"query\":\"#combine(\\\"cat\\\")\",\"count\":5}")).getAsJsonArray("results");
		String docno = results.get(0).getAsJsonObject().get("docno").getAsString();
		HttpResponse<String> r = post("/document", "{\"docno\":\"" + docno + "\"}");
		assertEquals(200, r.statusCode());
		assertEquals(docno, json(r).get("docno").getAsString());
		assertFalse(json(r).get("fulltext").getAsString().isEmpty());

		assertEquals(404, post("/document", "{\"docno\":\"__nope__\"}").statusCode());
	}

	@Test
	void documentResolvesSplittingCharDocno() throws Exception {
		HttpResponse<String> r = post("/document", "{\"docno\":\"wsj-90-01-01\"}");
		assertEquals(200, r.statusCode());
		assertTrue(json(r).get("fulltext").getAsString().contains("cat"));
	}

	@Test
	void wrongMethodIs405() throws Exception {
		HttpResponse<String> getSearch = get("/search");
		assertEquals(405, getSearch.statusCode());
		assertEquals("POST", getSearch.headers().firstValue("Allow").orElse(""));
		assertEquals(405, post("/healthz", "{}").statusCode());
	}

	@Test
	void unknownRouteIs404() throws Exception {
		assertEquals(404, get("/nope").statusCode());
	}
}
