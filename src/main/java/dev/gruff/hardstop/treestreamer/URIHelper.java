package dev.gruff.hardstop.treestreamer;

import dev.gruff.hardstop.treestreamer.navigators.Link;

import java.net.URI;

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

        StringBuilder sb=new StringBuilder();
        String scheme=u.getScheme();
        if(scheme!=null) {
            sb.append(scheme);
            sb.append("://");
        }
        String host=u.getHost();
        if(host!=null) {
            sb.append(host);
           int port=u.getPort();
           if(port>=0) {
               sb.append(":"+port);
           }
           String path=u.getPath();
           if(path==null) {
               path=childPath;
           } else {
               path = path + "/" + childPath;
           }
           path=path.replace("//","/");
            sb.append(path);

           String query=u.getQuery();
           if(query!=null) {
               sb.append("?");
               sb.append(query);
           }
           }
       try {
         return   URI.create(sb.toString());
       } catch(Exception e) {
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
        String name=u.getPath();
        if(name==null ||name.trim().equals("")) return null;
        String[] bits=name.split("/");
        return bits[bits.length-1];
    }

    /**
     * Computes the path of a child URI relative to a base URI.
     *
     * <p>The calculation uses the ASCII string representation of the URIs. It assumes that
     * {@code path} begins with {@code base}. If this precondition is not met, a
     * {@link StringIndexOutOfBoundsException} may be thrown by {@link String#substring(int)}.
     * A trailing slash in the result is removed.</p>
     *
     * @param base the base (parent) URI
     * @param path the child URI
     * @return the substring of {@code path} following {@code base}, without a trailing slash
     */
    public static String relative(URI base, URI path) {
        String s=base.toASCIIString();
        String p=path.toASCIIString();
        String sub=p.substring(s.length());
        if(sub.endsWith("/")) sub=sub.substring(0,sub.length()-1);

        return sub;
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
    public static boolean isChild(URI base,URI path) {
        String s=base.toASCIIString().trim().toLowerCase();
        String p=path.toASCIIString().trim().toLowerCase();
        if(p.length()<=s.length()) return false; //child is same size or less
        return p.startsWith(s); // child is  related to parent as it startw with same values.


    }
}
