package org.lemurproject.lucindri.searcher;

import java.io.IOException;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.LeafSimScorer;
import org.apache.lucene.search.similarities.Similarity;
import org.lemurproject.lucindri.searcher.similarities.IndriSimilarity;

/**
 * The single per-document length seam for Indri scoring (TASK-0012). Every Indri scorer reaches a
 * document's length through one call — {@link #score(int, float)} — instead of talking to a Lucene
 * {@link LeafSimScorer} directly. Two implementations back it:
 *
 * <ul>
 * <li>{@link NormLengthSource} — wraps a stock {@link LeafSimScorer}, which reads the lossy 1-byte
 * Lucene norm. This is the historical behavior.</li>
 * <li>{@link ExactLengthSource} — reads the exact per-doc token count from a {@code <field>_len}
 * {@link NumericDocValues} (written when the index was built with {@code exactDocumentLength=true}) and
 * scores with it verbatim, bypassing the SmallFloat quantization.</li>
 * </ul>
 *
 * <p>The choice is <b>auto-detected per field/leaf</b> in {@link #create}: if the {@code <field>_len}
 * DocValues exists (and the similarity supports exact-length scoring) the exact source is used, otherwise
 * the norm source. So an index without {@code _len} is scored exactly as before — bit-for-bit — with no
 * query-side configuration.
 */
abstract class IndriLengthSource {

	/**
	 * Suffix of the per-doc exact-length NumericDocValues. Must match
	 * {@code LuceneDocumentWriter.EXACT_LENGTH_SUFFIX} in the LucindriIndexer module (the searcher cannot
	 * depend on the indexer, so the constant is duplicated and kept in sync).
	 */
	static final String EXACT_LENGTH_SUFFIX = "_len";

	final Similarity.SimScorer simScorer;

	IndriLengthSource(Similarity.SimScorer simScorer) {
		this.simScorer = simScorer;
	}

	/** Scores {@code docId} at term frequency {@code freq} using this source's document length. */
	abstract float score(int docId, float freq) throws IOException;

	/** The underlying Lucene SimScorer (needed to build an {@code ImpactsDISI}); length source aside. */
	Similarity.SimScorer getSimScorer() {
		return simScorer;
	}

	/**
	 * Builds the length source for {@code field} in {@code context}: the exact-length source when the
	 * {@code <field>_len} DocValues is present and {@code simScorer} supports exact-length scoring,
	 * otherwise the norm source (today's behavior). {@code needsScores} is passed to the norm-path
	 * {@link LeafSimScorer}.
	 */
	static IndriLengthSource create(LeafReaderContext context, String field, Similarity.SimScorer simScorer,
			boolean needsScores) throws IOException {
		if (simScorer instanceof IndriSimilarity.ExactLengthSimScorer) {
			NumericDocValues lengths = context.reader().getNumericDocValues(field + EXACT_LENGTH_SUFFIX);
			if (lengths != null) {
				return new ExactLengthSource(simScorer, (IndriSimilarity.ExactLengthSimScorer) simScorer, lengths);
			}
		}
		return new NormLengthSource(new LeafSimScorer(simScorer, context.reader(), field, needsScores));
	}

	/** Norm-based length: delegates to a stock {@link LeafSimScorer} (lossy 1-byte norm). */
	private static final class NormLengthSource extends IndriLengthSource {
		private final LeafSimScorer leafSimScorer;

		NormLengthSource(LeafSimScorer leafSimScorer) {
			super(leafSimScorer.getSimScorer());
			this.leafSimScorer = leafSimScorer;
		}

		@Override
		float score(int docId, float freq) throws IOException {
			return leafSimScorer.score(docId, freq);
		}
	}

	/**
	 * Exact length: reads {@code <field>_len} and scores with it verbatim. The DocValues iterator is
	 * advanced with {@link NumericDocValues#advanceExact(int)} in the same monotonic (non-decreasing
	 * docId) way {@link LeafSimScorer} advances the norms iterator, which is exactly how the DAAT merge
	 * queries each leaf (both {@code score()} and {@code smoothingScore()}), so the access pattern is
	 * safe. A doc with no {@code _len} value is treated as length 0; a matching doc always has the term,
	 * hence the field, hence a {@code _len}, so 0 is only reached when smoothing a doc that lacks the field
	 * entirely.
	 */
	private static final class ExactLengthSource extends IndriLengthSource {
		private final IndriSimilarity.ExactLengthSimScorer exactScorer;
		private final NumericDocValues lengths;

		ExactLengthSource(Similarity.SimScorer simScorer, IndriSimilarity.ExactLengthSimScorer exactScorer,
				NumericDocValues lengths) {
			super(simScorer);
			this.exactScorer = exactScorer;
			this.lengths = lengths;
		}

		@Override
		float score(int docId, float freq) throws IOException {
			long length = lengths.advanceExact(docId) ? lengths.longValue() : 0L;
			return exactScorer.scoreWithExactLength(freq, length);
		}
	}
}
