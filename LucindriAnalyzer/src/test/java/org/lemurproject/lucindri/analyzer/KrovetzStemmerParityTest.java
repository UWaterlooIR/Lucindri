package org.lemurproject.lucindri.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.Test;

/**
 * Golden characterization of Lucindri's Krovetz (KStem) stemming, and a guard on where it diverges
 * from C++ Indri's Krovetz stemmer (TASK-0013).
 *
 * <p>Lucindri stems with Lucene's {@code KStemFilter}; Indri stems with its own C++
 * {@code indri::parse::KrovetzStemmer}. A full differential run over the 248,944 alphabetic term types
 * of an unstemmed LATimes index (see {@code scripts/stemmer-comparison/}) found the two agree on
 * <b>99.97% of types / 99.95% of token occurrences</b>. The remaining ~0.05% are all attributable to
 * Indri's Krovetz shipping dictionary/exception tables (proper-noun and head-word no-op lists, an
 * irregular-plural conflation table) that Lucene's port lacks, plus a lower word-length cap in Indri
 * (25 chars vs Lucene's ~50). See {@code docs/krovetz-comparison.md}.
 *
 * <p>This test pins the exact {@code KStemFilter} output through Lucindri's production analyzer path so
 * that a dependency bump (e.g. a new Lucene) that silently changes stemming fails loudly. The DIVERGENT
 * cases assert Lucindri's <i>current</i> stem and record Indri's differing stem in a comment; if a
 * future task ports Indri's tables to reach exact parity, those expectations flip to the Indri value.
 */
public class KrovetzStemmerParityTest {

	/** Run a single token through the production analyzer path (KStem, no stopword removal). */
	private static String stem(String token) throws Exception {
		EnglishAnalyzerConfigurable an = new EnglishAnalyzerConfigurable();
		an.setLowercase(true);
		an.setStopwordRemoval(false);
		an.setStemmer(EnglishAnalyzerConfigurable.StemmerType.KSTEM);
		String result = token;
		try (TokenStream ts = an.tokenStream("f", new StringReader(token))) {
			CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
			ts.reset();
			if (ts.incrementToken()) {
				result = term.toString();
			}
			ts.end();
		}
		return result;
	}

	/** Cases where Lucindri and Indri Krovetz AGREE — these lock the common stemming behavior. */
	@Test
	public void agreesWithIndriOnCommonVocabulary() throws Exception {
		Map<String, String> golden = new LinkedHashMap<>();
		golden.put("retrieving", "retrieve");
		golden.put("retrieval", "retrieval");
		golden.put("documents", "document");
		golden.put("cities", "city");
		golden.put("cars", "car");
		golden.put("abatements", "abate");
		golden.put("connected", "connected");
		// KStem is deliberately light: these are unchanged in BOTH engines (not a bug).
		golden.put("running", "running");
		golden.put("mice", "mice");
		golden.put("national", "national");
		golden.put("spoke", "spoke");
		for (Map.Entry<String, String> e : golden.entrySet()) {
			assertEquals(e.getValue(), stem(e.getKey()), "KStem(" + e.getKey() + ")");
		}
	}

	/**
	 * Cases where Lucindri (Lucene KStem) DIVERGES from Indri Krovetz. The assertion locks Lucindri's
	 * current output; the trailing comment is Indri's stem. All are Indri dictionary/exception-table
	 * entries Lucene's port lacks (TASK-0013). Update these if we ever port Indri's tables for parity.
	 */
	@Test
	public void divergesFromIndriOnDictionaryDrivenCases() throws Exception {
		Map<String, String> lucindri = new LinkedHashMap<>();
		lucindri.put("later", "late");            // Indri: later   (head-word no-op list)
		lucindri.put("thieves", "thieve");        // Indri: thief   (irregular-plural conflation)
		lucindri.put("wolves", "wolve");          // Indri: wolf    (irregular-plural conflation)
		lucindri.put("crises", "crise");          // Indri: crisis  (irregular-plural conflation)
		lucindri.put("housewives", "housewive");  // Indri: housewife
		lucindri.put("kelly", "kel");             // Indri: kelly   (proper-noun list)
		lucindri.put("weber", "web");             // Indri: weber   (head-word no-op list)
		for (Map.Entry<String, String> e : lucindri.entrySet()) {
			assertEquals(e.getValue(), stem(e.getKey()),
					"KStem(" + e.getKey() + ") diverges from Indri; update if parity is implemented");
		}
	}
}
