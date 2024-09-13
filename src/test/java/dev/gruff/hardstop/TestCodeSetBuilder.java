package dev.gruff.hardstop;

import dev.gruff.hardstop.api.HSClass;
import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.CodeSet;
import dev.gruff.hardstop.cachegenie.entities.ArtifactRef;
import dev.gruff.hardstop.resolver.Resolver;
import org.junit.Test;

import java.net.URISyntaxException;
import java.util.Set;

public class TestCodeSetBuilder {

    @Test
    public void test1() throws URISyntaxException {

        CacheGenie cg=CacheGenie.build();
        Resolver r=Resolver.Builder(cg).build();
        ArtifactRef ar1=r.resolveArtifact("io.quarkus:quarkus-arc:3.9.0");
        ArtifactRef ar2=r.resolveArtifact("io.quarkus:quarkus-arc:3.9.5");

        System.out.println(ar1.code());
        System.out.println(ar2.code());

        CodeSet cs1=CodeSet.Builder().code(ar2.code()).build();
        CodeSet cs2=CodeSet.Builder().code(ar1.code()).build();

       Set<HSClass> lost= cs2.unknownClasses(cs1);
       Set<HSClass> gained= cs1.unknownClasses(cs2);

        System.out.println("cs1="+cs1.size());
        System.out.println("cs2="+cs2.size());
        System.out.println("lost="+lost.size());
        System.out.println("gained="+gained.size());


        cs1.stream().forEach(hc -> {
            HSClass cs2Clazz=cs2.clazzByName(hc.className());

            // check methods
            var m1=hc.methods();
            var m2=cs2Clazz.methods();
            m1.stream().forEach(m -> {
                if(!m2.contains(m.reference())) {
                    System.out.println(hc.className()+" lost "+m.reference());
                } else {

                }

            });

            m2.stream().forEach(m -> {
                if(!m1.contains(m.reference())) {
                    System.out.println(hc.className()+" gained "+m.reference());
                }
            });

            // check fields
            

        });
    }
}
