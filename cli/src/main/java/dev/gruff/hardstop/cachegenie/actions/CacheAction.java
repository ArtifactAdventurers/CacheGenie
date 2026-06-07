package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.entities.POM;
import dev.gruff.hardstop.cachegenie.utils.Progress;
import dev.gruff.hardstop.resolver.Resolver;
import dev.gruff.hardstop.treestreamer.streamers.FileSystemTreeStreamer;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public class CacheAction {
    private static final Logger log = LoggerFactory.getLogger(CacheAction.class);
    private final CacheGenie cg;
    public CacheAction(CacheGenie cg) {
        this.cg=cg;
    }

    public  void cache(String d) {
        Resolver mc=Resolver.Builder(cg).build();
        cache(d,mc);
    }
     public void cache(List<String> args) {

         Resolver mc=Resolver.Builder(cg).build();
         Progress progress = Progress.start("Hydrate cache", 1, 0L);
        for (String d : args) {
            log.info("resolve {}", d);
            cache(d, mc);
            progress.tick(d);
        }
        progress.done();
    }

    private  void cache(String d, Resolver mc) {
        try {
            List<DependencyNode> x= mc.resolve(d);

            /*
            for(DependencyNode dn:x) {
                Set<String> visited=new HashSet<>();
                printKids(visited,0,dn);

            }*/

        } catch (DependencyResolutionException e) {
            log.error("Failed to resolve {}: {}", d, e.getMessage(), e);
        } catch(IllegalStateException jle) {
            log.error("Failed to resolve {}: {}", d, jle.getMessage(), jle);
        }
    }


    private  void printKids(Set<String> visited,int depth, DependencyNode dn) {

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append(" ");
        }

        String artifactId=dn.getArtifact().getArtifactId()+"/"+dn.getArtifact().toString();
        if(visited.isEmpty()) {
            System.out.println("= "+artifactId);
        } else {
            if(!visited.contains(artifactId)) {
                System.out.println(sb + " + " + artifactId);
            } else {
                System.out.println(sb + " ! " + artifactId);
            }
        }
        if(!visited.contains(artifactId)) {
            visited.add(artifactId);
            for(DependencyNode k: dn.getChildren()) {
                printKids(visited,depth+1,k);
            }
        }

    }

    public Stream<POM> stream() {

       return FileSystemTreeStreamer.builder(cg.repoRoot())
               .suppressDirectories(true)
               .build()
               .stream().filter(this::isValid)
                        .map(this::toPOM)
               .filter(Objects::nonNull);


    }

    private boolean isValid(Object O) {
        if(O==null) return false;
        if(O instanceof File f) {
           return  f.exists() && f.isFile() && f.getName().toLowerCase().endsWith(".pom");
        }
        return false;
    }

    private POM toPOM(File f) {
        if(f==null) return null;

        return POM.create(f);
        

    }

}
