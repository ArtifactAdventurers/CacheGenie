-- ECOSYSTEM-ANALYSIS.sql
-- Exploratory analysis of the Maven Central catalogue captured in graph.db:
-- how artifacts arrive, grow, slow down and get abandoned, and how often a
-- POM bumps the version of a dependency it already has.
--
-- HOW TO RUN
--   duckdb ~/.m2/cachegenie/graph.db < ECOSYSTEM-ANALYSIS.sql
-- (Run the whole file in one session: the TEMP VIEWs below are created once and
--  reused by every query. `cachegenie graph query "..."` runs each statement in
--  its own process, so the views would NOT persist between calls — use the
--  duckdb pipe for the full pack, or paste a section's CTEs inline.)
--
-- DATA NOTES / CAVEATS
--  * meta_versions.published is the per-version publish timestamp, populated by
--    index-sync (FILE_MODIFIED). It is an ISO-8601 STRING; we TRY_CAST it to a
--    TIMESTAMP (stripping the trailing 'Z'). Rows that fail to parse, or that
--    were discovered only via the HTML `scan` path (no publish date), become
--    NULL and are excluded from time-based metrics. Query 0b tells you how big
--    that hole is — read it first before trusting the rates.
--  * Sections 1-6 use ONLY the meta_* catalogue, so they cover everything
--    index-sync recorded, independent of mining/resolve.
--  * Section 7 (dependency churn) needs the mining/graph tables. The RESOLVED
--    variant (7a) needs `graph resolve` to have run; the RAW variant (7b) needs
--    only `graph mine`. They answer slightly different questions — see 7.
--  * Abandonment (Q6) is measured against CURRENT_TIMESTAMP. If your index-sync
--    snapshot is older than "now", swap CURRENT_TIMESTAMP for the snapshot date.

------------------------------------------------------------------------------
-- 0. SHARED VIEWS + COVERAGE SANITY CHECK
------------------------------------------------------------------------------

-- One normalised row per (artifact, version) with a real TIMESTAMP.
CREATE OR REPLACE TEMP VIEW mv AS
SELECT ma.gid,
       ma.aid,
       v.version,
       TRY_CAST(replace(v.published, 'Z', '') AS TIMESTAMP) AS pub
FROM meta_versions v
JOIN meta_artifacts ma ON ma.id = v.ga_id;

-- One row per artifact (gid:aid) with lifecycle rollups, time-parseable only.
CREATE OR REPLACE TEMP VIEW art AS
SELECT gid,
       aid,
       COUNT(*)                              AS versions,
       MIN(pub)                              AS first_pub,
       MAX(pub)                              AS last_pub,
       date_diff('day', MIN(pub), MAX(pub))  AS span_days
FROM mv
WHERE pub IS NOT NULL
GROUP BY gid, aid;

-- 0a. Headline totals.
SELECT
  (SELECT COUNT(*) FROM meta_artifacts)                              AS artifacts,
  (SELECT COUNT(*) FROM meta_versions)                               AS versions,
  (SELECT COUNT(DISTINCT gid) FROM meta_artifacts)                   AS group_ids,
  (SELECT MIN(pub) FROM mv)                                          AS earliest_release,
  (SELECT MAX(pub) FROM mv)                                          AS latest_release;

-- 0b. How much of the catalogue has a usable publish date? (Trust gate.)
SELECT
  COUNT(*)                                                  AS total_versions,
  COUNT(pub)                                                AS with_timestamp,
  COUNT(*) - COUNT(pub)                                     AS missing_timestamp,
  round(100.0 * COUNT(pub) / COUNT(*), 2)                   AS pct_dated
FROM mv;

------------------------------------------------------------------------------
-- 1. ARRIVAL RATE OVER TIME
------------------------------------------------------------------------------

-- 1a. New VERSIONS published per year (release volume).
SELECT year(pub) AS yr, COUNT(*) AS versions_published
FROM mv WHERE pub IS NOT NULL
GROUP BY yr ORDER BY yr;

-- 1b. New ARTIFACTS per year (first time a gid:aid ever appears = "births").
SELECT year(first_pub) AS yr, COUNT(*) AS new_artifacts
FROM art
GROUP BY yr ORDER BY yr;

-- 1c. New GROUPS per year (first time a gid ever appears = new projects/orgs).
SELECT year(first_pub) AS yr, COUNT(*) AS new_groups
FROM (SELECT gid, MIN(first_pub) AS first_pub FROM art GROUP BY gid)
GROUP BY yr ORDER BY yr;

-- 1d. Monthly release volume (finer grain; trim the date range as needed).
SELECT date_trunc('month', pub) AS mo, COUNT(*) AS versions_published
FROM mv WHERE pub IS NOT NULL
GROUP BY mo ORDER BY mo;

------------------------------------------------------------------------------
-- 2. VERSIONS PER ARTIFACT  (a.k.a. "average release number")
------------------------------------------------------------------------------

-- 2a. Central tendency + spread. Mean is skewed by a few huge artifacts; the
--     median and percentiles are the honest "typical artifact".
SELECT
  COUNT(*)                                 AS artifacts,
  round(AVG(versions), 2)                  AS mean_versions,
  median(versions)                         AS median_versions,
  quantile_cont(versions, 0.90)            AS p90_versions,
  quantile_cont(versions, 0.99)            AS p99_versions,
  MAX(versions)                            AS max_versions
FROM art;

-- 2b. Distribution by release-count bucket (the shape of the long tail).
SELECT
  CASE
    WHEN versions = 1            THEN '1'
    WHEN versions BETWEEN 2 AND 5   THEN '2-5'
    WHEN versions BETWEEN 6 AND 10  THEN '6-10'
    WHEN versions BETWEEN 11 AND 25 THEN '11-25'
    WHEN versions BETWEEN 26 AND 50 THEN '26-50'
    ELSE '50+'
  END                                                          AS release_bucket,
  COUNT(*)                                                     AS artifacts,
  round(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2)           AS pct
FROM art
GROUP BY release_bucket
ORDER BY MIN(versions);

-- 2c. The most prolific artifacts.
SELECT gid, aid, versions, first_pub, last_pub
FROM art ORDER BY versions DESC LIMIT 25;

------------------------------------------------------------------------------
-- 3. SINGLE-RELEASE ARTIFACTS ("one and done")
------------------------------------------------------------------------------

-- 3a. How many artifacts only ever shipped once?
SELECT
  COUNT(*) FILTER (WHERE versions = 1)                         AS single_release,
  COUNT(*)                                                    AS total,
  round(100.0 * COUNT(*) FILTER (WHERE versions = 1) / COUNT(*), 2) AS pct_single
FROM art;

------------------------------------------------------------------------------
-- 4. LIFESPAN: TIME BETWEEN FIRST AND LAST VERSION
------------------------------------------------------------------------------

-- 4a. Lifespan distribution, in days and years. Single-release artifacts have
--     span 0; the WHERE clause optionally excludes them to measure only things
--     that actually evolved.
SELECT
  COUNT(*)                                            AS artifacts,
  round(AVG(span_days), 1)                            AS mean_span_days,
  median(span_days)                                   AS median_span_days,
  round(AVG(span_days) / 365.25, 2)                   AS mean_span_years,
  round(median(span_days) / 365.25, 2)                AS median_span_years
FROM art
WHERE versions > 1;            -- drop "WHERE versions > 1" to include one-shots

-- 4b. Lifespan buckets (how long do projects stay alive?).
SELECT
  CASE
    WHEN span_days = 0                       THEN 'single release'
    WHEN span_days < 30                      THEN '< 1 month'
    WHEN span_days < 365                      THEN '< 1 year'
    WHEN span_days < 365*2                    THEN '1-2 years'
    WHEN span_days < 365*5                    THEN '2-5 years'
    ELSE '5+ years'
  END                                                          AS lifespan_bucket,
  COUNT(*)                                                     AS artifacts,
  round(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2)           AS pct
FROM art
GROUP BY lifespan_bucket
ORDER BY MIN(span_days);

------------------------------------------------------------------------------
-- 5. UPDATE FREQUENCY
------------------------------------------------------------------------------

-- 5a. Releases per year over an artifact's active life (versions>=2 only, so
--     the span denominator is non-zero).
SELECT
  round(AVG(releases_per_year), 3)     AS mean_releases_per_year,
  median(releases_per_year)            AS median_releases_per_year,
  quantile_cont(releases_per_year, 0.90) AS p90_releases_per_year
