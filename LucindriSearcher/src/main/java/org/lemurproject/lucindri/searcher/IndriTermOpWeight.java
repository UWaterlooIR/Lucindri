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
import java.util.TreeMap;

import org.apache.lucene.index.IndexReaderContext;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
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
	// Collection-wide statistics for the derived proximity/synonym "term", computed once across all
	// leaves (like Lucene's TermStates for a plain term). The per-leaf inverted list built in
	// getScorer only knows this segment's occurrences, so using its cf gave a SEGMENT-LOCAL collection
	// frequency. That barely moves a matching score (tf dominates tf + mu*cf/|C|) but corrupts the
	// smoothing/background score of an absent term-op in a belief combination (tf=0 -> the score is
	// entirely log(mu*cf/|C| / (|d|+mu))). Indri uses the collection-wide cf. (TASK-0011, 9th bug)
	private boolean statsComputed = false;
	private TermStatistics collectionTermStats;

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

	/**
	 * Whether an out-of-vocabulary operand forces the whole operator to be empty. True for window and
	 * boolean-AND operators (a window/AND containing a term that never occurs can never match — it
	 * becomes a cf=0 term). {@link IndriSynonymWeight} overrides this to false: a synonym skips an OOV
	 * operand and unions the remaining ones. (TASK-0011)
	 */
	protected boolean oovOperandForcesEmpty() {
		return true;
	}

	/** Floored-background scorer for a cf=0 term/proximity: empty iterator + p(w|C)=1/(2|C|). */
	private Scorer backgroundScorer(LeafReaderContext context) throws IOException {
		if (similarity instanceof org.lemurproject.lucindri.searcher.similarities.IndriSimilarity) {
			Similarity.SimScorer bg = ((org.lemurproject.lucindri.searcher.similarities.IndriSimilarity) similarity)
					.backgroundSimScorer(boost, collectionStats);
			LeafSimScorer leaf = new LeafSimScorer(bg, context.reader(), field, true);
			return new IndriMissingTermScorer(this, leaf, boost);
		}
		return null;
	}

	/**
	 * Builds this leaf's proximity/synonym inverted list. Returns {@code null} only when an
	 * out-of-vocabulary operand forces a window/AND operator empty (a synonym instead skips the OOV
	 * operand and unions the rest). When the operator's operands exist but produce no postings in this
	 * leaf, an empty enum is returned so the collection-wide cf can still smooth this leaf's documents.
	 */
	private IndriTermOpEnum buildLeafEnum(LeafReaderContext context) throws IOException {
		List<IndriDocAndPostingsIterator> iterators = new ArrayList<>();
		for (Weight w : weights) {
			Scorer scorer = w.scorer(context);
			if (scorer instanceof IndriMissingTermScorer) {
				// An operand is out-of-vocabulary (cf=0, absent from the collection). For a window / AND
				// the operator can never occur, so it becomes a floored cf=0 term; for a synonym the OOV
				// operand contributes no positions and is skipped (union the rest). (TASK-0010/0011)
				if (oovOperandForcesEmpty()) {
					return null;
				}
				continue;
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
			// No operand postings in this leaf (all operands absent from this segment, or a synonym whose
			// operands are all OOV). Return an empty enum: it matches nothing here, but if the term-op
			// occurs elsewhere in the collection its collection-wide cf still smooths this leaf's docs.
			return new IndriTermOpEnum(new IndriInvertedList(field));
		}
		return getProximityIterator(iterators);
	}

	/**
	 * Computes the derived term-op's collection-wide document frequency and collection frequency by
	 * building the inverted list for every leaf once, so the smoothing background uses the same cf
	 * Indri does. The per-leaf lists are discarded here (rebuilt lazily per leaf in
	 * {@link #getScorer}) to keep peak memory bounded on very large indexes.
	 */
	private synchronized void ensureCollectionStats(LeafReaderContext context) throws IOException {
		if (statsComputed) {
			return;
		}
		long totalDocFreq = 0;
		long totalTermFreq = 0;
		IndexReaderContext top = ReaderUtil.getTopLevelContext(context);
		for (LeafReaderContext leaf : top.leaves()) {
			IndriTermOpEnum leafEnum = buildLeafEnum(leaf);
			if (leafEnum == null) {
				continue; // OOV operand forces the operator empty in every leaf
			}
			for (TreeMap<Integer, IndriDocumentPosting> postings : leafEnum.getInvList().getDocPostings().values()) {
				totalDocFreq++;
				totalTermFreq += postings.size();
			}
		}
		if (totalDocFreq > 0) {
			collectionTermStats = new TermStatistics(new Term(field, "NEAR").bytes(), totalDocFreq, totalTermFreq);
		} else {
			collectionTermStats = null;
		}
		statsComputed = true;
	}

	protected Scorer getScorer(LeafReaderContext context) throws IOException {
		ensureCollectionStats(context);
		if (collectionTermStats == null) {
			// cf=0 across the whole collection: an OOV operand forced the operator empty, or a window
			// whose operands exist but never co-occur anywhere. Like Indri, a floored cf=0 term -- it
			// matches nothing on its own but contributes the floored background p(w|C)=1/(2|C|) to a
			// belief combination. (TASK-0011)
			return backgroundScorer(context);
		}
		IndriTermOpEnum postingsEnum = buildLeafEnum(context);
		if (postingsEnum == null) {
			postingsEnum = new IndriTermOpEnum(new IndriInvertedList(field));
		}
		// Score with the COLLECTION-WIDE cf (not this leaf's local count) so an absent term-op smooths
		// with the same background Indri uses; positions/matches still come from this leaf's list.
		this.simScorer = similarity.scorer(boost, collectionStats, collectionTermStats);
		LeafSimScorer leafScorer = new LeafSimScorer(simScorer, context.reader(), field, true);
		return new IndriTermOpScorer(this, postingsEnum, leafScorer, boost);
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
