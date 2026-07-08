package org.lemurproject.lucindri.searcher;

import java.io.IOException;
import java.nio.file.Paths;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;

/**
 * Fetch a document's stored contents ({@code fulltext}) by its external id (docno) and print them to
 * stdout — the standalone counterpart of the HTTP {@code /document} endpoint (TASK-0019), and the
 * proof-of-functionality utility for TASK-0020's keyword {@code externalId}.
 *
 * <p>Usage: {@code LucindriGetDoc <indexDir[,indexDir2,...]> <docno>}. The docno is matched with an exact
 * {@link TermQuery} on the non-analyzed {@code externalId} keyword field — <b>no analyzer</b> (that the
 * docno needs no analysis is exactly the property TASK-0020's keyword field creates). Found → the body to
 * stdout, exit 0; unknown docno → a message on stderr, exit 1; bad args → usage on stderr, exit 2.
 */
public class LucindriGetDoc {

	static final String EXTERNALID_FIELD = "externalId";
	static final String FULLTEXT_FIELD = "fulltext";

	/**
	 * Returns the stored {@code fulltext} of the document whose {@code externalId} equals {@code docno}
	 * (multiple stored values joined with {@code "\n"}), or {@code null} if no document matches. A matched
	 * document whose {@code fulltext} is absent/empty yields {@code ""} (found-but-empty), not {@code null}.
	 */
	public static String fetch(IndexReader reader, String docno) throws IOException {
		IndexSearcher searcher = new IndexSearcher(reader);
		ScoreDoc[] hits = searcher.search(new TermQuery(new Term(EXTERNALID_FIELD, docno)), 1).scoreDocs;
		if (hits.length == 0) {
			return null;
		}
		Document doc = reader.document(hits[0].doc);
		return String.join("\n", doc.getValues(FULLTEXT_FIELD));
	}

	/** Opens one index dir, or a comma-separated list as a {@link MultiReader} — as {@code IndriSearch} does. */
	static IndexReader openReader(String indexArg) throws IOException {
		if (indexArg.contains(",")) {
			String[] dirs = indexArg.split(",");
			IndexReader[] subReaders = new IndexReader[dirs.length];
			for (int i = 0; i < dirs.length; i++) {
				subReaders[i] = DirectoryReader.open(FSDirectory.open(Paths.get(dirs[i].trim())));
			}
			return new MultiReader(subReaders, true);
		}
		return DirectoryReader.open(FSDirectory.open(Paths.get(indexArg.trim())));
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			System.err.println("Usage: LucindriGetDoc <indexDir[,indexDir2,...]> <docno>");
			System.exit(2);
			return;
		}
		String indexArg = args[0];
		String docno = args[1];
		IndexReader reader = openReader(indexArg);
		try {
			String body = fetch(reader, docno);
			if (body == null) {
				System.err.println("no document with externalId: " + docno);
				System.exit(1);
				return;
			}
			System.out.println(body);
		} finally {
			reader.close();
		}
	}
}
