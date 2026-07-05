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
| 3 | `#odN` ordered window, overlapping docs (`#2` on C2) | **DIVERGES** (open — see below) |

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

### OPEN — `#odN` ordered window over-counts on overlapping documents
Minimal repro: collection C2, query `#2(10 20)`. Indri scores o1 −0.954913; Lucindri −0.773 (higher
= more occurrences counted). `#1` matches everywhere; only distance ≥ 2 ordered windows diverge, and
only when positions overlap (C1's `#2` matched). The ordered-window occurrence counter in
`IndriNearWeight` (the posting-pointer advancement, lines ~66–103) needs to be reconciled with
Indri's exact ordered-window counting semantics. Not yet fixed — tracked in TASK-0010 Phase 3.

## Reproduce

`scripts/trec-comparison/score_probe.sh` builds C1/C2 in both engines and prints the per-doc
`docno | Indri | Lucindri | Δ` table for any query.
