# CLI Cleanup Plan

A staged plan to remove obsolete code, retire superseded commands, and fix
stale help text in the CacheGenie CLI. Phases are ordered by risk: Phase 1 is
zero-behaviour-change dead-code deletion; later phases retire user-visible
commands and so warrant a deprecation cycle.

Verify after **every** phase with `mvn clean package` and `mvn test`. (The
sandbox can't build this project — JDK 11, no Maven/DuckDB — so compilation is
done on Steve's machine.)

---

## Phase 1 — Delete dead code (safe, no behaviour change) ✅ DONE (2026-06)

All five classes below were deleted; the stale `CreateDBAction` doc comment in
`DBAction` was tidied. Pre-flight confirmed no live references. Build pending on
Steve's machine.

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

1. Deprecation step (one release): ✅ DONE (2026-06)
   - ✅ In `GraphCmd`, both `@Command` descriptions now begin
     `"[DEPRECATED — use 'graph mine' + 'graph resolve'] …"`.
   - ✅ Each `run()` emits a `log.warn(...)` pointing to the replacement.
2. Removal step: ✅ DONE (2026-06)
   - ✅ Deleted the `GraphArtifact` and `GraphCacheCmd` static classes from
     `GraphCmd.java` and removed them from the `subcommands` list.

**Second-order cleanup:** ✅ DONE in the same pass (references were verified zero
after the command removal):

- ✅ `Resolver.resolveGraph(...)` removed (+ the now-orphaned private `collect0`).
- ✅ `GraphRepository.persist(DependencySet)` removed (kept `persistDirect`).
- ✅ `GraphRepository.isArtifactPresent(...)` removed.
- ✅ Then-orphaned classes `DependencySet`, `DependencyBuilder`, `DotViz` deleted
  (stale unused import dropped from `POM.java`).
- **Kept** `MetaRepository.loadByPattern(...)` — generic helper with live test
  coverage (`MetaRepositoryTest`); not obsolete, so left in place.

**Keep:** `CacheAction` (still used by the `cache`/hydrate command, `CacheCmd`)
and `DepOps` (shared arg-group used by `CompareCmd` and `CacheCmd`).

**Risk:** low — removes a redundant path with a documented replacement.
**Effort:** ~1 h including the second-order pass.

---

## Phase 3 — Consolidate redundant meta reporting/export

Meta data can currently be reported/exported four ways with overlap. Goal: one
exporter (`db export`) and one stats command (`graph stats`).

1. **`meta-csv` → fold into `db export`.** ✅ REMOVED (2026-06): deleted
   `MetaCSVCmd` + `CreateMetaCSVAction`, dropped from `RootCmd` subcommands. Use
   `db export -f csv`.
2. **`analyse meta` → overlaps `graph stats`.** ✅ REMOVED (2026-06): deleted
   `AnalyseMetaCmd` and the dead `AnalyseAction.analyseMeta()`, dropped from
   `AnalyseCmd`'s subcommands. Use `graph stats`. `analyse pom` kept (genuinely
   inspects on-disk POMs).
3. **`meta` (fetch) vs `graph mine` — DECIDED: keep (option a).** `fetch` stays as
   the targeted "just put this POM on disk" tool; no longer advertised as a core
   workflow step (the `RootCmd` footer now lists it under single-GAV tools). `mine`
   owns bulk POM acquisition. No deprecation.

**Risk:** medium (user-visible commands) — hence deprecate-then-remove.
**Effort:** ~1–2 h across two releases.

---

## Phase 4 — Fix stale help text & workflow guidance (do immediately, independent of the above)

Cheap, no logic change, reduces confusion now.

- ✅ `MetaCSVCmd` description corrected (now "Dump all discovery metadata from the
  DuckDB catalogue to a CSV").
- ✅ `AnalyseMetaCmd` description corrected (now "Report discovery-metadata counts
  from the DuckDB catalogue").
- ✅ `RootCmd` footer rewritten to the current flow (`index-sync` → `graph mine` →
  `graph resolve` → `metadata`/`cache`), with `scan`/`fetch` noted as targeted
  single-GAV tools.
- ⏳ Sync `README.md` and `CLAUDE.md` command maps with whatever lands in Phases
  1–3 (CLAUDE.md/README already updated for `metadata`; revisit after Phases 2–3).

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
