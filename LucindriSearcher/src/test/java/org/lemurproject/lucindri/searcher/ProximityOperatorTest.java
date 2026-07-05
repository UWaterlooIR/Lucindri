package org.lemurproject.lucindri.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.searcher.testutil.TestIndex;

/**
 * Tests for Lucindri term/proximity operators (TASK-0002). Asserts on doc SETS, not scores
 * (searcher Surefire disables assertions; Indri scores are negative log-probs). Test tokens are
 * lowercase, non-stopword, stem-invariant so the correct answer is unambiguous by construction.
 */
public class ProximityOperatorTest {

	private static Set<String> set(List<String> ids) {
		return new HashSet<>(ids);
	}

	// ---- Bug 1: a belief operand inside a proximity operator must throw, not NPE ----

	@Test
	public void beliefOperandInProximityThrowsClearError(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder().add("d1", "cat dog sun").build(dir)) {
			assertThrows(IllegalArgumentException.class, () -> ix.run("#band(#or(cat dog) sun)", 10));
		}
	}

	// ---- Bug 3: ordered window #N must match a phrase that begins at document position 0 ----

	@Test
	public void orderedWindowMatchesPhraseAtPositionZero(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder()
				.add("d0", "cat dog")          // phrase at position 0
				.add("d1", "filler cat dog")   // phrase at position 1
				.add("d2", "dog cat")          // reversed -> must NOT match
				.build(dir)) {
			assertEquals(Set.of("d0", "d1"), set(ix.ids("#1(cat dog)", 10)));
		}
	}

	// ---- Bug 2: #band / #uwN / #N with >=2 compound (#syn) operands must be correct ----
	// #syn(apple bird) = group1, #syn(cat dog) = group2.
	// d1,d2 each contain one of each group; d3 only group1; d4 only group2.

	private static TestIndex facetCorpus(Path dir) throws Exception {
		return TestIndex.builder()
				.add("d1", "apple cat")   // apple in g1, cat in g2   -> cover match
				.add("d2", "bird dog")    // bird in g1, dog in g2    -> cover match
				.add("d3", "apple bird")  // both g1, no g2           -> no
				.add("d4", "cat dog")     // both g2, no g1           -> no
				.build(dir);
	}

	@Test
	public void bandOfTwoSynFacets(@TempDir Path dir) throws Exception {
		try (TestIndex ix = facetCorpus(dir)) {
			assertEquals(Set.of("d1", "d2"),
					set(ix.ids("#band(#syn(apple bird) #syn(cat dog))", 10)));
		}
	}

	@Test
	public void unorderedWindowOfTwoSynFacets(@TempDir Path dir) throws Exception {
		try (TestIndex ix = facetCorpus(dir)) {
			assertEquals(Set.of("d1", "d2"),
					set(ix.ids("#uw4(#syn(apple bird) #syn(cat dog))", 10)));
		}
	}

	@Test
	public void orderedWindowOfTwoSynFacets(@TempDir Path dir) throws Exception {
		try (TestIndex ix = facetCorpus(dir)) {
			// #1 ordered: a group1 term immediately followed by a group2 term.
			// d1="apple cat", d2="bird dog" both match (also exercises the position-0 fix).
			assertEquals(Set.of("d1", "d2"),
					set(ix.ids("#1(#syn(apple bird) #syn(cat dog))", 10)));
		}
	}

	@Test
	public void broadeningAFacetNeverShrinksResults(@TempDir Path dir) throws Exception {
		try (TestIndex ix = facetCorpus(dir)) {
			// #band(#syn(apple bird) cat) must be a superset of #band(apple cat).
			Set<String> narrow = set(ix.ids("#band(apple cat)", 10));
			Set<String> broad = set(ix.ids("#band(#syn(apple bird) cat)", 10));
			assertEquals(Set.of("d1"), narrow, "plain-term band");
			// broad matches docs with (apple|bird) AND cat: d1 (apple,cat) and d4 (cat, no g1? d4=cat dog -> no apple/bird) => only d1
			assertEquals(true, broad.containsAll(narrow), () -> "broad=" + broad + " narrow=" + narrow);
		}
	}

	// ---- #uwN window width must be exactly N positions (inclusive span), matching Indri (TASK-0008) ----
	// Before the fix the unordered window was one position too wide (admitted span N+1). Isolated on
	// an integer collection vs C++ Indri; reproduced here with stem-invariant words.

	@Test
	public void unorderedWindowWidthIsExactlyN(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder()
				.add("adj",  "alpha beta")             // span 2
				.add("rev",  "beta alpha")             // span 2, reversed (unordered)
				.add("gap1", "alpha gamma beta")       // span 3 (one word between)
				.add("gap2", "alpha gamma delta beta") // span 4 (two words between)
				.build(dir)) {
			// #uwN = terms within an unordered window of N positions, i.e. inclusive span <= N.
			assertEquals(Set.of("adj", "rev"), set(ix.ids("#uw2(alpha beta)", 10)), "#uw2 = span<=2");
			assertEquals(Set.of("adj", "rev", "gap1"), set(ix.ids("#uw3(alpha beta)", 10)), "#uw3 = span<=3");
			assertEquals(Set.of("adj", "rev", "gap1", "gap2"), set(ix.ids("#uw4(alpha beta)", 10)), "#uw4 = span<=4");
		}
	}

	// ---- Regression: plain-term operators and standalone term-ops still correct ----

	@Test
	public void plainTermBandUnaffected(@TempDir Path dir) throws Exception {
		try (TestIndex ix = facetCorpus(dir)) {
			assertEquals(Set.of("d1"), set(ix.ids("#band(apple cat)", 10)));
		}
	}

	@Test
	public void standaloneSynAndNearUnaffected(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder()
				.add("d1", "filler apple cat")
				.add("d2", "bird moon")
				.add("d3", "moon star")
				.build(dir)) {
			assertEquals(Set.of("d1", "d2"), set(ix.ids("#syn(apple bird)", 10)));
			assertEquals(Set.of("d1"), set(ix.ids("#1(apple cat)", 10)));
		}
	}
}
