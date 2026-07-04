#!/bin/bash
# TASK-0005 harness: differential comparison of Lucindri vs. C++ Indri on the query-language
# elements Lucindri claims to implement (repo README). The SAME logical documents (a docid + a
# markup-free text body) are indexed by each engine in ITS NATIVE format:
#   - Lucindri: JSONL via the `climbmix` parser (docid -> externalId, contents -> fulltext) --
#               Lucindri's actual target format; clean ids and clean bodies (no markup tokens).
#   - Indri:    trectext via IndriBuildIndex (DOCNO/TEXT).
# Analysis aligned (Krovetz ~ KStem, lowercase); tokens chosen stem-invariant + non-stopword so
# differences are query SEMANTICS, not tokenization. Runs probe queries in both and diffs the
# retrieved doc SET and the ranked order. Record-keeping tool; depends on the external Indri
# install so it is NOT part of the JUnit suite.
#
# Config via env (defaults shown):
INDRI_BIN="${INDRI_BIN:-/ssd-8TB/installs/indri-5.21/bin}"
LUCINDRI_INDEXER="${LUCINDRI_INDEXER:-/ssd-8TB/git-repos/Lucindri/LucindriIndexer/target/LucindriIndexer-1.45-jar-with-dependencies.jar}"
LUCINDRI_SEARCHER="${LUCINDRI_SEARCHER:-/ssd-8TB/git-repos/Lucindri/LucindriSearcher/target/LucindriSearcher-1.5-jar-with-dependencies.jar}"
MU="${MU:-2000}"
W="${W:-$(mktemp -d)}"
set -u

# ---- Shared logical corpus: "docid|markup-free text". Stem-invariant, non-stopword tokens. ----
docs=(
"1|apple cat dog"
"2|banana cat"
"3|apple sun moon"
"4|cat dog moon"
"5|dog apple"
"6|tree bird fish"
)

mkdir -p "$W/jsonl" "$W/trec"
: > "$W/jsonl/corpus.jsonl"
: > "$W/trec/corpus.trec"
for d in "${docs[@]}"; do
  id="${d%%|*}"; txt="${d#*|}"
  printf '{"docid": "%s", "contents": "%s"}\n' "$id" "$txt" >> "$W/jsonl/corpus.jsonl"
  printf '<DOC>\n<DOCNO>%s</DOCNO>\n<TEXT>\n%s\n</TEXT>\n</DOC>\n' "$id" "$txt" >> "$W/trec/corpus.trec"
done

echo "### Building indexes (${#docs[@]} docs; Lucindri=JSONL/climbmix, Indri=trectext) ..."
# ---- Indri index (Krovetz) ----
rm -rf "$W/indri"
cat > "$W/indri_build.param" <<EOF
<parameters>
<index>$W/indri</index>
<corpus><path>$W/trec/corpus.trec</path><class>trectext</class></corpus>
<stemmer><name>krovetz</name></stemmer>
</parameters>
EOF
"$INDRI_BIN/IndriBuildIndex" "$W/indri_build.param" </dev/null >/dev/null 2>&1 \
  && echo "  indri:    ok" || { echo "  indri: BUILD FAILED"; exit 1; }

# ---- Lucindri index (JSONL via climbmix parser) ----
rm -rf "$W/lucindri"
cat > "$W/lucindri.properties" <<EOF
documentFormat=climbmix
indexingPlatform=lucene
dataDirectory=$W/jsonl
indexDirectory=$W
indexName=lucindri
indexFullText=true
fieldNames=
stemmer=kstem
removeStopwords=true
ignoreCase=true
EOF
java -jar -Xmx1G "$LUCINDRI_INDEXER" "$W/lucindri.properties" >/dev/null 2>&1 \
  && echo "  lucindri: ok" || { echo "  lucindri: BUILD FAILED"; exit 1; }

# ---- runners: return ranked docnos (one per line) ----
run_indri() {
  cat > "$W/q.indri" <<EOF
<parameters><index>$W/indri</index><trecFormat>true</trecFormat><count>20</count>
<rule>method:dirichlet,mu:$MU</rule>
<query><number>1</number><text>$1</text></query></parameters>
EOF
  "$INDRI_BIN/IndriRunQuery" "$W/q.indri" </dev/null 2>/dev/null | awk '$2=="Q0"{print $3}'
}
run_lucindri() {
  cat > "$W/q.lc" <<EOF
<parameters><index>$W/lucindri</index><trecFormat>true</trecFormat><rule>dirichlet:$MU</rule><count>20</count>
<query><number>1</number><text>$1</text></query></parameters>
EOF
  java -jar -Xmx1G "$LUCINDRI_SEARCHER" "$W/q.lc" 2>/dev/null | awk '$2=="Q0"{print $3}'
}

setof() { printf '%s\n' "$@" | tr ' ' '\n' | sort -u | tr '\n' ' ' | sed 's/ $//'; }

compare() {  # $1=label  $2=lucindri query  $3=indri query (default: same)
  local label="$1" lq="$2" iq="${3:-$2}" lc ind lcset indset setverdict rankverdict
  lc="$(run_lucindri "$lq" | tr '\n' ' ' | sed 's/ $//')"
  ind="$(run_indri "$iq" | tr '\n' ' ' | sed 's/ $//')"
  lcset="$(setof $lc)"; indset="$(setof $ind)"
  [ "$lcset" = "$indset" ] && setverdict="SET-MATCH" || setverdict="SET-DIFFER"
  if [ "$setverdict" = "SET-MATCH" ]; then
    [ "$lc" = "$ind" ] && rankverdict="rank-match" || rankverdict="rank-DIFFER"
  else rankverdict="-"; fi
  printf '%-14s | %-42s | %-11s %-11s\n' "$label" "$lq" "$setverdict" "$rankverdict"
  printf '               indri:    %s\n' "$ind"
  printf '               lucindri: %s\n' "$lc"
  [ "$iq" != "$lq" ] && printf '               (indri query: %s)\n' "$iq"
}

echo
printf '%-14s | %-42s | %s\n' "OPERATOR" "LUCINDRI QUERY" "VERDICT (set / rank), ranked docnos below"
echo "----------------------------------------------------------------------------------------------"
compare "term"          "cat"
compare "combine"       "#combine(cat dog)"
compare "or"            "#or(banana moon)"
compare "not"           "#combine(cat #not(dog))"
compare "wand"          "#wand(0.9 cat 0.1 dog)"
compare "wsum"          "#wsum(0.9 cat 0.1 dog)"
compare "max"           "#max(cat moon)"
compare "scoreif"       "#scoreif(dog #combine(cat))"      "#filreq(dog #combine(cat))"
compare "scoreifnot"    "#scoreifnot(dog #combine(cat))"   "#filrej(dog #combine(cat))"
compare "band"          "#band(cat dog)"
compare "near1"         "#1(apple cat)"
compare "uw2"           "#uw2(cat dog)"
compare "syn"           "#syn(apple banana)"
compare "band_syn"      "#band(#syn(apple banana) #syn(cat dog))"
compare "combine_or"    "#combine(#or(apple banana) moon)"

echo
echo "### workdir: $W"
