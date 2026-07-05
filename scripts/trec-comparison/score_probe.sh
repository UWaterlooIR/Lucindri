#!/usr/bin/env bash
# TASK-0010 score-level probe. Builds the integer collections C1/C2 in both C++ Indri and Lucindri
# (tokenizer/stemmer/stopwords are no-ops on integers, so any score difference is pure scoring
# logic) and prints the per-document "docno | Indri | Lucindri | delta" table for a query.
#
# Usage:  score_probe.sh '<indri-query>' [mu] [collection:c1|c2]
# Env:    INDRI_BIN, JAR_IDX, JAR_SRCH, WORK
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
INDRI_BIN="${INDRI_BIN:-/ssd-8TB/installs/indri-5.21/bin}"
JAR_IDX="${JAR_IDX:-$REPO/LucindriIndexer/target/LucindriIndexer-1.45-jar-with-dependencies.jar}"
JAR_SRCH="${JAR_SRCH:-$REPO/LucindriSearcher/target/LucindriSearcher-1.5-jar-with-dependencies.jar}"
WORK="${WORK:-/tmp/lucindri-score}"; mkdir -p "$WORK"
Q="${1:?usage: score_probe.sh '<query>' [mu] [c1|c2]}"; MU="${2:-2000}"; COL="${3:-c1}"

# Each TREC tag MUST be on its own line -- Lucindri's parser looks for a line that is exactly <DOC>.
emit_doc(){ printf '<DOC>\n<DOCNO>%s</DOCNO>\n<TEXT>%s</TEXT>\n</DOC>\n' "$1" "$2"; }
emit_c1(){
  emit_doc d1 "10 20 30"; emit_doc d2 "10 10 20"; emit_doc d3 "10 90 20"
  emit_doc d4 "20 10";    emit_doc d5 "30 40 50 90 90"
}
emit_c2(){
  emit_doc o1 "10 20 10 20"; emit_doc o2 "20 10 20"
  emit_doc o3 "10 10 20 20"; emit_doc o4 "10 20"
}

build(){ # $1=collection name
  local d="$WORK/$1"; mkdir -p "$d/src"
  emit_$1 > "$d/src/c.trec"
  if [ ! -d "$d/i" ]; then
    cat > "$d/i.param" <<EOF
<parameters><index>$d/i</index><corpus><path>$d/src/c.trec</path><class>trectext</class></corpus><stemmer><name>krovetz</name></stemmer></parameters>
EOF
    "$INDRI_BIN/IndriBuildIndex" "$d/i.param" </dev/null >/dev/null 2>&1
  fi
  if [ ! -d "$d/l" ]; then
    cat > "$d/l.properties" <<EOF
documentFormat=trectext
indexingPlatform=lucene
dataDirectory=$d/src
indexDirectory=$d
indexName=l
indexFullText=true
fieldNames=
contentTags=text
stemmer=kstem
removeStopwords=false
ignoreCase=true
EOF
    java -jar "$JAR_IDX" "$d/l.properties" >/dev/null 2>&1
  fi
}

build "$COL"
d="$WORK/$COL"
cat > "$WORK/qi.param" <<EOF
<parameters><index>$d/i</index><rule>method:dirichlet,mu:$MU</rule><count>50</count><trecFormat>true</trecFormat><query><number>1</number><text>$Q</text></query></parameters>
EOF
cat > "$WORK/ql.xml" <<EOF
<parameters><index>$d/l</index><rule>dirichlet:$MU</rule><count>50</count><trecFormat>true</trecFormat><removeStopwords>false</removeStopwords><query><number>1</number><text>$Q</text></query></parameters>
EOF
"$INDRI_BIN/IndriRunQuery" "$WORK/qi.param" </dev/null 2>/dev/null | awk '$2=="Q0"{print $3,$5}' | sort > "$WORK/si.txt"
java -jar "$JAR_SRCH" "$WORK/ql.xml" 2>/dev/null | awk '$2=="Q0"{print $3,$5}' | sort > "$WORK/sl.txt"
echo "collection=$COL  mu=$MU  query: $Q"
join -a1 -a2 -e MISS -o 0,1.2,2.2 "$WORK/si.txt" "$WORK/sl.txt" | awk '
  {if($2!="MISS"&&$3!="MISS"){d=$2-$3;f=sprintf("Δ=%+.6f",d);if((d<0?-d:d)>0.001)f=f"  <-- DIVERGE"}
   else f="ONE-SIDE("$2"/"$3")";
   printf "  %-4s Indri=%-13s Lucindri=%-13s %s\n",$1,$2,$3,f}'
