#!/usr/bin/env bash
# Phased score-level conformance battery (TASK-0010 Phases 0-5), re-run under the TASK-0016 quote-only
# grammar with all THREE dialects. Builds the tiny integer collections C1/C2 in both C++ Indri and
# Lucindri (tokenizer/stemmer/stopwords are no-ops on integers, so any per-document score difference is
# pure query/scoring logic — and every score is analytically checkable from the Dirichlet formula).
#
# For each battery query, the SAME abstract query is rendered three ways and each is run on its engine:
#   * indri  : bare terms          10             -> C++ Indri            (the reference oracle)
#   * quoted : analyzed splice      "10"           -> Lucindri (default path)
#   * token  : verbatim splice      #token("10")   -> Lucindri (verbatim path)
# then we diff, per document, quoted-vs-Indri, token-vs-Indri, and quoted-vs-token (tol 1e-3). A query
# PASSES when all three agree. Queries with a *known, cataloged* Indri divergence (filter nested inside
# a belief op, Phase 5; proximity-nested-in-proximity, TASK-0018) are marked KNOWN, not failures — but
# quoted-vs-token must STILL be 0 for them (that isolates a #token-path regression from an Indri gap).
#
# Usage:  phase_conformance.sh [phase]      # phase in {0,1,2,3,4,5,all}; default all
# Env:    INDRI_BIN, JAR_IDX, JAR_SRCH, WORK, MU (default 2000), TOL (default 0.001)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
INDRI_BIN="${INDRI_BIN:-/ssd-8TB/installs/indri-5.21/bin}"
JAR_IDX="${JAR_IDX:-$REPO/LucindriIndexer/target/LucindriIndexer-1.45-jar-with-dependencies.jar}"
JAR_SRCH="${JAR_SRCH:-$REPO/LucindriSearcher/target/LucindriSearcher-1.5-jar-with-dependencies.jar}"
WORK="${WORK:-/tmp/lucindri-phase}"; mkdir -p "$WORK"
MU="${MU:-2000}"; TOL="${TOL:-0.001}"; WANT="${1:-all}"

# ---------- integer collections (identical to score_probe.sh) ----------
emit_doc(){ printf '<DOC>\n<DOCNO>%s</DOCNO>\n<TEXT>%s</TEXT>\n</DOC>\n' "$1" "$2"; }
emit_c1(){ emit_doc d1 "10 20 30"; emit_doc d2 "10 10 20"; emit_doc d3 "10 90 20"
           emit_doc d4 "20 10";    emit_doc d5 "30 40 50 90 90"; }
emit_c2(){ emit_doc o1 "10 20 10 20"; emit_doc o2 "20 10 20"
           emit_doc o3 "10 10 20 20"; emit_doc o4 "10 20"; }
build(){ local d="$WORK/$1"; mkdir -p "$d/src"; emit_$1 > "$d/src/c.trec"
  if [ ! -d "$d/i" ]; then
    printf '<parameters><index>%s/i</index><corpus><path>%s/src/c.trec</path><class>trectext</class></corpus><stemmer><name>krovetz</name></stemmer></parameters>\n' "$d" "$d" > "$d/i.param"
    "$INDRI_BIN/IndriBuildIndex" "$d/i.param" </dev/null >/dev/null 2>&1; fi
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
    java -jar "$JAR_IDX" "$d/l.properties" >/dev/null 2>&1; fi; }

# ---------- dialect renderer ----------
# A term is a bare integer (^\d+$); a weight has a dot (0.5) and is left alone; window sizes live inside
# the operator token (#2,#uw3) so they are not standalone terms. quoted -> "N"; token -> #token("N");
# filters #filreq/#filrej rename to #scoreif/#scoreifnot for the Lucindri dialects.
render(){ # $1=dialect(indri|quoted|token)  $2=indri-form query
  python3 - "$1" "$2" <<'PY'
import sys,re
dia,q=sys.argv[1],sys.argv[2]
out=[]
for tok in q.split():
    # split leading '(' none here; tokens are space-separated already incl. parens as their own tokens
    if re.fullmatch(r'\d+',tok):
        if dia=='quoted': out.append('"%s"'%tok)
        elif dia=='token': out.append('#token("%s")'%tok)
        else: out.append(tok)
    else:
        if dia in ('quoted','token'):
            tok=tok.replace('#filreq','#scoreif').replace('#filrej','#scoreifnot')
        out.append(tok)
print(' '.join(out))
PY
}

