# Lucindri vs C++ Indri — score-level conformance (integer collections)

Systematic per-document **score** comparison on artificial **integer** collections, where tokenizer,
stemmer, and stopwords are all no-ops, so any score difference is a pure scoring-logic difference
(and every score is checkable against the Dirichlet formula). See **TASK-0010** for the plan.

Setup: index both engines with Krovetz/KStem, `removeStopwords=false`, `ignoreCase=true`, Dirichlet
`mu` set equal; query with `<removeStopwords>false</removeStopwords>`. Scores compared to ~1e-6.

## Collections

```
C1 (|C|=16): d1:"10 20 30"  d2:"10 10 20"  d3:"10 90 20"  d4:"20 10"  d5:"30 40 50 90 90"
C2 (overlaps): o1:"10 20 10 20"  o2:"20 10 20"  o3:"10 10 20 20"  o4:"10 20"
```

## Verdicts

| phase | operator(s) | verdict |
|---|---|---|
| 0 | collection stats (`|C|`, `cf`, `df`, `|d|`, positions) | **match exactly** |
| 1 | single term `#combine(w)` / bare term; μ ∈ {0,1,100,2000,10000} | **match** (~1e-6, = analytic) |
| 2 | `#or`, `#max`, `#wsum` vs **paper Eqs (3),(5),(7)** | **match** (both engines = paper) |
| 2 | `#combine`, `#weight` vs **Indri `WeightedAndNode`** | **match** (normalized; see below) |
| 2 | `#syn` (merged-term Dirichlet) | **match** (~1e-4) |
| 2 | zero-cf (out-of-vocabulary) term in a belief op | **bug found + FIXED** — Lucindri now floors `P(w|C)=0.5/|C|` like Indri (below) |
| 3 | `#1`, `#2` (C1), `#uw3`, `#band` | **match** |
| 3 | `#uwN` occurrence-finding | **bug found + fixed** (see below) |
| 3 | `#odN` ordered window over repeated terms | **bug found + FIXED** — ordered now = unordered = Indri (below) |
| 4 | filters `#scoreif`/`#scoreifnot` vs Indri `#filreq`/`#filrej` | **match** (set + scores; below) |
| 5 | nesting (`#syn` in windows, `#band`, `#max`/`#or`/`#wsum` of proximity, nested `#weight`, absent-term background) | **match** |
| 5 | `#weight`/`#wsum` with a single-operand child (e.g. `#combine(#1(...))`) | **bug found + FIXED** (below) |
| 5 | a filter (`#scoreif`) nested *inside* a belief operator | minor divergence — cataloged (below) |
| 6 | document length quantization (`|d|` in a 1-byte norm) | **resolved (opt-in)** — `exactDocumentLength` matches Indri to ~5e‑6 (below) |

Phase 2 confirms at the score level what TASK-0008 concluded indirectly: the belief operators combine
correctly; the earlier TREC divergence was analysis (tokenizer/doc-length), not the operators.

## Phase 2 — belief operators (formulas & sources)

Truth is the published model, not one engine. Metzler & Croft (2004), *"Combining the language model
and inference network approaches to retrieval,"* IP&M 40(5):735–750, **p.739 Eqs. (2)–(7)** define the
belief functions (with `p_i` = child belief, `w_i` = weights):

| operator | belief | source | both engines |
|---|---|---|---|
| `#not` | `1 − p₁` | Eq. (2) | — |
| `#or` | `1 − Πᵢ(1 − pᵢ)` | Eq. (3) | ✓ = paper |
| `#max` | `max pᵢ` | Eq. (5) | ✓ = paper |
| `#wsum` | `Σwᵢpᵢ / Σwᵢ` | Eq. (7) | ✓ = paper |
| `#combine`/`#weight` | `Σwᵢ·sᵢ / Σwᵢ` (log-belief; = normalized geometric mean) | Indri `WeightedAndNode.score` | ✓ = Indri |

**Important nuance:** the paper's `#and` (= `#combine`) is the **product** `Πpᵢ` (Eq. 4), but Indri's
C++ `WeightedAndNode.score()` computes `Σ(wᵢ·sᵢ)/Σwᵢ` — the **normalized** weighted mean of
log-beliefs (geometric mean of beliefs), so scores stay comparable across queries of different sizes.
So Indri deliberately deviates from the literal Eq. (4); Lucindri's `IndriAndScorer`
(`Σ boostᵢ·sᵢ / Σ boostᵢ`) matches Indri exactly. Verified to ~1e-6 on C1 and, as exact μ-independent
constants (single-doc `s = log(tf/|d|)`), locked by `BeliefOperatorScoreTest`.

