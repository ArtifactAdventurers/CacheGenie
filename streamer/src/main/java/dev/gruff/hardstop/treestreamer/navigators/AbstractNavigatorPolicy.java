package dev.gruff.hardstop.treestreamer.navigators;

import dev.gruff.hardstop.treestreamer.LinkReader;
import dev.gruff.hardstop.treestreamer.ContentType;
import dev.gruff.hardstop.treestreamer.RateLimiter;
import org.jsoup.Connection;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public abstract sealed class AbstractNavigatorPolicy implements NavigatorPolicy permits NavigatorPolicyBuilder.MyUriPolicy {


    int maxDepth=-1;

    private RateLimiter rateLimiter;


    private final List<Selector> selectors=new LinkedList<>();
    private LinkReader defaultHandler;

    public boolean belowDepth(int depth) {
        if(maxDepth<=0) return false;
        if(depth<maxDepth) return false;
        return true;
    }

    public void rateLimit(int c,Duration d) {
        this.rateLimiter=new RateLimiter(c,d);
    }

    /** Share an existing limiter across policies (e.g. one aggregate budget for a parallel crawl). */
    public void rateLimit(RateLimiter shared) {
        this.rateLimiter=shared;
    }

    public RateLimiter rateLimiter() {
        if(rateLimiter==null) return new RateLimiter(6, Duration.ofMinutes(1));
        return rateLimiter;
    }

   static class Selector {
        Predicate<Link> pred;
        LinkReader parser;
        private Function<Object, Object> transformer;

        public boolean canHandle(Connection.Response r) {
            final URI uri;
            try {
                uri = r.url().toURI();
            } catch (URISyntaxException e) {
                // If the response URL cannot be converted to a URI, this selector cannot handle it
                return false;
            }

            Link l = new Link() {
                @Override
                public URI path() {
                    return uri;
                }

                @Override
                public boolean isType(ContentType contentType) {
                    return contentType != null && contentType.match(r.contentType());
                }
            };
            try {
                return pred != null && pred.test(l);
            } catch (RuntimeException ex) {
                // Defensive: if the predicate throws due to unexpected input, treat as non-match
                return false;
            }
        }

        public void setTransformer(Function<? super Object, ? extends Object> t) {
            this.transformer = (t == null) ? null : o -> t.apply(o);
        }
   }

    @Override
    public LinkReader handler(Connection.Response r) {
        if(selectors.isEmpty()) {
            return (defaultHandler != null) ? defaultHandler : (uri, in) -> new LinkSetImpl();
        }
        for(Selector s:selectors) {
            if(s.canHandle(r)) {
                if(s.transformer!=null) {
                    return (uri, in) -> {
                        Object o=s.parser.parse(uri,in);

                        if(o!=null) {
                            return s.transformer.apply(o);
                        }
                        return new LinkSetImpl();
                    };
                }
                return s.parser;
            }
        }

        return (defaultHandler != null) ? defaultHandler : (uri, in) -> new LinkSetImpl();
    }

   Selector addSelector(Predicate<Link> pred, LinkReader parser) {

        if(pred==null) throw new RuntimeException("predicate is null");
        if(parser==null) throw new RuntimeException("parser is null");
        Selector s=new Selector();
       s.pred=pred;
       s.parser=parser;
       selectors.add(s);
       return s;
    }



    void defaultHandler(LinkReader parser) {
        this.defaultHandler=parser;
    }
}
