package org.lemurproject.lucindri.searcher.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.lucene.search.Query;
import org.junit.jupiter.api.Test;

/**
 * Guards the TASK-0019 thread-safety fix: {@link IndriQueryParser} no longer holds recursion depth in a
 * shared instance field, so many threads can parse against ONE parser without racing. Before the fix, a
 * corrupted shared {@code parseDepth} would intermittently trip the depth guard and turn a valid query into
 * a spurious "query nesting too deep" rejection.
 */
public class IndriQueryParserConcurrencyTest {

	/** A valid query nested {@code d} levels: #combine(#combine(...("cat")...)). */
	private static String nested(int d) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < d; i++) {
			sb.append("#combine(");
		}
		sb.append("\"cat\"");
		for (int i = 0; i < d; i++) {
			sb.append(")");
		}
		return sb.toString();
	}

	@Test
	public void oneSharedParser_manyThreads_neverSpuriouslyRejects() throws Exception {
		final IndriQueryParser parser = new IndriQueryParser();
		final String query = nested(100); // well under the 128 depth limit
		final int threads = 8, iters = 500;

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);

		for (int t = 0; t < threads; t++) {
			pool.submit(() -> {
				try {
					start.await();
					for (int i = 0; i < iters; i++) {
						Query q = parser.parseQuery(query);
						assertNotNull(q);
						// queryTerms shares the same recursion; exercise it concurrently too.
						assertEquals(List.of("cat"), parser.queryTerms(query));
					}
				} catch (Throwable e) {
					failures.add(e);
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertTrue(done.await(60, TimeUnit.SECONDS), "threads did not finish in time");
		pool.shutdownNow();
		assertTrue(failures.isEmpty(), "concurrent parsing failed: " + failures);
	}

	@Test
	public void depthLimitStillEnforced() throws Exception {
		IndriQueryParser parser = new IndriQueryParser();
		assertThrows(QueryParseException.class, () -> parser.parseQuery(nested(200)));
	}
}
