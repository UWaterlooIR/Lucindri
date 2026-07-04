# scripts/

Operational scripts (record-keeping; not part of the Maven build).

- **tasks.sh** — Prints a live task index (ID, Status, title) by reading each `tasks/TASK-*.md`
  header. The task files are the single source of truth; nothing is duplicated in `CLAUDE.md`,
  so the index can't go stale. Run from anywhere in the repo.


- **build_full.sh** — Builds the full climbmix index (`/ssd-8TB/indexes/climbmix_full/`) in 8
  parts. Steps: (1) parallel `gzip -t` integrity check of all shards under
  `/ssd-8TB/corpora/climbmix-400b-corpus-jsonl/` with fail-fast; (2) create staging symlink dirs
  (`/ssd-8TB/climbmix-staging-full/partNN/`) + per-part `.properties` (analyzer `kstem/true/true`,
  default 5 GB max segment); (3) launch 8 concurrent indexer JVMs (`-Xmx8G` each), one per part,
  and wait. Query the result via a comma-separated `<index>` MultiReader over `part00..part07`.
  Adjust `PARTS`/`CORP`/`IDXPARENT` at the top to reuse. The 10% scout used the same recipe with
  6 parts / first 654 shards.
