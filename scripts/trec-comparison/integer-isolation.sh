#!/usr/bin/env bash
# TASK-0008/0009 isolation probe. Uses an INTEGER-only collection so tokenization and stemming are
# identity maps in both engines (integers have no punctuation to split and nothing to stem) -- any
# difference is therefore pure query/scoring semantics, not the tokenizer. Interspersed stopwords
# test stopword handling with and without removal.
#
# It demonstrates: (1) proximity operators #odN / #uwN vs C++ Indri (the #uwN off-by-one, fixed in
# IndriWindowWeight), and (2) the document-length divergence (Indri counts removed stopwords in |d|,
# Lucindri does not -- TASK-0009).
#
# Env: INDRI_BIN, JAR_IDX, JAR_SRCH, WORK  (sensible defaults below).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
INDRI_BIN="${INDRI_BIN:-/ssd-8TB/installs/indri-5.21/bin}"
JAR_IDX="${JAR_IDX:-$REPO/LucindriIndexer/target/LucindriIndexer-1.45-jar-with-dependencies.jar}"
JAR_SRCH="${JAR_SRCH:-$REPO/LucindriSearcher/target/LucindriSearcher-1.5-jar-with-dependencies.jar}"
WORK="${WORK:-/tmp/lucindri-intiso}"; mkdir -p "$WORK/src"
STOPPER="$(bash "$HERE/make-indri-stopper.sh")"

# ---- integer collection: integers with interspersed stopwords ----
cat > "$WORK/src/c.trec" <<'EOF'
<DOC>
<DOCNO>p-adj</DOCNO>
<TEXT>100 200</TEXT>
</DOC>
<DOC>
<DOCNO>p-stop1</DOCNO>
<TEXT>100 the 200</TEXT>
</DOC>
<DOC>
<DOCNO>p-stop2</DOCNO>
<TEXT>100 the and 200</TEXT>
</DOC>
<DOC>
<DOCNO>p-content</DOCNO>
<TEXT>100 999 200</TEXT>
</DOC>
<DOC>
<DOCNO>p-rev</DOCNO>
<TEXT>200 100</TEXT>
</DOC>
EOF

# ---- a small Lucene probe for |C| / doc length ----
cat > "$WORK/DumpFieldStats.java" <<'EOF'
import org.apache.lucene.index.*; import org.apache.lucene.store.FSDirectory; import java.nio.file.Paths;
public class DumpFieldStats { public static void main(String[] a) throws Exception {
  DirectoryReader r=DirectoryReader.open(FSDirectory.open(Paths.get(a[0])));
  System.out.println("numDocs="+r.numDocs()+" |C|(sumTotalTermFreq)="+r.getSumTotalTermFreq("fulltext")); r.close(); } }
EOF
javac -cp "$JAR_SRCH" -d "$WORK" "$WORK/DumpFieldStats.java"

# ---- build both indexes (stopwords removed) ----
rm -rf "$WORK/i" "$WORK/l"
cat > "$WORK/i.param" <<EOF
<parameters><index>$WORK/i</index><corpus><path>$WORK/src/c.trec</path><class>trectext</class></corpus><stemmer><name>krovetz</name></stemmer>$STOPPER</parameters>
EOF
"$INDRI_BIN/IndriBuildIndex" "$WORK/i.param" </dev/null >/dev/null 2>&1
cat > "$WORK/l.properties" <<EOF
documentFormat=trectext
indexingPlatform=lucene
dataDirectory=$WORK/src
indexDirectory=$WORK
indexName=l
indexFullText=true
fieldNames=
contentTags=text
stemmer=kstem
removeStopwords=true
ignoreCase=true
EOF
java -jar "$JAR_IDX" "$WORK/l.properties" >/dev/null 2>&1

# ---- proximity comparison ----
qi(){ cat > "$WORK/q.param" <<EOF
<parameters><index>$WORK/i</index><rule>method:dirichlet,mu:2000</rule><count>10</count><trecFormat>true</trecFormat><query><number>1</number><text>$1</text></query></parameters>
EOF
"$INDRI_BIN/IndriRunQuery" "$WORK/q.param" </dev/null 2>/dev/null | awk '{print $3}' | sort | tr '\n' ' '; }
ql(){ cat > "$WORK/q.xml" <<EOF
<parameters><index>$WORK/l</index><rule>dirichlet:2000</rule><count>10</count><trecFormat>true</trecFormat><query><number>1</number><text>$1</text></query></parameters>
EOF
java -jar "$JAR_SRCH" "$WORK/q.xml" 2>/dev/null | awk '{print $3}' | sort | tr '\n' ' '; }
echo "docs: p-adj='100 200' p-stop1='100 the 200' p-stop2='100 the and 200' p-content='100 999 200' p-rev='200 100'"
for Q in "#combine( 100 )" "#1( 100 200 )" "#2( 100 200 )" "#uw2( 100 200 )" "#uw3( 100 200 )" "#uw4( 100 200 )"; do
  I="$(qi "$Q")"; L="$(ql "$Q")"; [ "$I" = "$L" ] && M="MATCH" || M="<-- DIFFER"
  printf "  %-16s Indri: %-30s Lucindri: %-30s %s\n" "$Q" "$I" "$L" "$M"
done

# ---- document length (TASK-0009) ----
echo "document length |C| (5 content tokens, 3 stopwords across the collection):"
printf "  Indri total terms = "; "$INDRI_BIN/dumpindex" "$WORK/i" s 2>/dev/null | awk -F'\t' '/^total terms/{print $2}'
printf "  Lucindri          : "; java -cp "$JAR_SRCH:$WORK" DumpFieldStats "$WORK/l"
