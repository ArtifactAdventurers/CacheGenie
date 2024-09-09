package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.Meta;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedList;
import java.util.List;

public class UpdateAction {
    private CacheGenie cg;
    public UpdateAction(CacheGenie cg) {
        this.cg=cg;
    }

    // updates items listeed in index.
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
        File f=cg.cacheGenieRoot();
        System.out.println("root "+f.toPath());
        Files.list(f.toPath()).filter(p -> { return p.toFile().isFile() && p.toFile().getName().endsWith(".properties");})
                .map(UpdateAction::toMeta)
                .forEach(m -> {
                    Instant updated=m.m.updated();
                    if(updated==null) {
                        //  System.out.println(m.f.getAbsolutePath()+"\n\n\n\n no updated");
                        return;
                    }
                    Instant generated=m.m.generated();
                    if(generated==null) {
                        //  System.out.println(m.f.getAbsolutePath()+"\n\n\n\n1 no gen");
                        return;
                    }
                    boolean inLocalScope=generated.isBefore(localInstantBoundary);
                    boolean inGlobalScope=updated.isAfter(globalInstantBoundary);
                    if(inGlobalScope ) {
                        System.out.println(m.f.getAbsolutePath() + " updated " + updated + " gen " + generated);
                        System.out.println("caching latest version");
                        String ref=m.m.gid+":"+m.m.aid+":"+m.m.latest;
                        LinkedList<String> l=new LinkedList<>();
                        l.add(ref);
                        CacheAction ca=new CacheAction(cg);
                        ca.cache(l);
                    }
                });
    }

    private record Entry(File f, Meta m){}


    static Entry toMeta(Path f) {
        File file=f.toFile();
        return new Entry(file,Meta.load(file));
    }
}
