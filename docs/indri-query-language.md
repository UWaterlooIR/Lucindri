# Indri Query Language — reference guide (and Lucindri support map)

> **Purpose.** A practical, self-authored reference to the *full* Indri structured query
> language, annotated with what **Lucindri** actually implements. Indri is the query language of
> the Lemur Project; Lucindri re-implements a subset on top of Lucene 8.10. This guide is written
> from knowledge of the language plus a reading of Lucindri's parser
> (`LucindriSearcher/.../parser/IndriQueryParser.java`) and query classes — it is **not** a copy
> of the Lemur wiki.
>
> **Authoritative upstream source:** the Lemur Project "Indri Query Language Reference" wiki
> (`https://sourceforge.net/p/lemur/wiki/Indri%20Query%20Language%20Reference/`). Consult it for
> the canonical wording; use this file for how it maps onto Lucindri.

## How Indri scoring works (one paragraph)

Indri combines a **language-modeling** retrieval model with an **inference-network** belief
model. Every query node produces a *belief* (a smoothed probability in [0,1], scored in log
space). **Term/proximity operators** (`#1`, `#uwN`, `#syn`, `#band`, …) don't score directly —
they synthesize a new "term" (a list of occurrences/extents) whose belief is then estimated with
the same smoothing (Dirichlet by default) as a raw term. **Belief operators** (`#combine`,
`#weight`, `#or`, …) combine child beliefs into the document score. A key consequence: belief
operators score **every** document, including those missing a term (the term still contributes
its smoothed background probability) — which is why `#combine(a b)` differs from a Boolean AND.

## Support legend

- ✅ implemented in Lucindri
- ⚠️ implemented but with a caveat / quirk / known bug
- ❌ not implemented in Lucindri

---

## 1. Terms and fields

| Indri | Meaning | Lucindri |
|---|---|---|
| `dog` | Single term. Belief = smoothed P(dog \| doc). Tokenized/stemmed by the analyzer. | ✅ |
| `dog.title` | **Field restriction**: evaluate `dog` only within the `title` field. | ✅ (`term.field`; `:` is also accepted and rewritten to `.`) |
| `dog.(title)` | Same, parenthesized field form. | ❌ (use `dog.title`) |
| `#any:field` | Matches any occurrence of an extent/field type (e.g. `#any:person`). | ❌ |

**Lucindri notes.** The default field is **`fulltext`** — an unqualified term queries it. The
query-time analyzer is **hardcoded** to KStem + stopword removal + lowercasing, so the index must
be built the same way (`stemmer=kstem`, `removeStopwords=true`, `ignoreCase=true`) or terms won't
match. Query stopwords are dropped, so `#1(the summit)` effectively becomes `#1(summit)`.
Wildcards (`dog*`) are **not** supported at all.

## 2. Proximity / term operators (produce a synthesized term)

These require **positions** in the index (Lucindri indexes `DOCS_AND_FREQS_AND_POSITIONS`, so all
text fields qualify).

| Indri | Meaning | Lucindri |
|---|---|---|
| `#N(a b c)` / `#odN(a b c)` | **Ordered window**: terms appear in order, with at most N−1 tokens between adjacent terms. `#1(a b)` = exact adjacent phrase. | ✅ as `#N`. The `#odN` spelling is ❌ (use `#N`). |
| `#uwN(a b c)` | **Unordered window**: all terms within a window of N tokens, any order. | ✅ |
| `#phrase(a b)` | Phrase (implementation-defined, ordered). | ❌ (use `#1(a b)`) |
| `#syn(a b c)` / `#synonym(...)` | **Synonym**: treat all operands as occurrences of one term (merge their position lists). | ✅ (`#syn`) |
| `#wsyn(w1 a w2 b)` | **Weighted synonym**: like `#syn` but occurrences are weighted. | ❌ |
| `#band(a b c)` | **Boolean AND** at term level: all operands must occur in the document (scored as an unordered window the length of the document). | ⚠️ ✅ for plain-term operands; **buggy** for compound operands — see "Known bugs". |
| `#od(a b)` (no N) | Ordered, adjacent. | ❌ (use `#1`) |

**Nesting rule (important).** Operands of a proximity operator must themselves be
position-producing: a plain term or another term-operator (`#1`, `#uwN`, `#syn`). A **belief**
operator (`#or`, `#combine`, …) is **not** a valid operand of a proximity operator — to express a
disjunctive facet *inside* a proximity operator, use `#syn`, not `#or`.

## 3. Belief operators (combine beliefs into a document score)

