# Polite POM mining at scale

`graph mine` fetches raw POMs into DuckDB (`pom_meta`/`direct_dep`/… — see
`DB_SCHEMA.md`); `graph resolve` then turns them into concrete `dependencies`
edges. This note covers running `mine` over a large backlog (e.g. the millions of
versions `index-sync` discovered) without tripping Maven Central's rate limits.

## What mine does (and why it's resumable)

- Worklist = `MetaRepository.selectVersionsToMine`: versions that are **not**
  `missing_pom` and **not** already mined (no `pom_meta` row). So it's exactly the
  un-mined remainder; re-running never re-fetches what's done.
- Fetch = `PomFetcher`: checks the local `~/.m2/repository` first (no network on a
  hit), else **one** plain HTTP GET (no checksum, no descriptor read, no parent/BOM
  fan-out), saved back into `~/.m2`. So `--rate` maps almost 1:1 to real requests.
- A `429` aborts the run cleanly with progress saved — re-run to continue.

## Route through the Google GCS mirror (recommended)

Google hosts a full Maven Central mirror in standard Maven layout. Point the global
`-r` at it so mining load lands on Google's infrastructure, not Sonatype's. `-r` is
global, so it goes **before** the subcommand:

```bash
java -jar target/cachegenie.jar \
  -r https://maven-central.storage-download.googleapis.com/maven2/ \
  -P -l info graph mine --all --rate 300 --threads 4
```

`--all` is required to mine the whole un-mined catalogue (no `--gav`/`--since`
filter) — a guard so a bare `graph mine` can't accidentally launch a full crawl.

Regional endpoints (use the nearest for speed):

- `https://maven-central.storage-download.googleapis.com/maven2/`
- `https://maven-central-eu.storage-download.googleapis.com/maven2/`
- `https://maven-central-asia.storage-download.googleapis.com/maven2/`

Notes:

- Apply `-r` to **`mine`** (and `deps`), the request-heavy steps. The mirror may not
  carry the `.index/` chunks, so if you re-run `index-sync` and it can't find the
  index there, drop `-r` for that command so it uses `repo1`.
- Using the mirror as a normal resolution endpoint (individual GETs) is its intended
  use; the "don't bulk-copy the mirror" caveat is about rsyncing the whole thing, not
  resolving artifacts. Still keep `--rate` sane.

## Choosing a rate

Sonatype publishes no hard requests/second limit; enforcement targets *sustained
high-volume* traffic, which a multi-million-POM crawl is. Pick a point on the curve
(times are for ~6M POMs, all network):

| `--rate` | ≈ req/s | ≈ time for 6M |
| :--- | :--- | :--- |
| 120 | 2 | ~35 days |
| 300 | 5 | ~14 days |
| 600 | 10 | ~7 days |

Keep `--threads` low (e.g. 4) so the stream is smooth rather than bursty — smooth
pacing matters more than the average. The GCS mirror makes a higher rate safer than
hitting `repo1` directly.

## Running it durably

`mine` exits `0` on a completed pass and `1` when a `429` aborts it. Two patterns:

**Resume-on-429 loop** (one long run, retries after a 429):

```bash
until java -jar target/cachegenie.jar \
  -r https://maven-central.storage-download.googleapis.com/maven2/ \
  -P -l info graph mine --all --rate 300 --threads 4; do
  echo "rate-limited — sleeping 1h then resuming"; sleep 3600
done
```

**Nightly chunked drip** (robust to reboots; uses `--limit`):

```bash
# crontab: mine up to 250k POMs each night, resuming automatically
0 2 * * *  java -jar /path/cachegenie.jar \
  -r https://maven-central.storage-download.googleapis.com/maven2/ \
  graph mine --all --rate 600 --threads 4 --limit 250000 >> ~/mine.log 2>&1
```

Run interactive sessions under `nohup`/`screen`/`tmux` since even the fast option is
days. Watch the backlog draw down with `graph mine --list` (prints the selected count
and exits) or `graph stats`.

## After mining

```bash
java -jar target/cachegenie.jar graph resolve     # mined POMs -> concrete edges
java -jar target/cachegenie.jar db optimize        # indexes + ANALYZE
# optional Neo4j read-side:
java -jar target/cachegenie.jar graph push-neo4j --uri bolt://localhost:7687 --user neo4j --password
```

## Offline resolution from local POMs

`mine` saves every fetched `.pom` into `~/.m2/repository`, so once you've mined a
slice you already hold the POMs locally. With one extra step you can resolve
dependency graphs for those artifacts **offline** — no further Maven Central traffic
— and at full Maven-model fidelity (version ranges, profiles, exclusions, relocation,
managed versions), which the SQL `graph resolve` pass deliberately does not do.

The one gap: `mine` fetches only `.pom`, never `maven-metadata.xml`, and an offline
resolve needs that to pick a version for a range or `LATEST`/`RELEASE`. CacheGenie
already knows every version from `index-sync`, so synthesise the metadata from the
catalogue instead of re-downloading it:

```bash
java -jar target/cachegenie.jar graph mine --since 30d --rate 600   # POMs -> local repo + raw tables
java -jar target/cachegenie.jar graph resolve                        # raw POMs -> concrete edges (SQL)
java -jar target/cachegenie.jar metadata                             # catalogue -> maven-metadata.xml in local repo (no network)
```

After `metadata`, the local repo has both POMs and version metadata, so an offline
resolve has everything it needs. The files are written as
`maven-metadata-<repo-id>.xml` (default id `central`) to match how the resolver's
`SimpleLocalRepositoryManager` looks up cached remote metadata; scope with
`-gav <group[:artifact]>` or run unscoped for the whole catalogue. The result is
functionally equivalent to Central's metadata for resolution, not a byte-for-byte
copy (`<lastUpdated>` and exact version ordering differ — both cosmetic, since Aether
re-sorts versions with its own comparator).

> Verify the filename once for your resolver before doing the whole catalogue:
> generate metadata for a single artifact that uses a version-range dependency and
> confirm an offline resolve picks a version. Use `--repo-id` if your remote uses a
> different id, or `--also-plain` to additionally emit plain `maven-metadata.xml`.

## Even gentler options

- A local caching proxy (Nexus/Artifactory) as `-r` is Sonatype's recommended
  approach, but only worth standing up if you'll reuse it.
- Seed the bulk graph from the Goblin dataset instead of mining it (`GOBLIN-IMPORT.md`)
  and use `mine --since <snapshot>` for just the delta — far fewer requests overall.
