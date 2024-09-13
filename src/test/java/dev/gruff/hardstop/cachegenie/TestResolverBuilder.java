package dev.gruff.hardstop.cachegenie;

import dev.gruff.hardstop.cachegenie.entities.ArtifactRef;
import dev.gruff.hardstop.resolver.Resolver;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.junit.Test;

import java.net.URISyntaxException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TestResolverBuilder {

    @Test
    public void test1() throws URISyntaxException {

        CacheGenie cg=CacheGenie.build();

        ArtifactRef ar=Resolver.Builder(cg)
                .localOnly()
                .build()
                .resolveArtifact("mrj:MRJToolkitStubs:1.0");

        assertNull(ar);

    }

    @Test
    public void test2() throws URISyntaxException, DependencyResolutionException {

        CacheGenie cg=CacheGenie.build();

        ArtifactRef ar=Resolver.Builder(cg)
                .localOnly()
                .build()
                .resolveArtifact("rubygems:jar-dependencies:0.3.2");

    }

    @Test
    public void test3() throws URISyntaxException, DependencyResolutionException {

        CacheGenie cg=CacheGenie.build();

        ArtifactRef ar=Resolver.Builder(cg)
                .build()
                .resolveArtifact("org.jenkins-ci:constant-pool-scanner:1.2");

        System.out.println(ar);
    }
}
