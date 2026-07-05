#!/usr/bin/env bash
# TASK-0011 Phase A: build a realistic integer collection from a real corpus via Indri's forward
# index, then index it in both engines. Integers neutralize tokenizer/stemmer/stopwords; the term
# distribution stays real. See tasks/TASK-0011.md.
#   usage: build_integer_corpus.sh <source.trec(.gz optional, plain here)> <N docs> <WORK dir>
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
BIN="${INDRI_BIN:-/ssd-8TB/installs/indri-5.21/bin}"
JARI="${JAR_IDX:-$REPO/LucindriIndexer/target/LucindriIndexer-1.45-jar-with-dependencies.jar}"
SRC="$1"; N="$2"; WORK="$3"; mkdir -p "$WORK"
# 1. index source in Indri (no stopper, krovetz)
cat > "$WORK/src_idx.param" <<P
<parameters><index>$WORK/src_idx</index><memory>1G</memory><corpus><path>$SRC</path><class>trectext</class></corpus><stemmer><name>krovetz</name></stemmer></parameters>
P
rm -rf "$WORK/src_idx"; "$BIN/IndriBuildIndex" "$WORK/src_idx.param" </dev/null >/dev/null 2>&1
# 2. dump the forward index (all inverted lists, positions) and reconstruct integer docs
"$BIN/dumpindex" "$WORK/src_idx" il 2>/dev/null > "$WORK/il.txt"
mkdir -p "$WORK/isrc"
python3 "$HERE/build_integers.py" "$WORK/il.txt" "$WORK/isrc/integers.trec" "$WORK/vocab.tsv"
# 3. index the integer corpus in BOTH engines with analysis disabled
cat > "$WORK/i.param" <<P
<parameters><index>$WORK/i</index><memory>1G</memory><corpus><path>$WORK/isrc/integers.trec</path><class>trectext</class></corpus></parameters>
P
rm -rf "$WORK/i"; "$BIN/IndriBuildIndex" "$WORK/i.param" </dev/null >/dev/null 2>&1
cat > "$WORK/l.properties" <<P
documentFormat=trectext
indexingPlatform=lucene
dataDirectory=$WORK/isrc
indexDirectory=$WORK
indexName=l
indexFullText=true
fieldNames=
contentTags=text
stemmer=kstem
removeStopwords=false
ignoreCase=true
P
rm -rf "$WORK/l"; java -jar -Xmx8G "$JARI" "$WORK/l.properties" >/dev/null 2>&1
echo "built integer corpus + both indexes under $WORK (verify |C|/cf/positions before scoring)"
