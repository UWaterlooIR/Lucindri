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
	/** Current recursion depth of {@link #parseQueryString}; reset per {@link #parseQuery}. (TASK-0015) */
	private int parseDepth;

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
	 * Count the number of occurrences of character c in string s.
	 * 
	 * @param c A character.
	 * @param s A string.
	 */
	private static int countChars(String s, char c) {
		int count = 0;

		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == c) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Get the index of the right parenenthesis that balances the left-most
	 * parenthesis. Return -1 if it doesn't exist.
	 * 
	 * @param s A string containing a query.
	 */
	private static int indexOfBalencingParen(String s) {
		int depth = 0;

		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == '(') {
				depth++;
			} else if (s.charAt(i) == ')') {
				depth--;

				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private QueryParserOperatorQuery createOperator(String operatorName, Occur occur) {
		QueryParserOperatorQuery operatorQuery = new QueryParserOperatorQuery();

		int operatorDistance = 0;
		String operatorNameLowerCase = (new String(operatorName)).toLowerCase();
		operatorNameLowerCase = operatorNameLowerCase.replace("#", "");
		operatorNameLowerCase = operatorNameLowerCase.replace("~", "");

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
	private String popSubquery(String argString, QueryParserOperatorQuery queryTree, Float weight, Occur occur) {

		int i = indexOfBalencingParen(argString);

		if (i < 0) {
			// A subquery whose parentheses never balance. The top-level check normally catches this;
			// guard here too so we never fall into substring() out of bounds. (TASK-0015)
			syntaxError("unbalanced parentheses in subquery");
		}

		String subquery = argString.substring(0, i + 1);
		queryTree.addSubquery(parseQueryString(subquery, occur), weight);

		argString = argString.substring(i + 1);

		return argString;
	}

	/**
	 * Remove a term from an argument string. Return the term and the modified
	 * argument string.
	 * 
	 * @param String A partial query argument string, e.g., "a b c d".
	 * @return PopData<String,String> The term string and the modified argString
	 *         (e.g., "a" and "b c d".
	 */
	private String popTerm(String argString, QueryParserOperatorQuery queryTree, Float weight, Occur occur) {
		String[] substrings = argString.split("[ \t\n\r]+", 2);
		String token = substrings[0];

		// Split the token into a term and a field.
		int delimiter = token.indexOf('.');
		String field = null;
		String term = null;

		if (delimiter < 0) {
			field = defaultField;
			term = token;
		} else { // Remove the field from the token
			field = token.substring(delimiter + 1).toLowerCase();
			term = token.substring(0, delimiter);
		}

		List<String> tokens = tokenizeString(analyzer, term);
		for (String t : tokens) {
			// Creat the term query
			QueryParserTermQuery termQuery = new QueryParserTermQuery();
			termQuery.setTerm(t);
			termQuery.setField(field);
			termQuery.setOccur(occur);
			queryTree.addSubquery(termQuery, weight);
		}

		if (substrings.length < 2) { // Is this the last argument?
			argString = "";
		} else {
			argString = substrings[1];
		}

		return argString;
	}

	private QueryParserQuery parseQueryString(String queryString, Occur occur) {
		// Guard recursion depth so a deeply nested query errors cleanly instead of StackOverflow. The
		// finally decrements even on a thrown syntax error, so sibling subqueries do not accumulate depth.
		// (TASK-0015)
		if (++parseDepth > MAX_PARSE_DEPTH) {
			parseDepth--;
			syntaxError("query nesting too deep (limit " + MAX_PARSE_DEPTH + ")");
		}
		try {
			return parseQueryStringInner(queryString, occur);
		} finally {
			parseDepth--;
		}
	}

	private QueryParserQuery parseQueryStringInner(String queryString, Occur occur) {
		// Create the query tree
		// This simple parser is sensitive to parenthesis placement, so check for basic errors first.
		queryString = queryString.trim(); // The last character should be ')'

		// Parentheses must be balanced, and if any are present the leading operator must enclose the whole
		// (sub)query (its balancing ')' is the last character). Zero parens is legal — a bare-term query
		// (e.g. "dog training") defaults to #combine. Previously this validation was disabled, so
		// malformed parentheses fell through to substring()/lastIndexOf() and crashed with
		// StringIndexOutOfBounds. (TASK-0015)
		int openParens = countChars(queryString, '(');
		int closeParens = countChars(queryString, ')');
		if (openParens != closeParens) {
			syntaxError("unbalanced parentheses (" + openParens + " '(' vs " + closeParens + " ')')");
		}
		if (openParens > 0 && indexOfBalencingParen(queryString) != (queryString.length() - 1)) {
			syntaxError("misplaced parentheses: an operator must enclose its entire (sub)query");
		}

		// The query language is prefix-oriented, so the query string can
		// be processed left to right. At each step, a substring is
		// popped from the head (left) of the string, and is converted to
		// a Qry object that is added to the query tree. Subqueries are
		// handled via recursion.

		// Find the left-most query operator and start the query tree.
		String[] substrings = queryString.split("[(]", 2);
		String queryOperator = AND;
		if (substrings.length > 1) {
			queryOperator = substrings[0].trim();
		}
		QueryParserOperatorQuery queryTree = createOperator(queryOperator, occur);
		if (queryOperator.endsWith(SCOREIF)) {
			occur = Occur.MUST;
		} else if (queryOperator.endsWith(SCOREIFNOT)) {
			occur = Occur.MUST_NOT;
		}

		// Start consuming queryString by removing the query operator and
		// its terminating ')'. queryString is always the part of the
		// query that hasn't been processed yet.

		if (substrings.length > 1) {
			queryString = substrings[1];
			queryString = queryString.substring(0, queryString.lastIndexOf(")")).trim();
			// A literal empty operator (e.g. #combine(), #uw2()) is malformed. Note this is NOT the same
			// as an operator whose operands are all stopwords (#combine(the a)): that has raw text here and
			// only becomes empty AFTER analysis, which is legal and yields no results, like Indri. (TASK-0015)
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

			// Now handle the argument (which could be a subquery).
			if (queryString.charAt(0) == '#' || queryString.charAt(0) == '~') { // Subquery
				queryString = popSubquery(queryString, queryTree, weight, occur).trim();
				occur = Occur.SHOULD;
			} else { // Term
				queryString = popTerm(queryString, queryTree, weight, occur);
				occur = Occur.SHOULD;
			}
		}

		return queryTree;
	}

	public Query parseQuery(String queryString) {
		// TODO: json or indri query
		if (queryString == null) {
			return null;
		}
		parseDepth = 0;
		queryString = queryString.replace("'", "");
		queryString = queryString.replace("\"", "");
		queryString = queryString.replace("+", " ");
		queryString = queryString.replace(":", ".");
		QueryParserQuery qry = parseQueryString(queryString, Occur.SHOULD);
		return getLuceneQuery(qry);
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
