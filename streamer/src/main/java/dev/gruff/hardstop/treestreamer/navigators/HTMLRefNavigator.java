package dev.gruff.hardstop.treestreamer.navigators;

import dev.gruff.hardstop.treestreamer.ContentType;
import dev.gruff.hardstop.treestreamer.LinkReader;
import dev.gruff.hardstop.treestreamer.URIHelper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LinkReader that parses HTML documents and extracts navigable child links.
 *
 * <p>This reader uses Jsoup to parse anchor elements (a[href]) and converts them into
 * Link instances rooted at the supplied base link. Only links that resolve to URIs that
 * are children of the base URI are returned. The following hrefs are ignored:</p>
 * <ul>
 *   <li>Parent-directory entries (.. or ../)</li>
 *   <li>In-page anchors beginning with '#'</li>
 *   <li>Non-navigable schemes such as mailto: and javascript:</li>
 * </ul>
 *
 * <p>Relative links are resolved via {@link URIHelper#subDirURI(URI, String)}
 * and constrained by {@link URIHelper#isChild(URI, URI)}.</p>
 *
 * <p>This class is stateless and safe for concurrent use.</p>
 */
public final class HTMLRefNavigator implements LinkReader {

    /**
     * Parses an HTML document from the given InputStream and extracts navigable child links.
     *
     * <p>The document is parsed as UTF-8 using Jsoup. All anchors with an href attribute are
     * resolved against the provided base link and filtered to remain within the base URI's
     * hierarchy. Parent-directory links, in-page anchors, and non-navigable schemes are skipped.</p>
     *
     * @param uri the base link whose URI is used to resolve relative hrefs
     * @param in  the HTML content stream; this method does not close the stream
     * @return a LinkSetImpl containing zero or more child links (never null)
     */
    @Override
    public LinkSetImpl parse(Link uri, InputStream in) {
        Document doc = null;
        try {
            doc = Jsoup.parse(in, StandardCharsets.UTF_8.name(), uri.path().toASCIIString());
        } catch (IOException e) {
            return new LinkSetImpl();
        }
        Set<Link> links = doc.select("a[href]")
                .stream()
                .map(l -> toLink(uri, l))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return new LinkSetImpl(links);
    }

    /**
     * Converts an anchor element into a navigable Link relative to the given base.
     *
     * <p>Returns null for anchors that point to parent directories, in-page anchors,
     * or non-navigable schemes. Valid links are resolved relative to the base and
     * must be children of the base URI.</p>
     *
     * @param base the base link used to resolve relative hrefs
     * @param a the anchor element to convert
     * @return a Link for the resolved child URI, or null if the anchor is not navigable
     */
    private Link toLink(Link base, Element a) {
        if (a == null) return null;
        String title = a.attr("href");
        if (title == null) return null;
        title = title.trim();
        if (title.isEmpty()) return null;
        // skip parent-directory links and non-navigable anchors/schemes
        if (title.equals("../") || title.equals("..")) return null;
        if (title.startsWith("#")) return null;
        String lower = title.toLowerCase();
        if (lower.startsWith("mailto:") || lower.startsWith("javascript:")) return null;

        URI lURI = URIHelper.subDirURI(base.path(), title);
        if (lURI != null && URIHelper.isChild(base.path(), lURI)) return new MyLink(lURI);
        return null;
    }

    /**
     * Immutable Link implementation used by HTMLRefNavigator to represent child URIs.
     *
     * <p>Wraps a resolved URI and provides minimal type information. Content type matching
     * is not performed here and {@link #isType(ContentType)}
     * returns false for all values.</p>
     */
    public static class MyLink implements Link {
        private final URI path;

        public MyLink(URI lURI) {
            this.path = lURI;
        }

        @Override
        public URI path() {
            return path;
        }

        @Override
        public boolean isType(ContentType contentType) {
            return false;
        }

        public String toString() {
            return "MS:" + path;
        }
    }
}
