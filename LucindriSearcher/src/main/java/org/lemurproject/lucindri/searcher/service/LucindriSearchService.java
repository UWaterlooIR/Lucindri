package org.lemurproject.lucindri.searcher.service;

import java.io.Closeable;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.FSDirectory;
import org.lemurproject.lucindri.analyzer.EnglishAnalyzerConfigurable;
import org.lemurproject.lucindri.searcher.IndriIndexSearcher;
import org.lemurproject.lucindri.searcher.LucindriGetDoc;
import org.lemurproject.lucindri.searcher.parser.IndriQueryParser;
import org.lemurproject.lucindri.searcher.similarities.IndriDirichletSimilarity;
import org.lemurproject.lucindri.searcher.similarities.IndriJelinekMercerSimilarity;

/**
 * The reusable Lucindri search core (TASK-0019): opens an index once and stays warm, so both the batch CLI
 * ({@link org.lemurproject.lucindri.searcher.IndriSearch}) and the HTTP server drive the <em>same</em>
 * retrieval path. It is constructed from the index/analysis config (the startup half of a query
 * parameters file) and exposes two operations — {@link #search} and {@link #document}.
 *
 * <p>Thread-safe for concurrent use: a Lucene {@code IndexReader}/{@code IndexSearcher} and {@code Analyzer}
 * are safe to share, and {@link IndriQueryParser} is stateless (TASK-0019). A single instance serves all
 * requests; no per-request reopen.
 */
public class LucindriSearchService implements Closeable {

	static final String EXTERNALID_FIELD = "externalId";
	static final String FULLTEXT_FIELD = "fulltext";

	private final IndexReader reader;
	private final IndriIndexSearcher searcher;
	private final IndriQueryParser parser;
	private final Analyzer analyzer;
	private final int maxPassages;

	/** One ranked result. Public fields so the HTTP layer can serialize it directly. */
	public static final class SearchResult {
		public final String docno;
		public final float score;
		/** Query-biased summary; {@code null} when summaries were not requested. */
		public final String summary;

		public SearchResult(String docno, float score, String summary) {
			this.docno = docno;
			this.score = score;
			this.summary = summary;
		}
	}

	/**
	 * @param index           one index dir, or a comma-separated list (opened as a {@link MultiReader})
	 * @param rule            smoothing rule (e.g. {@code dirichlet:2000}, {@code jm:0.4}); null =&gt; Dirichlet μ=2000
	 * @param stemmer         {@code kstem}/{@code krovetz}, {@code porter}, or {@code none} — must match the index
	 * @param removeStopwords must match the index
	 * @param ignoreCase      must match the index
	 * @param maxPassages     sentences per summary (also the leading-sentence fallback count)
	 */
	public LucindriSearchService(String index, String rule, String stemmer, boolean removeStopwords,
			boolean ignoreCase, int maxPassages) throws IOException {
		this.reader = openReader(index);
		this.searcher = new IndriIndexSearcher(reader);
		this.searcher.setSimilarity(similarityFromRule(rule));
		this.parser = new IndriQueryParser(FULLTEXT_FIELD, stemmer, removeStopwords, ignoreCase);
		this.analyzer = buildAnalyzer(stemmer, removeStopwords, ignoreCase);
		this.maxPassages = maxPassages;
	}

	/**
	 * Runs {@code queryText} and returns up to {@code count} ranked results (best first). A <b>syntax
	 * error</b> throws {@link org.lemurproject.lucindri.searcher.parser.QueryParseException}; a
	 * <b>degenerate</b> query that parses to nothing (null / all-stopword) returns an <b>empty list</b>
	 * (not an error). When {@code wantSummaries} is true, each result carries a query-biased summary (§4).
	 */
	public List<SearchResult> search(String queryText, int count, boolean wantSummaries) throws IOException {
		List<SearchResult> results = new ArrayList<>();
		Query query = parser.parseQuery(queryText); // QueryParseException propagates on a syntax error
		if (query == null) {
			return results; // degenerate query -> empty results (caller maps to 200 {"results":[]})
		}
		TopDocs topDocs = searcher.search(query, Math.max(1, count));
		ScoreDoc[] hits = topDocs.scoreDocs;
		String[] summaries = wantSummaries ? summarize(queryText, topDocs) : null;
		for (int i = 0; i < hits.length; i++) {
			Document doc = reader.document(hits[i].doc);
			String docno = doc.get(EXTERNALID_FIELD);
			String summary = summaries != null ? (summaries[i] == null ? "" : summaries[i]) : null;
			results.add(new SearchResult(docno, hits[i].score, summary));
		}
		return results;
	}

