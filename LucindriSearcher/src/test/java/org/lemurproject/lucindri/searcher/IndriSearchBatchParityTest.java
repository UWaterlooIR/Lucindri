package org.lemurproject.lucindri.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.lemurproject.lucindri.searcher.testutil.TestIndex;

/**
 * End-to-end parity check for the TASK-0019 core extraction: {@link IndriSearch#main} (now a thin driver
 * over {@code LucindriSearchService}) still produces the standard TREC line
 * {@code <number> Q0 <externalId> <rank> <score> lucene}, in rank order, over a real on-disk index.
 */
public class IndriSearchBatchParityTest {

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

	@Test
	public void batchMainProducesTrecOutput(@TempDir Path dir) throws Exception {
		Path index = dir.resolve("index");
		buildIndex(index,
				new String[] { "d1", "the cat sat on the mat. a dog ran in the park." },
				new String[] { "d2", "the fish swam in the lake." },
				new String[] { "d3", "cat and dog played. the cat chased the dog again." });

		Path queries = dir.resolve("queries.xml");
		Files.writeString(queries, "<parameters>\n"
				+ "  <index>" + index + "</index>\n"
				+ "  <trecFormat>true</trecFormat>\n"
				+ "  <rule>dirichlet:2000</rule>\n"
				+ "  <count>10</count>\n"
				+ "  <query><number>1</number><text>#combine(\"cat\")</text></query>\n"
				+ "</parameters>\n", StandardCharsets.UTF_8);

		String out = runMainCapturingStdout(queries.toString());

		List<String> lines = new ArrayList<>();
		for (String line : out.split("\\R")) {
			if (!line.isBlank()) {
				lines.add(line.trim());
			}
		}
		assertFalse(lines.isEmpty(), "expected TREC output lines");

		int rank = 0;
		float prevScore = Float.POSITIVE_INFINITY;
		for (String line : lines) {
			rank++;
			String[] f = line.split(" ");
			assertEquals(6, f.length, "TREC line has 6 fields: " + line);
			assertEquals("1", f[0], "topic number");
			assertEquals("Q0", f[1]);
			assertTrue(f[2].equals("d1") || f[2].equals("d3"), "docno is a cat doc, not d2: " + f[2]);
			assertEquals(String.valueOf(rank), f[3], "rank column increments from 1");
			float score = Float.parseFloat(f[4]);
			assertTrue(score <= prevScore, "scores non-increasing");
			prevScore = score;
			assertEquals("lucene", f[5]);
		}
	}

	private static String runMainCapturingStdout(String argsFile) throws Exception {
		PrintStream original = System.out;
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		try (PrintStream capture = new PrintStream(buf, true, StandardCharsets.UTF_8.name())) {
			System.setOut(capture);
			IndriSearch.main(new String[] { argsFile });
		} finally {
			System.setOut(original);
		}
		return buf.toString(StandardCharsets.UTF_8.name());
	}
}
