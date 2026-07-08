#!/usr/bin/env bash
# End-to-end proof for TASK-0019: build a real trectext index with the indexer jar, start the
# LucindriServer fat jar against it, and run the black-box conformance harness (scripts/conformance.py)
# over HTTP. Exits non-zero on any failure.
#
#   scripts/server-smoke.sh
#
# Prereq: fat jars built (mvn -o clean install at the repo root).
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
INDEXER_JAR="$(ls "$REPO"/LucindriIndexer/target/LucindriIndexer-*-jar-with-dependencies.jar 2>/dev/null | head -1)"
SERVER_JAR="$(ls "$REPO"/LucindriServer/target/LucindriServer-*-jar-with-dependencies.jar 2>/dev/null | head -1)"
if [[ -z "${INDEXER_JAR:-}" || -z "${SERVER_JAR:-}" ]]; then
  echo "FAIL: fat jars not found — run 'mvn -o clean install' at the repo root first."; exit 2
fi

WORK="$(mktemp -d)"
SRV_PID=""
cleanup() { [[ -n "$SRV_PID" ]] && kill "$SRV_PID" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT

DATA="$WORK/data"; mkdir -p "$DATA"; IDX="$WORK/index/smoke"
# Multi-line TREC format (the line-oriented TrecTextDocumentParser needs DOCNO/TEXT on their own lines).
cat > "$DATA/corpus.trec" <<EOF
<DOC>
<DOCNO>wsj-90-01-01</DOCNO>
<TEXT>mountain rescue avalanche training. climbers descended safely.</TEXT>
</DOC>
<DOC>
<DOCNO>wsj-90-01-02</DOCNO>
<TEXT>climbing rope anchor belay. the avalanche warning was issued.</TEXT>
</DOC>
EOF
cat > "$WORK/index.properties" <<EOF
documentFormat=trectext
indexingPlatform=lucene
dataDirectory=$DATA
indexDirectory=$WORK/index
indexName=smoke
indexFullText=true
stemmer=kstem
removeStopwords=true
ignoreCase=true
EOF

echo "== building index =="
if ! java -jar "$INDEXER_JAR" "$WORK/index.properties" > "$WORK/idx.log" 2>&1; then
  cat "$WORK/idx.log"; echo "FAIL: indexer"; exit 1
fi

PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
echo "== starting server on port $PORT =="
java -jar "$SERVER_JAR" --index "$IDX" --port "$PORT" > "$WORK/srv.log" 2>&1 &
SRV_PID=$!

# Wait for /healthz to come up.
up=0
for _ in $(seq 1 60); do
  if python3 - "$PORT" 2>/dev/null <<'PY'
import sys, urllib.request
urllib.request.urlopen(f"http://127.0.0.1:{sys.argv[1]}/healthz", timeout=1)
PY
  then up=1; break; fi
  sleep 0.3
done
if [[ "$up" != "1" ]]; then cat "$WORK/srv.log"; echo "FAIL: server did not come up"; exit 1; fi

echo "== conformance harness =="
python3 "$REPO/scripts/conformance.py" --port "$PORT" --query '#combine("avalanche")'
