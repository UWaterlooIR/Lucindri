package org.lemurproject.lucindri.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lemurproject.lucindri.searcher.testutil.TestIndex;

/**
 * Unit test for {@link LucindriGetDoc#fetch}: exact docno lookup on the keyword {@code externalId}
 * (TASK-0020), including a docno with tokenizer-splitting characters and a multi-part (MultiReader) index.
 */
public class LucindriGetDocTest {

	@Test
	public void fetchesByExactDocno_includingSplittingCharsAndMultiPart(@TempDir Path dir) throws Exception {
		try (TestIndex ix = TestIndex.builder()
				.add("d1", "cat dog")
				.add("wsj-90-01-01", "moon tree")
				.newPart()
				.add("shard_00000_0", "sun star")
				.build(dir)) {

			// A docno the text analyzer would split resolves exactly to its document.
			assertEquals("moon tree", LucindriGetDoc.fetch(ix.reader(), "wsj-90-01-01"));
			// A plain docno.
			assertEquals("cat dog", LucindriGetDoc.fetch(ix.reader(), "d1"));
			// A docno living in a second sub-index (MultiReader) still resolves.
			assertEquals("sun star", LucindriGetDoc.fetch(ix.reader(), "shard_00000_0"));

			// Unknown docno -> null (the caller maps this to a not-found exit / 404).
			assertNull(LucindriGetDoc.fetch(ix.reader(), "no-such-docno"));
			// Partial/sub-token of a docno must not match (keyword, not tokenized).
			assertNull(LucindriGetDoc.fetch(ix.reader(), "wsj"));
		}
	}
}
