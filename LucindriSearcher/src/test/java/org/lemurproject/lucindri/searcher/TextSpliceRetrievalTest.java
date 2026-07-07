package org.lemurproject.lucindri.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.searcher.parser.QueryParseException;
import org.lemurproject.lucindri.searcher.testutil.TestIndex;

/**
 * TASK-0016: the index analyzer is the single tokenization authority — the parser must not front-run
 * it. All query text is quoted ("..." literals); {@code #token("...")} is the verbatim escape hatch;
 * bare (unquoted) terms and field syntax are gone.
 */
public class TextSpliceRetrievalTest {

	// The TASK-0016 invariant: the same surface text, indexed and then queried, must match.
	@Test
	public void surfaceTextRoundTrips(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder()
				.add("d1", "the U.S.A. said google.com is 3.14 great")
				.add("d2", "cat dog")
				.build(dir)) {
			assertEquals(List.of("d1"), ix.ids("#combine(\"U.S.A.\")", 10));      // one literal
			assertEquals(List.of("d1"), ix.ids("#combine(\"google.com\")", 10));
			assertEquals(List.of("d1"), ix.ids("#combine(\"3.14\")", 10));
			assertEquals(List.of("d1"), ix.ids("#combine(\"said.\" \"great\")", 10)); // literal list
		}
	}

	// #token: verbatim vocabulary hit; surface form is NOT re-analyzed.
	@Test
	public void tokenSpliceIsVerbatim(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder()
				.add("d1", "the U.S.A. said")     // indexes token u.s.a
				.add("d2", "cat dog").build(dir)) {
			assertEquals(List.of("d1"), ix.ids("#combine(#token(\"u.s.a\"))", 10));
			assertTrue(ix.ids("#combine(#token(\"U.S.A.\"))", 10).isEmpty(),
					"verbatim is not analyzed: surface form is OOV");
		}
	}

	// A literal inside a proximity op is a phrase of its analyzed tokens.
	@Test
	public void literalInsideOrderedWindowIsAPhrase(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder()
				.add("p1", "hi tech gadget")
				.add("p2", "tech hi gadget").build(dir)) {
			assertEquals(List.of("p1"), ix.ids("#1(\"hi-tech\")", 10)); // hi-tech -> [hi][tech]
		}
	}

	// All-stopword literal is a legal empty splice: behaves as if absent.
	@Test
	public void emptySpliceIsLegal(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder().add("d1", "cat dog").build(dir)) {
			assertEquals(ix.ids("#combine(\"cat\")", 10), ix.ids("#combine(\"the of a\" \"cat\")", 10));
			assertTrue(ix.ids("#combine(\"the of a\")", 10).isEmpty());
		}
	}

	// Legacy pin: field syntax is DEAD — unquoted dog.title is a syntax error (all text is quoted);
	// quoted "dog.title" is analyzed text (one OOV-ish token), never a field restriction.
	@Test
	public void fieldSyntaxIsDead(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder().add("d1", "cat dog").build(dir)) {
			assertThrows(QueryParseException.class, () -> ix.run("#combine(dog.title)", 10));
			assertTrue(ix.ids("#combine(\"dog.title\")", 10).isEmpty(), "text, not field syntax");
		}
	}

	// Whole-query forms: a literal (or literal list, or #token) alone gets the implicit #combine.
	@Test
	public void wholeQueryLiteralAndTokenSplice(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder().add("d1", "cat dog").build(dir)) {
			assertEquals(List.of("d1"), ix.ids("\"cat dog\"", 10));
			assertEquals(List.of("d1"), ix.ids("#token(\"cat\")", 10));
		}
	}
}
