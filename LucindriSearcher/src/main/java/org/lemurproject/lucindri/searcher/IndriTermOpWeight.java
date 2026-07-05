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
package org.lemurproject.lucindri.searcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BulkScorer;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafSimScorer;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TermStatistics;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.similarities.Similarity;

public abstract class IndriTermOpWeight extends IndriWeight {

	private static ScoreMode scoreMode = ScoreMode.COMPLETE;
	private final ArrayList<Weight> weights;
	private final String field;
	private final float boost;
	private final Similarity similarity;
	private CollectionStatistics collectionStats;
	private Similarity.SimScorer simScorer;

	protected IndriTermOpWeight(IndriProximityQuery query, IndexSearcher searcher, String field, float boost)
			throws IOException {
		super(query, searcher, scoreMode, boost);
		this.field = field;
		this.boost = boost;
		this.similarity = searcher.getSimilarity();
		collectionStats = searcher.collectionStatistics(field);
		weights = new ArrayList<>();
		for (BooleanClause c : query) {
			Weight w = searcher.createWeight(c.getQuery(), scoreMode, 1.0f);
			weights.add(w);
		}
	}

	protected Scorer getScorer(LeafReaderContext context) throws IOException {
		List<IndriDocAndPostingsIterator> iterators = new ArrayList<>();
		for (Weight w : weights) {
			Scorer scorer = w.scorer(context);
			if (scorer instanceof IndriMissingTermScorer) {
				// An operand is out-of-vocabulary (cf=0, absent from the collection). The proximity can
				// never occur, so — like Indri — the whole operator becomes a cf=0 term: it matches no
				// document on its own but contributes the floored background p(w|C)=1/(2|C|) to a belief
				// combination. (TASK-0010)
				if (similarity instanceof org.lemurproject.lucindri.searcher.similarities.IndriSimilarity) {
					Similarity.SimScorer bg = ((org.lemurproject.lucindri.searcher.similarities.IndriSimilarity) similarity)
							.backgroundSimScorer(boost, collectionStats);
					LeafSimScorer leaf = new LeafSimScorer(bg, context.reader(), field, true);
					return new IndriMissingTermScorer(this, leaf, boost);
				}
				return null;
			}
			if (scorer != null) {
				DocIdSetIterator docIter = scorer.iterator();
				IndriDocAndPostingsIterator iterator;
				if (docIter instanceof IndriTermOpEnum) {
					iterator = (IndriTermOpEnum) docIter;
				} else if (docIter instanceof PostingsEnum) {
					iterator = new IndriPostingsEnumWrapper((PostingsEnum) docIter);
				} else {
					throw new IllegalArgumentException(
							"Term/proximity operators (#band, #N, #uwN, #syn) require term or proximity "
									+ "operands with position lists; received a belief operator (e.g. #or/#combine) "
									+ "with no positions. Use #syn (not #or) for a disjunctive facet inside a "
									+ "proximity operator. Operand scorer: " + scorer.getClass().getName());
				}
				iterators.add(iterator);
			}
		}

		if (iterators.isEmpty()) {
			return null;
		}

		IndriTermOpEnum postingsEnum = getProximityIterator(iterators);
		TermStatistics termStats = postingsEnum.getInvList().getTermStatistics();
		Scorer scorer = null;
		if (termStats != null) {
			this.simScorer = similarity.scorer(boost, collectionStats, termStats);
			LeafSimScorer leafScorer = new LeafSimScorer(simScorer, context.reader(), field, true);
			scorer = new IndriTermOpScorer(this, postingsEnum, leafScorer, boost);
		} else if (similarity instanceof org.lemurproject.lucindri.searcher.similarities.IndriSimilarity) {
			// The window never occurs in the collection (cf=0), even though its operands exist (e.g. two
			// common terms that are never adjacent). Like Indri, it becomes a cf=0 term: it matches no
			// document on its own but contributes the floored background p(w|C)=1/(2|C|) to a belief
			// combination. (TASK-0011) Without this the window is dropped, over-scoring belief combos.
			Similarity.SimScorer bg = ((org.lemurproject.lucindri.searcher.similarities.IndriSimilarity) similarity)
					.backgroundSimScorer(boost, collectionStats);
			LeafSimScorer leaf = new LeafSimScorer(bg, context.reader(), field, true);
			scorer = new IndriMissingTermScorer(this, leaf, boost);
		}
		return scorer;
	}

	protected IndriTermOpEnum getProximityIterator(List<IndriDocAndPostingsIterator> iterators) throws IOException {
		IndriInvertedList invList = createInvertedList(iterators);
		IndriTermOpEnum nearPostings = new IndriTermOpEnum(invList);

		return nearPostings;
	}

	protected abstract IndriInvertedList createInvertedList(List<IndriDocAndPostingsIterator> iterators)
			throws IOException;

	public String getField() {
		return field;
	}

	@Override
	public Scorer scorer(LeafReaderContext context) throws IOException {
		return getScorer(context);
	}

	@Override
	public BulkScorer bulkScorer(LeafReaderContext context) throws IOException {
		Scorer scorer = getScorer(context);
		if (scorer != null) {
			BulkScorer bulkScorer = new DefaultBulkScorer(scorer);
			return bulkScorer;
		}
		return null;
	}

}
