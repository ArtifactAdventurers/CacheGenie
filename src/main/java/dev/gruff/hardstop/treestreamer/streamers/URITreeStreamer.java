package dev.gruff.hardstop.treestreamer.streamers;

import dev.gruff.hardstop.treestreamer.LinkReader;
import dev.gruff.hardstop.treestreamer.RateLimiter;
import dev.gruff.hardstop.treestreamer.navigators.*;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Streams a tree of URI-addressable resources starting from a root URI.
 *
 * <p>This streamer fetches resources using Jsoup and delegates how to parse each
 * fetched resource, when to continue traversal, and how to rate-limit requests to an
 * {@link dev.gruff.hardstop.treestreamer.navigators.AbstractNavigatorPolicy}.</p>
 *
 * <p>Traversal is depth-first. For each visited {@link dev.gruff.hardstop.treestreamer.navigators.Link}
 * the policy supplies a {@link dev.gruff.hardstop.treestreamer.LinkReader} which either returns:
 * <ul>
 *   <li>a {@link dev.gruff.hardstop.treestreamer.navigators.LinkSet} whose members are expanded recursively, or</li>
 *   <li>an arbitrary terminal result object that is emitted into the stream</li>
 * </ul>
 * A {@code null} parser result is treated as an empty {@link dev.gruff.hardstop.treestreamer.navigators.LinkSet}.</p>
 *
 * <p>Traversal halts beneath depths that the policy rejects via
 * {@link dev.gruff.hardstop.treestreamer.navigators.AbstractNavigatorPolicy#belowDepth(int)}.
 * Errors (I/O, unsupported MIME type) and interruptions while waiting on the
 * rate limiter are swallowed for the current link and do not fail the stream.</p>
 *
 * <p>This class is sealed; create instances via {@link URITreeSteamVisitorBuilder}
 * or extend within the same module as permitted.</p>
 *
 * <p>Thread-safety: instances of this class are thread-safe for concurrent calls to {@link #stream()},
 * provided the supplied {@link dev.gruff.hardstop.treestreamer.navigators.AbstractNavigatorPolicy} and any
 * {@link dev.gruff.hardstop.treestreamer.LinkReader} implementations are not modified after construction.</p>
 */
public abstract sealed class URITreeStreamer  implements TreeStreamer<Object> permits URITreeSteamVisitorBuilder.InternalURITreeStreamer {


    private final Link root;
    private final AbstractNavigatorPolicy policy;
    private final RateLimiter limiter;
    /**
     * Creates a new URI tree streamer.
     *
     * @param root   the starting URI to traverse
     * @param policy the navigator policy that governs depth, parsing, and rate limiting
     */
    protected URITreeStreamer(URI root, AbstractNavigatorPolicy policy) {

        this.root=new HTMLRefNavigator.MyLink(root);
        this.policy=policy;
        this.limiter=policy.rateLimiter();


    }

    /**
     * Produces a lazy, depth-first stream of traversal outputs starting at the root URI.
     * <p>The stream may contain:</p>
     * <ul>
     *   <li>{@link dev.gruff.hardstop.treestreamer.navigators.Link} instances representing visited nodes, and</li>
     *   <li>terminal result objects produced by policy-provided {@link dev.gruff.hardstop.treestreamer.LinkReader}s</li>
     * </ul>
     * The exact mix depends on the configured {@link dev.gruff.hardstop.treestreamer.navigators.AbstractNavigatorPolicy}.
     *
     * @return a sequential Stream of traversal items
     */
    @Override
    public Stream<Object> stream() {

        return expand(0,root);

    }

    private Stream<Object> expand(int depth,Link r) {


        if(r==null) return Stream.empty();

        return Stream.concat(Stream.of(r),links(depth++,r));

    }

    private Stream<Object> links(final int depth,Link r) {




        if(policy.belowDepth(depth)) {

            return Stream.of();
        }

        Connection c = Jsoup.connect(r.path().toASCIIString());

          Connection.Response resp= null;
        try {
            limiter.waitForPermission();
            resp = c.execute();
            LinkReader parser = policy.handler(resp);

            if (parser != null) {
                Object result= parser.parse(r, resp.bodyStream());
                     if(result==null) result=new LinkSetImpl();
                    if(result instanceof LinkSet ls) {
                        return ls
                                .stream()
                                .dropWhile(Objects::isNull)
                                .flatMap(l -> {
                                    return expand(depth + 1, l);
                                });
                    } else {
                        return Set.of(result).stream();
                    }

            }

        }catch( org.jsoup.UnsupportedMimeTypeException ee) {

            return Stream.of();

        } catch (IOException | InterruptedException e) {

            System.out.println("error "+e.getLocalizedMessage()+" reading "+r.path());
        }

        return Stream.of();


    }

    private static  URI toURI(Object f) {
        return (URI) ((Link) f).path();
    }


}