run_indri(){ printf '<parameters><index>%s/i</index><rule>method:dirichlet,mu:%s</rule><count>50</count><trecFormat>true</trecFormat><query><number>1</number><text>%s</text></query></parameters>\n' "$1" "$MU" "$2" > "$WORK/qi.param"
  "$INDRI_BIN/IndriRunQuery" "$WORK/qi.param" </dev/null 2>/dev/null | awk '$2=="Q0"{print $3,$5}' | sort; }
run_luc(){ printf '<parameters><index>%s/l</index><rule>dirichlet:%s</rule><count>50</count><trecFormat>true</trecFormat><removeStopwords>false</removeStopwords><query><number>1</number><text>%s</text></query></parameters>\n' "$1" "$MU" "$2" > "$WORK/ql.xml"
  java -jar "$JAR_SRCH" "$WORK/ql.xml" 2>/dev/null | awk '$2=="Q0"{print $3,$5}' | sort; }
# max |Δ| over docs retrieved by BOTH; "1e9" if one side is empty and the other is not (set mismatch)
maxdelta(){ join "$1" "$2" | awk -v m=0 '{d=$2-$3; if(d<0)d=-d; if(d>m)m=d} END{printf "%.6f",m+0}'
  local na nb; na=$(wc -l <"$1"); nb=$(wc -l <"$2")
  if [ "$na" != "$nb" ]; then printf ' setdiff(%s/%s)' "$na" "$nb"; fi; }

# ---------- battery: "phase|collection|EXPECT|indri-form query" ----------
# EXPECT: ok = must match Indri; known = cataloged Indri divergence (quoted==token still required)
battery(){ cat <<'CASES'
1|c1|ok|#combine( 10 )
1|c1|ok|10
2|c1|ok|#combine( 10 20 )
2|c1|ok|#or( 10 20 )
2|c1|ok|#max( 10 20 )
2|c1|ok|#wsum( 0.3 10 0.7 20 )
2|c1|ok|#weight( 0.3 10 0.7 20 )
2|c1|ok|#syn( 10 20 )
2|c1|ok|#combine( 10 999 )
2|c1|ok|#combine( 10 40 )
3|c1|ok|#1( 10 20 )
3|c1|ok|#2( 10 20 )
3|c1|ok|#uw3( 10 20 )
3|c1|ok|#band( 10 20 )
3|c2|ok|#2( 10 20 )
3|c2|ok|#uw2( 10 20 )
3|c1|ok|#combine( 30 #1( 10 999 ) )
4|c1|ok|#filreq( 30 #combine( 10 ) )
4|c1|ok|#filrej( 30 #combine( 10 ) )
4|c1|ok|#filreq( #1( 10 20 ) #combine( 30 ) )
4|c1|ok|#filreq( 999 #combine( 10 ) )
5|c1|ok|#combine( #syn( 10 90 ) 20 )
5|c1|ok|#band( #syn( 10 30 ) 20 )
5|c1|ok|#max( #1( 10 20 ) #uw3( 10 90 ) )
5|c1|ok|#weight( 0.5 #combine( 10 20 ) 0.5 #combine( #1( 10 20 ) ) )
5|c1|ok|#weight( 0.8 #combine( 10 ) 0.1 #combine( #1( 10 20 ) ) 0.1 #combine( #uw4( 10 20 ) ) )
5|c1|known|#weight( 0.5 #filreq( 30 #combine( 10 ) ) 0.5 #combine( 20 ) )
5|c1|known|#band( 10 #band( 20 30 ) )
CASES
}

