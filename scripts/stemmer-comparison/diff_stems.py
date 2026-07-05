#!/usr/bin/env python3
"""TASK-0013 — diff Indri vs Lucindri Krovetz stems.

Inputs (all TAB-separated, aligned on the token in column 1):
  tokens_cf.tsv    token <tab> collection_frequency
  indri_stems.tsv  token <tab> indri_stem
  luc_stems.tsv    token <tab> lucindri_stem

Reports type-level and collection-frequency-weighted disagreement rates (the cf-weighted rate is the
retrieval-relevant number: the fraction of token OCCURRENCES that would stem differently), plus a
categorized breakdown and the highest-impact disagreements. Writes a full disagreement table to
--out if given.
"""
import sys
import argparse


def load(path):
    d = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2:
                d[parts[0]] = parts[1]
    return d


def categorize(token, si, sl):
    """Bucket a disagreement (si = Indri stem, sl = Lucindri stem)."""
    if si == token and sl != token:
        return "indri-noop-lucindri-stems"
    if sl == token and si != token:
        return "lucindri-noop-indri-stems"
    # both changed the token but to different stems
    if si.startswith(sl) or sl.startswith(si):
        return "different-truncation-depth"
    return "different-stem"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cf")
    ap.add_argument("indri")
    ap.add_argument("luc")
    ap.add_argument("--out", help="write full disagreement table (token cf indri luc category)")
    ap.add_argument("--top", type=int, default=40, help="how many top-cf disagreements to print")
    args = ap.parse_args()

    cf = {t: int(v) for t, v in load(args.cf).items()}
    indri = load(args.indri)
    luc = load(args.luc)

    tokens = [t for t in indri if t in luc]
    total_types = len(tokens)
    total_occ = sum(cf.get(t, 0) for t in tokens)

    disagree = []
    dis_types = 0
    dis_occ = 0
    cats = {}
    cats_occ = {}
    for t in tokens:
        si, sl = indri[t], luc[t]
        if si != sl:
            c = cf.get(t, 0)
            cat = categorize(t, si, sl)
            disagree.append((c, t, si, sl, cat))
            dis_types += 1
            dis_occ += c
            cats[cat] = cats.get(cat, 0) + 1
            cats_occ[cat] = cats_occ.get(cat, 0) + c

    print("=== Krovetz stemmer parity: Indri (C++) vs Lucindri (Lucene KStemFilter) ===")
    print(f"alphabetic token types compared : {total_types:,}")
    print(f"token occurrences (|C| alpha)   : {total_occ:,}")
    print()
    print(f"types that DISAGREE             : {dis_types:,}  ({100.0*dis_types/total_types:.4f}% of types)")
    print(f"occurrences that DISAGREE       : {dis_occ:,}  ({100.0*dis_occ/total_occ:.4f}% of occurrences)")
    print(f"  -> cf-WEIGHTED agreement      : {100.0*(total_occ-dis_occ)/total_occ:.4f}%")
    print()
    print("disagreement categories (by types | by occurrences):")
    for cat in sorted(cats, key=lambda k: -cats_occ[k]):
        print(f"  {cat:32s} {cats[cat]:>7,} types | {cats_occ[cat]:>12,} occ")
    print()
    disagree.sort(reverse=True)
    print(f"top {args.top} disagreements by collection frequency:")
    print(f"  {'cf':>10}  {'token':22} {'indri':18} {'lucindri':18} category")
    for c, t, si, sl, cat in disagree[:args.top]:
        print(f"  {c:>10,}  {t:22} {si:18} {sl:18} {cat}")

    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write("token\tcf\tindri\tlucindri\tcategory\n")
            for c, t, si, sl, cat in disagree:
                fh.write(f"{t}\t{c}\t{si}\t{sl}\t{cat}\n")
        print(f"\nfull disagreement table -> {args.out} ({len(disagree):,} rows)")


if __name__ == "__main__":
    main()
