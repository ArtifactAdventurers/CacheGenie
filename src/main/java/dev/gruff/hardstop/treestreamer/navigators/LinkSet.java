package dev.gruff.hardstop.treestreamer.navigators;

import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class LinkSet<P,T> {

   private final Set<Link<P,T>> data= new TreeSet<>(new Comparator<Link<P,T>>() {
        @Override
        public int compare(Link<P,T> o1, Link<P,T> o2) {
            if(o1!=null && o2!=null) {
                return o1.compareTo(o2);
            }
            if(o1==null) return 1;
            return -1;
        }
    });

    public LinkSet() {

    }
    public LinkSet(LinkSet<P,T> kids) {

    if(kids!=null)  data.addAll(kids.data);
    }

    public LinkSet(Set<Link<P,T>> refs) {
        if(refs!=null) data.addAll(refs);
    }


    public Stream<Link<P,T>> stream() {
        return data.stream();
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }


    public void addLink(Link<P,T> l) {
        if(l!=null &&  l.path()!=null ) data.add(l);
    }

    public LinkSet<P,T> select(Predicate<Link<P,T>> p) {
        LinkSet<P,T> ls=new LinkSet<>();
        if(p==null) return ls;
        for(Link<P,T> l:data) {
            if(l!=null && p.test(l)) ls.addLink(l);
        }
        return ls;
    }

    public int size() {
        return data.size();
    }

    public void addAll(LinkSet<P,T> mls) {
        if(mls!=null) {
            data.addAll(mls.data);
        }
    }
}
