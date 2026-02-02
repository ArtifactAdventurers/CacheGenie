package dev.gruff.hardstop.cachegenie;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class MetaVersionSet {

    public Stream<MavenMetaData.Version> stream() {

        return sequenced.stream().map(e -> { return e.version;});

    }

    private static class Entry {
        Instant date;
        MavenMetaData.Version version;
        public String toString() {
            return version.value()+"-("+date+") ";
        }
    }

    private Set<MavenMetaData.Version> versions=new HashSet<>();
    private Map<Instant,List<MavenMetaData.Version>> byDate=new TreeMap<>();
    private Map<String, MavenMetaData.Version> byNames=new TreeMap<>();
    private List<Entry> sequenced;

    public MetaVersionSet(Collection<MavenMetaData.Version> data) {
        if(data==null || data.isEmpty()) return; // nothing to do
        data.forEach( v -> {
            if(v!=null) {
                versions.add(v);
                byNames.put(v.value(),v);
                Instant i=v.updated;
                List<MavenMetaData.Version> group=byDate.get(i);
                if(group==null) {
                    group=new LinkedList<>();
                    byDate.put(i,group);
                }
                group.add(v);
            }
        });
        byDateSeq(); // build the seq.
    }

    public List<MavenMetaData.Version> byDate() {
        List<MavenMetaData.Version> r=new LinkedList<>();
        for(Instant i:byDate.keySet()) {
            r.addAll(byDate.get(i));
        }
        return r;
    }

    public boolean hasVersion(String vr) {
        return byNames.containsKey(vr);
    }

    private void byDateSeq() {
       sequenced=new LinkedList<>();
       byDate.keySet().forEach(i -> {
           List<MavenMetaData.Version> vers=byDate.get(i);
           vers.forEach(mv -> {
               Entry e=new Entry();
               e.date=i;
               e.version=mv;
               sequenced.add(e);
           });
       });
    }

    public String toString() {

        return sequenced.stream()
                .map(Entry::toString)
                .collect(Collectors.joining(", "));

    }
    public MavenMetaData.Version version(String s) {
        return byNames.get(s);
    }

    public MavenMetaData.Version previous(String value) {
        final MavenMetaData.Version v=byNames.get(value);
        if(v==null) return null;

        Entry last = null;
        for(Entry e:sequenced) {
            if(e.version==v) {
                if(last ==null) return null;
                return last.version;
            } else {
                last =e;
            }
        }

        return null;
    }
}
