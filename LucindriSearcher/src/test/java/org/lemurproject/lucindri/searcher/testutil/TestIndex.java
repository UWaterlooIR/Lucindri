package org.lemurproject.lucindri.searcher.testutil;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.LMDirichletSimilarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.lemurproject.lucindri.analyzer.EnglishAnalyzerConfigurable;
import org.lemurproject.lucindri.searcher.IndriIndexSearcher;
import org.lemurproject.lucindri.searcher.parser.IndriQueryParser;
import org.lemurproject.lucindri.searcher.similarities.IndriDirichletSimilarity;

/**
 * Shared test fixture for LucindriSearcher tests. Builds a controlled Lucene index (or several
 * sub-indexes wrapped in a {@link MultiReader}, mirroring the production multi-part layout) that
 * faithfully replicates Lucindri's index-time configuration, and runs Indri queries through the
 * real {@link IndriQueryParser} + {@link IndriIndexSearcher}.
 *
 * <p>Faithful to production (see {@code LuceneDocumentWriter}): index-time similarity is
 * {@link LMDirichletSimilarity} (so norms encode the same way), every field is
 * stored + tokenized + {@code DOCS_AND_FREQS_AND_POSITIONS}, and the id/body live under
 * {@code externalId} / {@code fulltext}.
 *
 * <p><b>Analyzer.</b> The default is the same analyzer the query parser hardcodes
 * (KStem + stopwords + lowercase) via {@link #indriAnalyzer()}, so indexed and query terms agree —
 * use it whenever a test runs queries. Choose test tokens that are lowercase, non-stopword, and
 * stem-invariant (e.g. {@code cat dog sun moon tree}) so token counts and terms are exactly known.
 * Override with a {@code WhitespaceAnalyzer} only for stats-only tests that do not run queries.
 *
 * <p>Usage:
 * <pre>
 *   try (TestIndex ix = TestIndex.builder()
 *           .add("d1", "cat dog")
 *           .add("d2", "dog sun")
 *           .build(tempDir)) {
 *       assertEquals(List.of("d1"), ix.ids("#1(cat dog)", 10));
 *   }
 * </pre>
 */
public final class TestIndex implements Closeable {

	public static final String EXTERNAL_ID = "externalId";
	public static final String FULLTEXT = "fulltext";

	/** One search result: the stored external id and its score. */
	public static final class Hit {
		public final String externalId;
		public final float score;

		Hit(String externalId, float score) {
			this.externalId = externalId;
			this.score = score;
		}

		@Override
		public String toString() {
			return externalId + ":" + score;
		}
	}

	private final IndexReader reader;
	private final IndriIndexSearcher searcher;
	private final List<Directory> directories;

	private TestIndex(IndexReader reader, IndriIndexSearcher searcher, List<Directory> directories) {
		this.reader = reader;
		this.searcher = searcher;
		this.directories = directories;
	}

	/** The analyzer the query parser uses (KStem + stopwords + lowercase). Default for indexing. */
	public static Analyzer indriAnalyzer() {
		EnglishAnalyzerConfigurable an = new EnglishAnalyzerConfigurable();
		an.setLowercase(true);
		an.setStopwordRemoval(true);
		an.setStemmer(EnglishAnalyzerConfigurable.StemmerType.KSTEM);
		return an;
	}

	public IndriIndexSearcher searcher() {
		return searcher;
	}

	public IndexReader reader() {
		return reader;
	}

	/** Parse and run an Indri query string; return hits (external id + score) up to {@code count}. */
	public List<Hit> run(String queryText, int count) throws IOException {
		List<Hit> hits = new ArrayList<>();
		Query query = new IndriQueryParser().parseQuery(queryText);
		if (query == null) {
			return hits;
		}
		TopDocs topDocs = searcher.search(query, count);
		for (ScoreDoc sd : topDocs.scoreDocs) {
			Document doc = searcher.doc(sd.doc);
			hits.add(new Hit(doc.get(EXTERNAL_ID), sd.score));
		}
		return hits;
	}

	/** Convenience: the external ids of a query's hits, in rank order. */
	public List<String> ids(String queryText, int count) throws IOException {
		List<String> ids = new ArrayList<>();
		for (Hit h : run(queryText, count)) {
			ids.add(h.externalId);
		}
		return ids;
	}

