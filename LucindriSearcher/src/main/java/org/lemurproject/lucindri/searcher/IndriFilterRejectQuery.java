package org.lemurproject.lucindri.searcher;

import java.io.IOException;
import java.util.List;

import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Weight;

/**
 * #scoreifnot (filter reject; equivalent to Indri #filrej). The candidate set is the scored
 * subquery's matching documents that do NOT match the FILTER query, each scored by the scored
 * subquery's Indri belief. By convention the parser supplies clause 0 = filter, clause 1 = scored
 * subquery (both as scoring clauses). (TASK-0006)
 */
public class IndriFilterRejectQuery extends IndriQuery {

	public IndriFilterRejectQuery(List<BooleanClause> clauses) {
		super(clauses);
	}

	@Override
	public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
		return new IndriFilterRejectWeight(this, searcher, scoreMode, boost);
	}

}
