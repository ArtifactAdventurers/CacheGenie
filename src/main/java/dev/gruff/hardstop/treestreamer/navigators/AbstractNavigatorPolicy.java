package dev.gruff.hardstop.treestreamer.navigators;

import dev.gruff.hardstop.treestreamer.LinkParser;
import dev.gruff.hardstop.treestreamer.ContentType;
import dev.gruff.hardstop.treestreamer.Node;
import dev.gruff.hardstop.treestreamer.RateLimiter;
import dev.gruff.hardstop.treestreamer.streamers.NodeSystem;
import org.jsoup.Connection;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;


public abstract sealed class AbstractNavigatorPolicy implements NavigatorPolicy permits NavigatorPolicyBuilder.Config.MyURIPolicy {


    int maxDepth=-1;

    private RateLimiter rateLimiter;
    private boolean consumed=true;

    private List<Selector> selectors=new LinkedList<>();
    private LinkParser defaultHandler;

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

    private static class Selector {
        Predicate<Node> pred;
        LinkParser parser;
        Predicate<NodeSystem.ContainerNode> posrSelector;

        public boolean canHandle(Connection.Response r) {
            Node n=new Node() {
                @Override
                public ContentType type() {
                    return null;
                }

                @Override
                public boolean noSuffix() {

                   boolean check= !r.url().getFile().contains(".");

                   return check;
                }

                @Override
                public String name() {

                    return r.url().getFile();
                }

                @Override
                public boolean isType(ContentType contentType) {

                    ContentType t=null;
                    String type=r.contentType();
                    if(type.startsWith("text/html")) t=ContentType.HTML;
                    if(type.startsWith("text/xml")) t=ContentType.XML;

                    return contentType==t;
                }

                @Override
                public boolean missingSuffix() {
                    //System.out.println("missing suffix? "+r.url().getFile());
                    return !r.url().getFile().contains(".");

                }

                @Override
                public boolean isName(String s) {
                    return r.url().getFile().equals(s);
                }
            };
            return pred.test(n);
        }


    }

    @Override
    public LinkParser handler(Connection.Response r) {
        if(selectors.isEmpty()) {

            return defaultHandler;
        }
        for(Selector s:selectors) {
            if(s.canHandle(r)) {

                return s.parser;
            }
        }

        return defaultHandler;
    }

    void addHandler(Predicate<Node> pred, LinkParser parser) {

        if(pred==null) throw new RuntimeException("predicate is null");
        if(parser==null) throw new RuntimeException("parser is null");
        Selector s=new Selector();
       s.pred=pred;
       s.parser=parser;
       selectors.add(s);
    }



    void defaultHandler(LinkParser parser) {
        this.defaultHandler=parser;
    }
}
