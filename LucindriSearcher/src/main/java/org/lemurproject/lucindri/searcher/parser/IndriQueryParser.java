/*
 * ===============================================================================================
 * Copyright (c) 2020 Carnegie Mellon University and University of Massachusetts. All Rights
 * Reserved.
 *
 * Use of the Lemur Toolkit for Language Modeling and Information Retrieval is subject to the terms
 * of the software license set forth in the LICENSE file included with this software, and also
 * available at http://www.lemurproject.org/license.html
 *
 * ================================================================================================
 */
package org.lemurproject.lucindri.searcher.parser;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndriAndQuery;
import org.apache.lucene.search.Query;
import org.lemurproject.lucindri.analyzer.EnglishAnalyzerConfigurable;
import org.lemurproject.lucindri.searcher.IndriBandQuery;
import org.lemurproject.lucindri.searcher.IndriFilterRejectQuery;
import org.lemurproject.lucindri.searcher.IndriFilterRequireQuery;
import org.lemurproject.lucindri.searcher.IndriMaxQuery;
import org.lemurproject.lucindri.searcher.IndriNearQuery;
import org.lemurproject.lucindri.searcher.IndriNotQuery;
import org.lemurproject.lucindri.searcher.IndriOrQuery;
import org.lemurproject.lucindri.searcher.IndriSynonymQuery;
import org.lemurproject.lucindri.searcher.IndriTermQuery;
import org.lemurproject.lucindri.searcher.IndriWeightedSumQuery;
import org.lemurproject.lucindri.searcher.IndriWindowQuery;
import org.lemurproject.lucindri.searcher.domain.QueryParserOperatorQuery;
import org.lemurproject.lucindri.searcher.domain.QueryParserQuery;
import org.lemurproject.lucindri.searcher.domain.QueryParserTermQuery;

public class IndriQueryParser {

	private final static String AND = "and";
	private final static String BAND = "band";
	private final static String NEAR = "near";
	private final static String OR = "or";
	private final static String WAND = "wand";
	private final static String WEIGHT = "weight";
	private final static String WINDOW = "window";
	private final static String UNORDER_WINDOW = "uw";
	private final static String WSUM = "wsum";
	private final static String MAX = "max";
	private final static String COMBINE = "combine";
	private final static String SCOREIF = "scoreif";
	private final static String SCOREIFNOT = "scoreifnot";
	private final static String SYNONYM = "syn";
	private final static String NOT = "not";
	/** #token(...) is a verbatim SPLICE, not a scoring operator — it must never reach createOperator. (TASK-0016) */
	private final static String TOKEN_SPLICE = "token";
	private final static String DEFAULT_FIELD_NAME = "fulltext";

	/**
	 * The operators Lucindri actually implements and has passing tests for, keyed by their NORMALIZED
	 * name (surface {@code #N}/{@code #odN} normalize to {@code near}; {@code #uwN} to {@code window}).
	 * Any operator not in this set is rejected with a clear error rather than silently degrading to
	 * {@code #and} — see {@link #createOperator}. (TASK-0014)
	 */
	private final static Set<String> KNOWN_OPERATORS = Set.of(AND, COMBINE, WEIGHT, WAND, WSUM, OR, NOT,
			MAX, SYNONYM, BAND, NEAR, WINDOW, SCOREIF, SCOREIFNOT);

	/** Upper bound on a proximity window distance (guards int overflow and pathological inputs). (TASK-0015) */
	private final static int MAX_WINDOW_DISTANCE = 1_000_000;
	/** Cap on query-tree nesting depth, so deeply nested queries error instead of StackOverflow. (TASK-0015) */
	private final static int MAX_PARSE_DEPTH = 128;

	private final Analyzer analyzer;
	private String defaultField;

	public IndriQueryParser() throws IOException {
		this(DEFAULT_FIELD_NAME);
	}

	public IndriQueryParser(String field) throws IOException {
		// Historical default analysis: kstem, stopwords removed, lowercased.
		this(field, "kstem", true, true);
	}

