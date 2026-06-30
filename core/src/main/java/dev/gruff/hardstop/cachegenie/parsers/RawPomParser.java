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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // --- Recoverable-POM sanitisation -------------------------------------
    //
    // Many POMs on Central are not well-formed XML: undeclared HTML named
    // entities (&copy; &aelig; &ndash; ...), a UTF-8 BOM or junk before the
    // XML declaration, NUL/control characters, or trailing bytes after
    // </project>. None of these are recoverable by Maven Resolver either — it
    // runs the same bytes through an equally strict reader (Xpp3/StAX) and
    // gives us no hook to pre-clean the bytes. We fix what we safely can here,
    // in two passes: a light clean that never alters document structure (so
    // POMs that already parse keep parsing), and — only if that still fails —
    // an aggressive clean that trims the prolog/trailer.

    private static final Pattern ENTITY_REF = Pattern.compile("&([a-zA-Z][a-zA-Z0-9]*);");
    private static final Pattern XML_DECL   = Pattern.compile("<\\?xml\\b[^>]*\\?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROOT_START = Pattern.compile("<project(?=[\\s/>])", Pattern.CASE_INSENSITIVE);
    private static final Pattern BAD_CHARS  = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFEFF]");
    private static final String  CLOSE_TAG  = "</project>";

    private static final Map<String, String> HTML_ENTITIES = buildHtmlEntities();

    /** Lenient read on the per-thread builder, with byte-level recovery of common malformations. */
    private static Document parseXML(File file) {
        final String light;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            light = lightSanitize(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Skipping unreadable POM {}: {}", file.getAbsolutePath(), e.getMessage());
            return null;
        }

        try {
            return parseString(light);
        } catch (Exception first) {
            // Second chance: trim prolog/trailing junk and retry. Only attempted
            // when the light parse failed, so well-formed POMs are never altered.
            String aggressive = aggressiveSanitize(light);
            if (!aggressive.equals(light)) {
                try {
                    return parseString(aggressive);
                } catch (Exception ignored) {
                    // fall through and report the original (more informative) failure
                }
            }
            log.warn("Skipping malformed POM {}: {}", file.getAbsolutePath(), first.getMessage());
            return null;
        }
    }

    private static Document parseString(String content) throws Exception {
        return BUILDER.get().parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Structure-preserving clean: strip a BOM/leading-trailing whitespace and
     * invalid XML control characters, then replace undeclared HTML named
     * entities with numeric character references. Does not touch the prolog or
     * element tree, so any POM that already parsed still parses unchanged.
     * Entity replacement is skipped when the document declares its own entities
     * ({@code <!ENTITY ...}), so a deliberately-redefined name is never clobbered.
     */
    private static String lightSanitize(String s) {
        s = BAD_CHARS.matcher(s).replaceAll("");
        s = s.strip();
        if (!s.contains("<!ENTITY")) {
            s = replaceHtmlEntities(s);
        }
        return s;
    }

    /**
     * Last-resort clean for documents that still fail: reduce the text to the
     * first {@code <project> .. </project>} element, optionally re-prefixed with
     * a leading XML declaration. This removes junk before the root ("content not
     * allowed in prolog", a misplaced {@code <?xml?>}, stray preceding markup)
     * and after it ("content not allowed in trailing section", concatenated
     * documents). Returns the input unchanged if no {@code <project>} root is found.
     */
    private static String aggressiveSanitize(String s) {
        Matcher rm = ROOT_START.matcher(s);
        if (!rm.find()) return s;               // can't locate a root; leave as-is
        int rootStart = rm.start();

        int closeIdx = indexOfIgnoreCase(s, CLOSE_TAG, rootStart);
        String body = closeIdx >= 0
                ? s.substring(rootStart, closeIdx + CLOSE_TAG.length())
                : s.substring(rootStart);

        Matcher dm = XML_DECL.matcher(s);
        String decl = (dm.find() && dm.start() == 0) ? s.substring(0, dm.end()) + "\n" : "";
        return decl + body;
    }

    /** Replace any {@code &name;} whose name is a known HTML entity with a numeric char ref. */
    private static String replaceHtmlEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY_REF.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        while (m.find()) {
            String repl = HTML_ENTITIES.get(m.group(1));
            // "$0" re-inserts the original match verbatim (the five XML-predefined
            // entities and any unknown names are intentionally left untouched).
            m.appendReplacement(out, repl != null ? repl : "$0");
        }
        m.appendTail(out);
        return out.toString();
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        final int end = haystack.length() - needle.length();
        for (int i = Math.max(0, from); i <= end; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    /**
     * The HTML4 named-entity set mapped to numeric character references. The five
     * XML-predefined entities (amp, lt, gt, quot, apos) are deliberately omitted
     * so they are never rewritten.
     */
    private static Map<String, String> buildHtmlEntities() {
        Map<String, String> m = new HashMap<>(300);
        // Latin-1 supplement (U+00A0–U+00FF)
        int[] latin = {
            160,161,162,163,164,165,166,167,168,169,170,171,172,173,174,175,
            176,177,178,179,180,181,182,183,184,185,186,187,188,189,190,191,
            192,193,194,195,196,197,198,199,200,201,202,203,204,205,206,207,
            208,209,210,211,212,213,214,215,216,217,218,219,220,221,222,223,
            224,225,226,227,228,229,230,231,232,233,234,235,236,237,238,239,
            240,241,242,243,244,245,246,247,248,249,250,251,252,253,254,255 };
        String[] latinNames = {
            "nbsp","iexcl","cent","pound","curren","yen","brvbar","sect","uml","copy","ordf","laquo","not","shy","reg","macr",
            "deg","plusmn","sup2","sup3","acute","micro","para","middot","cedil","sup1","ordm","raquo","frac14","frac12","frac34","iquest",
            "Agrave","Aacute","Acirc","Atilde","Auml","Aring","AElig","Ccedil","Egrave","Eacute","Ecirc","Euml","Igrave","Iacute","Icirc","Iuml",
            "ETH","Ntilde","Ograve","Oacute","Ocirc","Otilde","Ouml","times","Oslash","Ugrave","Uacute","Ucirc","Uuml","Yacute","THORN","szlig",
            "agrave","aacute","acirc","atilde","auml","aring","aelig","ccedil","egrave","eacute","ecirc","euml","igrave","iacute","icirc","iuml",
            "eth","ntilde","ograve","oacute","ocirc","otilde","ouml","divide","oslash","ugrave","uacute","ucirc","uuml","yacute","thorn","yuml" };
        for (int i = 0; i < latin.length; i++) m.put(latinNames[i], "&#" + latin[i] + ";");

        // Latin Extended-A, spacing modifiers
        put(m, "OElig", 338); put(m, "oelig", 339); put(m, "Scaron", 352); put(m, "scaron", 353);
        put(m, "Yuml", 376); put(m, "fnof", 402); put(m, "circ", 710); put(m, "tilde", 732);

        // General punctuation
        put(m, "ensp", 8194); put(m, "emsp", 8195); put(m, "thinsp", 8201);
        put(m, "zwnj", 8204); put(m, "zwj", 8205); put(m, "lrm", 8206); put(m, "rlm", 8207);
        put(m, "ndash", 8211); put(m, "mdash", 8212);
        put(m, "lsquo", 8216); put(m, "rsquo", 8217); put(m, "sbquo", 8218);
        put(m, "ldquo", 8220); put(m, "rdquo", 8221); put(m, "bdquo", 8222);
        put(m, "dagger", 8224); put(m, "Dagger", 8225); put(m, "bull", 8226);
        put(m, "hellip", 8230); put(m, "permil", 8240); put(m, "prime", 8242); put(m, "Prime", 8243);
        put(m, "lsaquo", 8249); put(m, "rsaquo", 8250); put(m, "oline", 8254); put(m, "frasl", 8260);
        put(m, "euro", 8364); put(m, "trade", 8482);

        // Greek
        int[] greekCp = {
            913,914,915,916,917,918,919,920,921,922,923,924,925,926,927,928,929,931,932,933,934,935,936,937,
            945,946,947,948,949,950,951,952,953,954,955,956,957,958,959,960,961,962,963,964,965,966,967,968,969,
            977,978,982 };
        String[] greekNm = {
            "Alpha","Beta","Gamma","Delta","Epsilon","Zeta","Eta","Theta","Iota","Kappa","Lambda","Mu","Nu","Xi","Omicron","Pi","Rho","Sigma","Tau","Upsilon","Phi","Chi","Psi","Omega",
            "alpha","beta","gamma","delta","epsilon","zeta","eta","theta","iota","kappa","lambda","mu","nu","xi","omicron","pi","rho","sigmaf","sigma","tau","upsilon","phi","chi","psi","omega",
            "thetasym","upsih","piv" };
        for (int i = 0; i < greekCp.length; i++) m.put(greekNm[i], "&#" + greekCp[i] + ";");

        // Letterlike / arrows / math (HTML4 symbol set)
        put(m, "weierp", 8472); put(m, "image", 8465); put(m, "real", 8476); put(m, "alefsym", 8501);
        put(m, "larr", 8592); put(m, "uarr", 8593); put(m, "rarr", 8594); put(m, "darr", 8595); put(m, "harr", 8596); put(m, "crarr", 8629);
        put(m, "lArr", 8656); put(m, "uArr", 8657); put(m, "rArr", 8658); put(m, "dArr", 8659); put(m, "hArr", 8660);
        put(m, "forall", 8704); put(m, "part", 8706); put(m, "exist", 8707); put(m, "empty", 8709); put(m, "nabla", 8711);
        put(m, "isin", 8712); put(m, "notin", 8713); put(m, "ni", 8715); put(m, "prod", 8719); put(m, "sum", 8721);
        put(m, "minus", 8722); put(m, "lowast", 8727); put(m, "radic", 8730); put(m, "prop", 8733); put(m, "infin", 8734);
        put(m, "ang", 8736); put(m, "and", 8743); put(m, "or", 8744); put(m, "cap", 8745); put(m, "cup", 8746);
        put(m, "int", 8747); put(m, "there4", 8756); put(m, "sim", 8764); put(m, "cong", 8773); put(m, "asymp", 8776);
        put(m, "ne", 8800); put(m, "equiv", 8801); put(m, "le", 8804); put(m, "ge", 8805);
        put(m, "sub", 8834); put(m, "sup", 8835); put(m, "nsub", 8836); put(m, "sube", 8838); put(m, "supe", 8839);
        put(m, "oplus", 8853); put(m, "otimes", 8855); put(m, "perp", 8869); put(m, "sdot", 8901);
        put(m, "lceil", 8968); put(m, "rceil", 8969); put(m, "lfloor", 8970); put(m, "rfloor", 8971);
        put(m, "lang", 9001); put(m, "rang", 9002); put(m, "loz", 9674);
        put(m, "spades", 9824); put(m, "clubs", 9827); put(m, "hearts", 9829); put(m, "diams", 9830);
        return m;
    }

    private static void put(Map<String, String> m, String name, int cp) {
        m.put(name, "&#" + cp + ";");
    }

    private static DocumentBuilder newBuilder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            // Never reach out for external DTDs/entities. Some POMs carry a DOCTYPE or a
            // SYSTEM entity (e.g. activemq's locator.ent); loading it off disk fails with
            // "No such file or directory" and the POM is counted 'bad'. We don't need a DTD
            // to read a POM, and fetching external resources during a mass crawl is both an
            // XXE security risk and a needless I/O failure — so disable it outright. This
            // hardens the parser and recovers the POMs that previously failed only on the
            // unresolvable external reference.
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DocumentBuilder b = f.newDocumentBuilder();
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
