package org.lemurproject.lucindri.indexer.documentparser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.indexer.domain.IndexingConfiguration;
import org.lemurproject.lucindri.indexer.domain.ParsedDocument;
import org.lemurproject.lucindri.indexer.domain.ParsedDocumentField;

/**
 * Regression tests for TASK-0020's docno-name fix: parsers that used to emit the docno under the base
 * constant's old value {@code "id"} (which the searcher never reads → blank TREC DOCNO) must now emit it
 * under {@code "externalId"}. These two formats have synthesizable input; the other previously-broken
 * formats (ClueWeb*, WARC, gov2, CAR, WaPo, MARCO) can't be exercised without their corpora and rely on the
 * single base constant `DocumentParser.EXTERNALID_FIELD`.
 */
public class DocnoFieldNameTest {

	private static String field(ParsedDocument doc, String name) {
		for (ParsedDocumentField f : doc.getDocumentFields()) {
			if (f.getFieldName().equals(name)) {
				return f.getContent();
			}
		}
		return null;
	}

	@Test
	public void textParser_emitsDocnoUnderExternalId(@TempDir Path dir) throws Exception {
		// TextDocumentParser uses the file name as the docno; it used to inherit the base "id".
		Files.write(dir.resolve("wsj-90-01-01"), "apple banana cat".getBytes(StandardCharsets.UTF_8));

		IndexingConfiguration cfg = new IndexingConfiguration();
		cfg.setDataDirectory(dir.toString());
		cfg.setIndexFullText(true);

		TextDocumentParser p = new TextDocumentParser(cfg);
		List<ParsedDocument> docs = parseAll(p);

		assertEquals(1, docs.size());
		assertEquals("wsj-90-01-01", field(docs.get(0), "externalId"),
				"TextDocumentParser must emit the docno under externalId, not id");
	}

	@Test
	public void jsonParser_emitsDocnoUnderExternalId(@TempDir Path dir) throws Exception {
		// JsonDocumentParser reads {docno,text,fields}; it used to inherit the base "id".
		Files.write(dir.resolve("docs.jsonl"),
				"{\"docno\":\"j-1\",\"text\":\"apple banana cat\",\"fields\":[]}\n".getBytes(StandardCharsets.UTF_8));

		IndexingConfiguration cfg = new IndexingConfiguration();
		cfg.setDataDirectory(dir.toString());
		cfg.setIndexFullText(true);
		cfg.setStemmer("kstem");
		cfg.setRemoveStopwords(true);
		cfg.setIgnoreCase(true);

		JsonDocumentParser p = new JsonDocumentParser(cfg);
		List<ParsedDocument> docs = parseAll(p);

		assertEquals(1, docs.size());
		String externalId = field(docs.get(0), "externalId");
		assertNotNull(externalId, "JsonDocumentParser must emit an externalId field (not id)");
		assertEquals("j-1", externalId);
	}

	private static List<ParsedDocument> parseAll(DocumentParser p) throws Exception {
		List<ParsedDocument> docs = new ArrayList<>();
		while (p.hasNextDocument()) {
			ParsedDocument d = p.getNextDocument();
			if (d != null) {
				docs.add(d);
			}
		}
		return docs;
	}
}
