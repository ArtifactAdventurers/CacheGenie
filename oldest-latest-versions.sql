-- oldest-latest-versions.sql
-- Top 100 "oldest latest versions" in the catalogue: for each artifact
-- (gid:aid), take its latest release (most recently published version),
-- then list the 100 of those latest releases that are furthest in the past.
-- Answers "how stale is the tip of the catalogue?".
--
-- HOW TO RUN
--   cachegenie graph query -f oldest-latest-versions.sql
--
-- NOTES
--  * "Latest" = newest by published date, not by version-string ordering.
--  * Artifacts with no parseable publish date (undated / scan-only versions)
--    are excluded — they can't be dated, so they can't be "oldest".
--  * To scope to a group, add a gid filter inside the `mv` CTE, e.g.:
--      WHERE ma.gid = 'org.foo'                 -- exact group
--      WHERE ma.gid = 'org.foo' OR ma.gid LIKE 'org.foo.%'  -- + subgroups

WITH mv AS (
    SELECT ma.gid, ma.aid, v.version,
           CASE WHEN julianday(replace(v.published, 'Z', '')) IS NOT NULL
                THEN replace(replace(v.published, 'Z', ''), 'T', ' ') END AS pub,
           ROW_NUMBER() OVER (
               PARTITION BY ma.gid, ma.aid
               ORDER BY CASE WHEN julianday(replace(v.published, 'Z', '')) IS NOT NULL
                             THEN replace(replace(v.published, 'Z', ''), 'T', ' ') END DESC
           ) AS rn
    FROM meta_versions v
    JOIN meta_artifacts ma ON ma.id = v.ga_id
)
SELECT gid, aid, version, pub AS published,
       CAST(julianday('now') - julianday(pub) AS INTEGER) AS age_days
FROM mv
WHERE rn = 1 AND pub IS NOT NULL
ORDER BY pub ASC
LIMIT 100;
