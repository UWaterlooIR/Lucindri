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

## Reproduce

`scripts/trec-comparison/score_probe.sh` builds C1/C2 in both engines and prints the per-doc
`docno | Indri | Lucindri | Δ` table for any query.
