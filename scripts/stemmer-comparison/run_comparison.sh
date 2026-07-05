#!/usr/bin/env bash
# TASK-0013 — confirm Krovetz stemming parity between Lucindri (Lucene KStemFilter) and C++ Indri.
#
# End-to-end, from scratch:
#   1. build an UNSTEMMED Indri index of a trectext corpus (isolates tokenization from stemming),
#   2. dump its vocabulary (raw tokens + collection frequency),
#   3. stem every alphabetic token with BOTH engines (indri_kstem oracle + LucindriKStem harness),
#   4. diff, reporting the cf-weighted disagreement rate + categorized examples.
#
#   usage: run_comparison.sh <corpus.trec(.gz)> <WORK dir>
#   env:   INDRI_HOME (default /ssd-8TB/installs/indri-5.21), FATJAR (searcher jar-with-dependencies)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$1"; WORK="$2"; mkdir -p "$WORK"
INDRI_HOME="${INDRI_HOME:-/ssd-8TB/installs/indri-5.21}"
BIN="$INDRI_HOME/bin"
FATJAR="${FATJAR:-$REPO/LucindriSearcher/target/LucindriSearcher-1.5-jar-with-dependencies.jar}"

# 0. build the two stemmer drivers
g++ -O2 -std=c++11 -w -I"$INDRI_HOME/include" "$HERE/indri_kstem.cpp" \
    -L"$INDRI_HOME/lib" -lindri -lz -lpthread -o "$WORK/indri_kstem"
LUC_OUT="$WORK/luc-classes"; mkdir -p "$LUC_OUT"
javac -cp "$FATJAR" "$HERE/LucindriKStem.java" -d "$LUC_OUT"

# 1. unstemmed Indri index (no <stemmer> element => stemmer none)
cat > "$WORK/nostem.param" <<P
<parameters><index>$WORK/idx_nostem</index><memory>1G</memory><corpus><path>$SRC</path><class>trectext</class></corpus></parameters>
P
rm -rf "$WORK/idx_nostem"
"$BIN/IndriBuildIndex" "$WORK/nostem.param" </dev/null >"$WORK/build.log" 2>&1

# 2. vocabulary: keep purely-alphabetic ascii tokens (Krovetz only rewrites alphabetic words) with cf.
#    dumpindex v prints "term totalCount docCount"; the first row is TOTAL.
"$BIN/dumpindex" "$WORK/idx_nostem" v 2>/dev/null | tail -n +2 \
  | awk '$1 ~ /^[a-z]+$/ {print $1"\t"$2}' > "$WORK/tokens_cf.tsv"
cut -f1 "$WORK/tokens_cf.tsv" > "$WORK/tokens.txt"

# 3. stem both ways (aligned, one token per line in the same order)
"$WORK/indri_kstem" < "$WORK/tokens.txt" > "$WORK/indri_stems.tsv"
java -cp "$FATJAR:$LUC_OUT" LucindriKStem < "$WORK/tokens.txt" > "$WORK/luc_stems.tsv"

# 4. diff
python3 "$HERE/diff_stems.py" "$WORK/tokens_cf.tsv" "$WORK/indri_stems.tsv" "$WORK/luc_stems.tsv" \
  --out "$WORK/disagreements.tsv" --top 45 | tee "$WORK/report.txt"
echo "artifacts in $WORK (tokens.txt, {indri,luc}_stems.tsv, disagreements.tsv, report.txt)"
