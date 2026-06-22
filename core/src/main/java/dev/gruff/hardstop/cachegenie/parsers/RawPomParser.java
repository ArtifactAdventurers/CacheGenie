package dev.gruff.hardstop.cachegenie.parsers;

import dev.gruff.hardstop.cachegenie.entities.MinedPom;
import dev.gruff.hardstop.cachegenie.entities.POMStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.gruff.hardstop.cachegenie.parsers.ParserHelper.getAll;
import static dev.gruff.hardstop.cachegenie.parsers.ParserHelper.getOnly;
import static dev.gruff.hardstop.cachegenie.parsers.ParserHelper.getOnlyText;

/**
 * Parses a single {@code pom.xml} into a {@link MinedPom} of <em>raw, as-declared</em>
 * values — no inheritance, no {@code ${...}} interpolation, no managed-version
 * resolution. This is the mining counterpart to {@link POMFileParser} (which only
 * extracts coordinates + flat dependencies); this one additionally captures
 * {@code <parent>}, {@code <scm>}, {@code <organization>}, identity fields,
 * {@code <properties>}, {@code <dependencyManagement>}, {@code <developers>},
 * {@code <contributors>}, {@code <licenses>}, and per-dependency scope/type/
 * classifier/optional.
 *
 * <p>Thread-safe: each thread gets its own {@link DocumentBuilder} (JAXP builders
 * are not shareable), so this can be driven from a worker pool — unlike the shared
 * static builder in {@link POMFileParser}.
 */
public final class RawPomParser {

    private static final Logger log = LoggerFactory.getLogger(RawPomParser.class);

    private static final ThreadLocal<DocumentBuilder> BUILDER = ThreadLocal.withInitial(RawPomParser::newBuilder);

    private RawPomParser() {}

    public static MinedPom parse(File pom) {
        if (pom == null || !pom.exists()) return MinedPom.failed(POMStatus.NO_CACHE_FILE);
        if (!pom.isFile()) return MinedPom.failed(POMStatus.WRONG_CACHE_TYPE);
        if (pom.length() == 0) return MinedPom.failed(POMStatus.EMPTY_FILE);

        Document doc = parseXML(pom);
        if (doc == null) return MinedPom.failed(POMStatus.XML_ERROR);

        Element root = doc.getDocumentElement();
        if (root == null) return MinedPom.failed(POMStatus.NO_ROOT);
        if (!root.getTagName().equalsIgnoreCase("project")) return MinedPom.failed(POMStatus.INCORRECT_ROOT_TAG);

        Element parent = getOnly(root, "parent");
        String parentGid = parent != null ? text(parent, "groupId") : null;
        String parentAid = parent != null ? text(parent, "artifactId") : null;
        String parentVersion = parent != null ? text(parent, "version") : null;
        String parentRelPath = parent != null ? text(parent, "relativePath") : null;

        // Coordinates: gid/version may be inherited from <parent> (as in POMFileParser).
        String gid = text(root, "groupId");
        if (gid == null) gid = parentGid;
        if (gid == null) return MinedPom.failed(POMStatus.NO_GROUPID);

        String aid = text(root, "artifactId");
        if (aid == null) return MinedPom.failed(POMStatus.NO_ARTIFACTID);

        String version = text(root, "version");
        if (version == null) version = parentVersion;
        if (version == null) return MinedPom.failed(POMStatus.NO_VERSION);

        String packaging = text(root, "packaging");
        if (packaging == null) packaging = "jar";

        // Identity / provenance.
        String name = text(root, "name");
        String description = text(root, "description");
        String url = text(root, "url");
        String inceptionYear = text(root, "inceptionYear");

        Element org = getOnly(root, "organization");
        String orgName = org != null ? text(org, "name") : null;
        String orgUrl = org != null ? text(org, "url") : null;

        Element scm = getOnly(root, "scm");
        String scmUrl = scm != null ? text(scm, "url") : null;
        String scmConn = scm != null ? text(scm, "connection") : null;
        String scmDevConn = scm != null ? text(scm, "developerConnection") : null;
        String scmTag = scm != null ? text(scm, "tag") : null;

        Element issue = getOnly(root, "issueManagement");
        String issueSystem = issue != null ? text(issue, "system") : null;
        String issueUrl = issue != null ? text(issue, "url") : null;

        Element ci = getOnly(root, "ciManagement");
        String ciSystem = ci != null ? text(ci, "system") : null;
        String ciUrl = ci != null ? text(ci, "url") : null;

        List<MinedPom.RawDep> deps = parseDeps(getOnly(root, "dependencies"));

        Element dmWrap = getOnly(root, "dependencyManagement");
        List<MinedPom.RawDep> depMgmt = dmWrap != null
                ? parseDeps(getOnly(dmWrap, "dependencies"))
                : List.of();

        Map<String, String> properties = parseProperties(getOnly(root, "properties"));

        List<MinedPom.Dev> developers = new ArrayList<>();
        developers.addAll(parsePeople(getOnly(root, "developers"), "developer"));
        developers.addAll(parsePeople(getOnly(root, "contributors"), "contributor"));

        List<MinedPom.License> licenses = parseLicenses(getOnly(root, "licenses"));

        return new MinedPom(gid, aid, version, POMStatus.OK, packaging,
                parentGid, parentAid, parentVersion, parentRelPath,
                name, description, url, inceptionYear,
                orgName, orgUrl,
                scmUrl, scmConn, scmDevConn, scmTag,
                issueSystem, issueUrl, ciSystem, ciUrl,
                deps, depMgmt, properties, developers, licenses);
    }

