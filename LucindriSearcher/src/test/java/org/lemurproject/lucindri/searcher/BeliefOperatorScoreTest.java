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

	// A proximity/window containing an OOV term can never occur (matches nothing), but like Indri it
	// is a cf=0 term that contributes the floored background to a belief combination — not dropped,
	// and not a crash (regression: the OOV scorer used to trip the proximity operand check). (TASK-0010 P5)
	@Test
	public void proximityWithOovOperandNeverMatchesButFloorsInBelief(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder().add("d", "alpha beta").build(dir)) {
			assertTrue(ix.run("#1( beta zzz )", 10).isEmpty(), "an OOV window must match nothing");
			// single doc |C|=|d|=2, mu=2000; p(#1|C)=0.5/2; #combine(alpha #1(beta zzz)) = (s_alpha + bg)/2.
			double sAlpha = Math.log(0.5);
			double bg = Math.log((2000.0 * (0.5 / 2.0)) / (2.0 + 2000.0));
			double combined = ix.run("#combine( alpha #1( beta zzz ) )", 10).get(0).score;
			assertEquals((sAlpha + bg) / 2.0, combined, 1e-3, "OOV window must floor its background, not drop");
		}
	}

	// A single-operand belief node #combine(X) ≡ X and must still receive a weight from an enclosing
	// #weight/#wsum. Regression: the stock IndriAndWeight single-scorer shortcut used to return the
	// child built with boost 1.0f, dropping the weight, so #weight(0.9 #combine(a) 0.1 #combine(b))
	// wrongly collapsed to the equal-weight mean. Fixed by collapsing single-operand belief nodes in
	// the parser (TASK-0010 Phase 5).
	@Test
	public void weightAppliesToSingleOperandCombineChildren(@TempDir Path dir) throws Exception {
		double skew = score(dir, "s1", "#weight(0.9 #combine(alpha) 0.1 #combine(beta))");
		double flip = score(dir, "s2", "#weight(0.1 #combine(alpha) 0.9 #combine(beta))");
		assertEquals(0.9 * SA + 0.1 * SB, skew, EPS, "weight must apply to a single-operand #combine child");
		assertEquals(0.1 * SA + 0.9 * SB, flip, EPS);
		assertTrue(Math.abs(skew - flip) > 0.1, "weights on single-operand children must not be dropped");
	}

	// Out-of-vocabulary term (cf=0): Indri floors p(w|C)=1/(2|C|) and the term contributes a
	// background belief rather than being dropped (TASK-0010). Here |C|=|d|=3, and TestIndex's default
	// similarity is IndriDirichletSimilarity(mu=2000), so p(zzz|C)=1/6 and
	// s_zzz = log((mu·1/6)/(|d|+mu)); #combine(alpha zzz) = (s_alpha + s_zzz)/2.
	@Test
	public void oovTermContributesFlooredBackgroundNotDropped(@TempDir Path dir) throws Exception {
		double mu = 2000.0, C = 3.0, d = 3.0;
		double sZzz = Math.log((mu * (0.5 / C)) / (d + mu));
		double combineOov = score(dir, "co", "#combine(alpha zzz)");
		assertEquals((SA + sZzz) / 2.0, combineOov, 1e-3, "OOV term must contribute the floored background");
		// And it must NOT be dropped: including the very-negative OOV background drags the score well
		// below the alpha-only score (which would be s_alpha ≈ -0.405).
		assertTrue(combineOov < SA - 0.5, "OOV term was dropped (score == alpha-only)");
	}
}
