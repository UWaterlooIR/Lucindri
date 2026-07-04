package org.lemurproject.lucindri.searcher;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BulkScorer;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;

public class IndriFilterRequireWeight extends IndriWeight {

	private final float boost;
	private final ArrayList<Weight> weights;

	public IndriFilterRequireWeight(IndriFilterRequireQuery query, IndexSearcher searcher, ScoreMode scoreMode,
			float boost) throws IOException {
		super(query, searcher, scoreMode, boost);
		this.boost = boost;
		// Create clause weights with the (scoring) scoreMode so term statistics are available even
		// for the filter clause. A NO_SCORES weight would throw "Cannot call docFreq()".
		weights = new ArrayList<>();
		for (BooleanClause c : query) {
			Weight w = searcher.createWeight(c.getQuery(), scoreMode, 1.0f);
			weights.add(w);
		}
	}

	private Scorer getScorer(LeafReaderContext context) throws IOException {
		// clause 0 = filter, clause 1 = scored subquery
		Scorer filterScorer = weights.get(0).scorer(context);
		Scorer scoredScorer = weights.get(1).scorer(context);
		if (filterScorer == null || scoredScorer == null) {
			return null; // require: nothing matches the filter in this segment
		}
		return new IndriFilterRequireScorer(this, filterScorer, scoredScorer, boost);
	}

	@Override
	public Scorer scorer(LeafReaderContext context) throws IOException {
		return getScorer(context);
	}

	@Override
	public BulkScorer bulkScorer(LeafReaderContext context) throws IOException {
		Scorer scorer = getScorer(context);
		if (scorer != null) {
			return new DefaultBulkScorer(scorer);
		}
		return null;
	}

}
