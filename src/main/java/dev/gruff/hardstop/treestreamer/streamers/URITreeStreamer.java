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

public abstract sealed class URITreeStreamer  implements TreeStreamer<Object> permits URITreeSteamVisitorBuilder.InternalURITreeStreamer {


    private final Link root;
    private AbstractNavigatorPolicy policy;
    private RateLimiter limiter;
    protected URITreeStreamer(URI root, AbstractNavigatorPolicy policy) {

        this.root=new HTMLRefNavigator.MyLink(root);
        this.policy=policy;
        this.limiter=policy.rateLimiter();


    }

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
