package dev.gruff.hardstop.resolver;


import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyResolutionException;


import java.util.List;

public class Main {


    public static void main(String[] args) {
        Resolver mc = new Resolver();

        for (String d : args) {
            System.out.println("resolve "+d);
            try {
               List<ArtifactResult> x= mc.resolve(d);
               for(ArtifactResult ar:x) {

                   System.out.println("+ "+ar.getArtifact());
               }
            } catch (DependencyResolutionException e) {
                throw new RuntimeException(e);
            }


        }
    }
}
