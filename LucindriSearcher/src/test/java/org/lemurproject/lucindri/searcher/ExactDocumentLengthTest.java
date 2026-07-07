package org.lemurproject.lucindri.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.apache.lucene.util.SmallFloat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.searcher.similarities.IndriDirichletSimilarity;
import org.lemurproject.lucindri.searcher.testutil.TestIndex;

/**
 * End-to-end test of exact document length (TASK-0012). On a <b>long</b> document (716 tokens, well past
 * SmallFloat's lossless range) an index built with {@code exactDocumentLength} scores with the true |d|
 * (matching the Dirichlet closed form), whereas the default norm index scores with the SmallFloat-rounded
 * length — the two differ. On a short document (lossless range) the two paths are identical.
 *
 * <p>Indri single-term Dirichlet score: {@code log((tf + mu*p) / (|d| + mu))}, {@code p = cf/|C|}. A
 * single-term query returns exactly this raw term score. Tokens are lowercase, non-stopword, stem-invariant
 * so tf/cf/|C|/|d| are exactly known.
 */
public class ExactDocumentLengthTest {

	private static final double MU = 2000.0;

	/** Builds "cat" + {@code fillerCount} copies of "sun" (so |d| = 1 + fillerCount, "cat" tf = 1). */
	private static String catPlusFiller(int fillerCount) {
		StringBuilder sb = new StringBuilder("cat");
		for (int i = 0; i < fillerCount; i++) {
			sb.append(" sun");
		}
		return sb.toString();
	}

	@Test
	public void longDoc_exactLengthMatchesClosedForm_andDiffersFromNorm(@TempDir Path dir) throws Exception {
		final int len1 = 716; // d1 length: "cat" + 715 "sun"
		final int len2 = 4; // d2: 4 "sun"
		final String d1 = catPlusFiller(len1 - 1);
		final String d2 = "sun sun sun sun";

		// |C| = 720; "cat" occurs once (in d1) => p = 1/720. "cat" is only in d1 => a single hit.
		final double p = 1.0 / (len1 + len2);
		final double expectedExact = Math.log((1 + MU * p) / (len1 + MU));

		final int quantizedLen = SmallFloat.byte4ToInt(SmallFloat.intToByte4(len1));
		final double expectedNorm = Math.log((1 + MU * p) / (quantizedLen + MU));

		// Sanity: 716 really is in the quantizing regime, so the two closed forms genuinely differ.
		assertTrue(quantizedLen != len1, "test precondition: 716 must be SmallFloat-quantized");
		assertNotEquals(expectedExact, expectedNorm, "closed forms must differ in the quantizing regime");

		double exactScore;
		try (TestIndex ix = TestIndex.builder()
				.querySimilarity(new IndriDirichletSimilarity((float) MU))
				.exactDocumentLength(true)
				.add("d1", d1)
				.add("d2", d2)
				.build(dir.resolve("exact"))) {
			List<TestIndex.Hit> hits = ix.run("\"cat\"", 10);
			assertEquals(1, hits.size(), () -> "hits=" + hits);
			exactScore = hits.get(0).score;
			assertEquals(expectedExact, exactScore, 1e-4, "exact-length score must use the true |d|=716");
		}

		double normScore;
		try (TestIndex ix = TestIndex.builder()
				.querySimilarity(new IndriDirichletSimilarity((float) MU))
				.exactDocumentLength(false)
				.add("d1", d1)
				.add("d2", d2)
				.build(dir.resolve("norm"))) {
			List<TestIndex.Hit> hits = ix.run("\"cat\"", 10);
			assertEquals(1, hits.size(), () -> "hits=" + hits);
			normScore = hits.get(0).score;
			assertEquals(expectedNorm, normScore, 1e-4, "norm score must use the SmallFloat-rounded length");
		}

		// The feature actually changed the score: exact length removed the quantization gap.
		assertNotEquals(normScore, exactScore, "exactDocumentLength must change the long-doc score");
	}

	@Test
	public void shortDoc_exactAndNormAreIdentical(@TempDir Path dir) throws Exception {
		// In SmallFloat's lossless range (<= ~40 tokens) the exact and norm lengths coincide, so scores match.
		double exactScore;
		try (TestIndex ix = TestIndex.builder()
				.querySimilarity(new IndriDirichletSimilarity((float) MU))
				.exactDocumentLength(true)
				.add("d1", "cat sun moon")
				.add("d2", "sun moon tree")
				.build(dir.resolve("exact"))) {
			exactScore = ix.run("\"cat\"", 10).get(0).score;
		}
		double normScore;
		try (TestIndex ix = TestIndex.builder()
				.querySimilarity(new IndriDirichletSimilarity((float) MU))
				.exactDocumentLength(false)
				.add("d1", "cat sun moon")
				.add("d2", "sun moon tree")
				.build(dir.resolve("norm"))) {
			normScore = ix.run("\"cat\"", 10).get(0).score;
		}
		assertEquals(normScore, exactScore, 0.0, "lossless-range scores must be identical exact vs norm");
	}
}
