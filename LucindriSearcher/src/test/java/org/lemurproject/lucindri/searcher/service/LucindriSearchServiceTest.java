package org.lemurproject.lucindri.searcher.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.searcher.parser.QueryParseException;
import org.lemurproject.lucindri.searcher.service.LucindriSearchService.SearchResult;
import org.lemurproject.lucindri.searcher.testutil.TestIndex;

/** Tests {@link LucindriSearchService} (TASK-0019) over a small on-disk index built in the test. */
public class LucindriSearchServiceTest {

	/** Builds a production-shaped index (keyword externalId + tokenized fulltext + LMDirichlet) at {@code dir}. */
	private static void buildIndex(Path dir, String[]... docs) throws IOException {
		FieldType textType = new FieldType();
		textType.setTokenized(true);
		textType.setStored(true);
		textType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
		IndexWriterConfig cfg = new IndexWriterConfig(TestIndex.indriAnalyzer());
		cfg.setSimilarity(new LMDirichletSimilarity());
		try (Directory d = FSDirectory.open(dir); IndexWriter w = new IndexWriter(d, cfg)) {
			for (String[] doc : docs) {
				Document ld = new Document();
				ld.add(new StringField("externalId", doc[0], Field.Store.YES));
				ld.add(new Field("fulltext", doc[1], textType));
				w.addDocument(ld);
			}
		}
	}

	private static LucindriSearchService service(Path dir) throws IOException {
		return new LucindriSearchService(dir.toString(), "dirichlet:2000", "kstem", true, true, 2);
	}

	private static List<String> docnos(List<SearchResult> results) {
		return results.stream().map(r -> r.docno).collect(Collectors.toList());
	}

	@Test
	public void searchReturnsMatchingDocnosInRankOrder(@TempDir Path dir) throws Exception {
		buildIndex(dir,
				new String[] { "d1", "the cat sat on the mat. a dog ran in the park." },
				new String[] { "d2", "the fish swam in the lake. a bird flew away." },
				new String[] { "d3", "cat and dog played. the cat chased the dog again." });
		try (LucindriSearchService s = service(dir)) {
			List<SearchResult> results = s.search("#combine(\"cat\")", 10, false);
			// Only cat-bearing docs are candidates (union of term postings); d2 has no cat.
			assertTrue(docnos(results).containsAll(List.of("d1", "d3")), "cat docs returned: " + docnos(results));
			assertFalse(docnos(results).contains("d2"), "d2 has no cat");
			// Ranked best-first: scores non-increasing.
			for (int i = 1; i < results.size(); i++) {
				assertTrue(results.get(i - 1).score >= results.get(i).score, "non-increasing scores");
			}
			// summaries not requested -> null summary field.
			assertNull(results.get(0).summary);
		}
	}

	@Test
	public void documentFetchesFulltext_unknownIsNull(@TempDir Path dir) throws Exception {
		buildIndex(dir, new String[] { "d2", "the fish swam in the lake. a bird flew away." });
		try (LucindriSearchService s = service(dir)) {
			assertEquals("the fish swam in the lake. a bird flew away.", s.document("d2"));
			assertNull(s.document("nope"));
		}
	}

	@Test
	public void degenerateQueryReturnsEmpty_syntaxErrorThrows(@TempDir Path dir) throws Exception {
		buildIndex(dir, new String[] { "d1", "the cat sat on the mat." });
		try (LucindriSearchService s = service(dir)) {
			// All-stopword query analyzes to nothing -> empty results (a 200 {"results":[]}, not a 400).
			assertTrue(s.search("#combine(\"the a of\")", 10, false).isEmpty());
			// Malformed query -> QueryParseException (a 400).
			assertThrows(QueryParseException.class, () -> s.search("#combine(", 10, false));
		}
	}

	@Test
	public void summariesRequested_everyResultHasNonEmptySummary(@TempDir Path dir) throws Exception {
		buildIndex(dir,
				new String[] { "d1", "the cat sat on the mat. a dog ran in the park." },
				new String[] { "d3", "cat and dog played. the cat chased the dog again." });
		try (LucindriSearchService s = service(dir)) {
			List<SearchResult> results = s.search("#combine(\"cat\")", 10, true);
			assertFalse(results.isEmpty());
			for (SearchResult r : results) {
				assertTrue(r.summary != null && !r.summary.isEmpty(), "non-empty summary for " + r.docno);
				assertTrue(r.summary.toLowerCase().contains("cat"), "summary highlights the query term: " + r.summary);
			}
		}
	}
}
