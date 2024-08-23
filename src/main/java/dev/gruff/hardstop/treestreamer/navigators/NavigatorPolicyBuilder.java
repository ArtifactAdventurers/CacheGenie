package dev.gruff.hardstop.treestreamer.navigators;


import dev.gruff.hardstop.treestreamer.LinkParser;
import dev.gruff.hardstop.treestreamer.ContentType;
import dev.gruff.hardstop.treestreamer.Node;

import java.time.Duration;
import java.util.function.Predicate;

public class NavigatorPolicyBuilder {
    public static Config builder() {

        return new Config();
    }

    public static class Config {

        MyURIPolicy p=new MyURIPolicy();

        public Config defaultNavigator(ContentType html, LinkParser parser) {
            p.defaultHandler(parser);
            return this;
        }

        public Config maxDepth(int i) {
            p.maxDepth=i;
            return this;
        }



        public Config rateLimit(int count, Duration d) {
            p.rateLimit(count,d);
            return this;
        }


        public class ConfigHandler {

            private Predicate<Node> pred=null;


            public ConfigHandler(Predicate<Node> identifier) {
                pred=identifier;
            }

            public ConfigHandler and(Predicate<Node> identifier) {
                if(identifier==null) throw new RuntimeException("predicate is null");
                pred=pred.and(identifier);
                return this;
            }

            public Config useNavigator(LinkParser handler) {
                if(handler==null) throw new RuntimeException("handler is null");
                Config.this.p.addHandler(pred,handler);
                return Config.this;
            }


        }


        public ConfigHandler when(Predicate<Node> identifier) {

            return new ConfigHandler(identifier);
        }

        public NavigatorPolicy build() {
           MyURIPolicy r=p;
           p=new MyURIPolicy();
           return r;
        }

        private Config copy() {
            return this;
        }

        public Config then() {
            return this;
        }

        final class MyURIPolicy extends AbstractNavigatorPolicy implements NavigatorPolicy {
            Config c= Config.this.copy();


        }
    }
}
