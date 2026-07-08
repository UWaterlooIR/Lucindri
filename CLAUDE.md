# CLAUDE.md — Lucindri working notes for Claude

This file orients a fresh Claude session working in this repository. Read it first,
then read any task you are asked to work on under `tasks/`.

## What this repo is

Lucindri is an open-source implementation of Indri search logic and the Indri
structured query language, built on top of Apache **Lucene 8.10.0**. It has a root Maven
**reactor** POM (TASK-0021) — `mvn clean install` at the repo root builds all modules in
dependency order (build one with `mvn -pl LucindriSearcher -am install`). The modules
(all version **2.0**):

1. `LucindriAnalyzer`  — artifact `org.lemurproject.lucindri:analyzer:2.0` (local-only)
2. `LucindriSearcher`  — query-time; produces `LucindriSearcher-2.0-jar-with-dependencies.jar`
3. `LucindriServer`    — long-running HTTP/JSON service over the searcher core (TASK-0019);
   produces `LucindriServer-2.0-jar-with-dependencies.jar` (main class `…server.LucindriServer`)
4. `LucindriIndexer`   — index-time; produces `LucindriIndexer-2.0-jar-with-dependencies.jar`

The two `-jar-with-dependencies.jar` fat jars are self-contained (Lucene is bundled
inside them — no separate Lucene install is ever needed).

Main classes:
- Indexer: `org.lemurproject.lucindri.indexer.BuildIndex` — takes one `.properties` file.
- Searcher: `org.lemurproject.lucindri.searcher.IndriSearch` — takes one queries `.xml` file.

The maintained project documentation is the repo-root `README.md`. (A stale
`docs/ReadMe.md` stub was deleted.)

### Indri-parity milestone (read before changing query-language behavior)

The git tag **`indri-parity`** (= commit `91d2451`, 2026-07-06, the pre-TASK-0016 master) marks the
last point where the Lucindri query language is a pure reimplementation of C++ Indri 5.21 — matching
Indri to the extent documented in `docs/indri-query-language.md` §6 and
`docs/lucindri-vs-indri-scores.md`. **From TASK-0016 onward, Lucindri deliberately extends beyond
the Indri query language** (string-literal splices, `#token`, field syntax removed). Consequence for
future work: "match C++ Indri" is the yardstick only for language elements that existed at the
parity point; newer language elements are Lucindri design decisions, recorded with rationale and a
compatibility ledger in their task files (TASK-0016 is the template). Diff the language surface
against the milestone with `git diff indri-parity..master`.

## Local modifications already made in this clone (not upstream)

- Deleted `docs/ReadMe.md` (obsolete stub with wrong package names).
- `LucindriIndexer/pom.xml`: bumped `com.github.TREMA-UNH:trec-car-tools-java` from `17` → `22`
  to match the local trec-car-tools-java clone, and added a `jitpack.io` `<repository>`
  block so that dependency resolves automatically (no manual `mvn install` of trec-car-tools
  needed anymore).

## Environment facts

- Host: Windows 11 + WSL2 (Ubuntu). Platform is Linux.
- JDK **11** (OpenJDK 11.0.31, 64-bit) and **Maven 3.6.3** are installed and on PATH.
  Both modules compile under JDK 11 (Lucindri targets Java 11; trec-car-tools targets 1.8).
- The Claude session's shell **resets its working directory after every command**. Always
  use absolute paths or `cd /ssd-8TB/git-repos/Lucindri/...` at the start of each command.
- Corpus of current interest: `/ssd-8TB/corpora/climbmix-400b-corpus-jsonl/` — 79 gzipped
  JSON-Lines shards named `shard_NNNNN.jsonl.gz`.

## Architecture: how indexing works (needed to add a document parser)

Lucene has no "parser" concept — you build `org.apache.lucene.document.Document` objects
and hand them to an `IndexWriter`. Lucindri wraps that in its own small framework:

- `IndexServiceImpl` drives the loop:
  ```java
  while (docParser.hasNextDocument()) {
      parsedDoc = docParser.getNextDocument();   // a DocumentParser subclass
      writer.writeDocuments(parsedDoc);          // LuceneDocumentWriter -> IndexWriter
  }
  ```
- A **document parser** is a subclass of
  `org.lemurproject.lucindri.indexer.documentparser.DocumentParser` implementing two methods:
  `boolean hasNextDocument()` and `ParsedDocument getNextDocument()`.
- A `ParsedDocument` is just a `List<ParsedDocumentField>`; each field is
  `(String fieldName, String content, boolean numeric)`.
- `LuceneDocumentWriter` turns every field into a Lucene `Field` that is **stored + tokenized
  + indexed with positions** (`DOCS_AND_FREQS_AND_POSITIONS`). Positions are what make Indri
  window operators (`#N`, `#uwN`, `#band`) work. Fields whose content is `null` are skipped.
- Parsers are selected by the `documentFormat=` property via
  `factory/DocumentParserFactory.java`, which is a **hardcoded `HashMap<String, Class>`** with
  reflection instantiation (constructor signature must be `(IndexingConfiguration options)`).
  There is **no SPI/plugin system** — to add a new format you must add a `docParserMap.put(...)`
  line here and rebuild the indexer jar. New parsers therefore must live in the
  `LucindriIndexer` module. The searcher is unaffected by new parsers.

### Field-name rules that matter (verified in source)