	@Override
	public void close() throws IOException {
		reader.close();
		for (Directory d : directories) {
			d.close();
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Builds a {@link TestIndex}. Call {@link #newPart()} to split docs into separate sub-indexes. */
	public static final class Builder {
		private Analyzer analyzer = indriAnalyzer();
		private Similarity querySimilarity = new IndriDirichletSimilarity();
		private boolean exactDocumentLength = false;
		private final List<List<String[]>> parts = new ArrayList<>();
		private List<String[]> current = new ArrayList<>();

		/** Index-time analyzer (default {@link TestIndex#indriAnalyzer()}). */
		public Builder analyzer(Analyzer analyzer) {
			this.analyzer = analyzer;
			return this;
		}

		/**
		 * When true, also write each text field's exact token count as a {@code <field>_len}
		 * NumericDocValues, mirroring {@code LuceneDocumentWriter} with {@code exactDocumentLength=true}
		 * (TASK-0012). The norm is still written (index-time similarity is unchanged), so the exact and norm
		 * paths differ only by which length the scorer reads.
		 */
		public Builder exactDocumentLength(boolean exactDocumentLength) {
			this.exactDocumentLength = exactDocumentLength;
			return this;
		}

		/** Query-time similarity (default {@link IndriDirichletSimilarity} with mu=2000). */
		public Builder querySimilarity(Similarity similarity) {
			this.querySimilarity = similarity;
			return this;
		}

		/** Add a document ({@code externalId}, {@code fulltext}) to the current sub-index. */
		public Builder add(String externalId, String fulltext) {
			current.add(new String[] { externalId, fulltext });
			return this;
		}

		/** Start a new sub-index (part), mirroring the production multi-part MultiReader layout. */
		public Builder newPart() {
			if (!current.isEmpty()) {
				parts.add(current);
				current = new ArrayList<>();
			}
			return this;
		}

		public TestIndex build(Path baseDir) throws IOException {
			if (!current.isEmpty()) {
				parts.add(current);
				current = new ArrayList<>();
			}
			if (parts.isEmpty()) {
				throw new IllegalStateException("TestIndex: no documents added");
			}

			FieldType fieldType = fieldType();
			List<Directory> dirs = new ArrayList<>();
			int p = 0;
			for (List<String[]> partDocs : parts) {
				Directory directory = FSDirectory.open(baseDir.resolve("part" + (p++)));
				IndexWriterConfig config = new IndexWriterConfig(analyzer);
				config.setOpenMode(OpenMode.CREATE);
				config.setSimilarity(new LMDirichletSimilarity()); // index-time: matches LuceneDocumentWriter
				config.setUseCompoundFile(false);
				try (IndexWriter writer = new IndexWriter(directory, config)) {
					for (String[] doc : partDocs) {
						Document d = new Document();
						// Mirror LuceneDocumentWriter (TASK-0020): externalId is a non-analyzed keyword
						// StringField (stored, exact-lookupable), NOT tokenized text, and gets no _len.
						d.add(new StringField(EXTERNAL_ID, doc[0], Field.Store.YES));
						d.add(new Field(FULLTEXT, doc[1], fieldType));
						if (exactDocumentLength) {
							// Mirror LuceneDocumentWriter: count posIncr>0 tokens (= numTerms, no +1).
							d.add(new NumericDocValuesField(FULLTEXT + "_len", tokenCount(analyzer, FULLTEXT, doc[1])));
						}
						writer.addDocument(d);
					}
				}
				dirs.add(directory);
			}

			IndexReader reader;
			if (dirs.size() == 1) {
				reader = DirectoryReader.open(dirs.get(0));
			} else {
				IndexReader[] subs = new IndexReader[dirs.size()];
				for (int i = 0; i < dirs.size(); i++) {
					subs[i] = DirectoryReader.open(dirs.get(i));
				}
				reader = new MultiReader(subs, true);
			}
			IndriIndexSearcher searcher = new IndriIndexSearcher(reader);
			searcher.setSimilarity(querySimilarity);
			return new TestIndex(reader, searcher, dirs);
		}

		/**
		 * Counts the tokens the analyzer emits with position increment &gt; 0 — the same document length
		 * {@code LuceneDocumentWriter.countTokens} computes and Lucene encodes in the norm
		 * ({@code numTerms}, no {@code +1}). Kept in sync with the indexer (this module cannot depend on it).
		 */
		private static long tokenCount(Analyzer analyzer, String field, String content) throws IOException {
			long count = 0;
			try (TokenStream ts = analyzer.tokenStream(field, content)) {
				PositionIncrementAttribute posIncr = ts.addAttribute(PositionIncrementAttribute.class);
				ts.reset();
				while (ts.incrementToken()) {
					if (posIncr.getPositionIncrement() > 0) {
						count++;
					}
				}
				ts.end();
			}
			return count;
		}

		private static FieldType fieldType() {
			FieldType ft = new FieldType();
			ft.setTokenized(true);
			ft.setStored(true);
			ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
			ft.freeze();
			return ft;
		}
	}
}
