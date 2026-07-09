#!/usr/bin/env bash
# Bring up the LucindriServer HTTP/JSON service over the FULL climbmix index (all 8 parts as one
# MultiReader). Runs in the foreground — Ctrl-C stops it cleanly. Override anything via env vars.
#
#   scripts/climbmix_server.sh                    # defaults below (port 8080, Xmx16G)
#   PORT=9000 XMX=24G scripts/climbmix_server.sh
#
# The index is norm + KEEP-stopwords, so the server MUST run with removeStopwords=false (the default here)
# to match how it was indexed. /document needs the keyword-externalId index (built by the 2.0 indexer;
# TASK-0020) — climbmix_full_keepstop_v2 has it.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO="$(cd "$HERE/.." && pwd)"

# Point this at the live full-climbmix index. (If the _v2 index is ever renamed back to
# climbmix_full_keepstop, update this default or pass INDEX_PARENT=...)
INDEX_PARENT="${INDEX_PARENT:-/ssd-8TB/indexes/climbmix_full_keepstop_v2}"
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8080}"
XMX="${XMX:-16G}"
RULE="${RULE:-dirichlet:2000}"
STEMMER="${STEMMER:-kstem}"
REMOVESTOP="${REMOVESTOP:-false}"        # KEEP stopwords (index was built with removeStopwords=false)
IGNORECASE="${IGNORECASE:-true}"
MAXPASSAGES="${MAXPASSAGES:-4}"
MAXSUMMARYWORDS="${MAXSUMMARYWORDS:-75}"
JAR="${JAR:-$(ls "$REPO"/LucindriServer/target/LucindriServer-*-jar-with-dependencies.jar 2>/dev/null | head -1)}"

[[ -n "${JAR:-}" && -f "$JAR" ]] || { echo "ABORT: server jar not found (build with 'mvn clean install'): ${JAR:-<none>}"; exit 1; }
[[ -d "$INDEX_PARENT" ]] || { echo "ABORT: index dir not found: $INDEX_PARENT"; exit 1; }

# One comma-separated <index> list over the part00.. sub-indexes -> a single MultiReader.
mapfile -t PARTS < <(find "$INDEX_PARENT" -maxdepth 1 -type d -name 'part*' | sort)
[[ ${#PARTS[@]} -gt 0 ]] || { echo "ABORT: no part* sub-indexes under $INDEX_PARENT"; exit 1; }
IDXARG=$(IFS=,; echo "${PARTS[*]}")

echo ">> climbmix server: ${#PARTS[@]} parts under $INDEX_PARENT"
echo ">> jar: $JAR"
echo ">> http://$HOST:$PORT   (removeStopwords=$REMOVESTOP, rule=$RULE, maxPassages=$MAXPASSAGES," \
     "maxSummaryWords=$MAXSUMMARYWORDS, Xmx=$XMX)"
echo ">> reader open over ~1.5 TB takes a few seconds; poll /healthz until it returns {\"ok\":true}. Ctrl-C to stop."
exec java -jar -Xmx"$XMX" "$JAR" \
  --index "$IDXARG" --host "$HOST" --port "$PORT" \
  --rule "$RULE" --stemmer "$STEMMER" --removeStopwords "$REMOVESTOP" --ignoreCase "$IGNORECASE" \
  --maxPassages "$MAXPASSAGES" --maxSummaryWords "$MAXSUMMARYWORDS"
