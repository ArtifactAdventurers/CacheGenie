package dev.gruff.hardstop.cachegenie.entities;

import java.util.List;
import java.util.Map;

/**
 * The <em>raw, as-declared</em> contents of a single {@code pom.xml}, exactly as
 * written in that file — <b>no</b> parent inheritance, <b>no</b> {@code ${...}}
 * interpolation, <b>no</b> managed-version resolution. Produced by
 * {@link dev.gruff.hardstop.cachegenie.parsers.RawPomParser} and persisted by
 * {@code GraphRepository.MiningWriter} into the POM-mining tables.
 *
 * <p>The effective view (inherited fields filled, properties interpolated, managed
 * versions resolved, transitive closure) is computed <em>later</em> in SQL/Java,
 * because the {@code parent*} coordinates here let a child be mined without ever
 * downloading its parent — the parent is its own catalogue node mined on its own
 * turn. Fields that are commonly inherited (scm, organization, url, …) are
 * therefore frequently null on a child until resolution runs.
 *
 * <p>Any field may be null; the {@code status} reports whether the parse succeeded.
 */
public record MinedPom(
        String gid, String aid, String version, POMStatus status,
        String packaging,
        String parentGid, String parentAid, String parentVersion, String parentRelPath,
        String name, String description, String url, String inceptionYear,
        String organizationName, String organizationUrl,
        String scmUrl, String scmConnection, String scmDevConnection, String scmTag,
        String issueSystem, String issueUrl, String ciSystem, String ciUrl,
        List<RawDep> dependencies,
        List<RawDep> dependencyManagement,
        Map<String, String> properties,
        List<Dev> developers,
        List<License> licenses) {

    /** A raw {@code <dependency>} (from {@code <dependencies>} or {@code <dependencyManagement>}). */
    public record RawDep(String gid, String aid, String version, String scope,
                         String type, String classifier, boolean optional) {}

    /** A raw {@code <developer>} or {@code <contributor>}. */
    public record Dev(String roleKind, String id, String name, String email,
                      String organization, String organizationUrl, String url, String roles) {}

    /** A raw {@code <license>}. */
    public record License(String name, String url, String distribution) {}

    /** Did the POM parse OK (vs. a missing/malformed file)? */
    public boolean ok() {
        return status == POMStatus.OK;
    }

    /** A failed parse with the given status and all fields null/empty. */
    public static MinedPom failed(POMStatus status) {
        return new MinedPom(null, null, null, status, null,
                null, null, null, null,
                null, null, null, null,
                null, null,
                null, null, null, null,
                null, null, null, null,
                List.of(), List.of(), Map.of(), List.of(), List.of());
    }
}