FROM (
  SELECT versions / (span_days / 365.25) AS releases_per_year
  FROM art
  WHERE versions > 1 AND span_days > 0
);

-- 5b. Median gap between consecutive releases (days), via window LAG.
WITH gaps AS (
  SELECT gid, aid,
         date_diff('day',
                   lag(pub) OVER (PARTITION BY gid, aid ORDER BY pub),
                   pub) AS gap_days
  FROM mv
  WHERE pub IS NOT NULL
)
SELECT
  COUNT(*)                          AS release_intervals,
  round(AVG(gap_days), 1)           AS mean_gap_days,
  median(gap_days)                  AS median_gap_days,
  quantile_cont(gap_days, 0.90)     AS p90_gap_days
FROM gaps
WHERE gap_days IS NOT NULL;

------------------------------------------------------------------------------
-- 6. ABANDONMENT  (pattern: evolve, slow down, go quiet)
------------------------------------------------------------------------------

-- 6a. Age of the last release. "Abandoned" here = no release in > 2 years.
SELECT
  COUNT(*)                                                          AS artifacts,
  COUNT(*) FILTER (WHERE last_pub < CURRENT_TIMESTAMP - INTERVAL 2 YEAR)  AS likely_abandoned_2y,
  round(100.0 * COUNT(*) FILTER (WHERE last_pub < CURRENT_TIMESTAMP - INTERVAL 2 YEAR)
        / COUNT(*), 2)                                              AS pct_abandoned_2y,
  COUNT(*) FILTER (WHERE last_pub < CURRENT_TIMESTAMP - INTERVAL 5 YEAR)  AS quiet_5y,
  round(100.0 * COUNT(*) FILTER (WHERE last_pub < CURRENT_TIMESTAMP - INTERVAL 5 YEAR)
        / COUNT(*), 2)                                              AS pct_quiet_5y
