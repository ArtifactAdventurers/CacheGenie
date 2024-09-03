package dev.gruff.hardstop.treestreamer.streamers;


import dev.gruff.hardstop.treestreamer.navigators.*;

import java.net.URI;
import java.util.*;
import java.util.function.Consumer;

public final class URITreeSteamVisitorBuilder {

    private  URI root;

    public static Configurator newInstance(URI root) {
            return new Configurator(root);
    }


    public static class Configurator {

        private NavigatorPolicy policy;
        private  URI root;

        private Configurator(URI root) {
            this.root=root;
        }



        public SelectConfig select() {
            return new SelectConfig();
        }


        public class SelectConfig {

            public <X> StreamConfig<X> on(Class<X> x) {
                return new StreamConfig<X>(x);
            }

            public OtherwiseStreamConfig otherwise() {
                return new OtherwiseStreamConfig();
            }

            public void visit() {
                Configurator.this.visit();
            }

            public class OtherwiseStreamConfig {
                public <X> Configurator consume(Consumer<X> x) {
                    Configurator.this.setOtherwise(x);
                    return Configurator.this;
                }
            }
            public class StreamConfig<X> {

                Selector s=new Selector();
                public StreamConfig(Class<X> x) {

                    s.matcher=x;
                }

                public SelectConfig consume(Consumer<X> x) {

                    s.consumer=x;
                    Configurator.this.addStreamer(s);
                    return SelectConfig.this;
                }
            }



        }

        private class Selector {
            Class matcher;
            Consumer consumer;
        }


        private List<Selector> selectors=new LinkedList<>();

        private Consumer defaultConsumer;

        private void addStreamer(Selector s) {
            selectors.add(s);
        }

        private  void setOtherwise(Consumer x) {
            defaultConsumer=x;
        }

        public Configurator policy(NavigatorPolicy policy) {
            this.policy=policy;
            return this;
        }

        public void visit() {
            InternalURITreeStreamer irs=new InternalURITreeStreamer(root, (AbstractNavigatorPolicy) policy);

           irs.stream().forEach(o -> {

               Consumer s= findConsumer(o);
                s.accept(o);
           });

        }

        private Consumer findConsumer(Object c) {
            for(Selector selector:selectors) {
               Class target=selector.matcher;
               if (target!=null) {
                   if(target.isInstance(c)) {
                       return selector.consumer;
                   }
               }

            }
            return defaultConsumer;
        }

    }





    public static final class InternalURITreeStreamer extends URITreeStreamer {

        private InternalURITreeStreamer(URI froot, AbstractNavigatorPolicy policy) {
            super(froot,policy);

        }
    }

}
