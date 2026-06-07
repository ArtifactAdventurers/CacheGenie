package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedList;
import java.util.List;

public class UpdateAction {
    private CacheGenie cg;
    public UpdateAction(CacheGenie cg) {
        this.cg=cg;
    }

    // updates items listed in the meta tables.
    public void update(List<String> params) throws IOException {
        int localAge=7;
        int globalAge=365;

        if(!params.isEmpty()) {
            String la=params.remove(0);
            localAge=Integer.parseInt(la);
            if(!params.isEmpty()) {
                la = params.remove(0);
                globalAge = Integer.parseInt(la);
            }
        }
        if(localAge<0) localAge=0;
        if(globalAge<0) globalAge=0;

        System.out.println("update based on local age "+localAge+", global age "+globalAge);
        Instant now=Instant.now();
        Instant localInstantBoundary=now.minus(7, ChronoUnit.DAYS);
        Instant globalInstantBoundary=now.minus(globalAge, ChronoUnit.DAYS);
        System.out.println("local boundary "+localInstantBoundary+" files updated on local cache after this date are ignored");
        System.out.println("global  boundary "+globalInstantBoundary+" files last updated on repo before this date are ignored");

        MetaRepository metaRepo = new MetaRepository(cg.cacheGenieRoot());
        metaRepo.loadAll().forEach(m -> {
            Instant updated=m.updated();
            if(updated==null) return;
            Instant generated=m.generated();
            if(generated==null) return;
            boolean inLocalScope=generated.isBefore(localInstantBoundary);
            boolean inGlobalScope=updated.isAfter(globalInstantBoundary);
            if(inGlobalScope ) {
                System.out.println(m.gid+":"+m.aid + " updated " + updated + " gen " + generated);
                System.out.println("caching latest version");
                String ref=m.gid+":"+m.aid+":"+m.latest;
                LinkedList<String> l=new LinkedList<>();
                l.add(ref);
                CacheAction ca=new CacheAction(cg);
                ca.cache(l);
            }
        });
    }
}