FROM art;

-- 6b. Last-release-age buckets (a crude survival curve).
SELECT
  CASE
    WHEN date_diff('day', last_pub, CURRENT_TIMESTAMP) < 365     THEN 'active (<1y)'
    WHEN date_diff('day', last_pub, CURRENT_TIMESTAMP) < 365*2   THEN '1-2y'
    WHEN date_diff('day', last_pub, CURRENT_TIMESTAMP) < 365*5   THEN '2-5y'
    WHEN date_diff('day', last_pub, CURRENT_TIMESTAMP) < 365*10  THEN '5-10y'
    ELSE '10y+'
  END                                                            AS last_release_age,
  COUNT(*)                                                       AS artifacts,
  round(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2)             AS pct
FROM art
GROUP BY last_release_age
ORDER BY MIN(date_diff('day', last_pub, CURRENT_TIMESTAMP));

-- 6c. Does "one and done" predict abandonment? Cross single-release vs age.
SELECT
  versions = 1                                                   AS single_release,
  COUNT(*)                                                       AS artifacts,
  round(AVG(date_diff('day', last_pub, CURRENT_TIMESTAMP) / 365.25), 2) AS mean_years_since_last
FROM art
GROUP BY single_release;

------------------------------------------------------------------------------
-- 7. DEPENDENCY VERSION CHURN
--    "How often does a POM bump the version of a dependency it ALREADY has?"
--    We compare consecutive versions of the SAME artifact and, for each
--    dependency coordinate present in BOTH, check whether its version changed.
--    New deps (absent in the earlier version) and removed deps are excluded by
--    construction (LAG is NULL for them), so this isolates *version bumps* of
--    retained dependencies, exactly as asked.
------------------------------------------------------------------------------

-- 7a. RESOLVED variant  (needs `graph resolve`; uses the concrete `dependencies`
--     edges, so it reflects EFFECTIVE versions incl. those that came from a
--     property or a managed BOM). This is the semantically cleanest measure.
WITH edges AS (
  SELECT pa.gid AS p_gid, pa.aid AS p_aid, pa.version AS p_ver,
         ca.gid AS c_gid, ca.aid AS c_aid, ca.version AS c_ver
  FROM dependencies d
  JOIN artifacts pa ON pa.id = d.parent_id AND coalesce(pa.classifier,'') = ''
  JOIN artifacts ca ON ca.id = d.child_id
),
dated AS (   -- attach the parent version's publish date so we can order releases
  SELECT e.*, m.pub
  FROM edges e
  JOIN mv m ON m.gid = e.p_gid AND m.aid = e.p_aid AND m.version = e.p_ver
  WHERE m.pub IS NOT NULL
),
seq AS (     -- previous resolved version of the same (parent-line, child-coord)
  SELECT p_gid, p_aid, c_gid, c_aid, pub, c_ver,
         lag(c_ver) OVER (PARTITION BY p_gid, p_aid, c_gid, c_aid ORDER BY pub) AS prev_ver
  FROM dated
)
SELECT
  COUNT(*) FILTER (WHERE prev_ver IS NOT NULL)                          AS retained_dep_transitions,
  COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver)    AS version_bumped,
  round(100.0 * COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver)
        / nullif(COUNT(*) FILTER (WHERE prev_ver IS NOT NULL), 0), 2)   AS pct_bumped
FROM seq;

