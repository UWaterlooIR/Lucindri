package org.lemurproject.lucindri.indexer.documentwriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.indexer.domain.IndexingConfiguration;
import org.lemurproject.lucindri.indexer.domain.ParsedDocument;
import org.lemurproject.lucindri.indexer.domain.ParsedDocumentField;

/**
 * Tests the index-time half of exact document length (TASK-0012): when {@code exactDocumentLength=true},
 * {@link LuceneDocumentWriter} writes a per-doc {@code <field>_len} NumericDocValues equal to the analyzer
 * token count ({@code numTerms}, no {@code +1}); when off, it writes none (bit-for-bit unchanged).
 *
 * <p>Uses the production kstem + stopword + lowercase analyzer, so choose stem-invariant non-stopword
 * tokens ({@code cat dog sun moon tree}) for exactly known counts.
 */
public class LuceneDocumentWriterExactLengthTest {

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

	private static ParsedDocument doc(ParsedDocumentField... fields) {
		ParsedDocument d = new ParsedDocument();
		d.setDocumentFields(new ArrayList<>(List.of(fields)));
		return d;
	}

	private static ParsedDocumentField text(String name, String content) {
		return new ParsedDocumentField(name, content, false);
	}

	/** Reads the single-segment {@code fieldName} DocValues into an array indexed by docId, or null. */
	private static long[] readLengths(Path dir, String indexName, String fieldName, int numDocs) throws Exception {
		try (Directory d = FSDirectory.open(Paths.get(dir.toString(), indexName));
				DirectoryReader reader = DirectoryReader.open(d)) {
			assertEquals(1, reader.leaves().size(), "test expects a single segment");
			LeafReaderContext leaf = reader.leaves().get(0);
			NumericDocValues dv = leaf.reader().getNumericDocValues(fieldName);
			if (dv == null) {
				return null;
			}
			long[] out = new long[numDocs];
			for (int i = 0; i < numDocs; i++) {
				assertTrue(dv.advanceExact(i), "expected a value for doc " + i);
				out[i] = dv.longValue();
			}
			return out;
		}
	}

	@Test
	public void writesExactTokenCount_stopwordsAndNumericExcluded(@TempDir Path dir) throws Exception {
		LuceneDocumentWriter w = new LuceneDocumentWriter(config(dir, true));
		// doc0: 3 content tokens
		w.writeDocuments(doc(text("externalId", "d0"), text("fulltext", "cat dog sun")));
		// doc1: a stopword ("the") is removed => length 2, not 3
		w.writeDocuments(doc(text("externalId", "d1"), text("fulltext", "cat the dog")));
		// doc2: a numeric field must not get a _len; its text fulltext still does
		ParsedDocumentField num = new ParsedDocumentField("year", "2026", true);
		w.writeDocuments(doc(text("externalId", "d2"), text("fulltext", "moon tree"), num));
		w.closeDocumentWriter();

		long[] fulltextLen = readLengths(dir, "idx", "fulltext_len", 3);
		assertEquals(3L, fulltextLen[0]);
		assertEquals(2L, fulltextLen[1], "removed stopword must not count toward |d|");
		assertEquals(2L, fulltextLen[2]);

		// The numeric field carries its own NumericDocValues (its value), but no "<field>_len".
		assertNull(readLengths(dir, "idx", "year_len", 3), "numeric fields must not get a _len");
	}

	@Test
	public void multiValuedField_lengthIsSummedAcrossValues(@TempDir Path dir) throws Exception {
		LuceneDocumentWriter w = new LuceneDocumentWriter(config(dir, true));
		// Same field name appears twice (multi-valued): 2 + 3 = 5, written as ONE _len value.
		w.writeDocuments(doc(text("externalId", "d0"), text("fulltext", "cat dog"), text("fulltext", "sun moon tree")));
		w.closeDocumentWriter();

		long[] fulltextLen = readLengths(dir, "idx", "fulltext_len", 1);
		assertEquals(5L, fulltextLen[0]);
	}

	@Test
	public void flagOff_writesNoLengthDocValues(@TempDir Path dir) throws Exception {
		LuceneDocumentWriter w = new LuceneDocumentWriter(config(dir, false));
		w.writeDocuments(doc(text("externalId", "d0"), text("fulltext", "cat dog sun")));
		w.closeDocumentWriter();

		assertNull(readLengths(dir, "idx", "fulltext_len", 1),
				"exactDocumentLength=false must not write any _len DocValues");
	}
}
