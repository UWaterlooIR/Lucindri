package org.lemurproject.lucindri.searcher;

import java.io.File;
import java.util.Arrays;

/**
 * Entry point (Main-Class) for the searcher fat jar: a thin dispatcher that keeps the existing batch CLI
 * working while adding the {@code getdoc} subcommand (TASK-0020).
 *
 * <ul>
 * <li>{@code <queries.xml>} (any first arg that is an existing readable file) → {@link IndriSearch} — the
 * legacy batch behavior, byte-for-byte unchanged.</li>
 * <li>{@code getdoc <indexDir[,...]> <docno>} → {@link LucindriGetDoc}.</li>
 * <li>anything else → usage on stderr, exit 2.</li>
 * </ul>
 *
 * <p>The file-first disambiguation means a real query file can never be mistaken for a subcommand (and the
 * only way {@code getdoc} routes to the subcommand is when no file named {@code getdoc} exists in the cwd).
 */
public class LucindriCli {

	/** The routing decision, exposed so it can be unit-tested without spawning a JVM or trapping exit. */
	enum Route {
		LEGACY_BATCH, GETDOC, USAGE
	}

	static Route route(String[] args) {
		if (args.length >= 1) {
			if (new File(args[0]).isFile()) {
				return Route.LEGACY_BATCH; // a real params/queries file -> legacy IndriSearch
			}
			if (args[0].equals("getdoc")) {
				return Route.GETDOC;
			}
		}
		return Route.USAGE;
	}

	public static void main(String[] args) throws Exception {
		switch (route(args)) {
		case LEGACY_BATCH:
			IndriSearch.main(args);
			break;
		case GETDOC:
			LucindriGetDoc.main(Arrays.copyOfRange(args, 1, args.length));
			break;
		default:
			System.err.println("Usage:");
			System.err.println("  <queries.xml>                       run a batch of Indri queries (TREC output)");
			System.err.println("  getdoc <indexDir[,...]> <docno>     print a document's fulltext by docno");
			System.exit(2);
		}
	}
}
