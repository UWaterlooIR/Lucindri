package org.lemurproject.lucindri.indexer.documentparser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.indexer.domain.IndexingConfiguration;
import org.lemurproject.lucindri.indexer.domain.ParsedDocument;
import org.lemurproject.lucindri.indexer.domain.ParsedDocumentField;

/**
 * Unit tests for {@link ClimbmixJsonlDocumentParser}. Each test writes a small
 * gzipped JSON-Lines shard into a temp directory, points the parser at that
 * directory, and asserts on the emitted {@link ParsedDocument}s. Assertions are
 * exact on the field NAMES ("externalId", "fulltext") because that name contract
 * is the whole point of this parser.
 */
public class ClimbmixJsonlDocumentParserTest {

	private static final String EXTERNAL_ID = "externalId";
	private static final String FULLTEXT = "fulltext";
	private static final String INTERNAL_ID = "internalId";

	/** Writes the given lines to a gzipped file (one line each, newline-terminated). */
	private static void writeGzShard(Path file, String... lines) throws IOException {
		try (OutputStream os = Files.newOutputStream(file);
				Writer w = new OutputStreamWriter(new GZIPOutputStream(os), StandardCharsets.UTF_8)) {
			for (String line : lines) {
				w.write(line);
				w.write("\n");
			}
		}
	}

	/** Writes plain (uncompressed) lines to a file. */
	private static void writePlainShard(Path file, String... lines) throws IOException {
		try (Writer w = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
			for (String line : lines) {
				w.write(line);
				w.write("\n");
			}
		}
	}

	private static IndexingConfiguration configFor(Path dataDir) {
		IndexingConfiguration cfg = new IndexingConfiguration();
		cfg.setDataDirectory(dataDir.toString());
		return cfg;
	}

	/** Drives the parser exactly like IndexServiceImpl does and collects every document. */
	private static List<ParsedDocument> parseAll(IndexingConfiguration cfg) throws IOException {
		ClimbmixJsonlDocumentParser parser = new ClimbmixJsonlDocumentParser(cfg);
		List<ParsedDocument> docs = new ArrayList<>();
		while (parser.hasNextDocument()) {
			ParsedDocument doc = parser.getNextDocument();
			if (doc != null) {
				docs.add(doc);
			}
		}
		return docs;
	}

	private static String field(ParsedDocument doc, String name) {
		for (ParsedDocumentField f : doc.getDocumentFields()) {
			if (f.getFieldName().equals(name)) {
				return f.getContent();
			}
		}
		return null;
	}

	// 1. Happy path: docid -> externalId, contents -> fulltext.
	@Test
	public void mapsDocidToExternalIdAndContentsToFulltext(@TempDir Path dir) throws IOException {
		writeGzShard(dir.resolve("shard_00001.jsonl.gz"),
				"{\"docid\":\"shard_00001_0\",\"contents\":\"the quick brown fox\"}");

		List<ParsedDocument> docs = parseAll(configFor(dir));

		assertEquals(1, docs.size());
		assertEquals("shard_00001_0", field(docs.get(0), EXTERNAL_ID));
		assertEquals("the quick brown fox", field(docs.get(0), FULLTEXT));
		assertEquals("1", field(docs.get(0), INTERNAL_ID));
	}

	// 2. Gzip is read directly (the shard above is gzipped). A plain .jsonl file must also work.
	@Test
	public void readsPlainJsonlFileToo(@TempDir Path dir) throws IOException {
		writePlainShard(dir.resolve("shard_plain.jsonl"),
				"{\"docid\":\"p_0\",\"contents\":\"plain text body\"}");

		List<ParsedDocument> docs = parseAll(configFor(dir));

		assertEquals(1, docs.size());
		assertEquals("p_0", field(docs.get(0), EXTERNAL_ID));
		assertEquals("plain text body", field(docs.get(0), FULLTEXT));
	}

	// 3. Extra keys (id, source_file, row_number, mode) are tolerated and ignored.
	@Test
	public void ignoresExtraKeys(@TempDir Path dir) throws IOException {
		writeGzShard(dir.resolve("shard_00001.jsonl.gz"),
				"{\"id\":\"shard_00001_0\",\"contents\":\"body here\",\"docid\":\"shard_00001_0\","
						+ "\"source_file\":\"/mnt/e/x.parquet\",\"row_number\":\"0\","
						+ "\"mode\":\"generated_segment_row\"}");

		List<ParsedDocument> docs = parseAll(configFor(dir));

		assertEquals(1, docs.size());
		ParsedDocument doc = docs.get(0);
		assertEquals("shard_00001_0", field(doc, EXTERNAL_ID));
		assertEquals("body here", field(doc, FULLTEXT));
		// The ignored keys must not have leaked in as fields.
		assertNull(field(doc, "source_file"));
		assertNull(field(doc, "row_number"));
		assertNull(field(doc, "mode"));
		assertNull(field(doc, "id"));
	}

