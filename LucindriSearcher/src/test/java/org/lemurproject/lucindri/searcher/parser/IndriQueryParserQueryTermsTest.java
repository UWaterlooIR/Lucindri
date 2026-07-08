package org.lemurproject.lucindri.searcher.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link IndriQueryParser#queryTerms(String)} — the positive-content-term extraction used to build
 * query-biased summaries (TASK-0019). Uses stem-invariant, non-stopword, lowercase tokens so the analyzed
 * terms are exactly the surface tokens.
 */
public class IndriQueryParserQueryTermsTest {

	private static List<String> terms(String query) throws Exception {
		return new IndriQueryParser().queryTerms(query);
	}

	@Test
	public void negatedTermsExcluded() throws Exception {
		assertEquals(List.of("cat"), terms("#combine(\"cat\" #not(\"dog\"))"));
	}

	@Test
	public void scoreifnotConditionExcluded_scoredKept() throws Exception {
		assertEquals(List.of("cat", "sun"), terms("#scoreifnot(\"dog\" #combine(\"cat sun\"))"));
	}

	@Test
	public void scoreifConditionIncluded() throws Exception {
		assertEquals(List.of("dog", "cat", "sun"), terms("#scoreif(\"dog\" #combine(\"cat sun\"))"));
	}

	@Test
	public void proximityConstituentsCollected() throws Exception {
		assertEquals(List.of("cat", "dog", "sun"), terms("#combine(#uw8(\"cat\" \"dog\") \"sun\")"));
	}

	@Test
	public void synonymAlternatesCollected() throws Exception {
		assertEquals(List.of("cat", "dog"), terms("#syn(\"cat\" \"dog\")"));
	}

	@Test
	public void doubleNegationCancels() throws Exception {
		assertEquals(List.of("cat", "dog"), terms("#combine(\"cat\" #not(#not(\"dog\")))"));
	}

	@Test
	public void duplicatesRemovedOrderPreserved() throws Exception {
		assertEquals(List.of("cat", "dog"), terms("#combine(\"cat\" \"cat\" \"dog\")"));
	}

	@Test
	public void nullAndEmptyAreEmpty() throws Exception {
		assertEquals(List.of(), terms(null));
		// A query of only stopwords analyzes to nothing -> no terms.
		assertEquals(List.of(), terms("#combine(\"the a of\")"));
	}
}