    /** Parse {@code <dependency>} children of a {@code <dependencies>} wrapper (may be null). */
    private static List<MinedPom.RawDep> parseDeps(Element depsWrap) {
        if (depsWrap == null) return List.of();
        List<MinedPom.RawDep> out = new ArrayList<>();
        for (Element d : getAll(depsWrap, "dependency")) {
            String type = text(d, "type");
            String classifier = text(d, "classifier");
            String optional = text(d, "optional");
            out.add(new MinedPom.RawDep(
                    text(d, "groupId"), text(d, "artifactId"), text(d, "version"),
                    text(d, "scope"),
                    type != null ? type : "jar",
                    classifier != null ? classifier : "",
                    "true".equalsIgnoreCase(optional)));
        }
        return out;
    }

    /** All direct child elements of {@code <properties>} as name/value pairs. */
    private static Map<String, String> parseProperties(Element propsWrap) {
        if (propsWrap == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Element e : childElements(propsWrap)) {
            String v = e.getTextContent();
            out.put(e.getTagName(), v != null ? v.trim() : "");
        }
        return out;
    }

    private static List<MinedPom.Dev> parsePeople(Element wrap, String childTag) {
        if (wrap == null) return List.of();
        List<MinedPom.Dev> out = new ArrayList<>();
        for (Element p : getAll(wrap, childTag)) {
            Element rolesWrap = getOnly(p, "roles");
            String roles = null;
            if (rolesWrap != null) {
                List<String> rs = new ArrayList<>();
                for (Element r : getAll(rolesWrap, "role")) {
                    String rt = r.getTextContent();
                    if (rt != null && !rt.isBlank()) rs.add(rt.trim());
                }
                if (!rs.isEmpty()) roles = String.join(",", rs);
            }
            out.add(new MinedPom.Dev(childTag,
                    text(p, "id"), text(p, "name"), text(p, "email"),
                    text(p, "organization"), text(p, "organizationUrl"),
                    text(p, "url"), roles));
        }
        return out;
    }

    private static List<MinedPom.License> parseLicenses(Element wrap) {
        if (wrap == null) return List.of();
        List<MinedPom.License> out = new ArrayList<>();
        for (Element l : getAll(wrap, "license")) {
            out.add(new MinedPom.License(text(l, "name"), text(l, "url"), text(l, "distribution")));
        }
        return out;
    }

    /** Trimmed text of the single child {@code tag}, or null if absent/blank. */
    private static String text(Element parent, String tag) {
        String t = getOnlyText(parent, tag);
        if (t == null) return null;
        t = t.trim();
        return t.isEmpty() ? null : t;
    }

    /** Direct child {@link Element}s of {@code parent}, in document order. */
    private static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) out.add((Element) n);
        }
        return out;
    }

    /** Same lenient read as {@link POMFileParser#parseXML} but on the per-thread builder. */
    private static Document parseXML(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String content = new String(bytes, StandardCharsets.UTF_8).trim();
            if (content.contains("&") && (content.contains("&oslash;") || content.contains("&nbsp;") || content.contains("&aacute;"))) {
                content = content.replace("&oslash;", "&#248;")
                                 .replace("&nbsp;", "&#160;")
                                 .replace("&aacute;", "&#225;");
            }
            return BUILDER.get().parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("Skipping malformed POM {}: {}", file.getAbsolutePath(), e.getMessage());
            return null;
        }
    }

    private static DocumentBuilder newBuilder() {
        try {
            DocumentBuilder b = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            b.setErrorHandler(new org.xml.sax.ErrorHandler() {
                @Override public void warning(SAXParseException e) { /* ignore */ }
                @Override public void error(SAXParseException e) throws SAXParseException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
            });
            return b;
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
    }
}
