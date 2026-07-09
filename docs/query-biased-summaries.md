# Query-biased summaries in Lucindri — the Lucene way

**Status:** design note / recipe (not yet implemented in the repo).
**Goal:** given a query and a retrieved document, produce a short **query-biased extractive
summary** — the sentences/passages of that document most relevant to the query.

## Why not "the Indri way"

C++ Indri does this by marking up sentences as structural extents inside a document and scoring
those sub-document extents (`#combine[sentence](...)`, passage retrieval `#combine[passageN:M]`,
restricting to a document, etc.). **Lucindri does not implement passage / field-extent retrieval**
(`#combine[field]`, `#combine[passageN:M]`, `#any:field` are all absent — see
`docs/indri-query-language.md`). So the in-index sentence-extent trick is not available.

It doesn't need to be: Lucindri is built on Lucene 8.10, and Lucene already ships query-biased
passage extraction. The classes are bundled in the searcher fat jar
(`org.apache.lucene.search.uhighlight.UnifiedHighlighter`, `PassageScorer`, and — as fallback —
`MemoryIndex`).

## The mechanism (`UnifiedHighlighter`)

Lucene's `UnifiedHighlighter` **is** query-biased extractive summarization:

1. It takes a query and a **stored** field, and breaks the field's text into passages with a
   `java.text.BreakIterator` — use `BreakIterator.getSentenceInstance(...)` for sentence passages.
2. It scores each passage against the query terms with a `PassageScorer` (a BM25-flavored scorer:
   rewards more and rarer query terms, with passage-length normalization).
3. It returns the top-N passages, in document order, joined into a snippet — i.e. a query-biased
   summary — one snippet per document.

Because Lucindri stores `fulltext` (stored + positions, but **not** offsets), the highlighter uses
its **ANALYSIS** offset source: it re-runs the analyzer over the stored text to locate terms. That
works out of the box; it just re-analyzes each summarized document (cheap for climbmix's short
documents).

## The one Lucindri-specific gotcha

The highlighters learn *which terms to weight* by asking the query to enumerate its terms
(`Query.visit(...)`). **Lucindri's Indri query classes do not implement `visit()`** — `IndriTermQuery`
extends `Query` directly and exposes nothing — so if you hand a raw `IndriAndQuery` to the
highlighter it will find **no terms** and highlight nothing.

Work around it by **supplying the query terms yourself**: tokenize the query text with the *same*
analyzer Lucindri uses at query time (KStem + English stopwords + lowercase), then build a plain
Lucene `BooleanQuery` of `TermQuery`s over `fulltext` and give *that* to the highlighter. The Indri
query is still used for retrieval/scoring; the plain term query is only used to tell the highlighter
what to look for. Tokenizing with the same analyzer means the highlight terms are stemmed exactly as
the indexed tokens, so a query for `run` highlights `runs` in the original text (KStem-aware).

`IndriQueryParser` already exposes the analyzer and a tokenizer:
`IndriQueryParser.tokenizeString(analyzer, text)` returns the stemmed query tokens.

## Recipe (illustrative Java, Lucene 8.10 API)

```java
import java.text.BreakIterator;
import java.util.List;
import java.util.Locale;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.lemurproject.lucindri.searcher.IndriIndexSearcher;
import org.lemurproject.lucindri.searcher.parser.IndriQueryParser;

// searcher: the same IndriIndexSearcher used for retrieval (has the fulltext field)
// queryText: the raw Indri query string the user ran
// topDocs: the retrieval results you want to summarize (or a subset)

String field = "fulltext";
Analyzer analyzer = IndriSearch.getConfigurableAnalyzer();          // KStem + stopwords + lowercase

// 1) query terms, tokenized exactly as the index was
List<String> terms = IndriQueryParser.tokenizeString(analyzer, queryText);

// 2) a PLAIN Lucene query the highlighter can enumerate (Indri queries can't be enumerated)
BooleanQuery.Builder hb = new BooleanQuery.Builder();
for (String t : terms) {
    hb.add(new TermQuery(new Term(field, t)), BooleanClause.Occur.SHOULD);
}
Query highlightQuery = hb.build();

// 3) sentence-passage highlighter; plain-text output (no <b> tags), passages joined with " ... "
UnifiedHighlighter uh = new UnifiedHighlighter(searcher, analyzer);
uh.setBreakIterator(() -> BreakIterator.getSentenceInstance(Locale.ENGLISH));
uh.setFormatter(new DefaultPassageFormatter("", "", " ... ", false)); // no markup; ellipsis joiner
uh.setMaxLength(100_000);                                             // cap analyzed field length

// 4) one summary per result doc: the top `maxPassages` sentences, in document order
int maxPassages = 2;
String[] summaries = uh.highlight(field, highlightQuery, topDocs, maxPassages);
// summaries[i] is the query-biased summary of topDocs.scoreDocs[i]
```

