package org.lemurproject.lucindri.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link LucindriCli#route}: the subcommand dispatch preserves the legacy {@code <queries.xml>}
 * behavior (a first arg that is an existing file routes to the batch path) while adding {@code getdoc}
 * (TASK-0020). Tests the routing decision directly, so no JVM spawn or {@code System.exit} trapping.
 */
public class LucindriCliRouteTest {

	@Test
	public void existingFileFirstArg_routesToLegacyBatch(@TempDir Path dir) throws Exception {
		Path queries = dir.resolve("queries.xml");
		Files.writeString(queries, "<parameters></parameters>");
		assertEquals(LucindriCli.Route.LEGACY_BATCH, LucindriCli.route(new String[] { queries.toString() }));
	}

	@Test
	public void getdocToken_routesToGetdoc() {
		// "getdoc" is not an existing file in the test's working directory, so it routes to the subcommand.
		assertEquals(LucindriCli.Route.GETDOC, LucindriCli.route(new String[] { "getdoc", "/some/index", "docno-1" }));
	}

	@Test
	public void unknownTokenOrNoArgs_routesToUsage() {
		assertEquals(LucindriCli.Route.USAGE, LucindriCli.route(new String[] { "not-a-file-not-a-subcommand" }));
		assertEquals(LucindriCli.Route.USAGE, LucindriCli.route(new String[] {}));
	}
}
