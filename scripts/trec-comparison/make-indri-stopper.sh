#!/usr/bin/env bash
# Emit an Indri <stopper> block from english-stopwords.txt so the C++ Indri index uses the
# exact same 33-word stop set as Lucindri (Lucene EnglishAnalyzer default). See TASK-0008.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
printf '<stopper>'
while read -r w; do [ -n "$w" ] && printf '<word>%s</word>' "$w"; done < "$DIR/english-stopwords.txt"
printf '</stopper>\n'
