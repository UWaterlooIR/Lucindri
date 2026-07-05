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
 * Filter operators (TASK-0010 Phase 4): Lucindri {@code #scoreif}/{@code #scoreifnot} (from TASK-0006)
 * vs C++ Indri {@code #filreq}/{@code #filrej}. Semantics (verified equal to Indri on the integer
 * collection at both the passing set and the scores): {@code #scoreif(c s)} keeps the documents that
 * match the scored query {@code s} AND the filter condition {@code c}, ranked by {@code s};
 * {@code #scoreifnot(c s)} keeps {@code s}'s matches that do NOT match {@code c}. The filter
 * contributes no score. Stem-invariant, non-stopword words map to the C1 integer collection
 * (alpha=10, beta=20, gamma=30, zeta=90).
 */
public class FilterOperatorTest {

	private static TestIndex c1(Path dir) throws Exception {
		return TestIndex.builder()
				.add("d1", "alpha beta gamma")          // 10 20 30
				.add("d2", "alpha alpha beta")          // 10 10 20
				.add("d3", "alpha zeta beta")           // 10 90 20
				.add("d4", "beta alpha")                // 20 10
				.add("d5", "gamma delta epsilon zeta zeta") // 30 40 50 90 90
				.build(dir);
	}

	private static Set<String> set(List<String> ids) {
		return new HashSet<>(ids);
	}

	@Test
	public void requireKeepsScoredMatchesThatAlsoMatchFilter(@TempDir Path dir) throws Exception {
		try (TestIndex ix = c1(dir)) {
			// scored #combine(alpha) matches {d1,d2,d3,d4}; filter gamma matches {d1,d5}; ∩ = {d1}.
			assertEquals(Set.of("d1"), set(ix.ids("#scoreif( gamma #combine( alpha ) )", 10)));
		}
	}

	@Test
	public void rejectDropsScoredMatchesThatMatchFilter(@TempDir Path dir) throws Exception {
		try (TestIndex ix = c1(dir)) {
			// {d1,d2,d3,d4} minus filter-gamma {d1} = {d2,d3,d4}.
			assertEquals(Set.of("d2", "d3", "d4"), set(ix.ids("#scoreifnot( gamma #combine( alpha ) )", 10)));
		}
	}

	@Test
	public void compoundAndProximityFiltersWork(@TempDir Path dir) throws Exception {
		try (TestIndex ix = c1(dir)) {
			// filter = ordered window #1(alpha beta) -> {d1,d2}; scored #combine(gamma) -> {d1,d5}; ∩ = {d1}.
			assertEquals(Set.of("d1"), set(ix.ids("#scoreif( #1( alpha beta ) #combine( gamma ) )", 10)));
			// filter zeta -> {d3,d5}; scored #combine(alpha beta) -> {d1,d2,d3,d4}; ∩ = {d3}.
			assertEquals(Set.of("d3"), set(ix.ids("#scoreif( zeta #combine( alpha beta ) )", 10)));
		}
	}

	@Test
	public void oovFilterMatchesNothing(@TempDir Path dir) throws Exception {
		try (TestIndex ix = c1(dir)) {
			// A filter term absent from the collection matches no document (the OOV floor is for
			// scoring, not matching), so require yields the empty set.
			assertEquals(Set.of(), set(ix.ids("#scoreif( zzznotthere #combine( alpha ) )", 10)));
		}
	}

	@Test
	public void filterContributesNoScore(@TempDir Path dir) throws Exception {
		try (TestIndex ix = c1(dir)) {
			// d1's score under #scoreif(gamma #combine(alpha)) equals its score under #combine(alpha) alone.
			double filtered = scoreOf(ix, "#scoreif( gamma #combine( alpha ) )", "d1");
			double scoredOnly = scoreOf(ix, "#combine( alpha )", "d1");
			assertEquals(scoredOnly, filtered, 1e-4, "filter must not change the scored subquery's score");
		}
	}

	private static double scoreOf(TestIndex ix, String query, String id) throws Exception {
		for (TestIndex.Hit h : ix.run(query, 10)) {
			if (h.externalId.equals(id)) {
				return h.score;
			}
		}
		throw new AssertionError(id + " not returned for " + query);
	}
}
