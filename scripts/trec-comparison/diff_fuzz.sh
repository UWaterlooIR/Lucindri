#!/usr/bin/env bash
# TASK-0011 §C differential harness, adapted for the TASK-0016 quote-only grammar.
#
# Fuzz a large batch of legal queries and diff per-document scores between C++ Indri and Lucindri on the
# INTEGER collection (build it first with build_integer_corpus.sh — integers survive the tokenizer/
# stemmer/stopper unchanged, so any score divergence is a real query/scoring bug, not analysis).
#
# The query generator (fuzz_queries.py) emits the SAME query tree in three dialects (identical RNG
# draws, only term formatting differs), and we route each to the engine that speaks it:
#   * Indri run           <- bare terms        (525)            — the reference oracle, run ONCE.
#   * Lucindri "quoted"   <- analyzed splices   ("525")          — the quote-only default path.
#   * Lucindri "#token"   <- verbatim splices   (#token("525"))  — the verbatim path.
# On an integer corpus "525", #token("525") and bare 525 are the same single token, so BOTH Lucindri
# runs must match the one Indri run. The #token run therefore differentially tests the #token code path
# for free. (Field restriction and stem/stop knobs are untested here by construction — integers are
# stem/stop-invariant and we never had field tests — exactly as noted in the TASK-0016 request.)
#
# Env (defaults suit a corpus built by build_integer_corpus.sh into $WORK):
#   WORK      work dir holding the integer indexes + runs   (default /ssd-8TB/trec-compare/intfuzz)
#   IIDX      C++ Indri integer index (keep-all-tokens)      (default $WORK/i)
#   LIDX      Lucindri integer index (removeStopwords=false) (default $WORK/l)
#   SEED      fuzz seed                                       (default 1)
#   M         number of queries                              (default 2000)
#   VFREQ     "frequent id" ceiling for term sampling        (default 800)
#   MU        Dirichlet mu                                    (default 2000)
#   COUNT     results per query                               (default 1000)
#   INDRI_BIN dir with IndriRunQuery                          (default /ssd-8TB/installs/indri-5.21/bin)
#   JAR_SRCH  LucindriSearcher fat jar
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
WORK="${WORK:-/ssd-8TB/trec-compare/intfuzz}"; mkdir -p "$WORK"
IIDX="${IIDX:-$WORK/i}"; LIDX="${LIDX:-$WORK/l}"
SEED="${SEED:-1}"; M="${M:-2000}"; VFREQ="${VFREQ:-800}"; MU="${MU:-2000}"; COUNT="${COUNT:-1000}"
INDRI_BIN="${INDRI_BIN:-/ssd-8TB/installs/indri-5.21/bin}"
JAR_SRCH="${JAR_SRCH:-$REPO/LucindriSearcher/target/LucindriSearcher-1.5-jar-with-dependencies.jar}"

for d in "$IIDX" "$LIDX"; do
  [ -d "$d" ] || { echo "ERROR: missing index $d — build it first with build_integer_corpus.sh" >&2; exit 1; }
done

# ---- generate the three dialect query fragments from one seed ----
echo ">> generating $M queries (seed=$SEED) in three dialects"
python3 "$HERE/fuzz_queries.py" "$SEED" "$M" "$VFREQ" \
  "$WORK/q.indri.frag" "$WORK/q.lucindri.frag" "$WORK/q.token.frag"

# ---- wrap fragments into run files (Indri param / Lucindri xml) ----
{ echo "<parameters><index>$IIDX</index><rule>method:dirichlet,mu:$MU</rule><count>$COUNT</count><trecFormat>true</trecFormat>";
  cat "$WORK/q.indri.frag"; echo "</parameters>"; } > "$WORK/run_indri.param"
lucindri_run() { # $1=fragfile  $2=outfile.xml   (keep stopwords: integers are kept-token, matches index)
  { echo "<parameters><index>$LIDX</index><rule>dirichlet:$MU</rule><count>$COUNT</count><trecFormat>true</trecFormat><removeStopwords>false</removeStopwords><stemmer>kstem</stemmer>";
    cat "$1"; echo "</parameters>"; } > "$2"
}
lucindri_run "$WORK/q.lucindri.frag" "$WORK/run_lucindri.xml"
lucindri_run "$WORK/q.token.frag"    "$WORK/run_token.xml"

# ---- run: Indri once (oracle), Lucindri twice (quoted + #token) ----
echo ">> running Indri (bare terms)";        "$INDRI_BIN/IndriRunQuery" "$WORK/run_indri.param" </dev/null 2>/dev/null > "$WORK/indri.run"
echo ">> running Lucindri (quoted)";  java -jar "$JAR_SRCH" "$WORK/run_lucindri.xml" 2>/dev/null > "$WORK/lucindri.run"
echo ">> running Lucindri (#token)";  java -jar "$JAR_SRCH" "$WORK/run_token.xml"    2>/dev/null > "$WORK/token.run"

# ---- diff each Lucindri run against the single Indri run ----
echo; echo "======== Indri  vs  Lucindri (quoted \"...\") ========"
python3 "$HERE/diff_fuzz.py" "$WORK/indri.run" "$WORK/lucindri.run"
echo; echo "======== Indri  vs  Lucindri (#token) ========"
python3 "$HERE/diff_fuzz.py" "$WORK/indri.run" "$WORK/token.run"
echo; echo "runs written under $WORK/{indri,lucindri,token}.run"
