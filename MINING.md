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

## Even gentler options

- A local caching proxy (Nexus/Artifactory) as `-r` is Sonatype's recommended
  approach, but only worth standing up if you'll reuse it.
- Seed the bulk graph from the Goblin dataset instead of mining it (`GOBLIN-IMPORT.md`)
  and use `mine --since <snapshot>` for just the delta — far fewer requests overall.
