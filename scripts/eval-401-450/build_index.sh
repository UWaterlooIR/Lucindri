#!/usr/bin/env bash
# Build a FRESH Lucindri index of the t45minusCR collection for the TREC-8 401-450 evaluation, in the
# "keep stopwords + exact document length" configuration (the closest-to-Indri length config).
#
#   scripts/eval-401-450/build_index.sh
#
# Rebuild from scratch whenever the indexer changes (e.g. TASK-0020 made externalId a keyword field, so a
# pre-TASK-0020 index is stale). Uses the LucindriIndexer-2.0 fat jar built by `mvn clean install`.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"

CORPUS="${CORPUS:-/ssd-8TB/corpora/t45minusCR}"              # 7 gzipped trectext files
OUTDIR="${OUTDIR:-/ssd-8TB/trec-compare/stability20}"        # where the index goes
NAME="${NAME:-t45_keepstop_exact}"
IJAR="${IJAR:-$(ls "$REPO"/LucindriIndexer/target/LucindriIndexer-*-jar-with-dependencies.jar | head -1)}"
XMX="${XMX:-6G}"

mkdir -p "$OUTDIR"
PROPS="$OUTDIR/$NAME.properties"
cat > "$PROPS" <<EOF
documentFormat=trectext
indexingPlatform=lucene
dataDirectory=$CORPUS
indexDirectory=$OUTDIR
indexName=$NAME
indexFullText=true
fieldNames=
contentTags=text,hl,head,headline,title,ttl,dd,date,date_time,lp,leadpara
stemmer=kstem
removeStopwords=false
ignoreCase=true
exactDocumentLength=true
EOF

echo ">> building index $OUTDIR/$NAME  (keep stopwords + exact length, kstem)"
echo ">> jar: $IJAR"
time java -jar -Xmx"$XMX" "$IJAR" "$PROPS"
echo ">> done. index at $OUTDIR/$NAME"
