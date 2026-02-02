package dev.gruff.hardstop.treestreamer.navigators;

import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class LinkSetImpl implements LinkSet{

   private final Set<Link> data= new TreeSet<>(new Comparator<Link>() {
        @Override
        public int compare(Link o1, Link o2) {
            if(o1!=null && o2!=null) {
                return o1.path().compareTo(o2.path());
            }
            if(o1==null) return 1;
            return -1;
        }
    });

    public LinkSetImpl() {

    }
    public LinkSetImpl(LinkSetImpl kids) {

    if(kids!=null)  data.addAll(kids.data);
    }

    public LinkSetImpl(Set<Link> refs) {
        if(refs!=null) data.addAll(refs);
    }


    public Stream<Link> stream() {
        return data.stream();
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }


    public void addLink(Link l) {
        if(l!=null &&  l.path()!=null ) data.add(l);
    }

    public LinkSetImpl select(Predicate<Link> p) {
        LinkSetImpl ls=new LinkSetImpl();
        if(p==null) return ls;
        for(Link l:data) {
            if(l!=null && p.test(l)) ls.addLink(l);
        }
        return ls;
    }

    public int size() {
        return data.size();
    }

    public void addAll(LinkSetImpl mls) {
        if(mls!=null) {
            data.addAll(mls.data);
        }
    }

    public Link first() {
        return data.stream().findFirst().orElse(null);
    }
}
