#!/usr/bin/env bash
# TASK-0018 probe: compare #band scoring (esp. #band nested inside #band) between C++ Indri and
# Lucindri on the realistic full-LATimes integer collection, where tokenizer/stemmer/stopwords are
# no-ops so any difference is pure operator logic. Uses the EXACT-length Lucindri index so document-
# length quantization (TASK-0012) is not a confound.
#
# Usage:
#   band_probe.sh                      # run the built-in isolation battery
#   band_probe.sh '<indri/lucindri query>'   # probe one query (same text works in both dialects)
#
# Env (defaults point at the persisted TASK-0011/0012 workspaces — see tasks/TASK-0018.md):
#   INDRI_BIN   default /ssd-8TB/installs/indri-5.21/bin
#   IIDX        Indri index      default /ssd-8TB/trec-compare/fuzzfull/i
#   LIDX        Lucindri index   default /ssd-8TB/trec-compare/el0012/lexact  (exactDocumentLength=true)
#   JAR_SRCH    default target/LucindriSearcher-2.0-jar-with-dependencies.jar
#   MU          default 2000     COUNT default 8
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
INDRI_BIN="${INDRI_BIN:-/ssd-8TB/installs/indri-5.21/bin}"
IIDX="${IIDX:-/ssd-8TB/trec-compare/fuzzfull/i}"
LIDX="${LIDX:-/ssd-8TB/trec-compare/el0012/lexact}"
JAR_SRCH="${JAR_SRCH:-$REPO/LucindriSearcher/target/LucindriSearcher-2.0-jar-with-dependencies.jar}"
MU="${MU:-2000}"; COUNT="${COUNT:-8}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

for p in "$IIDX" "$LIDX"; do
  if [ ! -d "$p" ]; then
    echo "MISSING index: $p" >&2
    echo "Rebuild the integer collection first: scripts/trec-comparison/build_integer_corpus.sh" >&2
    echo "(Indri index -> fuzzfull/i; Lucindri exact index: index isrc/integers.trec with exactDocumentLength=true)" >&2
    exit 2
  fi
done

probe(){ # $1=label $2=query
  cat > "$WORK/i.param" <<EOF
<parameters><index>$IIDX</index><rule>method:dirichlet,mu:$MU</rule><count>$COUNT</count><trecFormat>true</trecFormat><query><number>1</number><text>$2</text></query></parameters>
EOF
  cat > "$WORK/l.xml" <<EOF
<parameters><index>$LIDX</index><rule>dirichlet:$MU</rule><count>$COUNT</count><trecFormat>true</trecFormat><removeStopwords>false</removeStopwords><query><number>1</number><text>$2</text></query></parameters>
EOF
  echo "### $1 : $2"
  "$INDRI_BIN/IndriRunQuery" "$WORK/i.param" </dev/null 2>/dev/null | awk '$2=="Q0"{print $3,$5}' | sort > "$WORK/si.txt"
  java -jar "$JAR_SRCH" "$WORK/l.xml" 2>/dev/null | awk '$2=="Q0"{print $3,$5}' | sort > "$WORK/sl.txt"
  join -a1 -a2 -e MISS -o 0,1.2,2.2 "$WORK/si.txt" "$WORK/sl.txt" | sort -k2,2g | awk '
    {if($2!="MISS"&&$3!="MISS"){d=$2-$3;f=sprintf("Δ=%+.5f",d);if((d<0?-d:d)>0.01)f=f"  <-- DIVERGE"}
     else f="ONE-SIDE("$2"/"$3")  <-- DIVERGE";
     printf "  %-10s Indri=%-12s Lucindri=%-12s %s\n",$1,$2,$3,f}'
  echo
}

if [ "$#" -ge 1 ]; then
  probe "query" "$1"
else
  echo "== #band isolation battery (integer collection; exact-length Lucindri index) =="
  # Known-good baselines:
  probe "flat-3"        '#band( 492 732 577 )'
  probe "inner-only"    '#band( 732 577 )'
  probe "uw-in-band"    '#band( 492 #uw8( 732 577 ) )'
  probe "band-in-uw"    '#uw8( 492 #band( 732 577 ) )'
  probe "band-in-comb"  '#combine( 492 #band( 732 577 ) )'
  # The failing case (TASK-0018): a #band operand of a #band.
  probe "band-in-band"  '#band( 492 #band( 732 577 ) )'
  probe "band-in-band2" '#band( 355 #band( 620 395 ) )'
fi