# ---------- Phase 0: statistics alignment (Indri dumpindex vs a small Lucene probe) ----------
phase0(){ echo "== Phase 0 — collection statistics align (C1) =="
  local d="$WORK/c1"
  # compile a tiny Lucene stats probe once
  if [ ! -f "$WORK/StatProbe.class" ]; then
    cat > "$WORK/StatProbe.java" <<'EOF'
import org.apache.lucene.index.*; import org.apache.lucene.store.FSDirectory; import java.nio.file.*;
public class StatProbe { public static void main(String[] a) throws Exception {
  DirectoryReader r=DirectoryReader.open(FSDirectory.open(Paths.get(a[0])));
  System.out.println("numDocs="+r.numDocs()+" sumTTF="+r.getSumTotalTermFreq("fulltext"));
  for(String t:a.length>1?a[1].split(","):new String[0]){
    Term tm=new Term("fulltext",t);
    System.out.println("cf("+t+")="+r.totalTermFreq(tm)+" df("+t+")="+r.docFreq(tm)); }
  r.close(); } }
EOF
    javac -cp "$JAR_SRCH" -d "$WORK" "$WORK/StatProbe.java" 2>/dev/null; fi
  echo "  [Indri]   $("$INDRI_BIN/dumpindex" "$d/i" s 2>/dev/null | grep -iE 'documents:|total terms:' | tr '\n' ' ' | tr -s ' \t' ' ')"
  echo "  [Indri]   per-term cf/df: $(for t in 10 20 30 90; do cf=$("$INDRI_BIN/dumpindex" "$d/i" x "$t" 2>/dev/null | cut -d: -f2); df=$("$INDRI_BIN/dumpindex" "$d/i" t "$t" 2>/dev/null | tail -n +2 | grep -c .); printf '%s:%s/%s ' "$t" "$cf" "$df"; done)"
  echo "  [Lucindri] $(java -cp "$JAR_SRCH:$WORK" StatProbe "$d/l" 10,20,30,90 2>/dev/null | tr '\n' ' ')"
  echo "  expect: numDocs=5, |C|(sumTTF/total terms)=16; cf/df 10:5/4 20:4/4 30:2/2 90:3/2. Single-term scores below re-check |C|,cf,|d| analytically."
}

# ---------- driver ----------
build c1; build c2
pass=0; fail=0; known=0; tokbug=0
echo "=== phased conformance: Indri vs Lucindri(quoted) vs Lucindri(#token), mu=$MU, tol=$TOL ==="
[ "$WANT" = all -o "$WANT" = 0 ] && phase0
while IFS='|' read -r ph col expect q; do
  [ -z "${ph:-}" ] && continue
  case "$ph" in \#*) continue;; esac
  [ "$WANT" = all ] || [ "$WANT" = "$ph" ] || continue
  d="$WORK/$col"
  qi=$(render indri  "$q"); ql=$(render quoted "$q"); qt=$(render token "$q")
  run_indri "$d" "$qi" > "$WORK/a.i"; run_luc "$d" "$ql" > "$WORK/a.q"; run_luc "$d" "$qt" > "$WORK/a.t"
  dq=$(maxdelta "$WORK/a.i" "$WORK/a.q"); dt=$(maxdelta "$WORK/a.i" "$WORK/a.t"); dqt=$(maxdelta "$WORK/a.q" "$WORK/a.t")
  # numeric parts only (strip any setdiff suffix) for threshold tests
  ndq=${dq%% *}; ndt=${dt%% *}; ndqt=${dqt%% *}
  bad_i=$(awk -v a="$ndq" -v b="$ndt" -v t="$TOL" 'BEGIN{print (a>t||b>t)?1:0}')
  bad_qt=$(awk -v a="$ndqt" -v t="$TOL" 'BEGIN{print (a>t)?1:0}')
  # setdiff on quoted-vs-token is also a token-path bug
  case "$dqt" in *setdiff*) bad_qt=1;; esac
  verdict="PASS"
  if [ "$bad_qt" = 1 ]; then verdict="TOKEN-BUG"; tokbug=$((tokbug+1))
  elif [ "$bad_i" = 1 ]; then
    if [ "$expect" = known ]; then verdict="KNOWN-DIVERGE"; known=$((known+1)); else verdict="DIVERGE"; fail=$((fail+1)); fi
  else pass=$((pass+1)); fi
  printf 'P%s %-13s Δq=%-11s Δt=%-11s Δq~t=%-9s  %s\n' "$ph" "$verdict" "$dq" "$dt" "$dqt" "$q"
done < <(battery)
echo "---------------------------------------------------------------"
echo "PASS=$pass  KNOWN-DIVERGE=$known  unexpected DIVERGE=$fail  TOKEN-BUG=$tokbug"
[ "$fail" = 0 ] && [ "$tokbug" = 0 ] && echo "RESULT: OK (no unexpected divergence; #token == quoted everywhere)" || { echo "RESULT: FAILURES PRESENT"; exit 1; }
