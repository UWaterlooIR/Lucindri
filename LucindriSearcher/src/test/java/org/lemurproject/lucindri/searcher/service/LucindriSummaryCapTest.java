package org.lemurproject.lucindri.searcher.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
import org.lemurproject.lucindri.searcher.service.LucindriSearchService.SearchResult;
import org.lemurproject.lucindri.searcher.testutil.TestIndex;

/** TASK-0022: the summary word cap ({@link LucindriSearchService#capWords} + end-to-end). */
public class LucindriSummaryCapTest {

	// ---- capWords unit tests -------------------------------------------------------------------------

	@Test
	public void underCap_unchanged_noMarker() {
		assertEquals("one two three", LucindriSearchService.capWords("one two three", 5));
	}

	@Test
	public void exactlyCap_notTruncated() {
		assertEquals("one two three", LucindriSearchService.capWords("one two three", 3));
	}

	@Test
	public void overCap_keepsFirstN_appendsMarker() {
		assertEquals("one two three ...", LucindriSearchService.capWords("one two three four five", 3));
	}

	@Test
	public void preservesOriginalWhitespaceUpToCut() {
		// words: a, b, c, d, e — cap 3 keeps the original text through the 3rd word ("c").
		assertEquals("a  b\nc ...", LucindriSearchService.capWords("a  b\nc d e", 3));
	}

	@Test
	public void nullAndEmptyAndBadCap() {
		assertNull(LucindriSearchService.capWords(null, 5));
		assertEquals("", LucindriSearchService.capWords("", 5));
		assertEquals("a b c", LucindriSearchService.capWords("a b c", 0)); // maxWords < 1 -> no cap (defensive)
	}

	// ---- end-to-end through the service --------------------------------------------------------------

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

	private static SearchResult byDocno(List<SearchResult> results, String docno) {
		return results.stream().filter(r -> r.docno.equals(docno)).findFirst().orElseThrow();
	}

	@Test
	public void summaryIsWordCapped(@TempDir Path dir) throws Exception {
		// A run-on doc (no sentence punctuation) becomes one long passage; a short doc stays short.
		buildIndex(dir,
				new String[] { "long", "cat cat cat cat cat cat cat cat cat cat cat cat cat cat cat" },
				new String[] { "short", "the cat sat here" });
		// Cap at 10 words so truncation is easy to assert.
		try (LucindriSearchService s = new LucindriSearchService(dir.toString(), "dirichlet:2000", "kstem", true,
				true, 4, 10)) {
			List<SearchResult> results = s.search("#combine(\"cat\")", 10, true);

			String longSummary = byDocno(results, "long").summary;
			assertTrue(longSummary.endsWith(" ..."), "run-on summary is truncated: " + longSummary);
			// content = everything before the trailing " ..."; must be exactly 10 whitespace words.
			String content = longSummary.substring(0, longSummary.length() - " ...".length()).trim();
			assertEquals(10, content.split("\\s+").length, "capped to 10 words: " + content);

			String shortSummary = byDocno(results, "short").summary;
			assertFalse(shortSummary.endsWith(" ..."), "short summary not truncated: " + shortSummary);
			assertTrue(shortSummary.contains("cat"));
		}
	}
}