	/** Returns the stored {@code fulltext} of the document with docno {@code externalId}, or null if unknown. */
	public String document(String docno) throws IOException {
		return LucindriGetDoc.fetch(reader, docno);
	}

	// --- summaries (query-biased extractive, UnifiedHighlighter) ---------------------------------------

	private String[] summarize(String queryText, TopDocs topDocs) throws IOException {
		// Highlight the query's positive content terms (structure/negation stripped by the parser walk).
		BooleanQuery.Builder hb = new BooleanQuery.Builder();
		for (String term : parser.queryTerms(queryText)) {
			hb.add(new TermQuery(new Term(FULLTEXT_FIELD, term)), BooleanClause.Occur.SHOULD);
		}
		UnifiedHighlighter uh = new UnifiedHighlighter(searcher, analyzer);
		uh.setBreakIterator(() -> BreakIterator.getSentenceInstance(Locale.ENGLISH));
		uh.setFormatter(new DefaultPassageFormatter("", "", " ... ", false)); // plain text, ellipsis joiner
		// A hit that matches no query term (Indri background smoothing returns those) falls back to the
		// document's leading sentences instead of null, so every result gets a non-empty summary (§4).
		uh.setMaxNoHighlightPassages(maxPassages);
		uh.setMaxLength(1_000_000); // analyze deep enough to find matches in long docs
		return uh.highlight(FULLTEXT_FIELD, hb.build(), topDocs, maxPassages);
	}

	// --- construction helpers -------------------------------------------------------------------------

	/** Opens one index dir, or a comma-separated list as a {@link MultiReader} (as IndriSearch does). */
	private static IndexReader openReader(String index) throws IOException {
		if (index.contains(",")) {
			String[] dirs = index.split(",");
			IndexReader[] subReaders = new IndexReader[dirs.length];
			for (int i = 0; i < dirs.length; i++) {
				subReaders[i] = DirectoryReader.open(FSDirectory.open(java.nio.file.Paths.get(dirs[i].trim())));
			}
			return new MultiReader(subReaders, true);
		}
		return DirectoryReader.open(FSDirectory.open(java.nio.file.Paths.get(index.trim())));
	}

	/**
	 * Maps a smoothing {@code rule} string to a {@link Similarity} — the mapping formerly inlined in
	 * {@code IndriSearch.main}, lifted here so the batch CLI and the server share one implementation.
	 */
	public static Similarity similarityFromRule(String rule) {
		if (rule == null) {
			return new IndriDirichletSimilarity();
		}
		String[] parts = rule.toLowerCase().split(":");
		String name = parts[0];
		String param = parts.length > 1 ? parts[1] : null;
		if (name.equals("dirichlet") || name.equals("dir") || name.equals("d")) {
			return param != null ? new IndriDirichletSimilarity(Float.valueOf(param)) : new IndriDirichletSimilarity();
		} else if (name.equals("jelinek-mercer") || name.equals("jm") || name.equals("linear")) {
			return param != null ? new IndriJelinekMercerSimilarity(Float.valueOf(param))
					: new IndriJelinekMercerSimilarity();
		}
		return new IndriDirichletSimilarity();
	}

	/** Builds the highlighter's analyzer from the same config the parser uses (must match the index). */
	private static Analyzer buildAnalyzer(String stemmer, boolean removeStopwords, boolean ignoreCase) {
		EnglishAnalyzerConfigurable an = new EnglishAnalyzerConfigurable();
		an.setLowercase(ignoreCase);
		an.setStopwordRemoval(removeStopwords);
		EnglishAnalyzerConfigurable.StemmerType type = EnglishAnalyzerConfigurable.StemmerType.NONE;
		if (stemmer != null && (stemmer.equalsIgnoreCase("kstem") || stemmer.equalsIgnoreCase("krovetz"))) {
			type = EnglishAnalyzerConfigurable.StemmerType.KSTEM;
		} else if (stemmer != null && stemmer.equalsIgnoreCase("porter")) {
			type = EnglishAnalyzerConfigurable.StemmerType.PORTER;
		}
		an.setStemmer(type);
		return an;
	}

	@Override
	public void close() throws IOException {
		reader.close();
	}
}
