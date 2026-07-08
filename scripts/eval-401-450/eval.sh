#!/usr/bin/env bash
# Stability check for the post-TASK-0019/0020/0021 stack: run TREC-8 topics 401-450 against the
# LucindriServer (HTTP) over a fresh keep-stopwords + exact-length t45mCR index, and also through the
# batch searcher, then trec_eval both and compare to the recorded baseline. Because nothing in
# TASK-0019/0020/0021 changed the ranking, the server and batch runs must be identical and the numbers
# must match docs/exactlen-stopwords-trec-eval.md (config B, exact): MAP 0.2499, P@10 0.4340.
#
#   scripts/eval-401-450/eval.sh
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"

INDEX="${INDEX:-/ssd-8TB/trec-compare/stability20/t45_keepstop_exact}"
QUERIES="${QUERIES:-$REPO/docs/data/queries-401-450.metzler.lucindri.xml}"
QRELS="${QRELS:-/ssd-8TB/qrels/trec8-401-450.rel}"
TE="${TE:-/mnt/g/smucker/github-repos/trec_eval/trec_eval}"
SEARCHER_JAR="${SEARCHER_JAR:-$(ls "$REPO"/LucindriSearcher/target/LucindriSearcher-*-jar-with-dependencies.jar | head -1)}"
SERVER_JAR="${SERVER_JAR:-$(ls "$REPO"/LucindriServer/target/LucindriServer-*-jar-with-dependencies.jar | head -1)}"
MU="${MU:-2000}"; COUNT="${COUNT:-1000}"; XMX="${XMX:-6G}"
WORK="${WORK:-/ssd-8TB/trec-compare/stability20/eval}"; mkdir -p "$WORK"

for f in "$INDEX" "$QUERIES" "$QRELS" "$TE" "$SEARCHER_JAR" "$SERVER_JAR"; do
  [[ -e "$f" ]] || { echo "MISSING: $f"; exit 2; }
done

# ---- server run ----
PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
echo ">> starting server on 127.0.0.1:$PORT (removeStopwords=false, exact length auto-detected)"
java -jar -Xmx"$XMX" "$SERVER_JAR" --index "$INDEX" --port "$PORT" \
     --rule "dirichlet:$MU" --stemmer kstem --removeStopwords false --ignoreCase true \
     > "$WORK/server.log" 2>&1 &
SRV_PID=$!
trap '[[ -n "${SRV_PID:-}" ]] && kill "$SRV_PID" 2>/dev/null' EXIT
for _ in $(seq 1 100); do
  python3 -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:$PORT/healthz',timeout=1)" 2>/dev/null && break
  sleep 0.5
done
echo ">> running topics 401-450 through the SERVER"
python3 "$HERE/run_server_queries.py" --port "$PORT" --queries "$QUERIES" --count "$COUNT" --tag Lucindri-server \
     > "$WORK/server.run" 2> "$WORK/server.runlog"
kill "$SRV_PID" 2>/dev/null; SRV_PID=""

# ---- batch run (same index/queries) ----
echo ">> running topics 401-450 through the BATCH searcher"
{ echo "<parameters><index>$INDEX</index><rule>dirichlet:$MU</rule><count>$COUNT</count><trecFormat>true</trecFormat><removeStopwords>false</removeStopwords><stemmer>kstem</stemmer>";
  grep "<query>" "$QUERIES"; echo "</parameters>"; } > "$WORK/run_batch.xml"
java -jar -Xmx"$XMX" "$SEARCHER_JAR" "$WORK/run_batch.xml" 2>/dev/null > "$WORK/batch.run"

# ---- server vs batch: identical ranking? ----
echo; echo ">> server vs batch ranking agreement (must be identical)"
python3 - "$WORK/server.run" "$WORK/batch.run" <<'PY'
import sys
def order(path):
    d={}
    for line in open(path):
        p=line.split()
        if len(p)>=4: d.setdefault(p[0],[]).append(p[2])
    return d
a,b=order(sys.argv[1]),order(sys.argv[2])
topics=sorted(set(a)|set(b))
ident=sum(1 for t in topics if a.get(t)==b.get(t))
print(f"   topics: {len(topics)}   identical docno ranking: {ident}/{len(topics)}")
for t in topics:
    if a.get(t)!=b.get(t):
        print(f"   DIFF topic {t}: server {len(a.get(t,[]))} vs batch {len(b.get(t,[]))} docs")
PY

# ---- trec_eval ----
ev(){ "$TE" -m map -m P.10 "$QRELS" "$1" 2>/dev/null | awk '{v[$1]=$3} END{printf "MAP=%s  P@10=%s", v["map"], v["P_10"]}'; }
echo; echo "======== trec_eval (t45mCR, topics 401-450, mu=$MU, keep-stopwords + exact length) ========"
printf "%-22s %s\n" "SERVER:"            "$(ev "$WORK/server.run")"
printf "%-22s %s\n" "BATCH (static):"    "$(ev "$WORK/batch.run")"
printf "%-22s %s\n" "recorded baseline:" "MAP=0.2499  P@10=0.4340  (docs/exactlen-stopwords-trec-eval.md, config B exact)"
echo "runs under $WORK"
