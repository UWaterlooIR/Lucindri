#!/bin/bash
# Build the FULL climbmix index in 8 parts. Integrity-check all shards first (fail-fast),
# then run 8 concurrent indexer JVMs and wait for all.
set -u
CORP=/ssd-8TB/corpora/climbmix-400b-corpus-jsonl
STAGE=/ssd-8TB/climbmix-staging-full
IDXPARENT=/ssd-8TB/indexes/climbmix_full
JAR=/ssd-8TB/git-repos/Lucindri/LucindriIndexer/target/LucindriIndexer-1.45-jar-with-dependencies.jar
LOGDIR="$IDXPARENT/logs"
PARTS=8
mkdir -p "$IDXPARENT" "$LOGDIR" "$STAGE"

mapfile -t SHARDS < <(ls "$CORP"/*.jsonl.gz | sort)
TOTAL=${#SHARDS[@]}
echo "$(date '+%T') total shards: $TOTAL"

# 1) integrity check (parallel gzip -t), fail-fast
echo "$(date '+%T') integrity check (parallel gzip -t) ..."
printf '%s\n' "${SHARDS[@]}" | xargs -P 16 -I{} bash -c 'gzip -t "$1" 2>/dev/null || echo "BAD: $1"' _ {} > "$LOGDIR/gzcheck.txt" 2>&1
BAD=$(grep -c BAD "$LOGDIR/gzcheck.txt" 2>/dev/null || true)
echo "$(date '+%T') integrity done, bad=$BAD"
if [ "${BAD:-0}" != "0" ]; then
  echo "ABORT: $BAD bad shard(s) — not indexing. See $LOGDIR/gzcheck.txt:"
  grep BAD "$LOGDIR/gzcheck.txt" | head
  exit 1
fi

# 2) staging dirs + per-part properties; distribute TOTAL across PARTS
base=$(( TOTAL / PARTS )); rem=$(( TOTAL - base*PARTS ))
idx=0
for p in $(seq 0 $((PARTS-1))); do
  part=$(printf "part%02d" "$p")
  d="$STAGE/$part"; rm -rf "$d"; mkdir -p "$d"
  n=$base; [ "$p" -lt "$rem" ] && n=$((base+1))
  first=$idx; last=$((idx+n-1))
  for i in $(seq "$first" "$last"); do ln -s "${SHARDS[$i]}" "$d/"; done
  idx=$((last+1))
  cat > "$IDXPARENT/$part.properties" <<EOF
documentFormat=climbmix
indexingPlatform=lucene
dataDirectory=$d
indexDirectory=$IDXPARENT
indexName=$part
indexFullText=true
fieldNames=
stemmer=kstem
removeStopwords=true
ignoreCase=true
EOF
  echo "$part: $n shards [$(basename "${SHARDS[$first]}") .. $(basename "${SHARDS[$last]}")]"
done
echo "assigned $idx of $TOTAL shards"
if [ "$idx" != "$TOTAL" ]; then echo "ABORT: assignment mismatch ($idx != $TOTAL)"; exit 1; fi

# 3) launch PARTS indexers concurrently @ -Xmx8G, wait for all
echo "START $(date '+%F %T')" | tee "$LOGDIR/run.meta"
for p in $(seq 0 $((PARTS-1))); do
  part=$(printf "part%02d" "$p")
  java -jar -Xmx8G "$JAR" "$IDXPARENT/$part.properties" > "$LOGDIR/$part.log" 2>&1 &
  echo "launched $part pid=$!" | tee -a "$LOGDIR/run.meta"
done
wait
echo "ALL PARTS COMPLETE $(date '+%F %T')" | tee -a "$LOGDIR/run.meta"