	// 4. Records missing "contents" are skipped, not emitted, and do not throw.
	@Test
	public void skipsRecordsMissingContents(@TempDir Path dir) throws IOException {
		writeGzShard(dir.resolve("shard_00001.jsonl.gz"),
				"{\"docid\":\"a\",\"contents\":\"first\"}",
				"{\"docid\":\"b\"}", // no contents -> skipped
				"{\"docid\":\"c\",\"contents\":\"third\"}");

		List<ParsedDocument> docs = parseAll(configFor(dir));

		assertEquals(2, docs.size());
		assertEquals("a", field(docs.get(0), EXTERNAL_ID));
		assertEquals("c", field(docs.get(1), EXTERNAL_ID));
		// internalId is a running counter over emitted docs only.
		assertEquals("1", field(docs.get(0), INTERNAL_ID));
		assertEquals("2", field(docs.get(1), INTERNAL_ID));
	}

	// 5. Blank lines between records produce no documents.
	@Test
	public void skipsBlankLines(@TempDir Path dir) throws IOException {
		writeGzShard(dir.resolve("shard_00001.jsonl.gz"),
				"{\"docid\":\"a\",\"contents\":\"first\"}",
				"",
				"   ".trim(), // an empty line
				"{\"docid\":\"b\",\"contents\":\"second\"}");

		List<ParsedDocument> docs = parseAll(configFor(dir));

		assertEquals(2, docs.size());
		assertEquals("a", field(docs.get(0), EXTERNAL_ID));
		assertEquals("b", field(docs.get(1), EXTERNAL_ID));
	}

	// 6. Two shard files in the directory are both consumed, in sorted order.
	@Test
	public void consumesMultipleFilesInOrder(@TempDir Path dir) throws IOException {
		writeGzShard(dir.resolve("shard_00001.jsonl.gz"),
				"{\"docid\":\"s1_a\",\"contents\":\"one\"}",
				"{\"docid\":\"s1_b\",\"contents\":\"two\"}");
		writeGzShard(dir.resolve("shard_00002.jsonl.gz"),
				"{\"docid\":\"s2_a\",\"contents\":\"three\"}");

		List<ParsedDocument> docs = parseAll(configFor(dir));

		assertEquals(3, docs.size());
		assertEquals("s1_a", field(docs.get(0), EXTERNAL_ID));
		assertEquals("s1_b", field(docs.get(1), EXTERNAL_ID));
		assertEquals("s2_a", field(docs.get(2), EXTERNAL_ID));
	}

	// Bonus: a record with contents but no docid falls back to the internal counter for externalId,
	// so the TREC DOCNO is never blank.
	@Test
	public void fallsBackToCounterWhenDocidMissing(@TempDir Path dir) throws IOException {
		writeGzShard(dir.resolve("shard_00001.jsonl.gz"),
				"{\"contents\":\"no id here\"}");

		List<ParsedDocument> docs = parseAll(configFor(dir));

		assertEquals(1, docs.size());
		String externalId = field(docs.get(0), EXTERNAL_ID);
		assertTrue(externalId != null && !externalId.isEmpty(), "externalId must never be blank");
		assertEquals("1", externalId);
	}

	// Sanity: a single file path (not a directory) is also accepted.
	@Test
	public void acceptsSingleFilePath(@TempDir Path dir) throws IOException {
		Path shard = dir.resolve("only.jsonl.gz");
		writeGzShard(shard, "{\"docid\":\"solo\",\"contents\":\"lonely body\"}");

		IndexingConfiguration cfg = new IndexingConfiguration();
		cfg.setDataDirectory(shard.toString());

		List<ParsedDocument> docs = parseAll(cfg);

		assertEquals(1, docs.size());
		assertEquals("solo", field(docs.get(0), EXTERNAL_ID));
	}

	// Confirm the gzip round-trip helper actually produced gzip (guards the test itself).
	@Test
	public void helperWritesRealGzip(@TempDir Path dir) throws IOException {
		Path shard = dir.resolve("check.jsonl.gz");
		writeGzShard(shard, "{\"docid\":\"x\",\"contents\":\"y\"}");
		try (GZIPInputStream gz = new GZIPInputStream(Files.newInputStream(shard))) {
			assertTrue(gz.read() != -1);
		}
	}

}