-- 7b. RAW variant  (needs only `graph mine`; uses as-declared `direct_dep`
--     versions). Only literal versions are comparable, so we drop deps whose
--     version is NULL (managed) or a ${property} token — those can change
--     without the literal string changing, which would understate churn.
WITH ddl AS (
  SELECT a.gid AS p_gid, a.aid AS p_aid, a.version AS p_ver,
         dd.dep_gid AS c_gid, dd.dep_aid AS c_aid, dd.dep_version AS c_ver
  FROM direct_dep dd
  JOIN artifacts a ON a.id = dd.artifact_id AND coalesce(a.classifier,'') = ''
  WHERE dd.dep_version IS NOT NULL
    AND dd.dep_version NOT LIKE '%${%'
),
dated AS (
  SELECT d.*, m.pub
  FROM ddl d
  JOIN mv m ON m.gid = d.p_gid AND m.aid = d.p_aid AND m.version = d.p_ver
  WHERE m.pub IS NOT NULL
),
seq AS (
  SELECT p_gid, p_aid, c_gid, c_aid, pub, c_ver,
         lag(c_ver) OVER (PARTITION BY p_gid, p_aid, c_gid, c_aid ORDER BY pub) AS prev_ver
  FROM dated
)
SELECT
  COUNT(*) FILTER (WHERE prev_ver IS NOT NULL)                          AS retained_dep_transitions,
  COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver)    AS version_bumped,
  round(100.0 * COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver)
        / nullif(COUNT(*) FILTER (WHERE prev_ver IS NOT NULL), 0), 2)   AS pct_bumped
FROM seq;

-- 7c. Per-RELEASE view: when an artifact ships a new version, what share of its
--     retained dependencies did it bump? (Distribution across release events.)
--     Uses the resolved edges; switch the CTE to 7b's `ddl` for the raw view.
WITH edges AS (
  SELECT pa.gid AS p_gid, pa.aid AS p_aid, pa.version AS p_ver,
         ca.gid AS c_gid, ca.aid AS c_aid, ca.version AS c_ver
  FROM dependencies d
  JOIN artifacts pa ON pa.id = d.parent_id AND coalesce(pa.classifier,'') = ''
  JOIN artifacts ca ON ca.id = d.child_id
),
dated AS (
  SELECT e.*, m.pub FROM edges e
  JOIN mv m ON m.gid = e.p_gid AND m.aid = e.p_aid AND m.version = e.p_ver
  WHERE m.pub IS NOT NULL
),
seq AS (
  SELECT p_gid, p_aid, p_ver, pub, c_gid, c_aid, c_ver,
         lag(c_ver) OVER (PARTITION BY p_gid, p_aid, c_gid, c_aid ORDER BY pub) AS prev_ver
  FROM dated
),
per_release AS (
  SELECT p_gid, p_aid, p_ver,
         COUNT(*) FILTER (WHERE prev_ver IS NOT NULL)                       AS retained,
         COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver) AS bumped
  FROM seq
  GROUP BY p_gid, p_aid, p_ver
)
SELECT
  COUNT(*) FILTER (WHERE retained > 0)                              AS releases_with_retained_deps,
  COUNT(*) FILTER (WHERE bumped > 0)                                AS releases_that_bumped_something,
  round(100.0 * COUNT(*) FILTER (WHERE bumped > 0)
        / nullif(COUNT(*) FILTER (WHERE retained > 0), 0), 2)       AS pct_releases_bumping_a_dep,
  round(AVG(CASE WHEN retained > 0 THEN 100.0 * bumped / retained END), 2) AS avg_pct_deps_bumped_per_release
FROM per_release;

-- 7d. Which dependencies get bumped most often by their consumers? (Resolved.)
WITH edges AS (
  SELECT pa.gid AS p_gid, pa.aid AS p_aid, pa.version AS p_ver,
         ca.gid AS c_gid, ca.aid AS c_aid, ca.version AS c_ver
  FROM dependencies d
  JOIN artifacts pa ON pa.id = d.parent_id AND coalesce(pa.classifier,'') = ''
  JOIN artifacts ca ON ca.id = d.child_id
),
dated AS (
  SELECT e.*, m.pub FROM edges e
  JOIN mv m ON m.gid = e.p_gid AND m.aid = e.p_aid AND m.version = e.p_ver
  WHERE m.pub IS NOT NULL
),
seq AS (
  SELECT c_gid, c_aid, c_ver,
         lag(c_ver) OVER (PARTITION BY p_gid, p_aid, c_gid, c_aid ORDER BY pub) AS prev_ver
  FROM dated
)
SELECT c_gid, c_aid,
       COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver) AS times_bumped,
       COUNT(*) FILTER (WHERE prev_ver IS NOT NULL)                       AS retained_transitions,
       round(100.0 * COUNT(*) FILTER (WHERE prev_ver IS NOT NULL AND c_ver <> prev_ver)
             / nullif(COUNT(*) FILTER (WHERE prev_ver IS NOT NULL), 0), 2) AS pct_bumped
FROM seq
GROUP BY c_gid, c_aid
HAVING COUNT(*) FILTER (WHERE prev_ver IS NOT NULL) >= 50
ORDER BY times_bumped DESC
LIMIT 30;
