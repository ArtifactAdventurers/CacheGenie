package dev.gruff.hardstop.treestreamer.streamers;

import dev.gruff.hardstop.treestreamer.LinkParser;
import dev.gruff.hardstop.treestreamer.RateLimiter;
import dev.gruff.hardstop.treestreamer.navigators.AbstractNavigatorPolicy;
import dev.gruff.hardstop.treestreamer.navigators.HTMLRefNavigator;
import dev.gruff.hardstop.treestreamer.navigators.Link;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.stream.Stream;

public abstract sealed class URITreeStreamer  implements TreeStreamer<Link<URI,Object>> permits URITreeSteamBuilder.InternalURITreeStreamer {


    private final Link<URI,Object> root;
    private AbstractNavigatorPolicy policy;
    private RateLimiter limiter;
    protected URITreeStreamer(URI root, AbstractNavigatorPolicy policy) {

        this.root=new HTMLRefNavigator.MyLink(root);
        this.policy=policy;
        this.limiter=policy.rateLimiter();


    }

    @Override
    public Stream<Link<URI,Object>> stream() {

        return expand(0,root);

    }

    private Stream<Link<URI,Object>> expand(int depth,Link<URI,Object> r) {


        if(r==null) return Stream.empty();

        return Stream.concat(Stream.of(r),links(depth++,r));

    }

    private Stream<Link<URI,Object>> links(final int depth,Link<URI,Object> r) {




        if(policy.belowDepth(depth)) {

            return Stream.of();
        }

        Connection c = Jsoup.connect(r.path().toASCIIString());

          Connection.Response resp= null;
        try {
            limiter.waitForPermission();
            resp = c.execute();
            LinkParser<URI,Object> parser = policy.handler(resp);

            if (parser != null) {
                return parser.parse(r, resp.bodyStream())
                        .stream()
                        .dropWhile(Objects::isNull)
                        .flatMap(l -> {
                            return expand(depth + 1, l);
                        });


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
