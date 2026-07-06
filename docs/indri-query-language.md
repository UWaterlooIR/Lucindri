# Indri Query Language & Scoring — a guide to C++ Indri 5.21 and Lucindri (post‑1.5)

> **What this is.** A single, self-contained explainer of how the Indri structured query language is
> *scored* — grounded in the research papers, in the actual computations of **C++ Indri 5.21**, and in
> **Lucindri** (this repository, the post‑1.5 state after the TASK‑0002…0014 conformance work). It is
> written for two audiences: a human user who wants to understand what a query does and why two
> "identical" engines can score differently, and a future coding agent who needs to get up to speed
> fast without re-deriving everything.
>
> **Where the ground truth lives.** Operator *semantics* are pinned by the searcher test suite
> (`BeliefOperatorScoreTest`, `BeliefOperatorTest`, `ProximityOperatorTest`, `ProximityCountTest`,
> `FilterOperatorTest`) and the analyzer tests; the *user-facing* support claims live in the repo
> **`README.md`**; the *empirical* Indri-vs-Lucindri findings live in `docs/lucindri-vs-indri-scores.md`.
> This guide ties those together. Where it disagrees with the README, the README wins for "what is
> supported"; where it disagrees with a test, the test wins for "what it computes."
>
> **Papers referenced** (all under `docs/`, local-read-only, not redistributed): Metzler & Croft 2004,
> *Combining the language model and inference network approaches to retrieval*, IP&M 40(5) — the
> authority for the belief operators; Turtle & Croft, *inference networks*; the Indri tech report; and
> *Indri at TREC 2004*.

---

## 1. The retrieval model in one page

Indri combines a **language-modeling** view of relevance with an **inference-network / belief** view.
Every node in a query produces a **belief** — a smoothed probability in `[0,1]`, always carried in
**log space** to avoid underflow. Two kinds of node:

- **Term & proximity operators** (`dog`, `#1`, `#uwN`, `#syn`, `#band`) do **not** combine beliefs.
  Each *synthesizes a term*: a list of occurrences/extents in each document. Its belief is then
  estimated with the same smoothing (Dirichlet by default) used for a raw term — using the synthesized
  term's own `tf` (occurrences in the doc) and `cf` (occurrences in the collection).
- **Belief operators** (`#combine`, `#weight`, `#or`, `#not`, `#max`, `#wsum`, …) take child beliefs
  and combine them into the document's score.

A defining consequence: **belief operators score every document**, even one missing a query term — the
missing term still contributes its *smoothed background probability*. That is why `#combine(a b)` is a
soft-AND, not a Boolean AND, and why a document with only `a` still gets a (lower) score.

Lucindri implements this on Lucene 8.10 with custom `Query`/`Weight`/`Scorer`/`Similarity` classes
(`IndriTermQuery`, `IndriAndQuery`, `IndriNearQuery`, `IndriWindowQuery`, `IndriSynonymQuery`,
`IndriBandQuery`, `IndriDirichletSimilarity`, …). The stock Lucene `IndriAnd*` classes are reused; the
proximity/synonym/filter machinery is Lucindri's own.

---

## 2. Scoring a single term (the smoothing layer)

For a term `w` in document `d`:

- `tf` = occurrences of `w` in `d`
- `|d|` = document length
- `cf` = occurrences of `w` in the whole collection, `|C|` = total tokens in the collection
- `P(w|C)` = collection (background) probability = `cf / |C|`

**Dirichlet** (default, `μ = 2000`) — `IndriDirichletSimilarity`:

```
score(w,d) = log( (tf + μ·P(w|C)) / (|d| + μ) )
```

**Jelinek–Mercer** (`jm:λ`, default `λ = 0.4`) — `IndriJelinekMercerSimilarity`:

```
score(w,d) = log( (1−λ)·(tf/|d|) + λ·P(w|C) )
```

Both match C++ Indri to ~1e‑6 on tokenizer-neutral collections (TASK‑0010, Phase 1).

### The out-of-vocabulary floor (cf = 0)

If `w` never occurs in the collection, `cf = 0` and `log P(w|C) = −∞`, which would zero out the whole
belief. Indri floors it:

```
P(w|C) = cf/|C|      if cf > 0
P(w|C) = 1/(2·|C|)   if cf = 0        (i.e. "half an occurrence")
```

