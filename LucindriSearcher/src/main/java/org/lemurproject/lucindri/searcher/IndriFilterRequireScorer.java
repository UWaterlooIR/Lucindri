package org.lemurproject.lucindri.searcher;

import java.io.IOException;

import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndriScorer;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;

/**
 * Scorer for #scoreif (filter require; Indri #filreq). Driven by the scored subquery's candidate
 * documents, keeping only those that ALSO match the FILTER query; each surviving document is scored
 * by the scored subquery's Indri belief. (This matches Indri: #filreq(A B) = B's candidates that
 * match A, not every document matching A.) (TASK-0006)
 */
public class IndriFilterRequireScorer extends IndriScorer {

	private final Scorer scoredScorer;
	private final DocIdSetIterator scoredIterator;
	private final DocIdSetIterator filterIterator;
	private final DocIdSetIterator iterator;

	protected IndriFilterRequireScorer(Weight weight, Scorer filterScorer, Scorer scoredScorer, float boost) {
		super(weight, boost);
		this.scoredScorer = scoredScorer;
		this.scoredIterator = scoredScorer.iterator();
		this.filterIterator = (filterScorer == null) ? null : filterScorer.iterator();
		this.iterator = new RequireIterator();
	}

	/** True if the filter query matches {@code doc}. */
	private boolean filterMatches(int doc) throws IOException {
		if (filterIterator == null) {
			return false;
		}
		if (filterIterator.docID() < doc) {
			filterIterator.advance(doc);
		}
		return filterIterator.docID() == doc;
	}

	@Override
	public float score() throws IOException {
		// Candidates are scored-subquery matches at the current position.
		return scoredScorer.score();
	}

	@Override
	public float smoothingScore(int docId) throws IOException {
		return scoredScorer.smoothingScore(docId);
	}

	@Override
	public int docID() {
		return scoredIterator.docID();
	}

	@Override
	public DocIdSetIterator iterator() {
		return iterator;
	}

	@Override
	public float getMaxScore(int upTo) throws IOException {
		return 0;
	}

	private class RequireIterator extends DocIdSetIterator {
		@Override
		public int docID() {
			return scoredIterator.docID();
		}

		@Override
		public int nextDoc() throws IOException {
			return skipToRequired(scoredIterator.nextDoc());
		}

		@Override
		public int advance(int target) throws IOException {
			return skipToRequired(scoredIterator.advance(target));
		}

		@Override
		public long cost() {
			return scoredIterator.cost();
		}

		private int skipToRequired(int doc) throws IOException {
			while (doc != NO_MORE_DOCS && !filterMatches(doc)) {
				doc = scoredIterator.nextDoc();
			}
			return doc;
		}
	}

}
