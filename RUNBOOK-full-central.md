# Runbook: from zero to a full Maven Central dependency graph

This is the end-to-end pipeline using the commands that exist **today**. It takes
you from an empty machine to a queryable, ecosystem-wide direct-dependency graph
(transitive closure computed in SQL on top). Read the *Scale reality* section at
the end before you start — "all of Central" is the goal, not a guarantee, and the
graph-building step is the real bottleneck.

All commands assume the shaded jar built per `CLAUDE.md`:

```bash
mvn clean package           # Java 22; produces target/cachegenie.jar
alias cg='java -jar target/cachegenie.jar'
```

Global options (`RootCmd`) apply to every command: `-c/--cache` (local Maven
cache, default `~/.m2`), `-r/--repo` (remote, default Maven Central),
`-l/--log`, `-P/--progress` (periodic progress to stderr on long runs).

State lives under `~/.m2/cachegenie/`: discovery/index state in `work/`,
metadata in `meta/`, and the DuckDB graph in `graph.db`.

---

## Step 0 — Decide your discovery path

There are two ways to find out what exists on Central, and for "everything" only
one of them is viable:

- **`index-sync` (alias `central-sync`)** — pulls Central's *published* Maven
  index (maven-indexer format) and extracts coordinates in bulk. Tens of millions
  of GAVs in a handful of large file downloads. **This is the path for all of
  Central.**
- **`index` (alias `scan`)** — crawls HTML directory listings, rate-limited
  (`--rate`, `--threads`, `--max-age`). Right for targeted `--gav` lookups or when
  you need a specific coordinate *now*, wrong for enumerating the whole repo (far
  too many requests).

Use `index-sync` to build the catalogue; keep `index --gav` in your back pocket
for one-off targeted discovery.

---

## Step 1 — Populate the catalogue (what exists)

First run pulls the **full** index. This bulk-loads every ADD record into a
staging table via DuckDB's Appender, then merges set-based into the `meta_*`
tables (collapsing ~100M file-records to distinct versions once). The earlier
per-row path took ~24h; the staged path is far faster.

```bash
cg index-sync --full
```

Smoke-test the full path quickly without committing sync state:

```bash
cg -c /tmp/cg-scratch index-sync --full --limit 100000
```

`--limit` stops after N records and does **not** advance local sync state (so it
won't mark you up to date — it's for testing the pipeline, not a real run).

The full load grows `graph.db` with the staging table. Reclaim the space before
moving on:

```bash
cg db compact --rewrite      # rebuilds into a fresh, smaller file
```

At this point `meta_*` knows every coordinate Central publishes, including the
`pom`-packaging parents and BOMs — but there are **no dependency edges yet**.

Sanity-check the catalogue:

```bash
cg graph stats               # counts across the graph + discovery metadata
```

---

## Step 2 — Build the direct-dependency graph (the heavy step)

> **Prefer `mine` + `resolve` over `deps` at scale, and seed from Goblin if you can.**
> `graph deps` (below) uses Aether's effective-POM read, which fans out into
> parent/BOM fetches per artifact and trips Central's 429s. The polite path is to
> seed the bulk graph from the Goblin dataset (`GOBLIN-IMPORT.md`) and then mine only
> the delta with `graph mine` (one GET per POM, no fan-out) + `graph resolve` —
> routed through the Google GCS mirror. See **`MINING.md`** for the rate guidance,
> the `-r` mirror URL, and resumable/chunked runs. The `graph deps` description here
> is retained for targeted, immediate lookups.

`graph deps` (alias under `graph`/`map`) is what turns the catalogue into edges.
For each selected version that isn't already graphed and isn't marked
`missing_pom`, it reads the artifact's **effective** direct dependencies (one
Aether descriptor read, no transitive collection) and persists the edges.
Transitive trees are then a recursive-CTE query, not stored.

Selection is by coordinate (`--gav`), recency (`--since`), or both. For *all* of
Central you have two realistic strategies:

**A. Sweep the whole catalogue by group**, so you can checkpoint and resume:

```bash
# preview a slice without graphing anything
cg graph deps --gav org.apache --list

# graph it, paced
cg graph deps --gav org.apache --rate 60 --threads 8
cg graph deps --gav com.google --rate 60 --threads 8
# ...iterate across top-level groups
```

**B. Graph everything published in a window**, then keep the window moving:

```bash
cg graph deps --since 1300w --rate 60 --threads 8    # see caveat below
```