| Indri | Meaning | Lucindri |
|---|---|---|
| `#combine(a b)` | Equal-weight combination (≈ geometric mean of beliefs); the standard "soft AND". Alias `#and`. | ✅ (`#combine`, `#and`) |
| `#weight(0.6 a 0.4 b)` | **Weighted** combination (weighted geometric mean). | ⚠️ parsed; in Lucindri collapses to a boosted `#combine`-style AND (`IndriAndQuery` with per-clause boosts). Verify weighting matches Indri before relying on it. |
| `#wand(0.6 a 0.4 b)` | Weighted AND (in Indri, essentially `#weight`). | ⚠️ same as above — maps to boosted `IndriAndQuery`. |
| `#wsum(0.6 a 0.4 b)` | **Weighted arithmetic sum** of beliefs (not a product). | ✅ |
| `#or(a b)` | Belief **OR** (noisy-OR: 1 − ∏(1 − bᵢ)). | ✅ |
| `#not(a)` | Belief negation (1 − b). Usually nested, e.g. `#combine(sled #not(dog))`. | ✅ |
| `#max(a b)` | Maximum of child beliefs. | ✅ |
| `#filreq(A B)` | **Filter require**: keep only docs matching A, rank by B. | ❌ name not recognized; and there is no working filter (see `#scoreif`). |
| `#filrej(A B)` | **Filter reject**: drop docs matching A, rank by B. | ❌ name not recognized; no working filter. |
| `#scoreif(A B)` | Require A, score by B. | ❌ **parsed but NOT enforced.** Sets the first clause to `Occur.MUST`, then builds `IndriAndQuery`, which ignores clause occur — so it silently degrades to `#combine(A B)`. Verified empirically: filtering on a zero-document term returns the full, unchanged result set. |
| `#scoreifnot(A B)` | Reject A, score by B. | ❌ same — parsed (`Occur.MUST_NOT`) but not enforced; shares the code path, so it does not reject. |
| `#prior(name)` | Apply a named document prior (e.g. recency, PageRank). | ❌ |

**Default operator.** If a query has no leading operator (e.g. `dog training`), Lucindri wraps
the terms in `#combine` (implicit AND-of-beliefs).

## 4. Field / passage / extent retrieval

| Indri | Meaning | Lucindri |
|---|---|---|
| `#combine[field](...)` | Evaluate the query **within** `field` extents and return field-level results. | ❌ |
| `#combine[passageN:M](...)` | **Passage retrieval**: sliding window of N tokens, stride M; score passages. | ❌ (climbmix docs are already passages) |
| `field.(...)` extent restriction on a subquery | Restrict a subquery to a field's extents. | ❌ (only leaf `term.field` restriction) |

## 5. Numeric / date field operators

| Indri | Meaning | Lucindri |
|---|---|---|
| `#less(field n)`, `#greater(field n)`, `#between(field a b)`, `#equals(field n)` | Numeric field predicates. | ❌ |
| `#date:before(...)`, `#date:after(...)`, `#date:between(...)` | Date predicates. | ❌ |

---

## Quick Lucindri cheat-sheet (what you can actually run)

```
Terms:            dog            dog.title
Ordered window:   #1(white house)         #3(quick fox)
Unordered window: #uw8(climbing rope)
Synonym:          #syn(car automobile)    #syn(#1(new york) #1(nyc))
Boolean AND:      #band(dog cat)          # plain terms only until TASK-0002 lands
Soft AND:         #combine(dog training)
Weighted sum:     #wsum(0.7 dog 0.3 cat)
Weighted AND:     #wand(0.7 dog 0.3 cat)  # boosts applied; verify weighting
OR / NOT / MAX:   #or(dog cat)  #combine(sled #not(dog))  #max(dog wolf)
Filter:           (none that work) — #scoreif / #scoreifnot PARSE but do not filter;
                  they degrade to #combine. See known bugs #3.
Field-disjunction inside proximity → use #syn, NOT #or:
                  #band(#syn(dog canine) #syn(cat feline))   # see known bugs
```

## Known Lucindri bugs affecting this language (see `tasks/TASK-0002.md`)

1. **NPE on a belief operand inside a proximity operator.** `#band(#or(a b) c)` (and the same for
   `#N`/`#uwN`) throws a `NullPointerException` instead of a clear error. Correct idiom is `#syn`,
   not `#or`, inside a proximity operator.
2. **Wrong results for `#band` with ≥2 compound operands.** `#band(#syn(...) #syn(...))` (and
   likely `#uwN`/`#N` with `#syn`/proximity operands) miscounts and typically returns 0 — a
   *broader* facet set can return *fewer* hits, which is impossible for a correct AND. Until
   fixed, prefer the belief cover `#combine(#or(...) ...)` for multi-facet queries; positional
   covers over compound facets are not yet trustworthy.
3. **`#scoreif` / `#scoreifnot` do not filter.** They are parsed (first clause set to
   `Occur.MUST` / `MUST_NOT`) but the resulting `IndriAndQuery` ignores clause occur, so they
   silently behave like `#combine` — no require, no reject. Verified: `#scoreif` on a
   zero-document term returns the full result set, ranked identically to the unfiltered query.
   There is currently **no working hard filter** in Lucindri.

## Practical modeling notes for Lucindri

- Retrieval unit is whatever you indexed. For the climbmix corpus that's a **passage** (one JSONL
  line), not a full document — so document-spanning covers behave differently than in a
  doc-level index.
- Smoothing: default is Indri Dirichlet, μ=2000 (`<rule>dirichlet:2000</rule>`); Jelinek-Mercer
  available as `<rule>jm:0.4</rule>`.
- Output is TREC format; the DOCNO comes from the stored `externalId` field.
