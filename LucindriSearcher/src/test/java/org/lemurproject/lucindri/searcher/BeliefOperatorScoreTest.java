package org.lemurproject.lucindri.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.searcher.testutil.TestIndex;

/**
 * Score-level conformance for the belief operators (TASK-0010 Phase 2), checked against the
 * AUTHORITATIVE formulas — Metzler &amp; Croft (2004), "Combining the language model and inference
 * network approaches to retrieval," IP&amp;M 40(5):735–750, p.739 Eqs. (2)–(7) — and against Indri's
 * C++ implementation, NOT reverse-engineered from Lucindri.
 *
 * Belief formulas (p_i = child belief = exp(child score)):
 *   #or   = 1 − Π(1 − p_i)                        (Eq. 3)
 *   #max  = max p_i                               (Eq. 5)
 *   #wsum = Σ w_i p_i / Σ w_i                      (Eq. 7)
 *   #combine/#weight = Σ w_i·s_i / Σ w_i           (Indri WeightedAndNode.score — the NORMALIZED
 *          geometric mean of beliefs; deliberately NOT the paper's Eq. 4 product Π p_i.)
 *
 * Test trick: in a single-document index s_term = log(tf / |d|) exactly (μ cancels), so the belief
 * scores are exact, μ-independent constants. Document "alpha alpha beta": p_alpha = 2/3, p_beta = 1/3.
 */
public class BeliefOperatorScoreTest {

	private static final double A = 2.0 / 3.0; // p_alpha
	private static final double B = 1.0 / 3.0; // p_beta
	private static final double SA = Math.log(A);
	private static final double SB = Math.log(B);
	private static final double EPS = 1e-4;

	private static double score(Path base, String name, String query) throws IOException {
		Path dir = Files.createDirectories(base.resolve(name));
		try (TestIndex ix = TestIndex.builder().add("d", "alpha alpha beta").build(dir)) {
			List<TestIndex.Hit> hits = ix.run(query, 10);
			assertFalse(hits.isEmpty(), () -> "no hits for " + query);
			return hits.get(0).score;
		}
	}

	@Test
	public void combineIsNormalizedGeometricMeanNotProduct(@TempDir Path dir) throws Exception {
		double s = score(dir, "c", "#combine(alpha beta)");
		assertEquals((SA + SB) / 2.0, s, EPS, "#combine = mean of log-beliefs");
		// Lock the normalization: it must NOT be the paper Eq.(4) product (Σ s_i).
		assertTrue(Math.abs(s - (SA + SB)) > 0.1, "must not be the unnormalized product");
	}

	@Test
	public void weightIsNormalizedWeightedMean(@TempDir Path dir) throws Exception {
		assertEquals((0.7 * SA + 0.3 * SB) / (0.7 + 0.3),
				score(dir, "w", "#weight(0.7 alpha 0.3 beta)"), EPS);
	}

	@Test
	public void orIsOneMinusProductOfComplements(@TempDir Path dir) throws Exception {
		assertEquals(Math.log(1 - (1 - A) * (1 - B)), score(dir, "o", "#or(alpha beta)"), EPS);
	}

	@Test
	public void maxIsMaxBelief(@TempDir Path dir) throws Exception {
		assertEquals(Math.max(SA, SB), score(dir, "m", "#max(alpha beta)"), EPS);
	}

	@Test
	public void wsumIsWeightedArithmeticMeanOfBeliefs(@TempDir Path dir) throws Exception {
		assertEquals(Math.log((0.7 * A + 0.3 * B) / (0.7 + 0.3)),
				score(dir, "ws", "#wsum(0.7 alpha 0.3 beta)"), EPS);
	}
}
