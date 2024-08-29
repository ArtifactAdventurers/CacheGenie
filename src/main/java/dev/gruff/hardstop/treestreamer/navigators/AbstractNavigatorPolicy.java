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


    private List<Selector> selectors=new LinkedList<>();
    private LinkReader defaultHandler;

    public boolean belowDepth(int depth) {
        if(maxDepth<=0) return false;
        if(depth<maxDepth) return false;
        return true;
    }

    public void rateLimit(int c,Duration d) {
        this.rateLimiter=new RateLimiter(c,d);
    }

    public RateLimiter rateLimiter() {
        if(rateLimiter==null) return new RateLimiter(6, Duration.ofMinutes(1));
        return rateLimiter;
    }

   static class Selector {
        Predicate<Link> pred;
        LinkReader parser;
        Function transformer;

        public boolean canHandle(Connection.Response r) {

            Link l=new Link() {


                @Override
                public URI path() {
                    try {
                        return r.url().toURI();
                    } catch (URISyntaxException e) {
                       System.out.println(e);
                    }
                    return null;
                }

                @Override
                public boolean isType(ContentType contentType) {
                    return contentType.match(r.contentType());
                }
            };
            return pred.test(l);
        }





       public <F, T> void setTransformer(Function<F,T> t) {


                   transformer=t;
       }
   }

    @Override
    public LinkReader handler(Connection.Response r) {
        if(selectors.isEmpty()) {

            return defaultHandler;
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

        return defaultHandler;
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
