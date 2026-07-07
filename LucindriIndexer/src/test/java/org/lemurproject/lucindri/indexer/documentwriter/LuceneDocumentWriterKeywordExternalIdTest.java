package org.lemurproject.lucindri.indexer.documentwriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.indexer.domain.IndexingConfiguration;
import org.lemurproject.lucindri.indexer.domain.ParsedDocument;
import org.lemurproject.lucindri.indexer.domain.ParsedDocumentField;

/**
 * Tests that {@link LuceneDocumentWriter} indexes {@code externalId} as a non-analyzed keyword
 * {@link org.apache.lucene.document.StringField} (TASK-0020): the whole docno is a single index term, so a
 * doc is exact-lookupable by docno with one {@link TermQuery} — even for docnos that the text analyzer
 * would split ({@code shard_00000_0}, {@code wsj-90-01-01}, {@code doc.42}). This is the writer-side proof
 * that stands in for the document formats we cannot exercise without their corpora.
 */
public class LuceneDocumentWriterKeywordExternalIdTest {

	private static IndexingConfiguration config(Path dir, boolean exactDocumentLength) {
		IndexingConfiguration c = new IndexingConfiguration();
		c.setIndexDirectory(dir.toString());
		c.setIndexName("idx");
		c.setStemmer("kstem");
		c.setRemoveStopwords(true);
		c.setIgnoreCase(true);
		c.setExactDocumentLength(exactDocumentLength);
		return c;
	}

	private static ParsedDocumentField text(String name, String content) {
		return new ParsedDocumentField(name, content, false);
	}

	private static ParsedDocument doc(ParsedDocumentField... fields) {
		ParsedDocument d = new ParsedDocument();
		d.setDocumentFields(new ArrayList<>(List.of(fields)));
		return d;
	}

	private static IndexSearcher searcherOf(DirectoryReader reader) {
		return new IndexSearcher(reader);
	}

	private static int hitCount(IndexSearcher s, String field, String value) throws Exception {
		return s.search(new TermQuery(new Term(field, value)), 10).scoreDocs.length;
	}

	@Test
	public void externalIdIsAnExactKeyword_lookupSurvivesTokenizerSplittingChars(@TempDir Path dir) throws Exception {
		LuceneDocumentWriter w = new LuceneDocumentWriter(config(dir, false));
		w.writeDocuments(doc(text("externalId", "shard_00000_0"), text("fulltext", "cat dog")));
		w.writeDocuments(doc(text("externalId", "wsj-90-01-01"), text("fulltext", "moon tree")));
		w.writeDocuments(doc(text("externalId", "doc.42"), text("fulltext", "sun star")));
		w.closeDocumentWriter();

		try (Directory d = FSDirectory.open(Paths.get(dir.toString(), "idx"));
				DirectoryReader reader = DirectoryReader.open(d)) {
			IndexSearcher s = searcherOf(reader);

			// Each whole docno is exactly one term -> exactly one hit, and it is the right document.
			ScoreDoc[] hits = s.search(new TermQuery(new Term("externalId", "wsj-90-01-01")), 10).scoreDocs;
			assertEquals(1, hits.length, "exact docno must match exactly one doc");
			Document hit = reader.document(hits[0].doc);
			assertEquals("wsj-90-01-01", hit.get("externalId"), "stored docno round-trips");
			assertEquals("moon tree", hit.get("fulltext"), "the right document is returned");

			assertEquals(1, hitCount(s, "externalId", "shard_00000_0"));
			assertEquals(1, hitCount(s, "externalId", "doc.42"));

			// NOT tokenized: the analyzer's would-be sub-tokens are not index terms of externalId.
			assertEquals(0, hitCount(s, "externalId", "wsj"), "externalId must not be split into 'wsj'");
			assertEquals(0, hitCount(s, "externalId", "90"), "externalId must not be split into '90'");
			assertEquals(0, hitCount(s, "externalId", "shard"), "externalId must not be split into 'shard'");
			assertEquals(0, hitCount(s, "externalId", "doc"), "externalId must not be split into 'doc'");

			// Unknown docno matches nothing.
			assertEquals(0, hitCount(s, "externalId", "no-such-docno"));

			// Sanity: fulltext is still the tokenized, searchable field (only externalId changed).
			assertEquals(1, hitCount(s, "fulltext", "moon"), "fulltext stays tokenized/searchable");
		}
	}

	/** The keyword externalId must not receive an exactDocumentLength {@code _len} DocValues. (TASK-0020) */
	@Test
	public void externalIdKeyword_getsNoLenDocValues_whenExactLengthOn(@TempDir Path dir) throws Exception {
		LuceneDocumentWriter w = new LuceneDocumentWriter(config(dir, true));
		w.writeDocuments(doc(text("externalId", "wsj-90-01-01"), text("fulltext", "cat dog sun")));
		w.closeDocumentWriter();

		try (Directory d = FSDirectory.open(Paths.get(dir.toString(), "idx"));
				DirectoryReader reader = DirectoryReader.open(d)) {
			LeafReaderContext leaf = reader.leaves().get(0);
			assertNull(leaf.reader().getNumericDocValues("externalId_len"),
					"the keyword externalId must not get a _len DocValues");
			NumericDocValues fulltextLen = leaf.reader().getNumericDocValues("fulltext_len");
			assertTrue(fulltextLen != null && fulltextLen.advanceExact(0) && fulltextLen.longValue() == 3L,
					"fulltext still gets its exact length");
		}
	}
}
