package org.lemurproject.lucindri.searcher.parser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Parser-level tests for the TASK-0016 quote-only grammar: all query text is quoted ("..." literals,
 * analyzer-tokenized), {@code #token("...")} is the verbatim splice, bare/unquoted text and field
 * syntax are rejected, and {@code ~} is no longer an operator prefix.
 */
public class TextSpliceParsingTest {

	// Bare chunks are GONE: any unquoted text is a loud syntax error — the silent-typo catch-all is closed.
	@Test
	public void unquotedTextIsRejected() throws Exception {
		IndriQueryParser p = new IndriQueryParser();
		assertThrows(QueryParseException.class, () -> p.parseQuery("#combine(cat dog)"));       // legacy Indri form
		assertThrows(QueryParseException.class, () -> p.parseQuery("dog cat"));                 // bare top level
		assertThrows(QueryParseException.class, () -> p.parseQuery("#combine(dog.title)"));     // legacy field syntax
		assertThrows(QueryParseException.class, () -> p.parseQuery("#combine(~syn(\"a\" \"b\") \"cat\")")); // stray ~op
		assertThrows(QueryParseException.class, () -> p.parseQuery("#weight(2.0 dog)"));        // unquoted operand
		p.parseQuery("\"dog cat\"");                 // the quoted forms parse fine
		p.parseQuery("#combine(\"cat\" \"dog\")");
	}

	// Analysis applies inside literals: stem + lowercase + stop.
	@Test
	public void literalIsAnalyzed() throws Exception {
		String q = new IndriQueryParser().parseQuery("#combine(\"The DOGS\")").toString();
		assertTrue(q.contains("fulltext:dog"), q);
		assertFalse(q.contains("fulltext:the"), q);
	}

	// Escapes: \" and \\ decode; anything else is an error.
	@Test
	public void escapes() throws Exception {
		IndriQueryParser p = new IndriQueryParser();
		p.parseQuery("#combine(\"say \\\"hi\\\"\")");                       // parses
		p.parseQuery("#combine(\"back\\\\slash\")");                        // parses
		assertThrows(QueryParseException.class, () -> p.parseQuery("#combine(\"a\\x\")"));
		assertThrows(QueryParseException.class, () -> p.parseQuery("#combine(\"unterminated)"));
		assertThrows(QueryParseException.class, () -> p.parseQuery("#combine(say\"hi\")"));   // unquoted chunk
		assertThrows(QueryParseException.class, () -> p.parseQuery("#combine(\"a\"b)"));      // trailing garbage
	}

	// Parens inside literals are text; outside a #operator they are errors.
	@Test
	public void parensInLiteralsAreText() throws Exception {
		IndriQueryParser p = new IndriQueryParser();
		p.parseQuery("#combine(\"a smiley :) here\")");                     // parses
		assertThrows(QueryParseException.class, () -> p.parseQuery("dog(cat)"));
	}

	// #token is verbatim (case preserved, no stemming) and must not be lowercased.
	@Test
	public void tokenIsVerbatimInTree() throws Exception {
		String q = new IndriQueryParser().parseQuery("#combine(#token(\"U.S.A.\" \"dogs\"))").toString();
		assertTrue(q.contains("fulltext:U.S.A."), q);
		assertTrue(q.contains("fulltext:dogs"), q);   // NOT stemmed to dog
	}

	@Test
	public void tokenErrors() throws Exception {
		IndriQueryParser p = new IndriQueryParser();
		assertThrows(QueryParseException.class, () -> p.parseQuery("#combine(#token())"));
		assertThrows(QueryParseException.class, () -> p.parseQuery("#combine(#token(\"\"))"));
		assertThrows(QueryParseException.class, () -> p.parseQuery("#combine(#token(cat))")); // unquoted
	}

	// Empty / whitespace-only input parses to an empty tree — no error, no crash.
	@Test
	public void emptyQueryStillParses() throws Exception {
		IndriQueryParser p = new IndriQueryParser();
		p.parseQuery("");     // must not throw
		p.parseQuery("   ");  // trims to empty; must not throw
	}

	// ~ is no longer an operator prefix — with bare chunks gone, EVERY ~ use errors loudly.
	@Test
	public void tildeIsNotAnOperatorPrefix() throws Exception {
		IndriQueryParser p = new IndriQueryParser();
		assertThrows(QueryParseException.class, () -> p.parseQuery("~combine(\"cat\" \"dog\")"));
		assertThrows(QueryParseException.class, () -> p.parseQuery("#combine(~cat \"dog\")"));
	}

	// #weight: a multi-token splice copies the weight to EACH spliced token. BoostQuery renders (fulltext:t)^w.
	@Test
	public void weightIsCopiedPerSplicedToken() throws Exception {
		String q = new IndriQueryParser().parseQuery("#weight(2.0 \"cat dog\" 1.0 \"tree\")").toString();
		assertTrue(q.contains("(fulltext:cat)^2.0"), q);
		assertTrue(q.contains("(fulltext:dog)^2.0"), q);
		assertTrue(q.contains("(fulltext:tree)^1.0"), q);
	}
}
