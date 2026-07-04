package org.lemurproject.lucindri.searcher.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Smoke test proving the shared JUnit wiring runs and the {@link TestIndex} fixture works
 * end to end (build index, parse an Indri query, search, retrieve external ids). Also exercises
 * the multi-part {@code MultiReader} path that TASK-0002 / TASK-0003 rely on.
 *
 * <p>Note: docs here keep the queried phrase off document position 0. Lucindri's ordered-window
 * (`#N`) currently misses a phrase that begins at position 0 of a document (see TASK-0002); the
 * fixture and these tests exercise the working path.
 */
public class TestIndexSmokeTest {

	// Single index: an ordered-window (#1) proximity query returns only the doc with the phrase.
	@Test
	public void singleIndexProximityQuery(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder()
				.add("d1", "filler cat dog")   // "cat dog" adjacent (positions 1,2)
				.add("d2", "dog cat filler")   // reversed — must NOT match #1(cat dog)
				.add("d3", "sun moon rain")
				.build(dir)) {
			assertEquals(List.of("d1"), ix.ids("#1(cat dog)", 10));
		}
	}

	// Two sub-indexes wrapped in a MultiReader: results aggregate across parts.
	@Test
	public void multiPartAggregation(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder()
				.add("p0d1", "filler cat dog")
				.newPart()
				.add("p1d1", "alpha cat dog")
				.add("p1d2", "sun moon rain")
				.build(dir)) {
			List<String> ids = ix.ids("#1(cat dog)", 10);
			assertEquals(2, ids.size(), () -> "got " + ids);
			assertTrue(ids.contains("p0d1") && ids.contains("p1d1"), () -> "got " + ids);
		}
	}
}