	/**
	 * @param field           default query field
	 * @param stemmer         "kstem"/"krovetz", "porter", or "none" — must match the index's stemmer
	 * @param removeStopwords whether to drop the English stop set from queries (match the index)
	 * @param ignoreCase      whether to lowercase query terms (match the index)
	 */
	public IndriQueryParser(String field, String stemmer, boolean removeStopwords, boolean ignoreCase)
			throws IOException {
		analyzer = getConfigurableAnalyzer(stemmer, removeStopwords, ignoreCase);
		defaultField = field;
	}

	private String getDefaultField(IndexReader reader) throws IOException {
		List<String> fields = new ArrayList<String>();
		Document doc = reader.document(1);
		for (IndexableField field : doc.getFields()) {
			String fieldName = field.name().toLowerCase();
			if (!fieldName.contains("id")) {
				fields.add(fieldName);
			}
		}
		String defaultFieldName = null;
		if (fields.contains(DEFAULT_FIELD_NAME)) {
			defaultFieldName = DEFAULT_FIELD_NAME;
		} else if (fields.size() > 0) {
			defaultFieldName = fields.get(0);
		}
		return defaultFieldName;
	}

	/**
	 * Builds the query-time analyzer. Mirrors the index-time mapping in the indexer's
	 * {@code ConfigurableAnalyzerFactory} so a query can be analyzed exactly as its index was:
	 * "kstem"/"krovetz" -> KSTEM, "porter" -> PORTER, anything else (incl. "none") -> NONE.
	 */
	private Analyzer getConfigurableAnalyzer(String stemmer, boolean removeStopwords, boolean ignoreCase) {
		EnglishAnalyzerConfigurable an = new EnglishAnalyzerConfigurable();
		an.setLowercase(ignoreCase);
		an.setStopwordRemoval(removeStopwords);
		EnglishAnalyzerConfigurable.StemmerType stemmerType = EnglishAnalyzerConfigurable.StemmerType.NONE;
		if (stemmer != null && (stemmer.equalsIgnoreCase("kstem") || stemmer.equalsIgnoreCase("krovetz"))) {
			stemmerType = EnglishAnalyzerConfigurable.StemmerType.KSTEM;
		} else if (stemmer != null && stemmer.equalsIgnoreCase("porter")) {
			stemmerType = EnglishAnalyzerConfigurable.StemmerType.PORTER;
		}
		an.setStemmer(stemmerType);
		return an;
	}

