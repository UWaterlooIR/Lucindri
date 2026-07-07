package org.lemurproject.lucindri.searcher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.searcher.parser.QueryParseException;
import org.lemurproject.lucindri.searcher.testutil.TestIndex;

/**
 * Robustness gate (TASK-0015): malformed queries must be rejected with a {@link QueryParseException}
 * (a subtype of {@link IllegalArgumentException}) — never a low-level implementation-leak throwable
 * ({@code ArrayIndexOutOfBounds}, {@code StringIndexOutOfBounds}, {@code NumberFormatException},
 * {@code NullPointerException}, {@code StackOverflowError}). Legal queries must still parse. And a
 * single bad query in a batch must not abort the rest (the pattern {@code IndriSearch} now uses).
 *
 * <p>Each invalid case here crashed with a non-IAE exception (or silently accepted) before the fix, so
 * {@code assertThrows(QueryParseException.class, …)} is a genuine fail-before/pass-after gate:
 * a {@code StackOverflowError}/{@code NumberFormatException} would NOT satisfy it.
 */
public class InvalidQueryRejectionTest {

	private TestIndex ix;

	private TestIndex index(Path dir) throws Exception {
		if (ix == null) {
			ix = TestIndex.builder().add("d1", "cat dog sun").add("d2", "dog moon").build(dir);
		}
		return ix;
	}

	@AfterEach
	public void closeIndex() throws Exception {
		if (ix != null) {
			ix.close();
			ix = null;
		}
	}

	private void assertRejected(TestIndex ix, String query) {
		// Must throw QueryParseException specifically — and therefore NOT any other Throwable type.
		assertThrows(QueryParseException.class, () -> ix.run(query, 10), () -> "should reject: " + query);
	}

	@Test
	public void malformedParenthesesAreRejectedNotCrash(@TempDir Path dir) throws Exception {
		TestIndex ix = index(dir);
		assertRejected(ix, "#combine( cat dog");     // missing close  (was StringIndexOutOfBounds)
		assertRejected(ix, "#combine cat dog )");    // missing open   (was StringIndexOutOfBounds)
		assertRejected(ix, "#combine( \"cat\" )\"dog )\""); // extra close
		assertRejected(ix, "#combine( ( cat dog )"); // extra open
	}

	@Test
	public void malformedProximityIsRejectedNotCrash(@TempDir Path dir) throws Exception {
		TestIndex ix = index(dir);
		assertRejected(ix, "#uw( \"cat dog\" )");         // window, no size (was ArrayIndexOutOfBounds)
		assertRejected(ix, "#od( \"cat dog\" )");         // ordered, no size
		assertRejected(ix, "#9999999999( \"cat dog\" )"); // distance overflows int (was NumberFormatException)
		assertRejected(ix, "#0( \"cat dog\" )");          // zero window distance
	}

	@Test
	public void malformedWeightIsRejectedNotCrash(@TempDir Path dir) throws Exception {
		TestIndex ix = index(dir);
		assertRejected(ix, "#weight( abc \"cat\" )");    // non-numeric weight (was NumberFormatException)
		assertRejected(ix, "#weight( 0.5 \"cat\" 0.3 )"); // dangling weight, no operand
	}

	@Test
	public void emptyOperatorIsRejected(@TempDir Path dir) throws Exception {
		TestIndex ix = index(dir);
		assertRejected(ix, "#combine()");
		assertRejected(ix, "#uw2()");
		assertRejected(ix, "#syn()");
	}

	@Test
	public void deepNestingIsRejectedNotStackOverflow(@TempDir Path dir) throws Exception {
		TestIndex ix = index(dir);
		StringBuilder sb = new StringBuilder();
		int depth = 400; // well past the 128 cap
		for (int i = 0; i < depth; i++) {
			sb.append("#combine(");
		}
		sb.append("cat");
		for (int i = 0; i < depth; i++) {
			sb.append(" )");
		}
		assertRejected(ix, sb.toString());
	}

	// These already rejected cleanly before this task (belief-in-proximity, unknown operators from
	// TASK-0014) — keep them green, and confirm they now surface the specific QueryParseException type.
	@Test
	public void previouslyCleanRejectionsStayRejected(@TempDir Path dir) throws Exception {
		TestIndex ix = index(dir);
		assertRejected(ix, "#1( #combine( \"cat dog\" ) \"sun\" )"); // belief operand inside a proximity operator
		assertRejected(ix, "#uw4( #or( \"cat dog\" ) \"sun\" )");
		assertRejected(ix, "#foo( \"cat dog\" )");               // unknown operator
		assertRejected(ix, "#wsyn( \"cat dog\" )");              // unimplemented Indri operator
	}

	@Test
	public void legalQueriesStillParse(@TempDir Path dir) throws Exception {
		TestIndex ix = index(dir);
		assertDoesNotThrow(() -> ix.run("\"cat dog\"", 10));                 // bare terms -> implicit #combine
		assertDoesNotThrow(() -> ix.run("#combine( \"cat dog\" )", 10));
		assertDoesNotThrow(() -> ix.run("#od2( \"cat dog\" )", 10));
		assertDoesNotThrow(() -> ix.run("#uw3( \"cat dog\" )", 10));
		assertDoesNotThrow(() -> ix.run("#weight( 0.5 \"cat\" 0.5 \"dog\" )", 10));
		// #1(the cat) reduces to #1(cat) once the stopword is dropped -> legal, must NOT be an empty error.
		assertDoesNotThrow(() -> ix.run("#1( \"the cat\" )", 10));
	}

	// The per-query recovery pattern IndriSearch uses: a bad query in a batch is skipped, the rest run.
	@Test
	public void batchContinuesPastAMalformedQuery(@TempDir Path dir) throws Exception {
		TestIndex ix = index(dir);
		String[] batch = { "\"cat\"", "#uw( \"cat dog\" )", "\"dog\"" }; // valid, invalid (no window size), valid
		int completed = 0;
		int withHits = 0;
		for (String q : batch) {
			try {
				List<String> ids = ix.ids(q, 10);
				completed++;
				if (!ids.isEmpty()) {
					withHits++;
				}
			} catch (QueryParseException e) {
				// skip and continue, exactly as IndriSearch does
			}
		}
		assertEquals(2, completed, "both valid queries completed");
		assertTrue(withHits == 2, "both valid queries returned hits");
	}
}
