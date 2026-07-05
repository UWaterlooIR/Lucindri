// TASK-0015 — enforce the parser robustness invariant over a stream of (mostly invalid) queries.
//
// Reads `category<TAB>query` (or a bare query) per line from stdin, and for each runs
// IndriQueryParser.parseQuery + a bounded search on a tiny in-memory index, under a per-query timeout.
// Classifies each outcome:
//   OK        - parsed (and searched) without throwing
//   REJECTED  - threw QueryParseException (the desired rejection for malformed input)
//   VIOLATION - any OTHER throwable (implementation leak: AIOOBE/SIOOBE/NumberFormat/NPE/StackOverflow),
//               a raw IllegalArgumentException that is not a QueryParseException, or a timeout (hang)
// Prints a summary and every violation, and exits non-zero if any violation occurred.
//
// Build/run against the searcher fat jar:
//   javac -cp <fatjar> FuzzInvalidHarness.java -d <out>
//   python3 fuzz_invalid.py 1 20000 | java -cp <fatjar>:<out> FuzzInvalidHarness
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.similarities.LMDirichletSimilarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.lemurproject.lucindri.analyzer.EnglishAnalyzerConfigurable;
import org.lemurproject.lucindri.searcher.IndriIndexSearcher;
import org.lemurproject.lucindri.searcher.parser.IndriQueryParser;
import org.lemurproject.lucindri.searcher.parser.QueryParseException;
import org.lemurproject.lucindri.searcher.similarities.IndriDirichletSimilarity;

public class FuzzInvalidHarness {

	static Analyzer analyzer() {
		EnglishAnalyzerConfigurable a = new EnglishAnalyzerConfigurable();
		a.setLowercase(true);
		a.setStopwordRemoval(true);
		a.setStemmer(EnglishAnalyzerConfigurable.StemmerType.KSTEM);
		return a;
	}

	public static void main(String[] args) throws Exception {
		Directory dir = new ByteBuffersDirectory();
		FieldType ft = new FieldType();
		ft.setStored(true);
		ft.setTokenized(true);
		ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
		ft.freeze();
		IndexWriterConfig cfg = new IndexWriterConfig(analyzer());
		cfg.setSimilarity(new LMDirichletSimilarity());
		String[][] docs = { { "d1", "cat dog sun" }, { "d2", "dog moon tree" }, { "d3", "sun tree cat" } };
		try (IndexWriter w = new IndexWriter(dir, cfg)) {
			for (String[] d : docs) {
				Document doc = new Document();
				doc.add(new Field("externalId", d[0], ft));
				doc.add(new Field("fulltext", d[1], ft));
				w.addDocument(doc);
			}
		}
		IndexReader reader = DirectoryReader.open(dir);
		IndriIndexSearcher searcher = new IndriIndexSearcher(reader);
		searcher.setSimilarity(new IndriDirichletSimilarity());

		ExecutorService exec = Executors.newSingleThreadExecutor();
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		long total = 0, ok = 0, rejected = 0;
		java.util.Map<String, long[]> byCat = new java.util.TreeMap<>(); // cat -> {ok, rejected, violation}
		List<String> violations = new ArrayList<>();
		String line;
		while ((line = in.readLine()) != null) {
			int tab = line.indexOf('\t');
			String category = tab >= 0 ? line.substring(0, tab) : "(none)";
			String query = tab >= 0 ? line.substring(tab + 1) : line;
			total++;
			long[] cc = byCat.computeIfAbsent(category, k -> new long[3]);
			Future<?> f = exec.submit(() -> {
				Query q = new IndriQueryParser("fulltext", "kstem", true, true).parseQuery(query);
				if (q != null) {
					searcher.search(q, 10);
				}
				return null;
			});
			try {
				f.get(5, TimeUnit.SECONDS);
				ok++;
				cc[0]++;
			} catch (java.util.concurrent.ExecutionException e) {
				Throwable c = e.getCause();
				if (c instanceof QueryParseException) {
					rejected++;
					cc[1]++;
				} else {
					cc[2]++;
					violations.add(String.format("[%s] %s: %s  <<< %s", category,
							c == null ? "null" : c.getClass().getName(),
							c == null ? "" : String.valueOf(c.getMessage()), snippet(query)));
				}
			} catch (TimeoutException e) {
				f.cancel(true);
				cc[2]++;
				violations.add(String.format("[%s] TIMEOUT  <<< %s", category, snippet(query)));
			} catch (Throwable t) {
				cc[2]++;
				violations.add(String.format("[%s] %s  <<< %s", category, t, snippet(query)));
			}
		}
		exec.shutdownNow();
		reader.close();

		System.out.printf("total=%d  OK=%d  REJECTED(QueryParseException)=%d  VIOLATIONS=%d%n",
				total, ok, rejected, violations.size());
		System.out.println("per-category  (ok / rejected / violation):");
		for (java.util.Map.Entry<String, long[]> e : byCat.entrySet()) {
			long[] v = e.getValue();
			System.out.printf("  %-26s ok=%-6d rejected=%-6d violation=%d%n", e.getKey(), v[0], v[1], v[2]);
		}
		int shown = 0;
		for (String v : violations) {
			System.out.println("  VIOLATION " + v);
			if (++shown >= 50) {
				System.out.println("  ... (" + (violations.size() - shown) + " more)");
				break;
			}
		}
		System.exit(violations.isEmpty() ? 0 : 1);
	}

	static String snippet(String q) {
		q = q.length() > 80 ? q.substring(0, 80) + "…(" + q.length() + " chars)" : q;
		return q.replace("\n", " ");
	}
}
