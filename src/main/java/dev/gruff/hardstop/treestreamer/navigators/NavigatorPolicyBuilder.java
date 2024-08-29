package dev.gruff.hardstop.treestreamer.navigators;


import dev.gruff.hardstop.treestreamer.ContentType;
import dev.gruff.hardstop.treestreamer.LinkReader;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Predicate;

public class NavigatorPolicyBuilder {


    public static final class MyUriPolicy extends AbstractNavigatorPolicy {
    }

    AbstractNavigatorPolicy policy;

    public static NavigatorPolicyBuilder builder() {

        return new NavigatorPolicyBuilder();
    }

    private NavigatorPolicyBuilder() {
        policy= new MyUriPolicy();
    }


    public NavigatorPolicy build() {
        NavigatorPolicy r=policy;
        policy= new MyUriPolicy();
        return r;
    }
    public NavigatorPolicyBuilder defaultReader(LinkReader parser) {
        policy.defaultHandler(parser);
        return this;
    }

    public NavigatorPolicyBuilder maxDepth(int i) {
        policy.maxDepth=i;
        return this;
    }

    public NavigatorPolicyBuilder rateLimit(int count, Duration d) {
        policy.rateLimit(count,d);
        return this;
    }

    public MatchConfig<LinkSetImpl> onMatch(Predicate<Link> l) {
        return new MatchConfig<LinkSetImpl>(l);
    }

    public MatchConfig<LinkSetImpl> onMatch(ContentType t) {
        return new MatchConfig<LinkSetImpl>(u -> u.isType(t));
    }


  public class MatchConfig<M> {


      private Predicate<Link> predicate=null;

     private MatchConfig(Predicate<Link> l) {

         this.predicate=l;
     }

      public class ParserConfig<R> {

         private AbstractNavigatorPolicy.Selector s;
          public ParserConfig(AbstractNavigatorPolicy.Selector selector) {
              s=selector;
          }


          public NavigatorPolicy build() {
              return NavigatorPolicyBuilder.this.build();
          }


      }


      public MatchConfig<M> and(Predicate<Link> l) {
         predicate=predicate.and(l);
         return this;
      }

      public MatchConfig<M> or(Predicate<Link> l) {
          predicate=predicate.or(l);
          return this;
      }

      public <T> ParserConfig<T> useReader(LinkReader linkParser) {
        AbstractNavigatorPolicy.Selector selector= NavigatorPolicyBuilder.this.policy.addSelector(predicate,linkParser);
         return new ParserConfig<>(selector);
      }



    }
}
