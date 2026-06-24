# CLI Cleanup Plan

A staged plan to remove obsolete code, retire superseded commands, and fix
stale help text in the CacheGenie CLI. Phases are ordered by risk: Phase 1 is
zero-behaviour-change dead-code deletion; later phases retire user-visible
commands and so warrant a deprecation cycle.

Verify after **every** phase with `mvn clean package` and `mvn test`. (The
sandbox can't build this project — JDK 11, no Maven/DuckDB — so compilation is
done on Steve's machine.)

---

## Phase 1 — Delete dead code (safe, no behaviour change)

These classes are never registered in `RootCmd.subcommands` and have no live
references, so deleting them cannot change any reachable behaviour.

| Delete | Why |
|---|---|
| `cli/.../cli/MigrateMetaCmd.java` | `migrate-meta` is not registered in `RootCmd` → unreachable. Migrates `.properties`→`.json`, an on-disk format the project abandoned for DuckDB. |
| `cli/.../actions/MigrateMetaAction.java` | Only used by `MigrateMetaCmd`. |
| `cli/.../actions/UpdateAction.java` | Zero references anywhere. |
| `cli/.../actions/ListAction.java` | Zero references; streams `.properties` files that are no longer produced. |
| `cli/.../actions/CreateDBAction.java` | Referenced only in a comment in `DBAction` ("Replaces the old CSV-dumping CreateDBAction"); reads `.properties`. Superseded by `DBAction`. |

**Pre-flight (confirms nothing else references them):**

```bash
for c in MigrateMetaCmd MigrateMetaAction UpdateAction ListAction CreateDBAction; do
  echo "=== $c ==="; grep -rn "$c" --include='*.java' . | grep -v "/$c.java"
done
```

Expect only the `CreateDBAction` mention inside `DBAction.java`'s comment (tidy
that comment too). Then delete and build.

**Risk:** none. **Effort:** ~15 min.

---

## Phase 2 — Retire the legacy graph-resolution commands (`graph artifact`, `graph cache`)

Both inner classes in `GraphCmd` (`GraphArtifact`, `GraphCacheCmd`) use the old
Aether **transitive** `Resolver.resolveGraph` / `GraphRepository.persist` path —
the eager-resolution approach the POM-mining redesign replaced specifically to
stop the Maven Central 429 storms. They are superseded by the
`deps` → `mine` → `resolve` pipeline.

**Recommended: deprecate first, remove next release.**

1. Deprecation step (one release):
   - In `GraphCmd`, change the two `@Command` descriptions to begin
     `"[DEPRECATED — use 'graph mine' + 'graph resolve'] …"`.
   - Emit a one-line `log.warn(...)` at the top of each `run()` pointing to the
     replacement.
2. Removal step (next release):
   - Delete the `GraphArtifact` and `GraphCacheCmd` static classes from
     `GraphCmd.java`.
   - Remove them from the `subcommands = { … }` list on `GraphCmd`
     (`GraphCmd.GraphArtifact.class`, `GraphCmd.GraphCacheCmd.class`).

**Second-order cleanup (after removal — verify each is then unused):** these
become dead once the two commands are gone, but are public `core` API, so remove
deliberately in a follow-up rather than in the same commit:

- `Resolver.resolveGraph(...)` — only caller is `GraphCmd`.
- `GraphRepository.persist(...)` (the `DependencySet` overload — **not**
  `persistDirect`, which `graph deps` still uses).
- `GraphRepository.isArtifactPresent(...)` — only caller is `GraphCmd`.
- `MetaRepository.loadByPattern(...)` — callers are `GraphCmd` and a test
  (`MetaRepositoryTest`); drop or repoint the test.

**Keep:** `CacheAction` (still used by the `cache`/hydrate command, `CacheCmd`)
and `DepOps` (shared arg-group used by `CompareCmd` and `CacheCmd`).

**Risk:** low — removes a redundant path with a documented replacement.
**Effort:** ~1 h including the second-order pass.

---

## Phase 3 — Consolidate redundant meta reporting/export

Meta data can currently be reported/exported four ways with overlap. Goal: one
exporter (`db export`) and one stats command (`graph stats`).

1. **`meta-csv` → fold into `db export`.** `db export` already does
   `COPY … TO` in parquet/csv/json and is the superset. Deprecate `meta-csv`
   (`MetaCSVCmd`); after a release, delete `MetaCSVCmd` + `CreateMetaCSVAction`.
2. **`analyse meta` → overlaps `graph stats`.** It reads `graph.db` (not the
   cache), so it's also *misplaced* under `analyse` ("inspect the cache").
   Recommend deprecating `AnalyseMetaCmd` and pointing users to `graph stats`;
   keep `analyse pom` (it genuinely inspects on-disk POMs).
3. **`meta` (fetch) vs `graph mine` — decision needed, not an automatic cut.**
   `mine` is the scalable POM-acquisition path that also populates the graph;
   `fetch` only hydrates `~/.m2` and sets `missing_pom`, feeding nothing into the
   graph. Options:
   - (a) Keep `fetch` as the targeted "just put this POM on disk" tool, but stop
     advertising it as workflow step 2 (see Phase 4).
   - (b) Deprecate `fetch` entirely and let `mine` own POM acquisition.
   Recommend (a) unless there are no on-disk-only consumers of POMs.

**Risk:** medium (user-visible commands) — hence deprecate-then-remove.
**Effort:** ~1–2 h across two releases.

---

## Phase 4 — Fix stale help text & workflow guidance (do immediately, independent of the above)

Cheap, no logic change, reduces confusion now.

- `MetaCSVCmd` description says *"from all meta properties files"* — it reads
  DuckDB (`MetaRepository`). Fix wording.
- `AnalyseMetaCmd` description says *"Analyse meta properties files"* — it
  queries `graph.db`. Fix wording.
- `RootCmd` footer "Genie workflow" (scan→fetch→map→hydrate) predates
  `index-sync`, `mine`, and `resolve`, so it steers new users to the slow/legacy
  path. Update to the current recommended flow, e.g.
  `index-sync` → `graph mine` → `graph resolve` → `cache`, with `scan`/`fetch`
  noted as targeted single-GAV tools.
- Sync `README.md` and `CLAUDE.md` command maps with whatever lands in Phases
  1–3.

**Risk:** none. **Effort:** ~30 min.

---

## Suggested sequencing

1. **Phase 1 + Phase 4** together in one PR — pure cleanup, no deprecation
   cycle, immediate clarity win.
2. **Phase 2 (deprecate)** and **Phase 3 (deprecate)** in the next release.
3. **Phase 2 (remove) + second-order cleanup** and **Phase 3 (remove)** the
   release after.

## Quick reference — files touched

- Delete (Phase 1): `MigrateMetaCmd`, `MigrateMetaAction`, `UpdateAction`,
  `ListAction`, `CreateDBAction`.
- Edit (Phase 2): `GraphCmd.java` (descriptions/warns, then class + subcommand
  removal); later `Resolver`, `GraphRepository`, `MetaRepository`,
  `MetaRepositoryTest`.
- Edit/Delete (Phase 3): `MetaCSVCmd`, `CreateMetaCSVAction`, `AnalyseMetaCmd`,
  `AnalyseCmd` (drop subcommand), optionally `MetaCmd`.
- Edit (Phase 4): `MetaCSVCmd`, `AnalyseMetaCmd`, `RootCmd` footer, `README.md`,
  `CLAUDE.md`.