(C++ Indri: `TermScoreFunctionFactory.cpp:52`, `occurrences ? occurrences/|C| : 1/(2·|C|)`.) Lucindri
matches this in `IndriSimilarity.collectionProbability(cf,|C|)`. An OOV term is **not dropped**: it
produces an `IndriMissingTermScorer` — an empty iterator that never drives candidate documents but
supplies the floored background via `smoothingScore`, so it still lowers the score of every document
in a belief combination. Locked by `BeliefOperatorScoreTest.oovTermContributesFlooredBackgroundNotDropped`.
The same floor applies to a **proximity/synonym term that has zero collection frequency** (e.g. a phrase
whose words exist but never co-occur) — it matches nothing on its own but floors its background in a
belief op.

---

## 3. Belief operators (the combination layer)

Let `pᵢ` be child *beliefs* (probabilities), `sᵢ = log pᵢ` the child *log-beliefs*, and `wᵢ` weights.
Formulas from Metzler & Croft 2004, p.739, Eqs (2)–(7):

| operator | belief | source |
|---|---|---|
| `#not(a)` | `1 − p₁` | Eq. (2) |
| `#or(a b …)` | `1 − Πᵢ(1 − pᵢ)`  (noisy-OR) | Eq. (3) |
| `#max(a b …)` | `maxᵢ pᵢ` | Eq. (5) |
| `#wsum(w₁ a w₂ b …)` | `Σwᵢpᵢ / Σwᵢ`  (weighted arithmetic **mean** of beliefs) | Eq. (7) |
| `#combine` / `#and` | equal-weight case of `#weight` | — |
| `#weight(w₁ a w₂ b …)` / `#wand` | `exp( Σwᵢsᵢ / Σwᵢ )`  (weighted **geometric** mean of beliefs) | see nuance |

### The one nuance every agent must know: `#combine`/`#weight` are a *normalized* geometric mean

The paper's `#and` (Eq. 4) is the bare product `Πpᵢ`. **C++ Indri does not use that.** Its
`WeightedAndNode.score()` computes

```
score = Σᵢ(wᵢ·sᵢ) / Σᵢ(|wᵢ|·childResults.size())
```

— the weight-**normalized** mean of log-beliefs (so scores stay comparable across queries of different
lengths; a 5-term `#combine` is not automatically 5× more negative than a 1-term one). `#combine` is
just this with all `wᵢ = 1`, i.e. the arithmetic mean of the child log-beliefs. Lucindri's
`IndriAndScorer` computes `Σ boostᵢ·sᵢ / Σ boostᵢ` and matches Indri exactly (verified to ~1e‑6;
locked as μ-independent constants by `BeliefOperatorScoreTest`). Do **not** "correct" this to the
paper's product — that would break Indri parity.

> The `childResults.size()` factor in the denominator is what drives the one cataloged filter
> divergence in §5.

---

## 4. Proximity / term operators (how windows are counted)

A proximity operator scans the position lists of its operands and emits a **synthesized inverted list**
of matching windows; that list is then scored exactly like a term (§2), using its own `tf` (windows in
the doc) and `cf` (windows in the collection). Operands must be **position-producing** — a term or
another proximity operator (see the nesting rule below).

| operator | meaning |
|---|---|
| `#N(a b)` (= `#odN`) | **ordered** window: `a … b` in order, ≤ N−1 tokens between adjacent terms. `#1` = exact adjacent phrase. |
| `#uwN(a b …)` | **unordered** window: all operands within a span of N tokens, any order. |
| `#syn(a b …)` | **synonym**: merge the operands' position lists into one term (union). |
| `#band(a b …)` | **Boolean AND** at term level: scored as an unordered window the length of the document. |

### Windows are counted NON-overlapping (this was a real Lucindri bug)

The subtle and important rule: Indri counts **non-overlapping** occurrences. `UnorderedWindowNode.cpp`
(author *tds*) documents it — *"take each term-position pair in turn, find the smallest window that
includes it as the first term; extents that overlap get thrown out."* The same author wrote
`OrderedWindowNode.cpp`, so **ordered and unordered windows count the same way**. Worked example on the
single document `"10 10 20 20"` (so `tf = 4·e^score`):

