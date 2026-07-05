# Krovetz (KStem) stemming parity: Lucindri vs C++ Indri (TASK-0013)

**Question.** Lucindri and Indri both apply "Krovetz" stemming, but through **two independent
implementations** — Lucindri uses Lucene's Java `org.apache.lucene.analysis.en.KStemFilter`
(+ `KStemData1..8` dictionary tables); Indri uses its own C++ `indri::parse::KrovetzStemmer`. All prior
conformance work (TASK-0010/0011) routed *around* the stemmer (integer collections), so the two had
never been compared. Do they produce the same stems?

**Answer.** Almost — **99.97% of term types and 99.95% of token occurrences agree**. They are **not
identical**. Every disagreement is explained by Indri's Krovetz shipping dictionary/exception tables
that Lucene's port lacks (or implements differently), plus a lower word-length cap in Indri.

## Method (reproducible: `scripts/stemmer-comparison/run_comparison.sh`)

To isolate stemming from tokenization, we compare on the **already-tokenized vocabulary of an
unstemmed index**:

1. Build an **unstemmed** Indri index of LATimes (`t45minusCR/latimes.dat.gz`; 131,896 docs, 267,554
   unique terms, 66,235,630 tokens) — no `<stemmer>` element ⇒ stemmer *none*.
2. `dumpindex … v` → vocabulary with collection frequency; keep purely-alphabetic ASCII tokens
   (Krovetz only rewrites alphabetic words): **248,944 types**, 63,834,473 occurrences.
3. Stem every token with **both** engines:
   - **Indri oracle** `indri_kstem.cpp` — links `KrovetzStemmer::kstem_stemmer` from the installed
     `libindri.a` (no standalone Indri stemmer CLI exists).
   - **Lucindri harness** `LucindriKStem.java` — `KeywordTokenizer → LowerCaseFilter → KStemFilter`,
     the exact filter the production analyzer wires in for `stemmer=KSTEM`.
4. `diff_stems.py` joins on the token and reports type- and **cf-weighted** disagreement (the
   cf-weighted number is the retrieval-relevant one: the fraction of token *occurrences* that stem
   differently).

## Result

```
alphabetic token types compared : 248,944
token occurrences (alpha |C|)   :  63,834,473
types that DISAGREE             : 78        (0.0313% of types)
occurrences that DISAGREE       : 31,897    (0.0500% of occurrences)
cf-WEIGHTED agreement           : 99.9500%
```

One common word, **`later`** (Indri keeps `later`, Lucene stems to `late`), accounts for 24,385 of the
31,897 divergent occurrences — **76% of the entire divergent mass**. Excluding it, cf-weighted
disagreement drops to ~0.012%.

### Categories of disagreement (all 78 types)

| category | types | occ | example (token → Indri / Lucindri) |
|---|---:|---:|---|
| Indri no-ops, Lucindri stems | 29 | 28,342 | `later → later / late`, `weber → weber / web`, `kelly → kelly / kel` |
| different stem | 27 | 1,903 | `thieves → thief / thieve`, `wolves → wolf / wolve`, `crises → crisis / crise` |
| Lucindri no-ops, Indri stems | 6 | 1,197 | `hal → hum / hal`, `sal → sum / sal`, `mal → mum / mal` |
| different truncation depth | 16 | 455 | `gators → gator / gat`, `eerily → eerie / eeri` |

### Root causes (verified in `indri/src/KrovetzStemmer.cpp`)

- **Indri head-word / no-op list** (line ~6816: `"lacewing","later","lioness","sawfly","shoofly",
  "weber"…`) and **proper-noun list** (`kelly`, `abbas`, `weber`) — Indri leaves these unchanged;
  Lucene's KStemmer, lacking them, applies its default suffix rules (`later→late`, `kelly→kel`).
- **Indri irregular-plural conflation table** (`{"thieves","thief"}`, `{"wolves","wolf"}`,
  `{"crises","crisis"}`, `housewives→housewife`, `midwives→midwife`, `appendices→appendix`,
  `vortices→vortex`, …) — Lucene stops at the mechanical `-ves/-es` form (`thieve`, `wolve`, `crise`).
- **Odd Indri dictionary entries** — `hal→hum`, `sal→sum`, `mal→mum`, `bal→bum`, `testes→testicle`.
  Here Lucindri (which no-ops `hal`/`sal`/`mal`) is arguably the *better* behavior. Neither engine is
  uniformly more correct.
- **Word-length cap.** Indri no-ops words at/above `MAX_WORD_LENGTH = 25`; Lucene's cap is higher
  (~50), so 25–49-char tokens stem in Lucene but not Indri (e.g. the 46-char
  `countyconcertsorganizationsorchestrasclassical`). Rare in practice.

## Verdict and decision

The stemmers are **not identical**, but the divergence is **small (0.05% of occurrences) and fully
explained** by dictionary/exception-table differences — not by a defect in Lucindri's analyzer wiring
or an algorithmic mismatch. For typical IR use this is negligible; the one common word that could shift
a specific query's results is `later`.

**Decision: accept the divergence as a known, quantified property and guard it with a regression
test**, rather than porting Indri's dictionary. The golden test
`LucindriAnalyzer/.../KrovetzStemmerParityTest` pins Lucene KStem's exact output on both the agreeing
common vocabulary and the seven highest-impact Indri divergences, so a future Lucene bump that silently
changes stemming fails loudly.

**If exact stemmer parity is ever required** (a separate decision), the fix is to replace/augment
`KStemFilter` with a `TokenFilter` carrying Indri's head-word, proper-noun, and irregular-plural tables
(and matching the 25-char cap); the same golden test's expectations would then flip to the Indri column
and become the parity guard. Not undertaken here — the measured impact does not justify it, and Indri's
own tables include entries (`hal→hum`) that are worse than Lucene's behavior.

## Artifacts

- `scripts/stemmer-comparison/indri_kstem.cpp` — Indri Krovetz oracle (build line in the file header).
- `scripts/stemmer-comparison/LucindriKStem.java` — Lucene `KStemFilter` harness.
- `scripts/stemmer-comparison/diff_stems.py` — cf-weighted diff + categorization.
- `scripts/stemmer-comparison/run_comparison.sh` — end-to-end driver (build index → dump → stem → diff).
- `LucindriAnalyzer/src/test/java/.../KrovetzStemmerParityTest.java` — golden regression test.

Reproduce: `scripts/stemmer-comparison/run_comparison.sh /ssd-8TB/corpora/t45minusCR/latimes.dat.gz <WORK>`.
Large inputs/outputs (index, `tokens.txt`, `*_stems.tsv`, `disagreements.tsv`) stay under `$WORK`
(external), per repo convention.