### FIXED — out-of-vocabulary (zero collection frequency) term in a belief operator
For a query term absent from the whole collection (`cf = 0`, so `log P(w|C) = −∞`), Indri floors the
collection probability: `TermScoreFunctionFactory.cpp:52` sets
`collectionFrequency = occurrences ? occurrences/|C| : 1/(2·|C|)`, i.e. **`P(w|C) = 0.5/|C|`** — a
deliberate underflow/`−∞` guard (a component term never zeroes the whole belief). Lucindri previously
**dropped** the zero-cf term (it returned no scorer), so e.g. on single doc `"10 10 20"` with `999`
absent it scored `#combine(10 999) = −0.405` (= `s₁₀`) instead of Indri's `−1.099`.

**Fix (now matching Indri to ~1e-6):** (1) `IndriSimilarity.collectionProbability(cf,|C|)` floors
`cf=0` to `1/(2|C|)`, shared by the Dirichlet/JM/default collection models; (2) an OOV term now
produces an `IndriMissingTermScorer` — an empty iterator (it never drives candidates) that supplies
the floored background via `smoothingScore`, so it contributes to a belief combination instead of
being dropped. Locked by `BeliefOperatorScoreTest.oovTermContributesFlooredBackgroundNotDropped`.

**OOV inside a proximity operator (same rule):** a window/phrase containing an OOV term can never
occur (`xcount(#1(10 999)) = 0`, verified in Indri), so it matches no document on its own — but it is
itself a `cf=0` term and contributes the same floored background in a belief combination
(`#combine(30 #1(10 999))` matches Indri to ~1e-6). `IndriTermOpWeight` returns an
`IndriMissingTermScorer` for a proximity whose operand is OOV. (This also fixed a crash: the OOV
operand previously tripped the proximity operand type-check.) Locked by
`BeliefOperatorScoreTest.proximityWithOovOperandNeverMatchesButFloorsInBelief`.

**Note (does an OOV query term match the `[OOV]` stopword placeholders?):** No. When Indri removes a
stopword it leaves an `[OOV]` position that counts toward `|d|`/`|C|` but is **not a term in the
vocabulary** — querying a removed stopword, or any never-seen token, returns `cf = 0` (verified with
`dumpindex v`/`x`). So OOV query terms get the `0.5/|C|` floor and match nothing; they do not collide
with removed-stopword positions.

## Phase 3 findings

### FIXED — `#uwN` skipped valid windows after a leading duplicate
`#uw2(10 20)` on C1 missed **d2 `"10 10 20"`** (Indri matched d1,d2,d4; Lucindri d1,d4) and, because
the missed occurrence also under-counted the collection frequency, mis-scored d1/d4 too (−2.077 as if
`cf`=2 instead of the correct −1.673 with `cf`=3). Cause: `IndriWindowWeight` did `i = current - 1`
unconditionally, so when the window starting at position 0 failed, it skipped position 1 where the
valid window (`10@1,20@2`) begins. Fix: only skip past a *matched* window; otherwise advance by one.
After the fix, C1 and C2 (incl. overlapping windows) match Indri to ~1e-6. Regression test:
`ProximityOperatorTest.unorderedWindowFoundAfterLeadingDuplicate`. (This compounded the width
off-by-one fixed earlier in TASK-0008.)

### `#odN` ordered window over repeated terms — LUCINDRI OVER-COUNTS (ordered ≠ unordered)
The reference here is not an abstract "maximal matching" definition but **internal consistency**:
Indri's `UnorderedWindowNode.cpp` (lines 50–67, author *tds*) documents the intended rule — *"take
each term position pair in turn, and find the smallest window that includes it as the first term …
extents that overlap will get thrown out"* downstream — i.e. **smallest window per start, overlaps
removed → non-overlapping counts**. The same author wrote `OrderedWindowNode.cpp`, so both window
types should count the same way. Measured tf on `"10 10 20 20"` (single doc, so tf = 4·e^score):

| operator | Indri tf | Lucindri tf |
|---|---|---|
| `#2` / `#3` (ordered) | **1** | **2** |
| `#uw2` / `#uw3` / `#uw4` (unordered) | 1 | 1 |

