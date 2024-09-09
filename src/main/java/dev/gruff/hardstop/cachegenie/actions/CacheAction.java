package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.resolver.Resolver;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.DependencyResolutionException;

import java.util.List;
import java.util.Set;

public class CacheAction {
    private CacheGenie cg;
    public CacheAction(CacheGenie cg) {
        this.cg=cg;
    }

     public void cache(List<String> args) {

        Resolver mc = new Resolver(cg);
        for (String d : args) {
            System.out.println("resolve "+d);
            try {
                List<DependencyNode> x= mc.resolve(d);

                /*
                for(DependencyNode dn:x) {
                    Set<String> visited=new HashSet<>();
                    printKids(visited,0,dn);

                }*/

            } catch (DependencyResolutionException e) {
                e.printStackTrace();
            } catch(java.lang.IllegalStateException jle) {
                jle.printStackTrace();
            }


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
}
