#!/usr/bin/env bash
# Measure the effect of exactDocumentLength on the t45mCR / topics 401-450 comparison.
# Builds two Lucindri indexes with the CURRENT jar that differ ONLY by exactDocumentLength, reuses a
# C++ Indri index, runs the 50 topics through each, and reports MAP/P@10 (trec_eval) plus
# overlap@10/RBO agreement vs Indri (agreement.py).
#
#   REMOVESTOP=true  (default, "config A"): kstem + 33-word stopper, stopwords removed. Isolates the
#                    SmallFloat-quantization effect only (the stopword-length gap, TASK-0009, remains).
#   REMOVESTOP=false ("config B"): keep-all-tokens (no <stopper>; queries not stopped either). Aligns
#                    stopword counting AND removes quantization -> the closest-to-Indri length config.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
REMOVESTOP="${REMOVESTOP:-true}"
CORPUS="${CORPUS:-/ssd-8TB/corpora/t45minusCR}"
TOPICS="${TOPICS:-/ssd-8TB/trec-topics/401-450.metzler.xml}"
QRELS="${QRELS:-/ssd-8TB/qrels/trec8-401-450.rel}"
INDRI_BIN="${INDRI_BIN:-/ssd-8TB/installs/indri-5.21/bin}"
TE="${TE:-/mnt/g/smucker/github-repos/trec_eval/trec_eval}"
JAR_IDX="${JAR_IDX:-$REPO/LucindriIndexer/target/LucindriIndexer-1.45-jar-with-dependencies.jar}"
JAR_SRCH="${JAR_SRCH:-$REPO/LucindriSearcher/target/LucindriSearcher-1.5-jar-with-dependencies.jar}"
MU="${MU:-2000}"; COUNT="${COUNT:-1000}"; XMX="${XMX:-6G}"

if [ "$REMOVESTOP" = true ]; then
  WORK="${WORK:-/ssd-8TB/trec-compare/exactlen}";          STOPPER="$(bash "$HERE/make-indri-stopper.sh")"
  IIDX="${IIDX:-/ssd-8TB/trec-compare/indri_idx}"
else
  WORK="${WORK:-/ssd-8TB/trec-compare/exactlen_keepstop}"; STOPPER=""
  IIDX="${IIDX:-/ssd-8TB/trec-compare/indri_nostop}"
fi
mkdir -p "$WORK"
QFRAG="$WORK/queries.frag"; grep "<query>" "$TOPICS" > "$QFRAG"

# ---- C++ Indri (reference): reuse index if present, else build ----
if [ ! -d "$IIDX" ]; then
  echo ">> building Indri index (removeStopwords=$REMOVESTOP)"; IIDX="$WORK/indri_idx"; rm -rf "$IIDX"
  sed -e "s#\${INDEX}#$IIDX#" -e "s#\${CORPUS}#$CORPUS#" -e "s#\${STOPPER}#$STOPPER#" \
      "$HERE/indri-build.param.tmpl" > "$WORK/indri-build.param"
  "$INDRI_BIN/IndriBuildIndex" "$WORK/indri-build.param" </dev/null
fi
{ echo "<parameters><index>$IIDX</index><rule>method:dirichlet,mu:$MU</rule><count>$COUNT</count><trecFormat>true</trecFormat>"; cat "$QFRAG"; echo "</parameters>"; } > "$WORK/run_indri.param"
echo ">> running Indri"; "$INDRI_BIN/IndriRunQuery" "$WORK/run_indri.param" </dev/null 2>/dev/null > "$WORK/indri.run"

# ---- Lucindri: build norm + exact (identical except exactDocumentLength) ----
build_and_run() { # $1=name  $2=exactflag(true|false)
  local name="$1" exact="$2"
  local d="$WORK/$name"
  echo ">> building Lucindri: $name (exactDocumentLength=$exact, removeStopwords=$REMOVESTOP)"; rm -rf "$d"
  cat > "$WORK/$name.properties" <<EOF
documentFormat=trectext
indexingPlatform=lucene
dataDirectory=$CORPUS
indexDirectory=$WORK
indexName=$name
indexFullText=true
fieldNames=
contentTags=text,hl,head,headline,title,ttl,dd,date,date_time,lp,leadpara
stemmer=kstem
removeStopwords=$REMOVESTOP
ignoreCase=true
exactDocumentLength=$exact
EOF
  java -jar -Xmx"$XMX" "$JAR_IDX" "$WORK/$name.properties" >/dev/null 2>&1
  # Query analysis must match the index: keep stopwords on the query side too when REMOVESTOP=false.
  { echo "<parameters><index>$d</index><rule>dirichlet:$MU</rule><count>$COUNT</count><trecFormat>true</trecFormat><removeStopwords>$REMOVESTOP</removeStopwords><stemmer>kstem</stemmer>"; cat "$QFRAG"; echo "</parameters>"; } > "$WORK/run_$name.xml"
  echo ">> running Lucindri: $name"; java -jar -Xmx"$XMX" "$JAR_SRCH" "$WORK/run_$name.xml" 2>/dev/null > "$WORK/$name.run"
}
build_and_run lucindri_norm  false
build_and_run lucindri_exact true

# ---- evaluate ----
ev() { "$TE" -m map -m P.10 "$QRELS" "$1" 2>/dev/null | awk '{v[$1]=$3} END{printf "map=%s  P@10=%s", v["map"], v["P_10"]}'; }
echo; echo "======== RESULTS (t45mCR, topics 401-450, mu=$MU, removeStopwords=$REMOVESTOP) ========"
printf "%-24s %s\n" "C++ Indri:"     "$(ev "$WORK/indri.run")"
printf "%-24s %s\n" "Lucindri norm:"  "$(ev "$WORK/lucindri_norm.run")"
printf "%-24s %s\n" "Lucindri EXACT:" "$(ev "$WORK/lucindri_exact.run")"
echo "---- agreement vs C++ Indri (overlap@10 / RBO) ----"
echo "Indri vs Lucindri-norm :"; python3 "$HERE/agreement.py" "$WORK/indri.run" "$WORK/lucindri_norm.run"
echo "Indri vs Lucindri-EXACT:"; python3 "$HERE/agreement.py" "$WORK/indri.run" "$WORK/lucindri_exact.run"
echo "norm vs EXACT (how much exact length moved our own ranking):"; python3 "$HERE/agreement.py" "$WORK/lucindri_norm.run" "$WORK/lucindri_exact.run"
echo "runs + indexes under $WORK"
