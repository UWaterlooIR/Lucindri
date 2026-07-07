#!/usr/bin/env bash
# TASK-0008 harness: build a trectext collection with both C++ Indri and Lucindri under aligned
# settings, run the same topics file through each, and report overlap@k / RBO agreement.
#
# Analysis alignment (see docs/trec-comparison.md): identical 33-word stop set (Indri gets it via a
# generated <stopper>), Dirichlet mu on both, matched content tags. Stemmer (Krovetz vs KStem) and
# the index tokenizer (Indri word vs Lucene StandardTokenizer) are ACCEPTED AND CATALOGUED, not
# aligned -- the tokenizer is the dominant, expected source of residual divergence.
#
# Env overrides (all have defaults):
#   CORPUS     dir of gzipped trectext files      (default /ssd-8TB/corpora/t45minusCR)
#   TOPICS     Indri-language topics xml          (default /ssd-8TB/trec-topics/401-450.metzler.xml)
#   INDRI_BIN  dir with IndriBuildIndex/IndriRunQuery (default /ssd-8TB/installs/indri-5.21/bin)
#   JAR_IDX    LucindriIndexer fat jar
#   JAR_SRCH   LucindriSearcher fat jar
#   MU         Dirichlet mu                        (default 2000)
#   COUNT      results per query                   (default 1000)
#   WORK       output dir (indexes + runs)         (default /ssd-8TB/trec-compare)
#   REBUILD    1 to force re-indexing              (default: build only if missing)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"

CORPUS="${CORPUS:-/ssd-8TB/corpora/t45minusCR}"
# TASK-0016: the two engines no longer share one topics file. Lucindri needs the quote-only dialect
# ("..." literals); C++ Indri needs bare terms. Feed each its own per-dialect file (both parse to the
# identical query tree — see docs/data/). Legacy $TOPICS, if set, is honored for the Indri side only.
TOPICS_INDRI="${TOPICS_INDRI:-${TOPICS:-$REPO/docs/data/queries-401-450.metzler.indri.xml}}"
TOPICS_LUCINDRI="${TOPICS_LUCINDRI:-$REPO/docs/data/queries-401-450.metzler.lucindri.xml}"
INDRI_BIN="${INDRI_BIN:-/ssd-8TB/installs/indri-5.21/bin}"
JAR_IDX="${JAR_IDX:-$REPO/LucindriIndexer/target/LucindriIndexer-1.45-jar-with-dependencies.jar}"
JAR_SRCH="${JAR_SRCH:-$REPO/LucindriSearcher/target/LucindriSearcher-1.5-jar-with-dependencies.jar}"
MU="${MU:-2000}"; COUNT="${COUNT:-1000}"; WORK="${WORK:-/ssd-8TB/trec-compare}"
mkdir -p "$WORK"

STOPPER="$(bash "$HERE/trec-comparison/make-indri-stopper.sh")"

# ---- build Indri index ----
if [ "${REBUILD:-0}" = 1 ] || [ ! -d "$WORK/indri_idx" ]; then
  echo ">> building Indri index"
  rm -rf "$WORK/indri_idx"
  sed -e "s#\${INDEX}#$WORK/indri_idx#" -e "s#\${CORPUS}#$CORPUS#" -e "s#\${STOPPER}#$STOPPER#" \
      "$HERE/trec-comparison/indri-build.param.tmpl" > "$WORK/indri-build.param"
  "$INDRI_BIN/IndriBuildIndex" "$WORK/indri-build.param" </dev/null
fi

# ---- build Lucindri index ----
if [ "${REBUILD:-0}" = 1 ] || [ ! -d "$WORK/lucindri_idx" ]; then
  echo ">> building Lucindri index"
  rm -rf "$WORK/lucindri_idx"
  sed -e "s#\${CORPUS}#$CORPUS#" -e "s#\${INDEX_DIR}#$WORK#" -e "s#\${INDEX_NAME}#lucindri_idx#" \
      "$HERE/trec-comparison/lucindri.properties.tmpl" > "$WORK/lucindri.properties"
  java -jar -Xmx8G "$JAR_IDX" "$WORK/lucindri.properties"
fi

# ---- run topics through both engines (each gets its own dialect) ----
QFRAG_I="$WORK/queries.indri.frag";    grep "<query>" "$TOPICS_INDRI"    > "$QFRAG_I"
QFRAG_L="$WORK/queries.lucindri.frag"; grep "<query>" "$TOPICS_LUCINDRI" > "$QFRAG_L"
{ echo "<parameters>"; echo "<index>$WORK/indri_idx</index>"; echo "<rule>method:dirichlet,mu:$MU</rule>";
  echo "<count>$COUNT</count>"; echo "<trecFormat>true</trecFormat>"; cat "$QFRAG_I"; echo "</parameters>"; } > "$WORK/run_indri.param"
{ echo "<parameters>"; echo "<index>$WORK/lucindri_idx</index>"; echo "<rule>dirichlet:$MU</rule>";
  echo "<count>$COUNT</count>"; echo "<trecFormat>true</trecFormat>"; cat "$QFRAG_L"; echo "</parameters>"; } > "$WORK/run_lucindri.xml"

echo ">> running Indri";    "$INDRI_BIN/IndriRunQuery" "$WORK/run_indri.param" </dev/null 2>/dev/null > "$WORK/indri.run"
echo ">> running Lucindri"; java -jar -Xmx8G "$JAR_SRCH" "$WORK/run_lucindri.xml" 2>/dev/null > "$WORK/lucindri.run"

# ---- agreement ----
echo ">> agreement (Indri vs Lucindri):"
python3 "$HERE/trec-comparison/agreement.py" "$WORK/indri.run" "$WORK/lucindri.run"
echo
echo "runs written to $WORK/{indri,lucindri}.run"
