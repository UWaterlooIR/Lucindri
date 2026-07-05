package org.lemurproject.lucindri.searcher.parser;

/**
 * Thrown by {@link IndriQueryParser} when a query string is malformed and cannot be parsed (unbalanced
 * parentheses, an unknown operator, a missing/invalid window distance, a bad weight, a belief operator
 * used as a proximity operand, too-deep nesting, and so on).
 *
 * <p>It extends {@link IllegalArgumentException} so it stays unchecked and backward-compatible with the
 * parser's existing convention and with callers/tests that assert {@code IllegalArgumentException},
 * while giving callers a <em>specific</em> type they can catch to mean exactly "the query was invalid"
 * without swallowing a genuine internal bug. The parser must never surface a lower-level exception
 * (NPE, ArrayIndexOutOfBounds, NumberFormatException, StackOverflowError, ...) for malformed input —
 * those are defects; malformed input is a {@code QueryParseException}. (TASK-0015)
 */
public class QueryParseException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	/** The offending query text, if supplied; may be {@code null}. */
	private final String query;

	public QueryParseException(String message) {
		this(message, null);
	}

	public QueryParseException(String message, String query) {
		super(query == null ? message : message + " [query: " + query + "]");
		this.query = query;
	}

	/** The offending query text, or {@code null} if not supplied. */
	public String getQuery() {
		return query;
	}
}
