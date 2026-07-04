#!/bin/bash
# Render a live task index from the tasks/ directory. The task files are the single source of
# truth; this reads each TASK-*.md header (title line + Status) so nothing is duplicated or
# goes stale. Run from anywhere in the repo.
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tdir="$here/tasks"

shopt -s nullglob
files=("$tdir"/TASK-*.md)
if [ ${#files[@]} -eq 0 ]; then
  echo "No tasks in $tdir"
  exit 0
fi

printf "%-11s %-12s %s\n" "TASK" "STATUS" "TITLE"
printf "%-11s %-12s %s\n" "----" "------" "-----"
for f in "${files[@]}"; do
  id="$(basename "$f" .md)"
  # Title = first '# ' heading, minus the leading "# TASK-NNNN — " prefix.
  title="$(grep -m1 '^# ' "$f" | sed -E 's/^# (TASK-[0-9]+[[:space:]]*[—-][[:space:]]*)?//')"
  # Status = first "- **Status:** X" line.
  status="$(grep -m1 '\*\*Status:\*\*' "$f" | sed -E 's/.*\*\*Status:\*\*[[:space:]]*//; s/[[:space:]]*$//')"
  printf "%-11s %-12s %s\n" "$id" "${status:-?}" "$title"
done
