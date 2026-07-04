package org.lemurproject.lucindri.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.IndexSearcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.searcher.testutil.TestIndex;

/**
 * Tests for {@link IndriIndexSearcher#collectionStatistics(String)} (TASK-0003).
 *
 * <p>The collection length {@code sumTotalTermFreq} must equal the total number of indexed tokens
 * in the field ({@code Terms.getSumTotalTermFreq()} summed over leaves). The pre-fix code double
 * counted it by also summing every document's (wrongly-encoded) norm value.
 *
 * <p>Uses a {@link WhitespaceAnalyzer} so token counts are exactly known and independent of
 * stemming/stopwords. No queries are run here.
 */
public class CollectionStatisticsTest {

	private static CollectionStatistics indri(TestIndex ix) throws Exception {
		return ix.searcher().collectionStatistics(TestIndex.FULLTEXT);
	}

	private static CollectionStatistics stock(TestIndex ix) throws Exception {
		return new IndexSearcher(ix.reader()).collectionStatistics(TestIndex.FULLTEXT);
	}

	/**
	 * Ground-truth oracle: Lucene's stock {@link IndexSearcher#collectionStatistics(String)}
	 * computes the collection length from segment metadata alone. Indri's override must agree.
	 * (Fails against the pre-fix double-count; passes after.)
	 */
	@Test
	public void sumTotalTermFreqMatchesStockLucene(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder()
				.analyzer(new WhitespaceAnalyzer())
				.add("d1", "alpha beta beta")   // 3 fulltext tokens
				.add("d2", "beta gamma")        // 2 fulltext tokens
				.build(dir)) {
			assertEquals(stock(ix).sumTotalTermFreq(), indri(ix).sumTotalTermFreq(),
					"Indri collection length must equal Lucene's stock value");
		}
	}

	/** Collection length equals the exact, known total token count. */
	@Test
	public void sumTotalTermFreqIsExactKnownCount(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder()
				.analyzer(new WhitespaceAnalyzer())
				.add("d1", "alpha beta beta")   // 3
				.add("d2", "beta gamma")        // 2
				.build(dir)) {
			assertEquals(5L, indri(ix).sumTotalTermFreq());
		}
	}

	/** docCount and sumDocFreq were never part of the double count; they must match stock Lucene. */
	@Test
	public void docCountAndSumDocFreqUnaffected(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder()
				.analyzer(new WhitespaceAnalyzer())
				.add("d1", "alpha beta beta")
				.add("d2", "beta gamma")
				.build(dir)) {
			assertEquals(stock(ix).docCount(), indri(ix).docCount(), "docCount");
			assertEquals(stock(ix).sumDocFreq(), indri(ix).sumDocFreq(), "sumDocFreq");
			assertEquals(2L, indri(ix).docCount());
		}
	}

	/** Across a MultiReader (production 8-part layout), stats aggregate correctly. */
	@Test
	public void aggregatesAcrossMultiReaderParts(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder()
				.analyzer(new WhitespaceAnalyzer())
				.add("p0d1", "alpha beta beta")   // part 0: 3 tokens
				.newPart()
				.add("p1d1", "beta gamma")        // part 1: 2 tokens
				.add("p1d2", "gamma gamma gamma") // part 1: 3 tokens
				.build(dir)) {
			// 3 + 2 + 3 = 8 total tokens; must match stock over the same MultiReader.
			assertEquals(8L, indri(ix).sumTotalTermFreq());
			assertEquals(stock(ix).sumTotalTermFreq(), indri(ix).sumTotalTermFreq());
			assertEquals(stock(ix).docCount(), indri(ix).docCount());
			assertEquals(stock(ix).sumDocFreq(), indri(ix).sumDocFreq());
			assertEquals(3L, indri(ix).docCount());
		}
	}
}