`uh.highlight(field, query, topDocs, maxPassages)` returns a `String[]` aligned with
`topDocs.scoreDocs` — each entry is that document's summary (its `maxPassages` best sentences).
To keep the match markup for a UI instead of plain text, drop the custom formatter (the default
wraps matches in `<b>…</b>`).

## Knobs

- **Passage unit:** `setBreakIterator(...)` — sentence (`getSentenceInstance`), line, or a custom
  `BreakIterator`. A `WholeBreakIterator` treats the whole field as one passage.
- **How many sentences:** the `maxPassages` argument.
- **Scoring:** the default `PassageScorer` (BM25-like) is used; override `UnifiedHighlighter.getScorer(field)`
  to supply custom passage scoring or term weights.
- **Output:** `PassageFormatter` (plain text vs. `<b>` markup, ellipsis joiner).
- **Cost:** `setMaxLength(...)` caps how much of the field is analyzed per document.

## Caveats

- **Not the Dirichlet LM.** Passage scoring here is BM25-flavored, *not* Indri Dirichlet. Fine for
  readable snippets; it is not "the same model as retrieval." If you need LM-consistent sentence
  ranking, see the alternatives below.
- **Requires the stored field.** Lucindri stores `fulltext`, so this works. (Optionally index
  `fulltext` with offsets — `DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS` — or store term vectors with
  offsets to skip re-analysis; a future speed optimization, not required.)
- **Terms, not operators.** The highlighter weights query *terms*; it does not honor proximity /
  belief structure. Phrase-aware highlighting would need span extraction, which the Indri queries
  don't expose either.
- **Size cap (TASK-0022).** As shipped in the server, selected sentences are joined by a **single space**
  (the `DefaultPassageFormatter` separator is `" "`, not `" ... "`) and the result is hard-capped at
  `--maxSummaryWords` whitespace-separated words (default 75), truncating at a word boundary with a
  trailing `" ..."`. A document whose body is one punctuation-free run-on (SEO/legal text) yields a single
  huge passage; the cap keeps its **first** N words — bounded, though not necessarily the query-relevant N.
  Better centering (overlapping / match-centered windows) would need a custom summarizer (deferred).

## Alternatives (when you want Lucindri's own LM scoring)

These reproduce the *substance* of the Indri trick (rank a document's sentences with the retrieval
model) rather than BM25 snippets:

- **Ephemeral per-document index.** Split the retrieved document's `fulltext` into sentences, build
  a tiny in-memory index (`ByteBuffersDirectory`, one Lucene doc per sentence, same analyzer +
  `LMDirichletSimilarity`), and run the Lucindri Indri query against it with
  `IndriIndexSearcher` + `IndriDirichletSimilarity`. Top sentences = the summary. Nuance: collection
  statistics are then doc-local (`p(w|C)` from that one document); to match Indri's global model,
  inject the global collection stats — feasible because `IndriIndexSearcher` already overrides
  `collectionStatistics(...)` (the method fixed in TASK-0003).
- **Sentence-granular index with a parent-document field.** Index each sentence as its own Lucene
  doc with a stored parent `docid`. To summarize document D, run the query restricted to D's
  sentences — using the now-working `#scoreif`/`#filreq` (TASK-0006) to *require* the parent-id
  field, or by post-filtering on the stored `docid` — and take the top sentences. This uses the
  global collection model and Lucindri's real Dirichlet LM (closest to Indri end to end), at the
  cost of a separate index.

## Summary

The Indri sentence-extent operators aren't in Lucindri, but query-biased summarization is a solved
problem in the Lucene stack it's built on: `UnifiedHighlighter` with a sentence `BreakIterator`
gives it directly, provided you feed it query terms explicitly (since Indri queries don't enumerate
their terms). Use it for readable snippets; use an ephemeral or sentence-granular index if you need
the summary ranked by Lucindri's own Dirichlet model.
