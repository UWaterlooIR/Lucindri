#!/bin/bash
# Build a FRESH FULL climbmix index in 8 parts with stopwords KEPT (removeStopwords=false), so stopwords
# are indexed/searchable and counted in |d| and |C| (like C++ Indri with no <stopper>; see TASK-0009 §7b).
#
# Document length mode (env EXACTLEN):
#   EXACTLEN=false (DEFAULT) -> norm: Lucene's compact 1-byte length norm. Smaller and a bit faster to build.
#   EXACTLEN=true            -> also store the exact per-doc length (exactDocumentLength; TASK-0012): scores
#                               with the true |d| (no SmallFloat quantization), at ~2 B/doc + one extra
#                               index-time analysis pass. The closest-to-Indri length config.
# The index dir defaults differ by mode so the two never collide:
#   norm  -> /ssd-8TB/indexes/climbmix_full_keepstop
#   exact -> /ssd-8TB/indexes/climbmix_full_keepstop_exactlen
# Neither touches the existing /ssd-8TB/indexes/climbmix_full. Requires the LucindriIndexer jar from master.
#
# Env overrides: EXACTLEN, CORP, IDXPARENT, STAGE, JAR, PARTS, XMX, FORCE (=1 to overwrite a non-empty IDXPARENT).
set -u
EXACTLEN=${EXACTLEN:-false}
if [ "$EXACTLEN" = true ]; then _tag=keepstop_exactlen; else _tag=keepstop; fi
CORP=${CORP:-/ssd-8TB/corpora/climbmix-400b-corpus-jsonl}
STAGE=${STAGE:-/ssd-8TB/climbmix-staging-$_tag}
IDXPARENT=${IDXPARENT:-/ssd-8TB/indexes/climbmix_full_$_tag}
JAR=${JAR:-$(ls /ssd-8TB/git-repos/Lucindri/LucindriIndexer/target/LucindriIndexer-*-jar-with-dependencies.jar 2>/dev/null | head -1)}
PARTS=${PARTS:-8}
XMX=${XMX:-8G}
FORCE=${FORCE:-0}
LOGDIR="$IDXPARENT/logs"

if [ ! -f "$JAR" ]; then echo "ABORT: indexer jar not found: $JAR (build LucindriIndexer first)"; exit 1; fi
# Refuse to silently clobber an existing index unless FORCE=1.
if compgen -G "$IDXPARENT/part*" > /dev/null 2>&1 && [ "$FORCE" != "1" ]; then
  echo "ABORT: $IDXPARENT already contains part indexes. Set FORCE=1 to overwrite, or pick a new IDXPARENT."
  exit 1
fi
mkdir -p "$IDXPARENT" "$LOGDIR" "$STAGE"

mapfile -t SHARDS < <(ls "$CORP"/*.jsonl.gz | sort)
TOTAL=${#SHARDS[@]}
echo "$(date '+%T') total shards: $TOTAL   (keepStopwords; exactDocumentLength=$EXACTLEN -> $IDXPARENT)"
if [ "$TOTAL" -eq 0 ]; then echo "ABORT: no *.jsonl.gz shards under $CORP"; exit 1; fi
# (No gzip integrity check here — the corpus is trusted. Run scripts/check_shards_gzip.sh separately if needed.)

# 1) staging dirs (symlinks) + per-part properties; distribute TOTAL across PARTS
base=$(( TOTAL / PARTS )); rem=$(( TOTAL - base*PARTS ))
idx=0
for p in $(seq 0 $((PARTS-1))); do
  part=$(printf "part%02d" "$p")
  d="$STAGE/$part"; rm -rf "$d"; mkdir -p "$d"
  rm -rf "$IDXPARENT/$part"                       # clean any prior index for this part (fresh build)
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
removeStopwords=false
ignoreCase=true
exactDocumentLength=$EXACTLEN
EOF
  echo "$part: $n shards [$(basename "${SHARDS[$first]}") .. $(basename "${SHARDS[$last]}")]"
done
echo "assigned $idx of $TOTAL shards"
if [ "$idx" != "$TOTAL" ]; then echo "ABORT: assignment mismatch ($idx != $TOTAL)"; exit 1; fi

# 2) launch PARTS indexers concurrently, wait for all
echo "START $(date '+%F %T')  jar=$JAR  Xmx=$XMX" | tee "$LOGDIR/run.meta"
for p in $(seq 0 $((PARTS-1))); do
  part=$(printf "part%02d" "$p")
  java -jar -Xmx"$XMX" "$JAR" "$IDXPARENT/$part.properties" > "$LOGDIR/$part.log" 2>&1 &
  echo "launched $part pid=$!" | tee -a "$LOGDIR/run.meta"
done
wait
echo "ALL PARTS COMPLETE $(date '+%F %T')" | tee -a "$LOGDIR/run.meta"

# 3) hint: search all 8 parts with one <index> element per part (MultiReader), e.g.
#    <index>$IDXPARENT/part00</index> ... <index>$IDXPARENT/part07</index>
echo "index parts under: $IDXPARENT   (search with one <index>part0N</index> per part)"
