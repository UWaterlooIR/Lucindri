package org.lemurproject.lucindri.indexer.documentparser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.indexer.domain.IndexingConfiguration;
import org.lemurproject.lucindri.indexer.domain.ParsedDocument;
import org.lemurproject.lucindri.indexer.domain.ParsedDocumentField;

/**
 * Tests for {@link TrecTextDocumentParser} (TASK-0007): standard-TREC DOCNO extraction, markup-free
 * body, null-safe {@code fieldNames}, and configurable {@code contentTags}.
 */
public class TrecTextDocumentParserTest {

	private static void writeTrec(Path dir, String content) throws IOException {
		try (Writer w = new OutputStreamWriter(Files.newOutputStream(dir.resolve("corpus.trec")),
				StandardCharsets.UTF_8)) {
			w.write(content);
		}
	}

	private static IndexingConfiguration config(Path dir, List<String> contentTags) {
		IndexingConfiguration c = new IndexingConfiguration();
		c.setDataDirectory(dir.toString());
		c.setIndexFullText(true);
		// fieldNames intentionally left null (empty) — must not NPE.
		if (contentTags != null) {
			c.setContentTags(contentTags);
		}
		return c;
	}

	private static List<ParsedDocument> parseAll(IndexingConfiguration cfg) throws Exception {
		TrecTextDocumentParser p = new TrecTextDocumentParser(cfg);
		List<ParsedDocument> docs = new ArrayList<>();
		while (p.hasNextDocument()) {
			ParsedDocument d = p.getNextDocument();
			if (d != null) {
				docs.add(d);
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

	private static final String ONE_DOC = "<DOC>\n<DOCNO>d-42</DOCNO>\n<TEXT>apple banana cat</TEXT>\n</DOC>\n";

	// Standard TREC: <DOCNO> inside <DOC> is extracted as externalId (was: "1").
	@Test
	public void extractsStandardDocno(@TempDir Path dir) throws Exception {
		writeTrec(dir, ONE_DOC);
		List<ParsedDocument> docs = parseAll(config(dir, null));
		assertEquals(1, docs.size());
		assertEquals("d-42", field(docs.get(0), "externalId"));
	}

	// fulltext is the markup-free TEXT content (no doc/docno/text tag tokens).
	@Test
	public void fulltextIsMarkupFree(@TempDir Path dir) throws Exception {
		writeTrec(dir, ONE_DOC);
		String fulltext = field(parseAll(config(dir, null)).get(0), "fulltext");
		assertEquals("apple banana cat", fulltext);
		for (String markup : new String[] { "doc", "docno", "text" }) {
			assertFalse(Arrays.asList(fulltext.split("\\s+")).contains(markup), "markup token leaked: " + markup);
		}
	}

	// Empty fieldNames must not NPE (previously threw -> 0 docs).
	@Test
	public void emptyFieldNamesDoesNotNpe(@TempDir Path dir) throws Exception {
		writeTrec(dir, ONE_DOC);
		assertEquals(1, parseAll(config(dir, null)).size());
	}

	// DOCNO with surrounding whitespace is trimmed.
	@Test
	public void docnoWhitespaceTolerant(@TempDir Path dir) throws Exception {
		writeTrec(dir, "<DOC>\n<DOCNO>  d-42  </DOCNO>\n<TEXT>apple</TEXT>\n</DOC>\n");
		assertEquals("d-42", field(parseAll(config(dir, null)).get(0), "externalId"));
	}

	// Multiple documents parse with correct, distinct ids.
	@Test
	public void multipleDocsDistinctIds(@TempDir Path dir) throws Exception {
		writeTrec(dir, "<DOC>\n<DOCNO>a1</DOCNO>\n<TEXT>apple</TEXT>\n</DOC>\n"
				+ "<DOC>\n<DOCNO>a2</DOCNO>\n<TEXT>banana</TEXT>\n</DOC>\n");
		List<ParsedDocument> docs = parseAll(config(dir, null));
		assertEquals(2, docs.size());
		assertEquals("a1", field(docs.get(0), "externalId"));
		assertEquals("a2", field(docs.get(1), "externalId"));
		assertEquals("apple", field(docs.get(0), "fulltext"));
		assertEquals("banana", field(docs.get(1), "fulltext"));
	}

	// Default contentTags = text only: HEADLINE is NOT in fulltext.
	@Test
	public void defaultContentTagsIsTextOnly(@TempDir Path dir) throws Exception {
		writeTrec(dir, "<DOC>\n<DOCNO>h1</DOCNO>\n<HEADLINE>big news</HEADLINE>\n<TEXT>apple</TEXT>\n</DOC>\n");
		String fulltext = field(parseAll(config(dir, null)).get(0), "fulltext");
		assertEquals("apple", fulltext);
	}

	// Configured contentTags add extra tags to fulltext, in document order (headline before text).
	@Test
	public void configurableContentTags(@TempDir Path dir) throws Exception {
		writeTrec(dir, "<DOC>\n<DOCNO>h1</DOCNO>\n<HEADLINE>big news</HEADLINE>\n<TEXT>apple</TEXT>\n</DOC>\n");
		String fulltext = field(parseAll(config(dir, Arrays.asList("text", "headline"))).get(0), "fulltext");
		assertTrue(fulltext.contains("big news"), () -> "headline missing: " + fulltext);
		assertTrue(fulltext.contains("apple"), () -> "text missing: " + fulltext);
		assertEquals("big news apple", fulltext); // document order: headline precedes text
	}
}
