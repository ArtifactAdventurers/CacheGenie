package dev.gruff.hardstop.treestreamer;

import java.net.URI;
import java.util.Objects;

/**
 * Utility methods for working with java.net.URI instances inside the tree streamer.
 *
 * <p>Provides helpers to:
 * <ul>
 *   <li>Resolve a child path against a base URI while preserving scheme/host/port and query.</li>
 *   <li>Extract the last path segment (file name) from a URI.</li>
 *   <li>Compute the path of a child URI relative to a base URI.</li>
 *   <li>Check if a URI is a descendant of another URI.</li>
 * </ul>
 * </p>
 *
 * <p>Notes:
 * <ul>
 *   <li>Operations that compare URIs (e.g., isChild) use the ASCII string form and are case-insensitive.</li>
 *   <li>subDirURI collapses duplicate slashes in the generated path.</li>
 * </ul>
 * </p>
 */
public class URIHelper {


    /**
     * Builds a new URI by appending a child path to the base URI.
     *
     * <p>The resulting URI preserves the base's scheme, host, port and query. The path is
     * the base path followed by "/" + childPath (or just childPath if the base has no path).
     * Any duplicate slashes in the resulting path are collapsed to a single slash.</p>
     *
     * @param u the base URI to resolve against; must not be null
     * @param childPath the child path or file name to append; must not be null or blank
     * @return a new combined URI, or null if the URI could not be created
     */
    public static URI subDirURI(URI u, String childPath) {
        try {
            Objects.requireNonNull(u, "base URI must not be null");
            if (childPath == null || childPath.isBlank()) return null;
            // Use built-in resolution and normalize the result to remove any ./ or ../
            return u.resolve(childPath).normalize();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the last path segment (file name) of the given URI.
     *
     * @param u the URI whose path is to be inspected
     * @return the final segment of the URI path, or null if the path is null or blank
     */
    public static String file(URI u) {
        if (u == null) return null;
        String name = u.getPath();
        if (name == null || name.isBlank()) return null;
        int idx = name.lastIndexOf('/');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    /**
     * Computes the path of a child URI relative to a base URI.
     *
     * <p>Tries {@link URI#relativize(URI)} first. If that fails (returns the same path),
     * falls back to a safe substring approach. A trailing slash in the result is removed.</p>
     *
     * @param base the base (parent) URI
     * @param path the child URI
     * @return the substring of {@code path} following {@code base}, without a trailing slash
     */
    public static String relative(URI base, URI path) {
        if (base == null || path == null) return null;
        String rel = base.relativize(path).getPath();
        if (rel == null || rel.isEmpty() || rel.equals(path.getPath())) {
            String s = base.toASCIIString();
            String p = path.toASCIIString();
            if (p.startsWith(s)) {
                rel = p.substring(s.length());
            } else {
                rel = p; // best-effort fallback
            }
        }
        if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        return rel;
    }

    /**
     * Determines whether the given path URI is a descendant of the base URI.
     *
     * <p>Comparison is performed on lower-cased ASCII string forms of the URIs, making the
     * check effectively case-insensitive. The method returns false when the candidate path
     * is the same length as or shorter than the base.</p>
     *
     * @param base the potential parent URI
     * @param path the potential child URI
     * @return true if {@code path} starts with {@code base} and is longer; otherwise false
     */
    public static boolean isChild(URI base, URI path) {
        if (base == null || path == null) return false;
        String s = base.toASCIIString().trim().toLowerCase();
        String p = path.toASCIIString().trim().toLowerCase();
        if (p.length() <= s.length()) return false; // child is same size or less
        return p.startsWith(s); // child is related to parent as it starts with same values.
    }
}
