package org.lemurproject.lucindri.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.searcher.testutil.TestIndex;

/**
 * Belief-operator retrieval-set conformance vs Indri (TASK-0006): #not must not pull the negated
 * term's docs into the candidate set, and #scoreif/#scoreifnot must actually filter. Asserts on
 * doc SETS (stem-invariant, non-stopword tokens).
 */
public class BeliefOperatorTest {

	private static Set<String> set(List<String> ids) {
		return new HashSet<>(ids);
	}

	// #not must only modify belief, not add the negated term's docs to the candidate set.
	@Test
	public void notDoesNotRetrieveNegatedTermOnlyDocs(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder()
				.add("d1", "apple cat")   // apple  -> match
				.add("d2", "apple dog")   // apple  -> match (has dog, penalized, still retrieved)
				.add("d3", "banana dog")  // no apple, has dog -> must NOT be retrieved
				.build(dir)) {
			assertEquals(Set.of("d1", "d2"), set(ix.ids("#combine( \"apple\" #not( \"dog\" ) )", 10)));
		}
	}

	// #scoreif(b sub) = the scored query's candidates that ALSO match b (Indri #filreq).
	// #combine(cat) candidates = {s1,s2}; keep those matching dog -> {s1}. (Not all dog docs.)
	@Test
	public void scoreifFiltersToRequiredTerm(@TempDir Path dir) throws Exception {
		try (TestIndex ix = scoreifCorpus(dir)) {
			assertEquals(Set.of("s1"), set(ix.ids("#scoreif( \"dog\" #combine( \"cat\" ) )", 10)));
		}
	}

	// #scoreifnot(b sub) = reject docs matching b, scored by sub  (Indri #filrej).
	@Test
	public void scoreifnotRejectsTerm(@TempDir Path dir) throws Exception {
		try (TestIndex ix = scoreifCorpus(dir)) {
			assertEquals(Set.of("s2"), set(ix.ids("#scoreifnot( \"dog\" #combine( \"cat\" ) )", 10)));
		}
	}

	// A filter term matching no document -> empty result (currently returns the whole cat set).
	@Test
	public void scoreifWithZeroDocFilterReturnsEmpty(@TempDir Path dir) throws Exception {
		try (TestIndex ix = scoreifCorpus(dir)) {
			assertEquals(Set.of(), set(ix.ids("#scoreif( \"zzznope\" #combine( \"cat\" ) )", 10)));
		}
	}

	private static TestIndex scoreifCorpus(Path dir) throws Exception {
		return TestIndex.builder()
				.add("s1", "apple cat dog")  // cat + dog
				.add("s2", "apple cat")      // cat, no dog
				.add("s3", "banana dog")     // dog, no cat
				.add("s4", "apple")          // neither
				.build(dir);
	}
}