- The **searcher prints the TREC-output DOCNO from the stored field literally named
  `externalId`** (`IndriSearch.java`: `EXTERNALID_FIELD = "externalId"`, `doc.get(EXTERNALID_FIELD)`).
  A parser MUST store the external doc id under field name `"externalId"` or TREC results
  will have a blank id column.
- The **default query field is `fulltext`** (`DocumentParser.FULLTEXT_FIELD`). The document
  body should be stored under `"fulltext"` so unqualified Indri queries match it.
- NOTE: the existing generic `JsonDocumentParser` stores the id under `"id"` (the base-class
  constant `EXTERNALID_FIELD = "id"`), which the searcher does NOT read — so its TREC output
  DOCNO is blank. Do not copy that behavior. `TrecTextDocumentParser` does it correctly.
- Neither existing text parser reads gzip (both use a plain `FileInputStream`).

## Testing

**Policy (required).** All development work on this project **shall include appropriate automated
tests.** A change is not "done" until `mvn test` is green in every module it touches. Concretely:
- Every **bug fix** ships with a regression test that **fails before** the fix and **passes after**.
- Every **new feature** (a new document parser, a new query operator, etc.) ships with tests that
  cover its contract (field names, semantics, edge cases).
- Do not disable or delete a test to make a build pass; fix the code or the test.

**How testing is done here.** JUnit 5 + Maven Surefire, wired into each module's `pom.xml` with
pinned versions **`org.junit.jupiter:junit-jupiter:5.8.2`** (test scope) and
**`maven-surefire-plugin:2.22.2`**. Tests live under `<module>/src/test/java/...` mirroring the
main package. Run with `mvn test` (or `mvn install`) in the module.

- **Searcher tests reuse the shared fixture** `searcher/testutil` (`TestIndex`) — a builder that
  creates a controlled Lucene index (faithful to production: `LMDirichletSimilarity` index-time,
  positions, stored `fulltext`/`externalId`), optionally multiple sub-indexes in a `MultiReader`,
  and runs Indri query strings via the real parser + searcher. **Reuse it; do not rebuild
  tiny-index fixtures per test.** Default analyzer matches the query parser (KStem + stopwords +
  lowercase); pick stem-invariant, non-stopword, lowercase test tokens so counts/terms are exact.
- The searcher module **disables JVM assertions in Surefire** (`<enableAssertions>false</enableAssertions>`)
  on purpose: Indri scores are negative log-probabilities, which trip Lucene's internal
  `assert score >= 0`, and production runs via `java -jar` without `-ea`. This keeps tests aligned
  with real runtime behavior.
- Examples: indexer parser tests (`ClimbmixJsonlDocumentParserTest`), analyzer tokenization tests
  (`EnglishAnalyzerConfigurableTest`), searcher fixture smoke tests (`TestIndexSmokeTest`).
- **Standing end-to-end effectiveness/stability check (TREC-8 topics 401–450):** run it after any change
  to indexing, scoring, the parser, or the server. **`scripts/eval-401-450/`** is a self-contained kit with
  a README — `build_index.sh` builds a fresh keep-stopwords + exact-length t45mCR index, `eval.sh` runs the
  50 topics through the HTTP server *and* the batch searcher, confirms they rank identically, and
  `trec_eval`s both. Pass = 50/50 identical rankings and **MAP 0.2499 / P@10 0.4340** (the recorded
  baseline; full context in `docs/exactlen-stopwords-trec-eval.md`). Reproduced 2026-07-08 on the post-
  TASK-0019/0020/0021 stack. For the deeper Indri comparison, see `scripts/trec-comparison/exactlen_eval.sh`.
- A root parent/reactor POM aggregates the modules (TASK-0021): `mvn clean install` at the repo root
  builds all in dependency order, and all modules share version **2.0**. The parent is the single source
  of truth for shared third-party versions (`dependencyManagement`: Lucene/Solr 8.10.0, gson, commons-lang3,
  junit) and build-plugin versions/config (`pluginManagement`: compiler+Java 11, surefire, assembly);
  modules list those deps/plugins **without** versions. Module-specific bits stay per-module: the assembly
  `mainClass` (searcher → `LucindriCli`, indexer → `BuildIndex`) and the searcher's surefire
  `enableAssertions=false`.

## Task tracking system

Tasks live in `tasks/` as markdown files named `TASK-NNNN.md`, zero-padded, starting at
`TASK-0001.md`. Each file is a self-contained unit of work with enough context that a fresh
Claude session (no memory of prior conversation) can execute it.

Conventions:
- Filenames: `TASK-0001.md`, `TASK-0002.md`, … Allocate the next unused number.
- Each task begins with a header block: **Status**, **Created**, **Owner**, one-line summary.
  `Status` is one of: `Draft`, `Ready`, `In progress`, `Blocked`, `Done`.
- Keep tasks updated as work proceeds: change `Status`, and append progress/decisions to a
  `## Progress log` section (dated entries) so state survives context resets.
- When starting work: read this `CLAUDE.md`, then read the task file end-to-end before acting.
- Dates in tasks are absolute (e.g. `2026-07-03`), never relative ("today", "yesterday").

Seeing the current tasks — do NOT maintain a hand-written list here; it duplicates the task
files and goes stale. Each `tasks/TASK-*.md` header (its `Status` + title line) is the single
source of truth. Render a live index from those headers with:

    scripts/tasks.sh

To allocate the next task number, use the highest existing `tasks/TASK-*.md` + 1.