**Indri is self-consistent (ordered = unordered = 1). Lucindri is not (unordered 1, ordered 2).**
Lucindri's *unordered* window already implements the documented overlap-removal (matching Indri);
its *ordered* window (`IndriNearWeight`) does not — it counts the disjoint pairing (10@0→20@2)+
(10@1→20@3) = 2. So **Lucindri's ordered window over-counts**; it is the outlier versus both Indri and
Lucindri's own unordered window.

> **Note on an earlier flip-flop (kept for honesty).** A middle revision claimed the opposite —
> "Lucindri is right, Indri under-counts" — by judging against a maximal-non-overlapping definition.
> That was the wrong yardstick: the correct reference is the documented, self-consistent Indri
> semantics, which Lucindri's *own* unordered window already follows. The ordered-vs-unordered
> comparison (suggested by the owner) is what resolved it.

**This was a Lucindri bug (`IndriNearWeight` ordered-window counting) — now FIXED.** The fix counts
ordered occurrences non-overlapping: a window is only counted if it starts after the previously
counted window ends (`lastCountedEnd`), matching Indri's downstream overlap removal and Lucindri's
own unordered window. After the fix, ordered = unordered = Indri on `"10 10 20 20"` (all tf=1), on
C1/C2, and on the simple cases (`10 20`, `10 30 20`) which were already correct. Locked by
`ProximityCountTest` — **count-level** tests (single-doc index → `tf = round(L·e^score)`, μ-independent)
that assert exact occurrence counts and fail on the pre-fix over-count.

## Phase 4 — filter operators

Lucindri `#scoreif`/`#scoreifnot` (TASK-0006) vs C++ Indri `#filreq`/`#filrej`, both `(condition
scored)`. **Match at set and score level** on C1: `#filreq(c s)` keeps the documents matching the
scored query `s` AND the filter `c`, ranked by `s` (the filter adds no score); `#filrej` keeps `s`'s
matches that do *not* match `c`. Verified across a bare-term filter, a proximity filter (`#1(...)`,
`#uw2(...)`), multi-term scored parts, and an OOV filter (which matches nothing — the `cf=0` floor is
for *scoring*, not *matching*). E.g. `#filreq(30 #combine(10))` = `{d1}` (scored `{d1..d4}` ∩ filter
`{d1,d5}`), scores identical to Indri. Locked by `FilterOperatorTest`.

## Phase 5 — nesting / interactions

