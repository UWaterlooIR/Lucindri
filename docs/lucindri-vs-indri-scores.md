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
| 2 | `#combine`, `#syn`, `#max`, `#or`, `#wsum`, `#weight` | **match** (~1e-5) |
| 2 | `#not(w)` standalone | both return empty (consistent) |
| 3 | `#1`, `#2` (C1), `#uw3`, `#band` | **match** |
| 3 | `#uwN` occurrence-finding | **bug found + fixed** (see below) |
| 3 | `#odN` ordered window over repeated terms | **diverges — Indri under-counts; Lucindri = truth** (below) |

Phase 2 confirms at the score level what TASK-0008 concluded indirectly: `#weight` (and the other
belief operators) combine correctly; the earlier TREC divergence was analysis (tokenizer/doc-length),
not the operators.

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

### `#odN` ordered window over repeated terms — LUCINDRI IS CORRECT, INDRI UNDER-COUNTS
Ordered `#odN` (distance ≥ 2) diverges when a term repeats such that a **disjoint** ordered pairing
exists. Hand-computed against the maximal-non-overlapping-occurrences definition (checked by
isolating single documents, **not** by trusting either engine):

| document | true `#2(10 20)` occurrences | Indri | Lucindri |
|---|---|---|---|
| `10 20` | 1 | 1 | 1 |
| `10 10 20` (one 20) | 1 | 1 | 1 |
| `10 10 20 20` (two 20s) | **2** = (10@0→20@2)+(10@1→20@3) | **1** ✗ | **2** ✓ |

**Lucindri matches the truth; Indri under-counts.** Indri's ordered-window iterator pairs each `10`
with the *nearest* `20` (it enumerates (0,2) and (1,2) — identical for `10 10 20` and `10 10 20 20`,
never considering the second `20`), then dedups the overlap to 1, missing the disjoint (1,3) pairing.
Confirmed via `dumpindex e` (enumeration) vs `dumpindex x` (scoring count), which are themselves
inconsistent in Indri here (enumerates 2 overlapping, scores 1).

**This is an Indri bug, not a Lucindri bug** — do **not** "fix" Lucindri to match it. Open question for
the owner: for strict Indri-compatibility, do we want to *replicate* Indri's under-count, or keep
Lucindri's correct behavior? (The case — repeated terms inside an ordered window — is rare in
practice.) Corrects an earlier mis-characterization that assumed Indri was ground truth.

## Reproduce

`scripts/trec-comparison/score_probe.sh` builds C1/C2 in both engines and prints the per-doc
`docno | Indri | Lucindri | Δ` table for any query.
