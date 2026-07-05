package org.lemurproject.lucindri.searcher.parser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.lucene.search.Query;
import org.junit.jupiter.api.Test;

/**
 * Query-time analysis must be configurable so it can match the index's analysis. Stopword removal
 * may legitimately differ between queries and documents; stemmer and tokenizer must not (TASK-0008
 * follow-up). Defaults preserve historical behavior: kstem, stopwords removed, lowercased.
 */
public class IndriQueryParserAnalysisTest {

	// Default: the English stop set is removed from queries.
	@Test
	public void defaultRemovesQueryStopwords() throws Exception {
		Query q = new IndriQueryParser().parseQuery("#combine( the cat )");
		assertFalse(q.toString().contains(":the"), () -> "stopword leaked: " + q);
		assertTrue(q.toString().contains(":cat"), () -> q.toString());
	}

	// removeStopwords=false keeps stopwords in the query (needed to match an all-tokens index).
	@Test
	public void stopwordRemovalIsConfigurable() throws Exception {
		Query q = new IndriQueryParser("fulltext", "kstem", false, true).parseQuery("#combine( the cat )");
		assertTrue(q.toString().contains(":the"), () -> "stopword should be retained: " + q);
		assertTrue(q.toString().contains(":cat"), () -> q.toString());
	}

	// Stemmer is configurable: kstem stems dogs->dog; none leaves it.
	@Test
	public void stemmerIsConfigurable() throws Exception {
		Query kstem = new IndriQueryParser("fulltext", "kstem", false, true).parseQuery("dogs");
		assertTrue(kstem.toString().contains(":dog"), () -> "kstem should stem: " + kstem);
		Query none = new IndriQueryParser("fulltext", "none", false, true).parseQuery("dogs");
		assertTrue(none.toString().contains(":dogs"), () -> "none should not stem: " + none);
	}

	// ignoreCase is configurable: lowercasing on vs off.
	@Test
	public void ignoreCaseIsConfigurable() throws Exception {
		Query lower = new IndriQueryParser("fulltext", "none", false, true).parseQuery("Dog");
		assertTrue(lower.toString().contains(":dog"), () -> "should lowercase: " + lower);
		Query cased = new IndriQueryParser("fulltext", "none", false, false).parseQuery("Dog");
		assertTrue(cased.toString().contains(":Dog"), () -> "should preserve case: " + cased);
	}
}