Two caveats on the `--since` route to "everything": the window must exceed
Central's entire lifetime (it has launched around 2005, so ~21+ years — a 10-year
`520w` window would silently miss everything older), and `--since` only works once
`index-sync` has populated publish dates. If either is in doubt, prefer the
group-sweep in (A), which doesn't depend on publish-date coverage and is the more
reliable way to actually reach *all* of the catalogue.

Pacing matters. `--rate <req/min>` (new) caps the total descriptor-read rate
across all workers via one shared limiter; `--threads` only sets concurrency, not
politeness. `0` = unlimited. **Set `--rate` conservatively**: the limiter takes
one permit per artifact, but resolving an effective POM fans out into several real
HTTP requests (parent POMs, imported BOMs), so true request volume to Central is a
multiple of `--rate`.

Outcomes per version are recorded so re-runs are cheap and resumable:

- **OK** — edges persisted.
- **NOT_FOUND** — POM (or a required parent/BOM) genuinely absent → marked
  `missing_pom`, skipped on re-runs.
- **TRANSIENT** — 5xx/timeout/connection → left alone, retried next run.
- **RATE_LIMITED (HTTP 429)** — the first one **aborts the whole run** (saves
  progress, tells you to re-run later) rather than poisoning data by hammering a
  throttled Central.

So the real-world loop is: run a slice, let it abort or finish, re-run to pick up
TRANSIENT/aborted work, repeat until `graph stats` stops growing.

> Optional pre-population: `meta` (alias `fetch`) downloads missing POMs for
> indexed versions. `graph deps` resolves POMs on demand anyway (Aether uses the
> local cache when present), so a prior `fetch` isn't required — it just front-
> loads the downloads.

---

## Step 3 — Optimise the database for querying

After the bulk writes, build indexes and convenience views:

```bash
cg db compact --rewrite      # reclaim space again after the edge writes
cg db optimize               # secondary indexes on hot columns + ANALYZE
cg db views                  # create gav, dependents, version_ranges views
```

---

## Step 4 — Query, browse, export

The graph is direct edges; transitive closure is a recursive CTE over them.

```bash
cg graph query "SELECT * FROM dependents WHERE gid='org.slf4j' AND aid='slf4j-api' LIMIT 50"
cg graph stats
cg view                      # browser UI over the graph (read-only); --no-open to skip launch
cg db export -f parquet -o central-graph.parquet
cg db export -f csv          # dump tables (incl. discovery metadata) to CSV
```

See `DB_SCHEMA.md` for the schema and example analysis queries.

---

## Step 5 — Keep it current (incremental)

Once the baseline exists, you never do `--full` again:

```bash
cg index-sync                      # incremental: pulls only the diff since last sync
cg graph deps --since 30d --rate 60 --threads 8   # graph just the new versions
cg db optimize                     # refresh stats periodically
```

This is a natural fit for a scheduled job (e.g. weekly sync + `--since` graph).

---

## Scale reality — read this

"A complete graph for all of Maven Central" is asymptotic with the current code,
and **Step 2 is where it bites**:

1. **Volume.** Central holds tens of millions of artifact versions. `graph deps`
   does at least one descriptor read each, and the effective-POM resolution pulls
   parent chains and imported BOMs on top — so the real request count is a large
   multiple of the version count. Even fully paced, this is a multi-day-to-week
   crawl, run in resumable slices.

2. **Rate limiting.** Central throttles sustained automated traffic. The new
   `--rate` makes the deps step *polite*, but politeness means *slow*. The 429
   abort is a backstop, not a throughput strategy.

3. **The structural fix isn't built yet.** The effective-POM fan-out is the thing
   driving both the request volume and the 429s. The planned redesign — fetch each
   raw POM once, record declared direct edges + parent + dependencyManagement, and
   resolve managed/inherited/property versions later as a recursive SQL pass over
   the graph (since parents and BOMs are themselves catalogue nodes) — collapses
   the per-artifact burst to a single GET and removes the repeated parent/BOM
   refetching. Until that lands, treat "complete" as "as complete as a paced crawl
   gets before you stop."

4. **Completeness is never 100%.** `missing_pom` versions are skipped by design;
   TRANSIENT failures need re-runs; some versions have unresolvable parents/BOMs.
   `graph stats` plateauing is your practical "done" signal.

### TL;DR pipeline

```bash
mvn clean package
cg index-sync --full          # 1. discover everything
cg db compact --rewrite
cg graph deps --gav org.apache --rate 60 --threads 8   # 2. build edges, sweep by group (resumable, slow, the bottleneck)
cg db compact --rewrite && cg db optimize && cg db views   # 3. tune
cg graph query "..."          # 4. analyse / cg view / cg db export
cg index-sync && cg graph deps --since 30d --rate 60   # 5. keep current
```
