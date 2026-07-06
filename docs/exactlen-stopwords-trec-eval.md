# exactDocumentLength × stopwords — effect on Indri agreement (t45mCR, topics 401–450)

How much do the two Lucindri document-length choices — **keeping stopwords** (`removeStopwords=false`, so
`|d|`/`|C|` count every token like C++ Indri with no `<stopper>`) and **exact document length**
(`exactDocumentLength=true`, TASK-0012, so the scorer uses the true `|d|` instead of Lucene's lossy 1-byte
norm) — move Lucindri's rankings toward C++ Indri's?

## Setup

- **Corpus:** `t45minusCR` (FBIS, FR94, FT91–94, LATimes), `trectext`.
- **Topics/queries:** TREC-8 topics **401–450** (50 Metzler SDM queries), captured verbatim at
  [`docs/data/queries-401-450.metzler.xml`](data/queries-401-450.metzler.xml).
- **Qrels:** `trec8-401-450.rel`. **Smoothing:** Dirichlet μ=2000. **Stemmer:** Krovetz/KStem on both.
- **Engines:** C++ Indri 5.21 (reference) vs Lucindri (this repo, post-1.5).
- **Design:** two experiments; within each, the Lucindri **norm** and **exact** indexes differ by *only*
  the `exactDocumentLength` flag. Config A removes stopwords (33-word set on both engines); config B keeps
  all tokens (no `<stopper>`; queries not stopped either). Tokenizer + Krovetz differences remain (accepted;
  see `docs/trec-comparison.md`), so 100% agreement is not expected.
- **Reproduce:** `scripts/trec-comparison/exactlen_eval.sh` (`REMOVESTOP=true` → A, `REMOVESTOP=false` → B).
  Metrics: `trec_eval` (MAP, P@10) + `agreement.py` (overlap@k, RBO) vs Indri.

## Effectiveness (vs qrels) — essentially a wash

| system | config A (stopwords removed) | config B (keep all tokens) |
|---|---|---|
| C++ Indri | MAP 0.2501, P@10 0.4340 | MAP 0.2501, P@10 0.4340 |
| Lucindri norm | MAP 0.2486, P@10 0.4340 | MAP 0.2500, P@10 0.4380 |
| Lucindri exact | MAP 0.2484, P@10 0.4340 | MAP 0.2499, P@10 0.4340 |

All three are within noise of each other (and of Indri). **This comparison is about *fidelity to Indri’s
ranking*, not retrieval quality** — the length choices barely move MAP/P@10.

## Agreement with C++ Indri — where the effect shows

| config | Lucindri variant | overlap@10 | overlap@100 | overlap@1000 | RBO(0.9) | top-1 identical |
|---|---|---|---|---|---|---|
| **A** — stopwords removed | norm | 0.924 | 0.947 | 0.930 | 0.920 | 42/50 |
| **A** — stopwords removed | exact | 0.924 | 0.952 | 0.935 | 0.923 | 42/50 |
| **B** — keep all tokens | norm | 0.980 | 0.987 | 0.981 | 0.974 | 46/50 |
| **B** — keep all tokens | exact | **0.996** | **0.992** | **0.990** | **0.989** | **49/50** |

## Findings

1. **Keeping stopwords is the big lever.** It lifts overlap@10 from 0.924 → 0.980 and top-1 from 42 → 46/50,
   because it aligns the `|d|`/`|C|` *stopword counting* — the dominant length difference from Indri, which
   always counts stopword positions in `|d|` (see TASK-0009). MAP also rises to match Indri (0.2500 vs 0.2501).
2. **Exact length only pays off once stopwords are kept.** With stopwords removed (config A) it does
   essentially nothing (overlap@10 0.924 → 0.924); with stopwords kept (config B) it pushes overlap@10
   0.980 → **0.996** and top-1 46 → **49/50**. Once the stopword gap is gone, the SmallFloat norm
   quantization becomes the dominant remaining length difference, so removing it closes most of the rest.
3. **They stack.** Both length differences (stopword counting + norm quantization) matter, and only
   **keep-stopwords + exact length** reaches ~0.99 agreement / 49-of-50 identical top docs.

## Implication (why the climbmix full index uses norm + keep-stopwords)

Norm + keep-stopwords already captures the big win (overlap@10 0.980, top-1 46/50, MAP matching Indri to
0.0001). Exact length adds only the last ~1.6% (→0.996, 49/50) — and on a 563M-doc / ~1.4 TB index that
costs ~2 B/doc plus an extra index-time analysis pass per field. Trading that last mile for a smaller/faster
index is a sound call; the big Indri-fidelity gain comes from keeping stopwords, which is free.
(The `scripts/build_full_keepstop.sh` build script exposes both via `EXACTLEN=false|true`.)

## Artifacts

- Script: `scripts/trec-comparison/exactlen_eval.sh` (env: `REMOVESTOP`, `MU`, `COUNT`, `XMX`, paths).
- Queries: [`docs/data/queries-401-450.metzler.xml`](data/queries-401-450.metzler.xml).
- Runs + indexes: `/ssd-8TB/trec-compare/exactlen` (A) and `/ssd-8TB/trec-compare/exactlen_keepstop` (B).
