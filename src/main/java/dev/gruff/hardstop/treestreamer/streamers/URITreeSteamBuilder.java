package dev.gruff.hardstop.treestreamer.streamers;


import dev.gruff.hardstop.treestreamer.navigators.AbstractNavigatorPolicy;
import dev.gruff.hardstop.treestreamer.navigators.Link;
import dev.gruff.hardstop.treestreamer.navigators.NavigatorPolicy;

import java.net.URI;
import java.util.function.Predicate;

public final class URITreeSteamBuilder {

    public static Config builder(URI root) {
            return new Config(root);
    }


    public static class Config {
       private final URI root;
        private AbstractNavigatorPolicy policy;

       private static class Selector {
           Predicate<String> pathFilter;
           Predicate<String> hostFilter;
       }

       Selector selectorPolicy =new Selector();

        private Config(URI root) {
            this.root=root;
        }



        public Config addPathFilter(Predicate<String> s) {

            if(s==null) return this;

            if(selectorPolicy.pathFilter==null) selectorPolicy.pathFilter=s;
            else selectorPolicy.pathFilter= selectorPolicy.pathFilter.and(s);
            return this;
        }
        public Config addHostFilter(Predicate<String> s) {
           if(s==null) return this;
           if(selectorPolicy.hostFilter==null) selectorPolicy.hostFilter=s;
           else selectorPolicy.hostFilter= selectorPolicy.hostFilter.and(s);
           return this;
        }

        public TreeStreamer<Link<URI,Object>> build() {
            Selector current= selectorPolicy; // move config out of the way.
            selectorPolicy =new Selector();
            return new InternalURITreeStreamer(root,policy, selectorPolicy);
        }

        public Config policy(NavigatorPolicy policy) {
            this.policy= (AbstractNavigatorPolicy) policy;  //
            return this;
        }
    }


    public static final class InternalURITreeStreamer extends URITreeStreamer {
        private Config.Selector selector;
        private InternalURITreeStreamer(URI froot, AbstractNavigatorPolicy policy, Config.Selector selector) {
            super(froot,policy);
            this.selector=selector;
        }
    }

}
