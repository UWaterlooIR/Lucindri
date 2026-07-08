# TREC-8 (topics 401–450) effectiveness + stability eval

**What this is.** A self-contained recipe to (1) build a fresh Lucindri index of the **t45minusCR**
collection, (2) run the 50 TREC-8 ad-hoc topics **401–450** (Metzler SDM queries) through the
**LucindriServer** (HTTP) *and* the batch searcher, and (3) score both with `trec_eval` against the qrels.
It’s the standing "did we break ranking?" check — run it after any change to indexing, scoring, the query
parser, or the server. Nothing in the retrieval model should move these numbers.

**The pass condition.** Server and batch produce **identical rankings** (50/50 topics), and the numbers
match the recorded baseline:

| config | MAP | P@10 |
|---|---|---|
| **keep stopwords + exact length** (what this kit builds) | **0.2499** | **0.4340** |

(For reference, C++ Indri on the same setup is MAP 0.2501 / P@10 0.4340. Full context and the norm-vs-exact,
remove-vs-keep-stopwords matrix are in [`docs/exactlen-stopwords-trec-eval.md`](../../docs/exactlen-stopwords-trec-eval.md)
and [`docs/trec-comparison.md`](../../docs/trec-comparison.md).)

## Why this config (keep stopwords + exact length)

It’s the **closest-to-Indri length configuration**: keeping stopwords aligns the `|d|`/`|C|` counting Indri
does, and `exactDocumentLength=true` stores the exact per-doc token count (bypassing Lucene’s lossy 1-byte
norm). Together they take overlap@10 vs Indri to ~0.996. See the docs above for the full rationale.

## Assets (host paths — verified 2026-07)

| what | path |
|---|---|
| Corpus (7 gzipped trectext files, ~528k docs) | `/ssd-8TB/corpora/t45minusCR` |
| Topics, Lucindri dialect (quote-only, TASK-0016) | `docs/data/queries-401-450.metzler.lucindri.xml` |
| Topics, Indri dialect (bare terms, for C++ Indri) | `docs/data/queries-401-450.metzler.indri.xml` |
| Qrels | `/ssd-8TB/qrels/trec8-401-450.rel` |
| `trec_eval` | `/mnt/g/smucker/github-repos/trec_eval/trec_eval` |
| C++ Indri 5.21 (reference engine, optional) | `/ssd-8TB/installs/indri-5.21/bin` |

All of these are overridable via env vars at the top of each script.

## Prerequisites

Build the fat jars once, from the repo root (the reactor builds every module):

```
mvn clean install
```

## Steps

**1. Build the fresh index** (~2 min; keep stopwords + exact length, kstem). Rebuild from scratch after any
indexer change — e.g. TASK-0020 made `externalId` a keyword field, so a pre-TASK-0020 index is stale:

```
scripts/eval-401-450/build_index.sh
# -> /ssd-8TB/trec-compare/stability20/t45_keepstop_exact
```

**2. Run the eval** (starts the server, runs 50 topics via HTTP and via batch, checks they agree, scores
both):

```
scripts/eval-401-450/eval.sh
```

Expected output:

```
>> server vs batch ranking agreement (must be identical)
   topics: 50   identical docno ranking: 50/50

SERVER:                MAP=0.2499  P@10=0.4340
BATCH (static):        MAP=0.2499  P@10=0.4340
recorded baseline:     MAP=0.2499  P@10=0.4340
```

## Files in this kit

- **`build_index.sh`** — builds the keep-stopwords + exact-length index with the `LucindriIndexer-2.0` jar.
- **`eval.sh`** — starts `LucindriServer` over the index (`--removeStopwords false`; exact length is
  auto-detected from the `<field>_len` DocValues), runs the topics through the server with
  `run_server_queries.py`, runs the same topics through the batch searcher, diffs the two rankings, and runs
  `trec_eval` (MAP, P@10) on both.
- **`run_server_queries.py`** — reads a Lucindri query-parameters XML, POSTs each `<query>` to the server’s
  `/search` (`count=1000`), and writes the results in TREC run format (`topic Q0 docno rank score tag`).

## Gotchas (learned the hard way)

- **Query analysis must match the index.** This index keeps stopwords, so the server/batch **must** run with
  `removeStopwords=false` (the scripts do). Mismatch → few or no results, wrong numbers.
- **`exactDocumentLength` is auto-detected at query time** — no searcher/server flag needed; the scorer uses
  the `<field>_len` DocValues if present, else the norm.
- **The trectext parser is line-oriented and reads `.gz` directly** — `<DOC>`/`<DOCNO>`/`<TEXT>` must be on
  their own lines (single-line TREC docs produce an empty index).
- **`trec_eval` re-sorts by score**, so the run’s rank column is cosmetic; ties break by docno, deterministically.

## Related (deeper / reference) scripts

- `scripts/trec-comparison/exactlen_eval.sh` — the fuller comparison that also builds a **C++ Indri** index
  and reports the norm-vs-exact × remove-vs-keep-stopwords matrix + overlap@k/RBO agreement vs Indri. Use
  this when you want to compare against Indri, not just against the recorded numbers.
- `scripts/trec-comparison/agreement.py` — overlap@k / RBO between two runs.