	/**
	 * Given {@code s.charAt(start)=='"'}, returns the index of the literal's closing quote. Escapes:
	 * only {@code \"} and {@code \\} are legal. Errors: unterminated literal, unknown escape. (TASK-0016)
	 */
	private static int endOfLiteral(String s, int start) {
		for (int i = start + 1; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\') {
				if (i + 1 >= s.length() || (s.charAt(i + 1) != '"' && s.charAt(i + 1) != '\\')) {
					syntaxError("unknown escape in string literal (only \\\" and \\\\ are supported)");
				}
				i++; // skip the escaped character
			} else if (c == '"') {
				return i;
			}
		}
		syntaxError("unterminated string literal");
		throw new AssertionError("unreachable"); // syntaxError always throws
	}

	/** Unescape the CONTENTS of a literal (between the quotes). Assumes {@link #endOfLiteral} validated it. */
	private static String unescapeLiteral(String contents) {
		StringBuilder sb = new StringBuilder(contents.length());
		for (int i = 0; i < contents.length(); i++) {
			char c = contents.charAt(i);
			if (c == '\\') {
				i++;
				c = contents.charAt(i);
			}
			sb.append(c);
		}
		return sb.toString();
	}

	/** Counts '(' and ')' OUTSIDE string literals; validates every literal en route. (TASK-0016) */
	private static int[] countParens(String s) {
		int open = 0, close = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '"') {
				i = endOfLiteral(s, i);
			} else if (c == '(') {
				open++;
			} else if (c == ')') {
				close++;
			}
		}
		return new int[] { open, close };
	}

	/** Index of the ')' balancing the left-most '(' outside literals; -1 if none. (TASK-0016) */
	private static int indexOfBalancingParen(String s) {
		int depth = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '"') {
				i = endOfLiteral(s, i);
			} else if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	/** Index of the first '(' outside literals; -1 if none. (TASK-0016) */
	private static int indexOfFirstParen(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '"') {
				i = endOfLiteral(s, i);
			} else if (c == '(') {
				return i;
			}
		}
		return -1;
	}

	/** True iff the next operand is a {@code #token(...)} splice (case-insensitive). (TASK-0016) */
	private static boolean isTokenSplice(String s) {
		int paren = s.indexOf('(');
		if (paren < 0) {
			return false;
		}
		String name = s.substring(0, paren).trim().toLowerCase().replace("#", "");
		return name.equals(TOKEN_SPLICE);
	}

	private QueryParserOperatorQuery createOperator(String operatorName, Occur occur) {
		QueryParserOperatorQuery operatorQuery = new QueryParserOperatorQuery();

		int operatorDistance = 0;
		String operatorNameLowerCase = (new String(operatorName)).toLowerCase();
		operatorNameLowerCase = operatorNameLowerCase.replace("#", "");
		// '~' is no longer an operator prefix (TASK-0016): the only prefix is '#'.

		// Translate indri syntax for near and unordered window. #N and Indri's canonical #odN spelling
		// are the same ordered window (near); #uwN is the unordered window. (TASK-0014 aliases #odN.)
		if (operatorNameLowerCase.matches("\\d+")) {
			operatorNameLowerCase = String.join("/", NEAR, operatorNameLowerCase);
		} else if (operatorNameLowerCase.matches("od\\d+")) {
			operatorNameLowerCase = String.join("/", NEAR, operatorNameLowerCase.substring(2));
		} else if (operatorNameLowerCase.matches("uw\\d+")) {
			operatorNameLowerCase = String.join("/", WINDOW, operatorNameLowerCase.substring(2));
		} else if (operatorNameLowerCase.equals("od") || operatorNameLowerCase.equals(UNORDER_WINDOW)) {
			// #od / #uw with no window size. (Previously #uw split to an empty array and threw
			// ArrayIndexOutOfBounds; now a clear error.) (TASK-0015)
			syntaxError("proximity operator #" + operatorNameLowerCase + " requires a window size, e.g. #"
					+ operatorNameLowerCase + "5");
		}

		// Remove the distance argument to proximity operators.
		if (operatorNameLowerCase.startsWith(NEAR) || operatorNameLowerCase.startsWith(WINDOW)) {
			String[] substrings = operatorNameLowerCase.split("/", 2);

			if (substrings.length < 2) {
				syntaxError("Missing distance argument for #near or #window");
			}

			operatorNameLowerCase = substrings[0];
			operatorDistance = parseWindowDistance(substrings[1]);
		}

		// Reject any operator Lucindri does not implement, rather than silently degrading it to #and.
		// A user porting Indri queries must never get the wrong operator run without warning. (TASK-0014)
		if (!KNOWN_OPERATORS.contains(operatorNameLowerCase)) {
			syntaxError("Unknown or unsupported operator: #" + operatorNameLowerCase
					+ ". Lucindri implements: #combine/#and, #weight, #wand, #wsum, #or, #not, #max, "
					+ "#N (= #odN), #uwN, #syn, #band, #scoreif, #scoreifnot.");
		}
		operatorQuery.setOperator(operatorNameLowerCase);
		operatorQuery.setField(defaultField);
		operatorQuery.setDistance(operatorDistance);
		operatorQuery.setOccur(occur);

		return operatorQuery;
	}

	/** Parse and validate a proximity window distance; reject non-numeric or out-of-range. (TASK-0015) */
	private int parseWindowDistance(String s) {
		if (!s.matches("\\d{1,7}")) {
			syntaxError("invalid window distance '" + s + "'");
		}
		int distance = Integer.parseInt(s);
		if (distance < 1 || distance > MAX_WINDOW_DISTANCE) {
			syntaxError("window distance out of range (1.." + MAX_WINDOW_DISTANCE + "): " + distance);
		}
		return distance;
	}

	private class PopWeight {
		private Float weight;
		private String queryString;

		public Float getWeight() {
			return weight;
		}

		public void setWeight(Float weight) {
			this.weight = weight;
		}

		public String getQueryString() {
			return queryString;
		}

		public void setQueryString(String queryString) {
			this.queryString = queryString;
		}
	}

	/**
	 * Remove a weight from an argument string. Return the weight and the modified
	 * argument string.
	 * 
	 * @param String A partial query argument string, e.g., "3.0 fu 2.0 bar".
	 * @return PopData<String,String> The weight string and the modified argString
	 *         (e.g., "3.0" and "fu 2.0 bar".
	 */
	private PopWeight popWeight(String argString, Float weight) {

		String[] substrings = argString.split("[ \t]+", 2);

		if (substrings.length < 2) {
			syntaxError("Missing weight or query argument");
		}

		Float value;
		try {
			value = Float.valueOf(substrings[0]);
		} catch (NumberFormatException e) {
			// A weighted operator (#weight/#wand/#wsum) expects "weight operand" pairs; a non-numeric
			// weight is a malformed query, not a crash. (TASK-0015)
			syntaxError("invalid weight '" + substrings[0] + "' (expected a number)");
			throw new AssertionError("unreachable"); // syntaxError always throws
		}
		if (value.isNaN() || value.isInfinite()) {
			syntaxError("invalid weight '" + substrings[0] + "'");
		}

		PopWeight popWeight = new PopWeight();
		popWeight.setWeight(value);
		popWeight.setQueryString(substrings[1]);

		return popWeight;
	}

	/**
	 * Remove a subQuery from an argument string. Return the subquery and the
	 * modified argument string.
	 * 
	 * @param String A partial query argument string, e.g., "#and(a b) c d".
	 * @return PopData<String,String> The subquery string and the modified argString
	 *         (e.g., "#and(a b)" and "c d".
	 */
	private String popSubquery(String argString, QueryParserOperatorQuery queryTree, Float weight, Occur occur,
			int depth) {

		int i = indexOfBalancingParen(argString);

		if (i < 0) {
			// A subquery whose parentheses never balance. The top-level check normally catches this;
			// guard here too so we never fall into substring() out of bounds. (TASK-0015)
			syntaxError("unbalanced parentheses in subquery");
		}

		String subquery = argString.substring(0, i + 1);
		queryTree.addSubquery(parseQueryString(subquery, occur, depth + 1), weight);

		argString = argString.substring(i + 1);

		return argString;
	}

	/**
	 * Pops one {@code "..."} literal and splices its analyzed tokens into {@code queryTree}. ALL query
	 * text is quoted: an unquoted chunk here is a syntax error (bare terms were removed — they were the
	 * catch-all that turned typos into queries). The whole literal goes to the index analyzer, so query
	 * tokens equal index tokens by construction; 0..n term nodes come out. (TASK-0016)
	 */
	private String popLiteral(String argString, QueryParserOperatorQuery queryTree, Float weight, Occur occur) {
		if (argString.charAt(0) != '"') {
			String chunk = argString.split("[ \t\n\r]+", 2)[0];
			syntaxError("unquoted text '" + chunk + "' — query text must be quoted, e.g. #combine(\"dog cat\")");
		}
		int end = endOfLiteral(argString, 0);
		if (end + 1 < argString.length() && !Character.isWhitespace(argString.charAt(end + 1))) {
			syntaxError("unexpected character after closing quote (a literal must be its own chunk)");
		}
		String rawText = unescapeLiteral(argString.substring(1, end));
		String remainder = argString.substring(end + 1);

		for (String t : tokenizeString(analyzer, rawText)) {
			QueryParserTermQuery termQuery = new QueryParserTermQuery();
			termQuery.setTerm(t);
			termQuery.setField(defaultField);
			termQuery.setOccur(occur);
			queryTree.addSubquery(termQuery, weight);
		}
		return remainder.trim();
	}

	/**
	 * {@code #token("a" "b" ...)}: whitespace-separated QUOTED tokens, each a vocabulary entry used
	 * AS-IS — no tokenization, stemming, stopping, or lowercasing. Unquoted contents are a syntax error
	 * (the same all-text-is-quoted rule as everywhere else). OOV entries flow through the existing
	 * floored-background path (IndriMissingTermScorer), like any other absent term. (TASK-0016)
	 */
	private String popTokenSplice(String argString, QueryParserOperatorQuery queryTree, Float weight,
			Occur occur) {
		int close = indexOfBalancingParen(argString);
		if (close < 0) {
			syntaxError("unbalanced parentheses in #token");
		}
		String contents = argString.substring(argString.indexOf('(') + 1, close).trim();
		if (contents.isEmpty()) {
			syntaxError("#token has no operands");
		}
		while (!contents.isEmpty()) {
			if (contents.charAt(0) != '"') {
				String chunk = contents.split("[ \t\n\r]+", 2)[0];
				syntaxError("unquoted token '" + chunk + "' in #token — tokens must be quoted, e.g. #token(\"u.s.a\")");
			}
			int end = endOfLiteral(contents, 0);
			if (end + 1 < contents.length() && !Character.isWhitespace(contents.charAt(end + 1))) {
				syntaxError("unexpected character after closing quote in #token");
			}
			String term = unescapeLiteral(contents.substring(1, end));
			contents = contents.substring(end + 1).trim();
			if (term.isEmpty()) {
				// #token("") — a verbatim EMPTY token can never exist in the vocabulary; unlike an
				// analyzed empty splice (legal), this is necessarily a mistake. Loud.
				syntaxError("#token: empty term");
			}
			QueryParserTermQuery termQuery = new QueryParserTermQuery();
			termQuery.setTerm(term);
			termQuery.setField(defaultField);
			termQuery.setOccur(occur);
			queryTree.addSubquery(termQuery, weight);
		}
		return argString.substring(close + 1);
	}

	private QueryParserQuery parseQueryString(String queryString, Occur occur, int depth) {
		// Guard recursion depth so a deeply nested query errors cleanly instead of StackOverflow. depth is a
		// method-local threaded through the recursion (NOT a shared instance field), so one parser instance
		// is safe for concurrent parseQuery/queryTerms calls. (TASK-0015 guard; TASK-0019 thread-safety)
		if (depth > MAX_PARSE_DEPTH) {
			syntaxError("query nesting too deep (limit " + MAX_PARSE_DEPTH + ")");
		}
		return parseQueryStringInner(queryString, occur, depth);
	}

	private QueryParserQuery parseQueryStringInner(String queryString, Occur occur, int depth) {
		// Create the query tree
		// This simple parser is sensitive to parenthesis placement, so check for basic errors first.
		queryString = queryString.trim();

		// Parens must be balanced (counted OUTSIDE literals; countParens also validates every literal).
		// If any structural parens are present, the query must be a single #operator enclosing everything
		// (its balancing ')' is the last character). Zero parens is legal (a whole-query "..." literal or
		// #token defaults to #combine). (TASK-0015 hardening; TASK-0016 literal-awareness.)
		int[] parens = countParens(queryString);
		if (parens[0] != parens[1]) {
			syntaxError("unbalanced parentheses (" + parens[0] + " '(' vs " + parens[1] + " ')')");
		}
		if (parens[0] > 0) {
			if (queryString.charAt(0) != '#') {
				// e.g. dog(cat) — parens require a #operator; must NOT silently become analyzed text.
				// ('~' is no longer an operator prefix; ~foo(...) errors here.) (TASK-0016)
				syntaxError("parentheses require a #operator (use \"...\" to include parens as text)");
			}
			if (indexOfBalancingParen(queryString) != (queryString.length() - 1)) {
				syntaxError("misplaced parentheses: an operator must enclose its entire (sub)query");
			}
		}

		// Leading operator: only when the query starts with # AND has a structural paren AND is not a
		// #token splice (a splice is an operand, not an operator — a whole-query #token(...) is treated
		// like a term query: wrapped in the implicit #combine and popped by the operand loop).
		int firstParen = indexOfFirstParen(queryString);
		String queryOperator = AND;
		// ORDER MATTERS: firstParen > 0 must be tested FIRST — it implies queryString is non-empty, so the
		// charAt(0) calls are safe. parseQuery("") is reachable (only null is guarded) and must keep
		// yielding an empty AND tree, not StringIndexOutOfBounds. (TASK-0016 plan review)
		boolean hasOperator = firstParen > 0 && queryString.charAt(0) == '#' && !isTokenSplice(queryString);
		if (hasOperator) {
			queryOperator = queryString.substring(0, firstParen).trim();
		}
		QueryParserOperatorQuery queryTree = createOperator(queryOperator, occur);
		if (queryOperator.endsWith(SCOREIF)) {
			occur = Occur.MUST;
		} else if (queryOperator.endsWith(SCOREIFNOT)) {
			occur = Occur.MUST_NOT;
		}

		if (hasOperator) {
			// Balancing ')' is enforced above to be the LAST character — strip the operator and that paren
			// positionally (the old lastIndexOf(")") could bite a ')' inside a trailing literal). An operator
			// with no operands (#combine()) is malformed; one emptied only by analysis (#combine("the a")) is
			// legal and matches nothing, like Indri. (TASK-0015/0016)
			queryString = queryString.substring(firstParen + 1, queryString.length() - 1).trim();
			if (queryString.isEmpty()) {
				syntaxError("operator " + queryOperator + " has no operands");
			}
		}

		// Each pass below handles one argument to the query operator.
		// Note: An argument can be a token that produces multiple terms
		// (e.g., "near-death") or a subquery (e.g., "#and (a b c)").
		// Recurse on subqueries.

		while (queryString.length() > 0) {

			// If the operator uses weighted query arguments, each pass of
			// this loop must handle "weight arg". Handle the weight first.

			Float weight = null;
			if ((queryTree.getOperator().equals(WEIGHT)) || (queryTree.getOperator().equals(WAND))
					|| queryTree.getOperator().equals(WSUM)) {
				PopWeight popWeight = popWeight(queryString, weight);
				weight = popWeight.getWeight();
				queryString = popWeight.getQueryString();
			}

			// Now handle the argument: a #operator subquery, a #token(...) verbatim splice, or a "..."
			// literal. Anything unquoted (bare terms, stray '~', an operator missing its '#') errors
			// inside popLiteral — the quote-only grammar (TASK-0016).
			if (queryString.charAt(0) == '#') {
				if (isTokenSplice(queryString)) {
					queryString = popTokenSplice(queryString, queryTree, weight, occur).trim();
				} else {
					queryString = popSubquery(queryString, queryTree, weight, occur, depth).trim();
				}
				occur = Occur.SHOULD;
			} else { // must be a "..." literal — anything unquoted is rejected inside popLiteral
				queryString = popLiteral(queryString, queryTree, weight, occur);
				occur = Occur.SHOULD;
			}
		}

		return queryTree;
	}

	public Query parseQuery(String queryString) {
		// The index analyzer is the single tokenization authority (TASK-0016): no pre-rewrites. Query
		// text enters only through "..." literals (analyzed) or #token("...") (verbatim); the four old
		// replaces ('/"/+/:) and popTerm's dot-split were the bug that made query tokens != index tokens.
		if (queryString == null) {
			return null;
		}
		QueryParserQuery qry = parseQueryString(queryString, Occur.SHOULD, 1);
		return getLuceneQuery(qry);
	}

	/**
	 * Returns the analyzed content terms of {@code queryString} that a query-biased summary should
	 * highlight: the leaf terms reached under POSITIVE polarity. Terms the query is trying to AVOID are
	 * excluded — polarity flips when descending into a {@code #not(...)} subtree or the CONDITION (first
	 * child) of {@code #scoreifnot(C S)}; a double negation cancels. Because it reuses the same parse the
	 * Lucene query is built from, the terms are exactly the analyzed (stemmed/stopped/lowercased) tokens the
	 * index holds. Duplicates are removed, order preserved. (TASK-0019)
	 */
	public List<String> queryTerms(String queryString) {
		Set<String> terms = new java.util.LinkedHashSet<>();
		if (queryString != null) {
			collectPositiveTerms(parseQueryString(queryString, Occur.SHOULD, 1), true, terms);
		}
		return new ArrayList<>(terms);
	}

	private void collectPositiveTerms(QueryParserQuery node, boolean positive, Set<String> out) {
		if (node instanceof QueryParserTermQuery) {
			if (positive) {
				out.add(((QueryParserTermQuery) node).getTerm());
			}
			return;
		}
		if (!(node instanceof QueryParserOperatorQuery)) {
			return;
		}
		QueryParserOperatorQuery op = (QueryParserOperatorQuery) node;
		List<QueryParserQuery> children = op.getSubqueries();
		if (children == null) {
			return;
		}
		boolean isNot = NOT.equals(op.getOperator());
		boolean isScoreIfNot = SCOREIFNOT.equals(op.getOperator());
		for (int i = 0; i < children.size(); i++) {
			// #not negates all children; #scoreifnot negates only its condition (child 0 — child 1..n are
			// the scored subquery S, which keeps polarity). Everything else preserves polarity, including
			// #scoreif's condition (wanted present) and #syn's alternates / proximity constituents.
			boolean childPositive = positive;
			if (isNot || (isScoreIfNot && i == 0)) {
				childPositive = !positive;
			}
			collectPositiveTerms(children.get(i), childPositive, out);
		}
	}

	public Query parseJsonQueryString(String jsonQueryString) {
		// TODO: json implementation
		return null;
	}

	private Query getLuceneQuery(QueryParserQuery queryTree) {
		BooleanClause clause = createBooleanClause(queryTree);
		Query query = null;
		if (clause != null) {
			query = clause.getQuery();
		}
		return query;
	}

	public BooleanClause createBooleanClause(QueryParserQuery queryTree) {
		Query query = null;
		if (queryTree instanceof QueryParserOperatorQuery) {
			QueryParserOperatorQuery operatorQuery = (QueryParserOperatorQuery) queryTree;

			// Create clauses for subqueries
			List<BooleanClause> clauses = new ArrayList<>();
			if (operatorQuery.getSubqueries() != null) {
				for (QueryParserQuery subquery : operatorQuery.getSubqueries()) {
					BooleanClause clause = createBooleanClause(subquery);
					if (clause != null) {
						clauses.add(clause);
					}
				}

				// Create Operator
				if (operatorQuery.getOperator().equalsIgnoreCase(OR)) {
					query = new IndriOrQuery(clauses);
				} else if (operatorQuery.getOperator().equalsIgnoreCase(WSUM)) {
					query = new IndriWeightedSumQuery(clauses);
				} else if (operatorQuery.getOperator().equalsIgnoreCase(MAX)) {
					query = new IndriMaxQuery(clauses);
				} else if (operatorQuery.getOperator().equalsIgnoreCase(WAND)) {
					query = new IndriAndQuery(clauses);
				} else if (operatorQuery.getOperator().equalsIgnoreCase(NEAR)) {
					if (clauses.size() > 1) {
						query = new IndriNearQuery(clauses, operatorQuery.getField(), operatorQuery.getDistance());
					} else if (clauses.size() == 1) {
						// A window of a single operand is just that operand (e.g. #1(the house) once the
						// stopword "the" is dropped). Matches Indri (#1(x) == x). Return the child directly
						// rather than a 1-clause window (which scored to null and silently vanished), so an
						// enclosing weight still propagates. (TASK-0014)
						query = clauses.get(0).getQuery();
					}
				} else if (operatorQuery.getOperator().equalsIgnoreCase(WINDOW)) {
					if (clauses.size() > 1) {
						query = new IndriWindowQuery(clauses, operatorQuery.getField(), operatorQuery.getDistance());
					} else if (clauses.size() == 1) {
						query = clauses.get(0).getQuery();
					}
				} else if (operatorQuery.getOperator().equalsIgnoreCase(BAND)) {
					query = new IndriBandQuery(clauses, operatorQuery.getField());
				} else if (operatorQuery.getOperator().equalsIgnoreCase(SYNONYM)) {
					query = new IndriSynonymQuery(clauses, operatorQuery.getField());
				} else if (operatorQuery.getOperator().equalsIgnoreCase(NOT)) {
					query = new IndriNotQuery(clauses);
				} else if (operatorQuery.getOperator().equalsIgnoreCase(SCOREIF)
						|| operatorQuery.getOperator().equalsIgnoreCase(SCOREIFNOT)) {
					// #scoreif/#scoreifnot: clause 0 is the filter, the rest are the scored subquery.
					// Emit a native Indri filter query so it composes with Indri scoring (stats +
					// smoothing). Rebuild as scoring clauses [filter, scored]; combine multiple scored
					// operands with IndriAndQuery. (TASK-0006)
					if (clauses.size() >= 2) {
						Query scoredQuery;
						if (clauses.size() == 2) {
							scoredQuery = clauses.get(1).getQuery();
						} else {
							List<BooleanClause> scoredClauses = new ArrayList<>();
							for (int i = 1; i < clauses.size(); i++) {
								scoredClauses.add(clauses.get(i));
							}
							scoredQuery = new IndriAndQuery(scoredClauses);
						}
						List<BooleanClause> filterClauses = new ArrayList<>();
						filterClauses.add(new BooleanClause(clauses.get(0).getQuery(), Occur.SHOULD));
						filterClauses.add(new BooleanClause(scoredQuery, Occur.SHOULD));
						if (operatorQuery.getOperator().equalsIgnoreCase(SCOREIF)) {
							query = new IndriFilterRequireQuery(filterClauses);
						} else {
							query = new IndriFilterRejectQuery(filterClauses);
						}
					} else {
						query = new IndriAndQuery(clauses);
					}
					} else if (clauses.size() == 1) {
						// #combine(X)/#weight(w X)/#and(X) with a single operand is just X. Do NOT wrap it
						// in a single-child IndriAndQuery: the stock IndriAndWeight single-scorer shortcut
						// returns that child built with boost 1.0f, dropping a weight assigned by an
						// enclosing #weight/#wsum. (TASK-0010 Phase 5)
						query = clauses.get(0).getQuery();
					} else {
						query = new IndriAndQuery(clauses);
					}
			}
		} else if (queryTree instanceof QueryParserTermQuery) {
			// Create term query
			QueryParserTermQuery termQuery = (QueryParserTermQuery) queryTree;
			// System.out.println(jsonQuery);
			String field = "all";
			if (termQuery.getField() != null) {
				field = termQuery.getField();
			}
			query = new IndriTermQuery(new Term(field, termQuery.getTerm()));
		}
		if (queryTree.getBoost() != null && query != null) {
			query = new BoostQuery(query, queryTree.getBoost().floatValue());
		}
		BooleanClause clause = null;
		if (query != null) {
			clause = new BooleanClause(query, queryTree.getOccur());
		}
		return clause;
	}

	/**
	 * Given part of a query string, returns an array of terms with stopwords
	 * removed and the terms stemmed using the Krovetz stemmer. Use this method to
	 * process raw query terms.
	 * 
	 * @param query String containing query.
	 * @return Array of query tokens
	 * @throws IOException Error accessing the Lucene index.
	 */
	public static List<String> tokenizeString(Analyzer analyzer, String string) {
		List<String> tokens = new ArrayList<>();
		try (TokenStream tokenStream = analyzer.tokenStream(null, new StringReader(string))) {
			tokenStream.reset(); // required
			while (tokenStream.incrementToken()) {
				tokens.add(tokenStream.getAttribute(CharTermAttribute.class).toString());
			}
		} catch (IOException e) {
			new RuntimeException(e); // Shouldn't happen...
		}
		return tokens;
	}

	/**
	 * Throw an error specialized for query parsing syntax errors.
	 * 
	 * @param errorString The string "Syntax
	 * @throws IllegalArgumentException The query contained a syntax error
	 */
	static private void syntaxError(String errorString) throws QueryParseException {
		throw new QueryParseException("Syntax Error: " + errorString);
	}

}