| operator | Indri tf | Lucindri (pre‑fix) | Lucindri (post‑fix) |
|---|---|---|---|
| `#2`/`#3` (ordered) | **1** | 2 (over-count) | **1** |
| `#uw2`/`#uw3`/`#uw4` | 1 | 1 | 1 |

Lucindri's *unordered* window already removed overlaps; its *ordered* window
(`IndriNearWeight`) did not — it counted the disjoint pairing `(10@0→20@2)+(10@1→20@3) = 2`. **Fixed**
(TASK‑0010): an ordered window is counted only if it starts after the previously-counted window ends
(`lastCountedEnd`). Now ordered = unordered = Indri. Locked by `ProximityCountTest` (single-doc,
`tf = round(L·e^score)`, μ-independent). Two related window bugs were also fixed: a `#uwN` **width**
off-by-one (the inclusive span must be `end − start + 1`) and a `#uwN` that **skipped valid windows
after a leading duplicate** (e.g. missed `"10 10 20"` for `#uw2(10 20)`).

### The collection-frequency-must-be-collection-wide bug (TASK‑0011, 9th bug)

A proximity/synonym term's `cf` must be aggregated across the **whole collection**, not the current
Lucene segment. Lucindri originally computed it from the per-segment inverted list built inside
`getScorer(context)`. On a multi-segment index this gave a **segment-local** `cf`, which barely moves a
*matching* score (there `tf` dominates the `tf + μ·cf/|C|` numerator) but **corrupts the background**
of a proximity term that is *absent* from a document in a belief combination (`tf = 0` → the score is
entirely `log(μ·cf/|C| / (|d|+μ))`). Fixed in `IndriTermOpWeight.ensureCollectionStats`, which sums
`cf`/`df` across all leaves once and scores each segment's positions against those collection-wide
stats. Locked by `proximityBackgroundUsesCollectionWideCfAcrossSegments`. (This was the single largest
source of structural divergence at full-LATimes scale.)

### Nesting rule

Operands of a proximity operator must produce positions. A **belief** operator (`#or`, `#combine`, …)
is **not** a valid operand of a proximity operator — passing one is a clear error, not a silent wrong
answer. To express a disjunctive facet *inside* a proximity operator, use `#syn`, not `#or`:
`#1(#syn(us usa) senate)`, never `#1(#or(us usa) senate)`.

---

## 5. Filter operators

| Lucindri | C++ Indri | meaning |
|---|---|---|
| `#scoreif(C S)` | `#filreq(C S)` | keep only documents matching condition `C`, rank them by `S` |
| `#scoreifnot(C S)` | `#filrej(C S)` | keep documents **not** matching `C`, rank by `S` |

The filter contributes **no score** — a document's score under `#scoreif(C S)` equals its score under
`S` alone; `C` only gates membership. Lucindri emits native `IndriFilterRequireQuery` /
`IndriFilterRejectQuery` (TASK‑0006), matching Indri at both the passing-set and the score level
(`FilterOperatorTest`). An OOV condition matches nothing (the `cf=0` floor is for *scoring*, not
*matching*).

### Cataloged difference: a filter nested *inside* a belief operator

`#weight(0.5 #scoreif(30 #combine(10)) 0.5 #combine(20))` matches Indri for documents that pass the
filter but diverges for those that fail. Recall the `#weight` denominator `Σ|wᵢ|·childResults.size()`
(§3): in Indri a filter that yields **0 extents** for a non-passing document drops out of *both*
numerator and denominator, so the document renormalizes to `#combine(20)` alone. Lucindri keeps the
static weight. This only triggers for a **zero-result child inside a belief op** — an unusual
construction (filters are normally top-level), so it is left cataloged rather than fixed (it would
require replacing the stock Lucene `IndriAndScorer`). A genuinely absent *term* is unaffected — it
contributes a background, not zero results.

---

## 6. Indri vs Lucindri — the differences that remain

After the TASK‑0002…0014 work, the two engines agree to ~1e‑6 on tokenizer-neutral collections and are
statistically indistinguishable on TREC (t45mCR, topics 401–450: overlap@10 ≈ 0.92, MAP 0.2486 vs
0.2501, P@10 identical). The residual differences, all understood and quantified:

