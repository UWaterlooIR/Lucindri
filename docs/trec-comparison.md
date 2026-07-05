# Lucindri vs. C++ Indri — large-scale TREC comparison (TASK-0008)

End-to-end comparison of Lucindri against C++ Indri 5.21 on a real TREC collection, run
2026-07-04. This complements the operator-by-operator conformance catalog in
[`lucindri-vs-indri.md`](lucindri-vs-indri.md): here we index a full collection with **both**
engines, run the same 50 topics through each, and measure how closely the rankings agree.

**Bottom line:** Lucindri faithfully reproduces Indri's query-operator semantics — including
`#weight` weighted belief combination. The residual ranking divergence on topics 401–450 is
almost entirely attributable to the **index tokenizer difference** (Lucene `StandardTokenizer`
vs. Indri's `word` tokenizer), which the project decided to *accept and catalog* rather than fix.
The tokenizer difference is amplified through proximity belief-combination.

> **UPDATE (TASK-0010) — this "bottom line" was wrong.** A later score-level study on integer
> collections (`lucindri-vs-indri-scores.md`) found the topic divergence was **not** mostly the
> tokenizer: it was real scoring bugs, chiefly `#weight` **dropping the weight of a single-operand
> child** (e.g. `0.1 #combine(#uw8(...))` — exactly the topic shape), plus the `#uwN`/`#odN`
> occurrence-count and OOV bugs. After fixing them, full-topic agreement rose from overlap@10 **0.428
> → 0.666** (stopword-removed) / **0.434 → 0.684** (keep-all-tokens), top-1 **19 → 31–35 / 50**. The
> numbers in §3 below are the pre-fix baseline; the tokenizer is now a smaller residual, not the
> dominant cause. This is why the integer-collection study mattered — the tokenizer-confounded TREC
> comparison could not isolate these bugs.

---

## 1. Setup

| | Indri 5.21 | Lucindri |
|---|---|---|
| Corpus | `/ssd-8TB/corpora/t45minusCR` (TREC disks 4&5 − Congressional Record; 7 gzipped trectext files) | same |
| Documents indexed | **528,155** | **528,155** (exact match) |
| Stemmer | Krovetz | KStem |
| Stopwords | Lucindri's 33-word Lucene `EnglishAnalyzer` set, supplied via `<stopper>` | built-in (hardcoded) |
| Content indexed | whole document text (include-tag regions) | `contentTags=text,hl,head,headline,title,ttl,dd,date,date_time,lp,leadpara` (= Indri's trectext include set) |
| Smoothing | Dirichlet, μ=2000 (`method:dirichlet,mu:2000`) | Dirichlet, μ=2000 (`dirichlet:2000`) |
| Topics | `/ssd-8TB/trec-topics/401-450.metzler.xml` — 50 Indri-language topics | same |
| Retrieved | top 1000, TREC format | top 1000, TREC format |

Operators exercised by the topics: `#weight`, `#combine`, `#1` (ordered window), `#uw8`, `#uw12`
(unordered windows). 46 of 50 topics use proximity operators.

Reproduce with `scripts/compare_trec.sh` (see §7).

---

## 2. Alignment verification (before comparing)

- **Document counts:** identical, 528,155 — both parsers extract the same document set and DOCNOs.
- **Collection size (Indri `dumpindex s`):** 253,367,449 total terms, 664,594 unique.
- **Single-term rankings match.** `#combine(osteoporosis)` top-3 is byte-identical in rank order
  (`FR940525-1-00062`, `LA071290-0133`, `FR940525-1-00078`); scores differ by a small, roughly
  uniform offset (Indri −5.10 vs. Lucindri −4.97) from the tokenizer/doc-length difference below.

---

## 3. Agreement metrics (50 topics, Lucindri vs. Indri)

Overlap@k = |top-k(Indri) ∩ top-k(Lucindri)| / k. RBO with p=0.9.

| Query form | overlap@10 | overlap@100 | overlap@1000 | RBO | top-1 identical |
|---|---|---|---|---|---|
| **Full topics** (term + proximity, weighted) | 0.428 | 0.417 | 0.411 | 0.436 | 19 / 50 |
| **Term-only** (proximity stripped, `#combine(terms)`) | **0.716** | **0.736** | **0.854** | 0.705 | **33 / 50** |

Highest-agreement full topics: 403 (`osteoporosis`, single term) overlap 1.00; 417 (`creativity`)
0.92 RBO. Lowest: 418, 433, 427, 405, 410 (multi-term with proximity), overlap ≈ 0.

**Reading:** stripping the proximity components nearly doubles agreement and lifts set overlap@1000
to 0.85 — i.e. the two engines largely retrieve the *same documents*; they mostly disagree on
*ordering*, and the disagreement is concentrated in the proximity-bearing topics.

---

## 4. Root cause: the index tokenizer (accepted & catalogued)

`LucindriAnalyzer` (`EnglishAnalyzerConfigurable`) tokenizes with Lucene's **`StandardTokenizer`**;
Indri uses its own **`word`** tokenizer. They disagree on punctuation-internal tokens. Ground truth
from a synthetic document, dumping the actual indexed terms from each engine
(`dumpindex … dv`; a small Lucene `PostingsEnum` probe):

| input token | Indri (`word`) | Lucindri (`StandardTokenizer`) |
|---|---|---|
| `don't` | `dont` (apostrophe stripped) | `don't` (kept) |
| `U.S.A.` | `usa` (dots stripped → 1 token) | `u.s.a` (kept) |
| `3.14` | `3` + `14` (**split on the period → 2 tokens**) | `3.14` (1 token) |
| `foo-bar` | `foo` + `bar` | `foo` + `bar` (same) |

The decisive one is decimals/times/ratios: Indri splits `3.14` into two tokens, so number-heavy
TREC news text yields **more Indri tokens**. Measured on one document (FBIS4-68773):

| | body indexed | doc length (tokens) | unique terms |
|---|---|---|---|
| Indri | whole document | **5115** | 1216 |
| Lucindri | same content tags | **3302** | 1217 |

Nearly identical vocabulary, but Indri counts ~55 % more token *occurrences* (numbers, dates,
codes). Consequences:

1. **Document length** feeds the Dirichlet denominator (`docLen + μ`), so every document is
   rescored. The per-document offset is small but **varies by document** (documents dense with
   numbers diverge most), which reorders rankings — this is the term-only 0.72/0.85 divergence.
2. **Token positions** differ (`3.14` is 2 positions in Indri, 1 in Lucene), so proximity-window
   collection frequencies and per-document matches differ. Example: `cf(#1(sugar exports))` = 45 in
   Indri; Lucindri's smoothing implies a collection probability ≈ 6× smaller.

This tokenizer difference was **deliberately accepted** for this comparison (owner decision, TASK-0008
§3); it is a Category-B difference in the sense of `lucindri-vs-indri.md`, not a defect.

---

## 5. Why proximity amplifies the divergence

Indri belief operators (`#combine`, `#weight`) score *every* document a component touches; a
document that lacks a proximity window still contributes that window's **smoothed background**
`log( μ·p(window|C) / (docLen+μ) )`. Because windows are rare, this background is large in
magnitude (≈ −17 for `#1(sugar exports)`) and **document-length-sensitive**. So when a proximity
component carries non-trivial weight, its background dominates the belief average and *amplifies*
the tokenizer-induced document-length differences. Measured on `LA100290-0174` (has `germany`, not
the window):

| query | Indri | Lucindri |
|---|---|---|
| `#combine(germany)` | −3.694 | −3.432 |
| `#combine(germany #1(sugar exports))` | −10.191 | −10.954 |

The pure-term score is slightly *higher* in Lucindri, but the mixed-with-proximity score is
*lower* — the window background is ≈ 1.8 more negative in Lucindri, and it flips the relative
order. This is the mechanism behind the full-topic overlap collapse (0.72 → 0.43).

Individual operators, in isolation, match Indri well (Lucindri-vs-Indri overlap@10): `#combine`
(3 terms) 0.90, `#1` (2 terms) 1.00, `#uw8` (2 terms) 0.80.

---

## 6. `#weight` parity — the central question — CONFIRMED CORRECT

TASK-0008's central hypothesis was that Lucindri's parser recognizes `weight` but has no dedicated
branch in `createBooleanClause`, so it might not reproduce Indri's weighted combination. Investigated
thoroughly:

- **Parse structure is correct.** `#weight(0.8 A 0.1 B …)` parses to
  `IndriAndQuery([BoostQuery(A,0.8), BoostQuery(B,0.1), …])`; per-component weights become Lucene
  `BoostQuery` boosts (via `QueryParserOperatorQuery.addSubquery → setBoost`). Proximity children
  parse to real `IndriNearQuery`/`IndriWindowQuery` (verified by walking the parsed `Query` tree —
  the flat `toString` is misleading but the classes are correct).
- **Scoring math is correct.** Stock Lucene `IndriAndScorer.scoreDoc` computes the boost-weighted
  average `Σ(boostᵢ · sᵢ) / Σ(boostᵢ)` (with `smoothingScore` for non-matching children). For weights
  summing to 1 this equals Indri's normalized `#weight`.
- **Empirically weights are applied.** Extreme-weight tests run *sequentially*:
  `#weight(1.0 germany 0.0 sugar)` reproduces `#combine(germany)` exactly, and
  `#weight(0.0 germany 1.0 sugar)` reproduces `#combine(sugar)` exactly (disjoint rankings). The
  same holds for `#combine` and proximity components. Disjoint multi-component flips track the
  weights and match Indri.

> **Methodology note / correction.** An earlier round of weight-flip tests appeared to show weights
> being "dropped." That was a bug in the *test harness*, not Lucindri: `diff <(run A) <(run B)` ran two
> query files that both wrote the **same** temp path in parallel, so both sides read the last-written
> query and looked identical. Re-running the queries sequentially removed the artifact. Recorded here
> because it materially changed the conclusion.

---

## 7. Tokenizer-free isolation (integer collection) — found a real `#uwN` bug

To separate genuine query-semantics differences from the (accepted) tokenizer noise, we built a
tiny collection whose vocabulary is **integers** (`100`, `200`, `999`). Integers have no punctuation
to split and nothing to stem, so both engines tokenize them **identically** — any remaining
difference is pure query/scoring semantics. Stopwords (`the`, `and`) were interspersed to test
stopword handling. Reproduce with `scripts/trec-comparison/integer-isolation.sh`.

Two findings:

1. **`#uwN` unordered window was off by one (fixed).** On docs `100 200` (span 2), `100 the 200`
   and `100 999 200` (span 3), `100 the and 200` (span 4):

   | operator | Indri | Lucindri (before) | Lucindri (after fix) |
   |---|---|---|---|
   | `#uw2(100 200)` | span ≤ 2 | span ≤ 3 ✗ | span ≤ 2 ✓ |
   | `#odN` ordered | correct | correct | correct |

   Lucindri's unordered window admitted span `N+1` instead of `N`. Root cause: `IndriWindowWeight`
   compared `(end - start)` against the distance, but for single tokens `end == start`, so it used
   the position *gap* (`p₂−p₁`) instead of the inclusive *span* (`p₂−p₁+1`). Fixed by adding `+ 1`
   (`IndriWindowWeight.java`), with regression test `ProximityOperatorTest.unorderedWindowWidthIsExactlyN`
   (fails before, passes after). This matters directly to topics 401–450, which use `#uw8`/`#uw12`.
   Ordered windows (`#odN`) were already correct.

   **Re-running the 50 topics with the fix barely moved aggregate agreement** (overlap@10
   0.428 → 0.426). The full-topic divergence is dominated by *score-scale* differences (the tokenizer
   of §4 and the doc-length gap of §7.2 below), not by which documents a window matches — proximity
   components carry only ~0.1–0.2 weight, so correcting window membership reshuffles little once the
   underlying scores already differ. The `#uwN` fix is a correctness win; lifting the agreement
   numbers requires attacking the scores (tokenizer isolation and/or TASK-0009), for which the
   term-only 0.72 / 0.85 is the current ceiling.

2. **Stopword position handling is identical; document length is not.** Both engines preserve the
   position gap for a removed stopword (so `#1(a b)` over `a the b` correctly does *not* match in
   either — no false phrase hits). But Indri counts removed stopwords in the document length
   (`|d|`) while Lucindri counts only indexed tokens — e.g. the 5-doc collection has Indri length 14
   vs Lucindri 11. Since `|d|` is the Dirichlet denominator, scores differ systematically whenever
   stopwords are present. This is filed as a separate decision: **TASK-0009**.

---

## 7b. Keep-all-tokens run (aligning document length)

The doc-length gap of §7.2 can be neutralized *by configuration*: index both engines with **no
stopword removal** (Indri: no `<stopper>`; Lucindri: `removeStopwords=false`) so every token counts
toward `|d|`, and — crucially — **do not stop the queries either**, so query analysis still matches
the index. The latter was previously impossible (Lucindri hardwired query-side stopword removal);
it is now a query-param option (`<removeStopwords>false</removeStopwords>`).

Collection length aligned to **0.66 %** (Indri 253,367,449 vs Lucindri 251,697,028; residual is the
tokenizer splitting numbers), versus the ~154M-vs-253M gap when Lucindri removed stopwords.

Agreement (Indri vs Lucindri), stopword-removed → keep-all-tokens:

| query form | overlap@10 | overlap@100 | overlap@1000 | RBO | top-1 |
|---|---|---|---|---|---|
| term-only, stopwords removed | 0.716 | 0.736 | 0.854 | 0.705 | 33/50 |
| **term-only, keep all tokens** | **0.790** | **0.795** | **0.894** | **0.798** | **40/50** |
| full topics, keep all tokens | 0.434 | 0.471 | 0.430 | 0.450 | 23/50 |

Aligning document length is a **real, clean gain for term ranking** (overlap@10 0.716 → 0.790, RBO
0.705 → 0.798, top-1 33 → 40). Full-topic agreement barely moved (0.426 → 0.434): proximity
backgrounds still dominate there. The remaining term-only gap is now essentially **the tokenizer
alone** — KStem *is* the Krovetz stemmer, so stemming is not a factor. Reproduce with
`scripts/compare_trec.sh` after setting `removeStopwords=false` / dropping the stopper (see the
`_nostop` variants).

---

## 8. Artifacts & reproducibility

Committed:
- `scripts/compare_trec.sh` — builds both indexes, runs a topics file through both engines, computes
  overlap@k / RBO. Env-configurable (`CORPUS`, `TOPICS`, `INDRI_BIN`, jars, `MU`, `WORK`).
- `scripts/trec-comparison/english-stopwords.txt` + `make-indri-stopper.sh` — the 33-word set and the
  `<stopper>` generator.
- `scripts/trec-comparison/agreement.py` — the overlap@k / RBO calculator.
- `scripts/trec-comparison/indri-build.param.tmpl`, `lucindri.properties.tmpl` — index-build configs.
- `scripts/trec-comparison/integer-isolation.sh` — the tokenizer-free integer probe (§7).

Not committed (large / regenerable): the corpus, the two indexes, and the raw TREC runs live under
`$WORK` (default outside the repo). The 401–450 topics stay at their external path.

Enabling change shipped with this task: `TrecTextDocumentParser` now reads gzipped (`.gz`) trectext
files directly (test `readsGzippedTrec`).
