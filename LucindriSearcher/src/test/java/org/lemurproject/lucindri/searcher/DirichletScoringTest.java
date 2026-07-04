package org.lemurproject.lucindri.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.searcher.similarities.IndriDirichletSimilarity;
import org.lemurproject.lucindri.searcher.testutil.TestIndex;

/**
 * End-to-end scoring test proving the corrected collection statistics (TASK-0003) feed Indri
 * Dirichlet smoothing correctly. Docs are short enough (&lt;= 8 tokens) that SmallFloat length
 * encoding is exact, so the document length used in scoring is exactly known; tokens are
 * lowercase, non-stopword, and stem-invariant, so tf/ttf/lengths are exactly known.
 *
 * <p>Indri Dirichlet score for a single term: {@code log((tf + mu*p) / (docLen + mu))}, with
 * {@code p = ttf / collectionLength}. A single-term query returns exactly this raw term score.
 * (Before the fix, {@code collectionLength} was inflated by the double count, so scores differed.)
 */
public class DirichletScoringTest {

	@Test
	public void singleTermScoreMatchesDirichletClosedForm(@TempDir Path dir) throws Exception {
		final double mu = 2000.0;
		try (TestIndex ix = TestIndex.builder()
				.querySimilarity(new IndriDirichletSimilarity((float) mu))
				.add("d1", "apple apple tree")  // apple tf=2, len=3
				.add("d2", "apple tree sun")    // apple tf=1, len=3
				.add("d3", "tree sun moon")     // apple tf=0, len=3 (no "apple" -> not a hit)
				.build(dir)) {

			// collection = 9 tokens; apple occurs 3 times -> p(apple|C) = 3/9
			final double p = 3.0 / 9.0;
			final double expectedD1 = Math.log((2 + mu * p) / (3 + mu));
			final double expectedD2 = Math.log((1 + mu * p) / (3 + mu));

			List<TestIndex.Hit> hits = ix.run("apple", 10);
			assertEquals(2, hits.size(), () -> "hits=" + hits);
			assertEquals("d1", hits.get(0).externalId, "d1 (higher tf) should rank first");
			assertEquals("d2", hits.get(1).externalId);
			assertEquals(expectedD1, hits.get(0).score, 1e-4, "d1 Dirichlet score");
			assertEquals(expectedD2, hits.get(1).score, 1e-4, "d2 Dirichlet score");
		}
	}
}