| # | difference | status | where |
|---|---|---|---|
| A | **Document length & stopwords** — Indri counts removed-stopword positions in `|d|`; Lucindri counts only indexed tokens. Every score shifts when a doc has stopwords. | **open decision** | **TASK‑0009** |
| B | **Norm quantization** — by default Lucindri stores `|d|` in a lossy **1-byte Lucene norm** (SmallFloat); Indri uses the exact length. ~0.02–0.07 log divergence on long docs; invisible on ≤ ~40-token docs. **Resolved:** the index-time flag `exactDocumentLength=true` stores the exact `|d|` in NumericDocValues and scores with it (matches Indri to ~5e‑6). Default off = norm (unchanged). | **resolved (opt-in)** | **TASK‑0012** |
| C | **Krovetz stemming** — two independent implementations; agree on **99.95%** of token occurrences. | accepted + guarded | **TASK‑0013** |
| D | **Filter-in-belief renormalization** — §5. | cataloged | — |

Everything else — collection stats, single-term Dirichlet/JM, all belief operators, all proximity
operators (counting + backgrounds), synonyms, `#band`, top-level filters, OOV flooring — **matches**.

### A. Document length & stopwords (TASK‑0009)

Dirichlet divides by `|d| + μ`, so `|d|` matters. On the document `"5 the 7 and 5 9 the 7"` (8 raw
tokens, 3 stopwords), C++ Indri reports `|d| = 8` (stopword positions counted) while Lucindri reports
`|d| = 5` (indexed content tokens only). Both preserve the *position gaps* so proximity is unaffected,
but the length differs, and `|C| = Σ|d|` differs too, shifting `P(w|C)`. A **configuration-only**
workaround exists: index and query both engines with stopword removal **off** (`removeStopwords=false`;
Indri: no `<stopper>`). That aligns collection length to 0.66% and raised term-only overlap@10 from
0.716 → 0.790 — at the cost of stopwords being searchable. True parity (stopwords count toward `|d|`
but stay non-searchable, as Indri's OOV placeholders do) needs code. **Decision pending in TASK‑0009.**

### B. Norm quantization (baked into Lucene 8.10) — RESOLVED, opt-in (TASK‑0012)

By default the stored document length is a **single byte**: the standalone indexer builds norms with
`LMDirichletSimilarity`, whose `SimilarityBase.computeNorm` stores `SmallFloat(numTerms)` (no `+1`); at
query time `getLengthValue(norm) = LENGTH_TABLE[…]` decodes that lossy byte. So a 716-token document is
scored as if it were ~664 tokens (nearest SmallFloat bucket) — e.g. term `45`: Indri −4.85425 vs Lucindri
−4.83491 (Δ ≈ 0.019). Toy docs (≤ ~40 tokens) are exact because SmallFloat is lossless there — which is
why this hid until realistic lengths. (Note: the query-time `IndriSimilarity.computeNorm` — which returns
`numTerms + 1` and uses a `getPosition()` length — is **not** what runs here; it is reachable only via the
Solr `IndriDirichletSimilarityFactory`. See its Javadoc. So today's effective `|d|` already has no `+1`.)

**Resolution (TASK‑0012):** build the index with **`exactDocumentLength=true`** and the indexer also writes
each text field's exact token count (`numTerms`, no `+1` = Indri's `|d|`) to a per-doc `<field>_len`
NumericDocValues; the scorer **auto-detects** that DocValues and scores with the exact length, bypassing
SmallFloat. Measured on the full-LATimes integer collection (131,896 docs), `#combine(525)` over all 15,376
matching docs: **Lucindri-exact vs C++ Indri max |Δ| = 5×10⁻⁶** (mean 2.5×10⁻⁶), on docs up to 24,124
tokens — versus max |Δ| = 0.067 for the norm path. Storage cost ≈ **2 bytes/doc (~0.09%)** after Lucene's
DocValues compression. Default (flag **off**) is unchanged: the new-jar norm index is **byte-identical**
(max |Δ| = 0.0) to the pre-change index. This was the dominant remaining term-level residual and the
cap on achievable TREC agreement.

### C. Krovetz stemming (TASK‑0013)

Lucindri stems with Lucene's `KStemFilter`; Indri with its own C++ `KrovetzStemmer`. Over the 248,944
alphabetic types of an unstemmed LATimes index they agree on **99.97% of types / 99.95% of
occurrences**. The 78 divergent types are all Indri dictionary/exception-table entries Lucene's port
lacks — a head-word no-op list (`later→later` vs `late`; the single most common divergence, 76% of the
mass), a proper-noun list (`kelly→kelly` vs `kel`), and irregular-plural conflations (`thieves→thief`
vs `thieve`, `wolves→wolf` vs `wolve`) — plus a lower Indri word-length cap (25 vs ~50). Some Indri
entries are actually *worse* (`hal→hum`). **Decision: accepted**, not fixed; guarded by
`KrovetzStemmerParityTest`. Full write-up: `docs/krovetz-comparison.md`.

---

## 7. Operator support map (Lucindri)

The parser aliases `#odN → #N` (so `#od5` ≡ `#5`, the ordered window) and **rejects** any operator not
on this allow-list with a clear error, rather than silently degrading it to `#and` (TASK‑0014). A
single-operand window is the term itself (`#1(x) ≡ x`, matching Indri — e.g. `#1(the house)` after the
stopword is dropped).

**Implemented & test-covered** (the allow-list):

- Belief: `#combine` (= `#and`), `#weight`, `#wand`, `#wsum`, `#or`, `#not`, `#max`
- Proximity/term: `#N` (= `#odN`, ordered), `#uwN` (unordered), `#syn`, `#band`
- Filter: `#scoreif`, `#scoreifnot`
- Terms & fields: `dog`, `dog.title` (field restriction; `:` is rewritten to `.`). Default field is
  **`fulltext`**. A bare query (`dog training`) is wrapped in `#combine`.

**Not implemented — rejected with a clear error** (post‑TASK‑0014): `#wsyn`, `#prior`, passage/field
retrieval (`#combine[field]`, `#combine[passageN:M]`), numeric/date operators (`#less`, `#greater`,
`#between`, `#equals`, `#date:*`), wildcards (`dog*`), and the `#filreq`/`#filrej` spellings (use
`#scoreif`/`#scoreifnot`).

---

## 8. Practical guidance (users & coding agents)

- **Query analysis must match index analysis.** Lucindri's query-time `stemmer` / `removeStopwords` /
  `ignoreCase` (query-XML elements, defaults `kstem`/`true`/`true`) must equal how the index was built,
  or terms won't match. This is configurable now (it used to be hardcoded).
- **`#combine` is a soft-AND, not a filter.** To *require* a term, use `#scoreif`. Documents missing a
  term still score (background).
- **Keep filters at the top level; don't nest `#scoreif`/`#scoreifnot` inside `#weight`/`#combine`.** At
  the top level both engines agree exactly. Nested inside a belief operator, a document that *fails* the
  filter is an under-specified case the two engines resolve differently — Indri renormalizes the failed
  clause out (scoring the doc as if that clause weren't there), Lucindri keeps its static weight — so
  scores diverge for filter-failing docs. Prefer `#scoreif(C #weight(...))` over
  `#weight(... #scoreif(C ...) ...)`. See §5.
- **Use `#syn`, not `#or`, inside proximity operators.** `#or` is a belief op and is not a valid
  proximity operand.
- **Windows count non-overlapping**, and ordered `#N` = unordered `#uwN` in counting rule. Don't expect
  `#2("10 10 20 20")` to count 2.
- **By default, expect ~0.02–0.07-level score differences from Indri on long documents** (norm
  quantization, §6B) and occasional stem divergences (§6C). These shift near-ties in rankings; they are
  not bugs in the operators. Index with **`exactDocumentLength=true`** to remove the length quantization
  (matches Indri to ~5e‑6; §6B).
- **Scores are negative log-probabilities.** More negative = worse. Lucindri runs `java -jar` without
  `-ea`; assertions are disabled in the searcher's Surefire config on purpose (Indri scores trip
  Lucene's internal `assert score >= 0`).

---

## 9. Maintenance note

This guide documents system behavior that open decisions may change:

- **TASK‑0009** (document length / stopwords) — still an open decision; if implemented, §6A here must be
  updated to reflect the new state (that task carries a reminder to do so).
- **TASK‑0012** (exact document length vs norm quantization) — **done** (opt-in `exactDocumentLength`
  index flag); §6B reflects the shipped behavior.
- **TASK‑0014** (`#odN` alias + reject unknown operators) — **done**; §7 reflects the shipped behavior.

When the code moves, update this guide **and** the pinning tests; do not let the two drift.