Most compositions match Indri to ~1e-6: `#syn` inside `#1`/`#uwN`, `#band` of `#syn`s,
`#max`/`#or`/`#wsum` of proximity children, `#combine` with a nested `#weight`, and — importantly —
an **absent term** in `#combine` contributes its background (both engines average with the missing
term's smoothed belief; sumWeight includes it).

### FIXED — `#weight`/`#wsum` dropped the weight of a single-operand child
On integers (no tokenizer effect), the topic-401 shape diverged by a constant offset:
`#weight(0.8 #combine(t) 0.1 #combine(#1(t)) 0.1 #combine(#uw(t)))`. Root cause: a single-operand
belief node like `#combine(#1(10 20))` was built as a **single-child `IndriAndQuery`**, and the stock
Lucene `IndriAndWeight` single-sub-scorer shortcut returns that child built with a hardcoded boost
`1.0f` — so the `0.1` weight assigned by the enclosing `#weight` was **dropped** (the component was
scored at weight 1.0). Confirmed by decomposition: `#weight(0.5 #combine(10 20) 0.5 #combine(#1(10 20)))`
gave `(0.5·s_A + 1.0·s_B)/1.5` instead of Indri's `(0.5·s_A + 0.5·s_B)/1.0`.

**Fix:** in the parser, a single-operand `#combine`/`#weight`/`#and`/`#wand` (`#combine(X) ≡ X`) is no
longer wrapped in a single-child `IndriAndQuery` — it returns `X` directly, so an enclosing weight
propagates via `BoostQuery` (which works). After the fix the topic-401 shape and
`#weight(0.9 #combine(10) 0.1 #combine(20))` match Indri. Regression test
`BeliefOperatorScoreTest.weightAppliesToSingleOperandCombineChildren`.

> **This corrects TASK-0008.** That study attributed the topic-401 divergence entirely to the
> tokenizer and dismissed a `#weight` weight-drop as a test-harness artifact. The integer collection
> (clean, sequential) shows the weight-drop was **real** and contributed to the divergence — the
> single-term proximity components (`0.1 #combine(#uw8(...))`) had their weights dropped. Re-running
> the TREC comparison after this fix should improve the agreement on the proximity-bearing topics.

### Cataloged — a filter nested inside a belief operator
`#weight(0.5 #scoreif(30 #combine(10)) 0.5 #combine(20))` matches for docs that pass the filter but
diverges for docs that fail it. Indri's `WeightedAndNode` normalizes by `Σ|wᵢ|·(child extent count)`;
a filter yields **0 extents** for a non-passing doc, so Indri renormalizes by the remaining weight
(the doc scores as `#combine(20)` alone). Lucindri keeps the static weight. This dynamic per-doc
renormalization only triggers for a zero-result child, i.e. a filter nested *inside* a belief
operator — an unusual construction (filters are normally top-level). Left as a known minor
difference; a genuinely absent *term* is unaffected (it contributes a background, not zero results).

## TASK-0011 — large-scale fuzzing on a realistic integer collection

Built a realistic integer collection from **20,000 LATimes documents** via Indri's forward index
(`dumpindex il` → global `term→id` by cf → integer trectext → index in both engines), so tokenizer/
stemmer/stopwords are neutral but the distribution is real English (80,053 ids, |C|≈10M; `|C|`, `cf`,
`numDocs`, positions align exactly). Fuzzed **1,500 legal Indri queries** (all operators, random
nesting, both dialects) and diffed per-document scores. **Four real bugs found and fixed** — none
visible on the tiny C1/C2 collections (the 4th needed the full multi-segment LATimes):

1. **cf=0 proximity window dropped instead of floored.** A window whose operands both exist but which
   never co-occurs (e.g. two common terms never adjacent) was dropped; Indri floors it as a `cf=0`
   term. `#weight(0.6 #combine(45 90) 0.4 #1(45 90))` diverged by ~4.8. Fixed in `IndriTermOpWeight`.
2. **Crash: proximity operator as a filter condition.** `#scoreifnot(#uw2(…) …)` threw
   `Cannot call docFreq() when needsStats=false` — a filter condition's operand weights are built in a
   no-scores context. Fixed: always build `TermStates` with stats.
3. **`#syn` with an OOV operand returned empty.** The OOV-operand floor (correct for windows) was
   wrong for synonyms, which union operands and should skip an OOV one. Fixed with an operator hook.
4. **Term-op collection frequency was segment-local, not collection-wide** (found only after scaling to
   the full 131,896-doc / 6-segment LATimes). A proximity/synonym term-op (`#N`, `#uwN`, `#syn`,
   `#band`) derived its `cf` from the inverted list built inside `getScorer(context)`, which only sees
   **one segment**. Lucene aggregates a plain term's `cf` collection-wide (`TermStates.build`), which is
   what Indri uses. The error is invisible on a *matching* document (there `tf` dominates the
   `tf + μ·cf/|C|` numerator) but corrupts the **smoothing/background** score of a term-op that is
   absent from a document in a belief combination (`tf=0` → the score is entirely
   `log(μ·cf/|C| / (|d|+μ))`). Worse, when the term-op was absent from the *whole segment* the code fell
   all the way back to the floored `cf=0` background. Example: `#weight(0.79 #syn(34533 28149) 0.48
   #syn(442 517) 0.58 #1(387 1767))` on its top doc `d130788` (a high docid, in a small late segment)
   diverged by **Δ=2.05** (Indri −9.787 vs Lucindri −11.842); after the fix Δ=0.007 (norm noise).
   Fixed in `IndriTermOpWeight`: compute the derived term's `cf`/`df` once across **all** leaves
   (`ensureCollectionStats`), then score each leaf's positions against those collection-wide stats; an
   absent-in-leaf term-op now returns an empty-positions scorer that still smooths with the correct
   background instead of collapsing to the floor. This was the single largest source of the remaining
   structural (non-filter) divergences at scale.

Each is locked by a regression test (the 4th, `proximityBackgroundUsesCollectionWideCfAcrossSegments`,
uses a two-part `MultiReader` so the term-op is absent from the query doc's segment). After the fixes, **no further structural bug** was found; the
remaining divergences are:

- **Filter nested inside a belief operator** (98 % of the Δ>1 queries): the Phase-5 cataloged
  renormalization difference — Indri's `WeightedAndNode` normalizes by `Σ|wᵢ|·(child extent count)`, so
  a filter that yields 0 extents for a document renormalizes it out, while Lucindri keeps the static
  weight. Common in fuzzed queries but an unusual construction in practice (filters are normally
  top-level). Hard to fix (stock-Lucene `IndriAndScorer`); left cataloged.
- **Norm quantization** (**TASK-0012**): the pervasive small (~0.02) term-level residual, amplified on
  long documents and rare-proximity backgrounds (max per-doc Δ up to a few, always on outlier long
  docs). Lucene's lossy 1-byte doc-length norm vs Indri's exact length. **RESOLVED (opt-in)** — see
  Phase 6.

Reproduce: `scripts/trec-comparison/build_integer_corpus.sh`, `fuzz_queries.py`, `diff_fuzz.py`.

## Phase 6 — exact document length (TASK-0012)

Dirichlet divides by `|d| + μ`. By default Lucindri stores `|d|` in Lucene's lossy 1-byte norm
(SmallFloat), so a 716-token doc is scored as ~664 tokens — the dominant remaining term-level residual
above. Building the index with **`exactDocumentLength=true`** makes the indexer also write each text
field's exact token count (`numTerms`, no `+1` = Indri's `|d|`) to a per-doc `<field>_len` NumericDocValues;
the scorer auto-detects it and scores with the exact length, bypassing SmallFloat. No query-side setting.

Measured on the **full-LATimes integer collection** (131,896 docs; Phase-0 aligned), query `#combine(525)`
over all **15,376** matching docs, μ=2000:

| comparison | max \|Δ\| | mean \|Δ\| |
|---|---|---|
| C++ Indri vs Lucindri **exact** (`exactDocumentLength=true`) | **5×10⁻⁶** | 2.5×10⁻⁶ |
| C++ Indri vs Lucindri **norm** (default) | 0.067 | 8.4×10⁻³ |

Per-doc, on the longest docs (Δnorm grows with `|d|`, Δexact stays 0):

```
docno       |d|      Indri     Lu-exact    Lu-norm     Δexact    Δnorm
d4655      24124   -9.76270    -9.76270   -9.70064   +0.00000  -0.06206
d130305    13311   -9.22842    -9.22842   -9.16095   +0.00000  -0.06747
d79992      7502   -8.24150    -8.24150   -8.20833   +0.00000  -0.03317
```

Cost: **~2 bytes/doc (~0.09 %)** after Lucene DocValues compression (268 KB over the 311 MB index).
No-regression: with the flag **off**, the new-jar index is **byte-identical** (max |Δ| = 0.0) to the
pre-change Lucindri index. Reproduce: build two Lucindri indexes from
`/ssd-8TB/trec-compare/fuzzfull/isrc/integers.trec` (`exactDocumentLength` on/off), score `#combine(525)`
against them and the Indri index at `fuzzfull/i`, and join on docno.

**Full multi-operator fuzz (the TASK-0011 differential, re-run on the exact-length index).** 1,500 fuzzed
queries exercising every belief/proximity/filter operator and their nestings (seed 7), vs the Indri oracle,
per-query max-Δ distribution:

| band (per-query max Δ) | NORM (default) | EXACT (`exactDocumentLength=true`) |
|---|---|---|
| median max Δ | 0.0589 | **0.0000** |
| ≤ 0.05 (noise) | 322 | **1158** |
| 0.05–0.3 (**norm-quantization residual**) | **939** | **103** |
| > 0.3 (structural) | 132 | 132 (unchanged) |

Exact length **eliminates the quantization residual across all operator interactions**, not just single
terms: median max-Δ 0.059 → 0.000; the 0.05–0.3 band collapses 939 → 103. Crucially, **every remaining
divergence is length-independent** (its Δ is ~identical with the flag on or off), so exact length correctly
does not touch it. Confirmed by construction:
- Of queries with **no** nested filter, **884/887 (99.7 %)** now match Indri to ≤ 0.05 (median exactly 0).
- All 103 remaining 0.05–0.3 queries — and the 132 `>0.3` — contain a nested **filter-in-belief**
  (`#scoreif`/`#scoreifnot`): the cataloged Phase-5 `WeightedAndNode` renormalization, a different root
  cause (§5), not quantization.
- The only 3 non-filter queries above 0.05 are all **nested `#band`** (e.g. `#band( 355 #band( 620 395 ) )`),
  with exactΔ ≈ normΔ (0.68/1.10) — a separate pre-existing structural difference, also not length-related.

Reproduce: `fuzz_queries.py 7 1500 800 qi.frag ql.frag`, wrap and run against `fuzzfull/i` +
`el0012/{lexact,lnorm}`, then `diff_fuzz.py fi.run fl_{exact,norm}.run`.

## Reproduce

`scripts/trec-comparison/score_probe.sh` builds C1/C2 in both engines and prints the per-doc
`docno | Indri | Lucindri | Δ` table for any query.
