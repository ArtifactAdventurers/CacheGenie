package dev.gruff.hardstop.cachegenie.actions;


import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import dev.gruff.hardstop.cachegenie.entities.ArtifactRef;
import dev.gruff.hardstop.cachegenie.utils.ObjectChecks;
import dev.gruff.hardstop.resolver.Resolver;

import java.io.File;
import java.util.Set;

public class CompareAction {

    private CacheGenie cg;
    private Resolver r;
    public CompareAction(CacheGenie cg) {
        ObjectChecks.isPresent("cg",cg);
        this.cg=cg;
        r= Resolver.Builder(cg).build();
    }

    public void compareVersions(String gid, String aid, MavenMetaData.Version v1, MavenMetaData.Version v2) {
        System.out.println("Compare Versions ");

        throw new RuntimeException("not implimented");

        //ArtifactRef ar1=r.resolveArtifact(gid+":"+aid+":"+v1.value());
        //ArtifactRef ar2=r.resolveArtifact(gid+":"+aid+":"+v2.value());

        //File code1=ar1.code();
        //File code2=ar2.code();


    }
}
