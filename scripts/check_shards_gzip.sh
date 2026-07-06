#!/bin/bash
# Optional, standalone gzip integrity check for the climbmix shards (parallel `gzip -t`).
# Not part of the build scripts — the corpus is normally trusted. Run this only if you suspect
# corruption. Exit 0 if all shards are good; exit 1 (and list the bad ones) otherwise.
#
# Env overrides: CORP (shard dir), JOBS (parallelism), OUT (report file).
set -u
CORP=${CORP:-/ssd-8TB/corpora/climbmix-400b-corpus-jsonl}
JOBS=${JOBS:-16}
OUT=${OUT:-/tmp/climbmix-gzcheck.txt}

mapfile -t SHARDS < <(ls "$CORP"/*.jsonl.gz 2>/dev/null | sort)
TOTAL=${#SHARDS[@]}
if [ "$TOTAL" -eq 0 ]; then echo "ABORT: no *.jsonl.gz shards under $CORP"; exit 1; fi
echo "$(date '+%T') checking $TOTAL shards under $CORP (gzip -t, -P$JOBS) ..."

printf '%s\n' "${SHARDS[@]}" | xargs -P "$JOBS" -I{} bash -c 'gzip -t "$1" 2>/dev/null || echo "BAD: $1"' _ {} > "$OUT" 2>&1
BAD=$(grep -c BAD "$OUT" 2>/dev/null || true)
echo "$(date '+%T') done, bad=$BAD (report: $OUT)"
if [ "${BAD:-0}" != "0" ]; then
  echo "FAIL: $BAD bad shard(s):"; grep BAD "$OUT" | head
  exit 1
fi
echo "OK: all $TOTAL shards passed gzip -t"
