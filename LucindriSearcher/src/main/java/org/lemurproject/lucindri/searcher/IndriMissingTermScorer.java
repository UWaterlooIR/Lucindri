package org.lemurproject.lucindri.searcher;

import java.io.IOException;

import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndriScorer;
import org.apache.lucene.search.LeafSimScorer;
import org.apache.lucene.search.Weight;

/**
 * Scorer for a query term that is absent from the collection ({@code cf == 0}). It matches no
 * documents (empty iterator) but supplies the floored background belief {@code p(w|C) = 1/(2|C|)} via
 * {@link #smoothingScore(int)}, so an out-of-vocabulary term contributes a small background to a
 * belief combination instead of being dropped — matching C++ Indri. See TASK-0010 and
 * {@code docs/lucindri-vs-indri-scores.md}.
 */
public class IndriMissingTermScorer extends IndriScorer {

	private final LeafSimScorer docScorer;
	private final float boost;
	private final DocIdSetIterator iterator = DocIdSetIterator.empty();

	public IndriMissingTermScorer(Weight weight, LeafSimScorer docScorer, float boost) {
		super(weight, boost);
		this.docScorer = docScorer;
		this.boost = boost;
	}

	@Override
	public float smoothingScore(int docId) throws IOException {
		return docScorer.score(docId, 0);
	}

	@Override
	public int docID() {
		return iterator.docID();
	}

	@Override
	public float score() throws IOException {
		// The empty iterator never lands on a document, so score() is never used for a real match.
		return 0;
	}

	@Override
	public DocIdSetIterator iterator() {
		return iterator;
	}

	@Override
	public float getBoost() {
		return boost;
	}

	@Override
	public float getMaxScore(int upTo) throws IOException {
		return 0;
	}
}
