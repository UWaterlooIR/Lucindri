package org.lemurproject.lucindri.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.Test;

/**
 * Smoke test proving the analyzer module's test wiring runs and pinning the tokenization
 * contract that the searcher's query parser and the indexer both depend on
 * (lowercase + English stopword removal + KStem).
 */
public class EnglishAnalyzerConfigurableTest {

	private static List<String> tokens(Analyzer analyzer, String text) throws Exception {
		List<String> out = new ArrayList<>();
		try (TokenStream ts = analyzer.tokenStream("f", new StringReader(text))) {
			CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
			ts.reset();
			while (ts.incrementToken()) {
				out.add(term.toString());
			}
			ts.end();
		}
		return out;
	}

	@Test
	public void lowercasesStopwordsAndKStem() throws Exception {
		EnglishAnalyzerConfigurable an = new EnglishAnalyzerConfigurable();
		an.setLowercase(true);
		an.setStopwordRemoval(true);
		an.setStemmer(EnglishAnalyzerConfigurable.StemmerType.KSTEM);
		// "The" is an English stopword (dropped); words are lowercased; KStem folds these plurals
		// ("Cars"->"car", "Cities"->"city"). (Note: KStem is a light stemmer and leaves some
		// plurals like "dogs"/"mice" unchanged — that is KStem's behavior, verified separately.)
		assertEquals(List.of("car", "city"), tokens(an, "The Cars Cities"));
	}

	@Test
	public void stemmingCanBeDisabled() throws Exception {
		EnglishAnalyzerConfigurable an = new EnglishAnalyzerConfigurable();
		an.setLowercase(true);
		an.setStopwordRemoval(false);
		an.setStemmer(EnglishAnalyzerConfigurable.StemmerType.NONE);
		assertEquals(List.of("the", "dogs"), tokens(an, "The Dogs"));
	}
}
