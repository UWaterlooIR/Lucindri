package org.lemurproject.lucindri.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.searcher.testutil.TestIndex;

/**
 * Score-level (occurrence-count) conformance for proximity windows, checked against C++ Indri and
 * hand computation (TASK-0010; see docs/lucindri-vs-indri-scores.md). Uses stem-invariant,
 * non-stopword words on an integer-like vocabulary so tokenization/stemming/stopwords are no-ops.
 *
 * Key trick: in a single-document index the collection length |C| equals the document length L and a
 * window's collection frequency equals its term frequency, so the Dirichlet score reduces to
 * log(tf / L) — mu cancels — and the exact integer occurrence count is recoverable as
 * round(L * exp(score)). This lets us assert exact counts, which doc-set membership tests cannot.
 *
 * The occurrence count of a proximity window is NON-OVERLAPPING (Indri's documented semantics,
 * consistent across ordered and unordered windows). All expected values below were verified equal to
 * Indri's `dumpindex x` collection frequency.
 */
public class ProximityCountTest {

	/** Exact occurrence count of {@code query} in a single-document index holding {@code docText}. */
	private static long occ(Path base, String name, String docText, String query) throws IOException {
		Path dir = Files.createDirectories(base.resolve(name));
		int len = docText.trim().split("\\s+").length; // words are all non-stopword, stem-invariant
		try (TestIndex ix = TestIndex.builder().add("d", docText).build(dir)) {
			List<TestIndex.Hit> hits = ix.run(query, 10);
			if (hits.isEmpty()) {
				return 0;
			}
			return Math.round(len * Math.exp(hits.get(0).score));
		}
	}

	// ---- Ordered window occurrence counting is NON-OVERLAPPING (the TASK-0010 IndriNearWeight fix) ----

	@Test
	public void orderedWindowCountsNonOverlappingOnRepeatedTerms(@TempDir Path dir) throws Exception {
		// "alpha alpha beta beta": ordered (alpha,beta) windows (a@0,b@2) and (a@1,b@2) overlap -> 1.
		// The disjoint pairing (a@0,b@2)+(a@1,b@3) must NOT be counted (was the over-count = 2).
		assertEquals(1, occ(dir, "od2", "alpha alpha beta beta", "#2( \"alpha beta\" )"), "#2");
		assertEquals(1, occ(dir, "od3", "alpha alpha beta beta", "#3( \"alpha beta\" )"), "#3");
	}

	@Test
	public void orderedEqualsUnorderedOnRepeatedTerms(@TempDir Path dir) throws Exception {
		// Same document, both window types must agree (Indri counts both = 1).
		assertEquals(1, occ(dir, "uw2", "alpha alpha beta beta", "#uw2( \"alpha beta\" )"), "#uw2");
		assertEquals(1, occ(dir, "uw4", "alpha alpha beta beta", "#uw4( \"alpha beta\" )"), "#uw4");
	}

	@Test
	public void orderedWindowCountsGenuinelyDisjointOccurrences(@TempDir Path dir) throws Exception {
		// Two separate, non-overlapping adjacent phrases -> 2.
		assertEquals(2, occ(dir, "d", "alpha beta gamma alpha beta", "#1( \"alpha beta\" )"));
	}

	@Test
	public void orderedWindowRequiresCorrectOrder(@TempDir Path dir) throws Exception {
		assertEquals(1, occ(dir, "fwd", "alpha beta", "#1( \"alpha beta\" )"), "in order");
		assertEquals(0, occ(dir, "rev", "beta alpha", "#1( \"alpha beta\" )"), "reversed must not match");
	}

	@Test
	public void orderedWindowRespectsDistance(@TempDir Path dir) throws Exception {
		// "alpha gamma beta": alpha@0, beta@2 -> gap of one token.
		assertEquals(0, occ(dir, "d1", "alpha gamma beta", "#1( \"alpha beta\" )"), "#1( \"adjacent\" )\"no match\"");
		assertEquals(1, occ(dir, "d2", "alpha gamma beta", "#2( \"alpha beta\" )"), "#2 matches distance 2");
		assertEquals(1, occ(dir, "d3", "alpha gamma beta", "#3( \"alpha beta\" )"), "#3 matches");
	}

	// ---- Unordered window occurrence counting (also non-overlapping) ----

	@Test
	public void unorderedWindowCountsNonOverlapping(@TempDir Path dir) throws Exception {
		// "alpha beta alpha beta": two disjoint span-2 windows -> 2.
		assertEquals(2, occ(dir, "two", "alpha beta alpha beta", "#uw2( \"alpha beta\" )"), "disjoint");
		// "alpha alpha beta": only one beta -> a single window -> 1.
		assertEquals(1, occ(dir, "one", "alpha alpha beta", "#uw2( \"alpha beta\" )"), "single");
	}

	@Test
	public void unorderedWindowIsOrderIndependent(@TempDir Path dir) throws Exception {
		assertEquals(1, occ(dir, "ab", "alpha beta", "#uw2( \"alpha beta\" )"), "in order");
		assertEquals(1, occ(dir, "ba", "beta alpha", "#uw2( \"alpha beta\" )"), "reversed still matches");
	}
}
