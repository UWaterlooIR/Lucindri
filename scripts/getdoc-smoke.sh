#!/usr/bin/env bash
# End-to-end proof for TASK-0020: build a tiny index with the REAL LucindriIndexer jar (trectext, a
# format whose docno path this task fixes) and read it back with the searcher jar's `getdoc` subcommand.
# This is the cross-module proof the per-module unit tests can't be (the two Maven modules don't depend on
# each other). Exits non-zero on any failure.
#
#   scripts/getdoc-smoke.sh
#
# Prereq: both fat jars are built (run `mvn -q -o package -DskipTests` in LucindriIndexer and
# LucindriSearcher, or `mvn install`). The script locates them under each module's target/.
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"

INDEXER_JAR="$(ls "$REPO"/LucindriIndexer/target/LucindriIndexer-*-jar-with-dependencies.jar 2>/dev/null | head -1)"
SEARCHER_JAR="$(ls "$REPO"/LucindriSearcher/target/LucindriSearcher-*-jar-with-dependencies.jar 2>/dev/null | head -1)"
if [[ -z "${INDEXER_JAR:-}" || -z "${SEARCHER_JAR:-}" ]]; then
  echo "FAIL: fat jars not found. Build them first:"
  echo "  (cd $REPO/LucindriIndexer && mvn -q -o package -DskipTests)"
  echo "  (cd $REPO/LucindriSearcher && mvn -q -o package -DskipTests)"
  exit 2
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
DATA="$WORK/data"; mkdir -p "$DATA"
IDX_DIR="$WORK/index"; IDX_NAME="smoke"; IDX="$IDX_DIR/$IDX_NAME"

DOCNO1="wsj-90-01-01"   # hyphens: a docno the text analyzer would split -> proves keyword lookup
DOCNO2="wsj-90-01-02"
cat > "$DATA/corpus.trec" <<EOF
<DOC>
<DOCNO>$DOCNO1</DOCNO>
<TEXT>mountain rescue avalanche training</TEXT>
</DOC>
<DOC>
<DOCNO>$DOCNO2</DOCNO>
<TEXT>climbing rope anchor belay</TEXT>
</DOC>
EOF

cat > "$WORK/index.properties" <<EOF
documentFormat=trectext
indexingPlatform=lucene
dataDirectory=$DATA
indexDirectory=$IDX_DIR
indexName=$IDX_NAME
indexFullText=true
stemmer=kstem
removeStopwords=true
ignoreCase=true
EOF

PASS=0; FAIL=0
ok()   { echo "[PASS] $1"; PASS=$((PASS+1)); }
bad()  { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }

echo "== building index with LucindriIndexer =="
if ! java -jar "$INDEXER_JAR" "$WORK/index.properties" > "$WORK/index.log" 2>&1; then
  cat "$WORK/index.log"; bad "indexer run"; echo "$PASS passed, $FAIL failed"; exit 1
fi
ok "indexer built the index"

echo "== getdoc: exact docno with splitting characters =="
OUT1="$(java -jar "$SEARCHER_JAR" getdoc "$IDX" "$DOCNO1")"; RC1=$?
[[ $RC1 -eq 0 ]] && ok "getdoc($DOCNO1) exit 0" || bad "getdoc($DOCNO1) exit $RC1"
echo "$OUT1" | grep -q "avalanche" && ok "returned the right document (has 'avalanche')" \
  || bad "expected doc1 body, got: $OUT1"
echo "$OUT1" | grep -q "belay" && bad "leaked the other document (has 'belay')" \
  || ok "did not return the other document"

OUT2="$(java -jar "$SEARCHER_JAR" getdoc "$IDX" "$DOCNO2")"
echo "$OUT2" | grep -q "belay" && ok "getdoc($DOCNO2) returns its own body" \
  || bad "expected doc2 body, got: $OUT2"

echo "== getdoc: unknown docno -> exit 1 =="
java -jar "$SEARCHER_JAR" getdoc "$IDX" "no-such-docno" > "$WORK/miss.out" 2>/dev/null; RCM=$?
[[ $RCM -eq 1 && ! -s "$WORK/miss.out" ]] && ok "unknown docno exits 1 with empty stdout" \
  || bad "unknown docno rc=$RCM stdout=$(cat "$WORK/miss.out")"

echo "== legacy batch path still works (dispatcher untouched) =="
cat > "$WORK/queries.xml" <<EOF
<parameters>
  <index>$IDX</index>
  <trecFormat>true</trecFormat>
  <count>10</count>
  <query><number>1</number><text>#combine("avalanche")</text></query>
</parameters>
EOF
BATCH="$(java -jar "$SEARCHER_JAR" "$WORK/queries.xml" 2>/dev/null)"
echo "$BATCH" | grep -q "$DOCNO1" && ok "batch run prints the (non-blank) DOCNO $DOCNO1" \
  || bad "batch run did not print DOCNO; got: $BATCH"

echo
echo "$PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]] || exit 1
