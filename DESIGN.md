# CacheGenie — Design & Objectives (north star)

> This is the **agreed target design**, not a description of the current code. The
> existing implementation still reflects the earlier "resolve dependency graphs
> on demand (via Aether)" framing; this document is the direction we are moving
> toward and the reference for pruning/refactoring decisions. Where the code and
> this document disagree, this document is the intent.

## Mission

> CacheGenie maintains a **current, queryable database of Maven artifacts** — their
> facts and their **direct** dependencies — **enriched with external risk
> annotations** (CVE, end-of-life/lifecycle, …), in a way that is **cheap to keep
> current** and supports both **graph-like queries** and **ordinary DB statistics**.
> A **complete local cache of POMs and metadata** is kept as a (free) byproduct
> because it enables later Maven-native actions (offline resolution, mirroring, API
> diffing).

The product is the **data asset**, not on-demand graph resolution. Resolution is one
consumer of the data, not the point.

## The four layers

Each layer has different cost and refresh characteristics, and the schema should
respect that separation.

1. **Catalogue** — cheap, authoritative, from the published repository index
   (`index-sync`): every release coordinate, version, publish date, checksums,
   packaging. Refresh: incremental, frequent.
2. **Facts** — moderate, from mining raw POMs: declared properties (scm, license,
   organization, developers) and **direct** dependencies + `dependencyManagement`.
   Immutable once mined (a published artifact's POM never changes); refresh only
   covers new/changed variants.
3. **Projections** — derived (resolution, transitive closures, centrality).
   Recomputed from layers 1–2; never a source of truth.
4. **Annotations** — cheap, external, time-varying (CVE, lifecycle/EOL, …). Keyed to
   releases/variants, refreshed on their own cadence, never mixed into the immutable
   facts.

**Store direct edges only; transitive is always a query.** This is what makes "keep
current cheaply" possible — a new version adds rows, it never forces recomputing
anyone's transitive tree.

## Pipeline (decoupled stages)

```
Repository Registry → Discover → Catalogue → Acquire → Mine → Resolve → Annotate
```

- **Registry** — first-class data about each repo (see below); drives discovery and
  acquisition routing.
- **Discover** — "what exists and where," per repo, by the cheapest available means
  (published Maven index, HTML crawl, or a repo-specific API).
- **Catalogue** — the unified release/variant inventory (layer 1).
- **Acquire** — fetch bytes (POM, later JARs). **Indexing is separate from
  acquisition**: take the authoritative version list from one source (e.g. Central's
  index) but fetch bytes from the fastest source (e.g. a CDN mirror).
- **Mine** — extract facts from POMs (layer 2).
- **Resolve** — project facts into concrete edges / effective graphs (layer 3).
- **Annotate** — layer 4.

Every stage is idempotent and independently runnable/refreshable.

## Identity model

Two units that must never be conflated:

- **release** — the logical coordinate `group:artifact:version` (GAV). What
  dependencies reference, the join axis for comparison, and what discovery finds.
- **variant** — a concrete **build** of a release, **first-class**. Distinguished by
  `supplier` (who built it: upstream/Apache, Red Hat, HeroDevs, …) and bytes
  (`sha1`/`sha256`). This is the thing people actually consume and the thing
  HeroDevs sells support for.

```
repository(id, name, type, base_url, mirror_of, access_method, rate_limits)
release  (id, group, artifact, version, purl_normalized)
variant  (id, release_id, supplier, sha1, sha256, packaging, classifier, published, first_seen)
variant_source(variant_id, repo_id, url, first_seen)   -- where this exact build is served
```

Discovery dedups on **(GAV, classifier, type, sha)**:

- **Mirror** — same GAV, same bytes in multiple repos → one variant, multiple
  `variant_source` rows.
- **Variant** (rebuild/republish) — same GAV, different bytes (e.g. a Red Hat GA
  rebuild, a HeroDevs NES build) → a distinct `variant` under the same release.
- **New** — a GAV present only in repo X → a new release.

Checksum-based dedup is also the cost-control mechanism: collapsing mirrors stops us
mining and storing the same POM once per mirror. For Central-only scope every release
has exactly one variant, so the model adds no overhead until real variant diversity
exists.

## Repositories

A registry table, because access varies and "distinguish mirrors from new artifacts"
is a per-repo concern:

- `type` — Central-mirror, index-publishing, HTML-crawlable, API-based (e.g. Google's
  storage endpoint), …
- `access_method` and operating restrictions (rate limits / ToS).
- `mirror_of` — a pointer that, with checksum equality, lets us assert "this is just a
  mirror copy, don't re-mine."

Adding a repo should be data, not code.

## Edges and resolution context

Dependencies are declared on **coordinates**, not builds — a POM says
`org.foo:bar:1.2`, a release. So the stored edge is **`variant → release`**:

```
direct_dep(from_variant_id, to_release_id, scope, type, classifier, optional)
```

Which variant *satisfies* a dependency is a **resolution-context** decision: a
supplier preference (e.g. "prefer Red Hat variants, else upstream"; "prefer HeroDevs
NES, else upstream"). Transitive graph for a root = walk edges, resolving each target
release to a variant per the chosen context.

This makes **transitive comparison across variants** a first-class capability: resolve
the same root under two supplier preferences and diff the resulting variant sets —
showing where a Red Hat / NES tree diverges from upstream, and where it silently falls
back to unsupported upstream dependencies (itself a risk signal).

## Annotation layer (two-tier)

Public feeds describe **coordinates**, not builds (OSV/GHSA: "`org.foo:bar` in range
`[1.0,1.5)` is vulnerable"; endoflife.date / OpenEoX: coordinate/version lifecycle).
Supplier-specific truth (NES backported the fix; Red Hat's support ends later) is an
**override** on top. So:

```
cve_applicability  (release_id, cve_id, affected_range, source, fetched_at)   -- public baseline
lifecycle          (release_id, supplier, support_level, milestone_type, date, source, fetched_at)
variant_remediation(variant_id, cve_id, status, source, fetched_at)           -- supplier override
```

- **Effective CVE exposure** for a variant = baseline applicability for its release
  **minus** that variant's remediations.
- **Effective lifecycle** = the supplier's track for that variant.
- Lifecycle is **milestone-typed** per OpenEoX — `milestone_type ∈ {GA, EndOfSales,
  EndOfSecuritySupport, EndOfLife, …}` — not a single `eol_date`, and "is it EOL now?"
  is **derived** from date-vs-today, never stored as a status that goes stale.
  Milestone facts are additive/immutable once known but correctable with provenance.
  OpenEoX is still in development (OASIS ratification expected ~late 2026/2027), so
  milestone types are modelled as data, not baked-in enums.

This represents the HeroDevs value proposition natively, as a cross-variant query:
*"`spring-x:5.3.20` — upstream variant: EOL, 7 open CVEs; HeroDevs NES variant:
supported through 2027, 0 open CVEs."*

## Storage strategy

- **DuckDB is the single system of record** (relational + analytical; good for stats,
  direct-edge lookups, bounded-depth transitive).
- A **graph engine (Neo4j) is an optional, regenerable projection**, not core. The one
  query that genuinely stresses a relational engine is reverse-transitive closure
  (blast radius: "everything that transitively depends on X"). Decide empirically:
  build blast-radius as a DuckDB recursive CTE first; stand up the graph projection
  only if it proves too slow. The `export-neo4j` / `push-neo4j` path already provides
  this without a runtime DB-abstraction layer (which we deliberately avoid).
- **purl** is a derived, normalized join key for matching external (CVE/EOL) data —
  not the primary key. The primary key is the component tuple (group, artifact,
  version, classifier, type); purl is computed, with a normalization function to
  absorb the format drift different SBOM tools emit.

## Scope

- **In:** all of Maven Central and other public Maven repositories, distinguished by
  the registry; mirror-vs-variant resolved by checksum.
- **Out (for now):** display/analysis UI and a served API — revisited once the data
  model is settled.

## Implications for the current CLI

The agreed mission made several existing commands legacy or redundant. Pruned so far
(see `CLI-CLEANUP-PLAN.md` and the CHANGELOG):

- ✅ Removed the Aether transitive-resolution commands `graph artifact` and
  `graph cache` (superseded by `mine` → `resolve`) and their dead supporting API
  (`Resolver.resolveGraph`, `GraphRepository.persist(DependencySet)`/`isArtifactPresent`,
  and the orphaned `DependencySet`/`DependencyBuilder`/`DotViz`).
- ✅ Removed dead `.properties`-era code (`migrate-meta`, `UpdateAction`, `ListAction`,
  `CreateDBAction`).
- ✅ Removed `meta-csv` (→ `db export`) and `analyse meta` (→ `graph stats`).
- ✅ Decided `meta`/`fetch` is kept as a targeted single-GAV POM-on-disk tool;
  `graph mine` owns bulk acquisition.

Still open:

- `graph deps` (Aether descriptor reads) is kept for now as an online, targeted path;
  revisit whether it stays once `mine`/`resolve` fully cover its uses.
- The browser viewer (`view` + the `viewer` module) is display-side → out of scope;
  not yet removed.
