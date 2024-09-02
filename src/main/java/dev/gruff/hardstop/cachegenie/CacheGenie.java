package dev.gruff.hardstop.cachegenie;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public class CacheGenie {

    private URI base;
    private File cachegenie;
    private File repoRoot;

    public static CacheGenie build() throws URISyntaxException {
        CacheGenie cg=new CacheGenie();
        cg.base=new URI("https://repo1.maven.org/maven2/");

        File m2=new File(System.getProperty("user.home"),".m2");
        cg.repoRoot=new File(m2,"repository");
        cg.cachegenie =new File(m2,"cachegenie");
        cg.cachegenie.mkdirs();

        return cg;
    }

    public URI base() {
        return base;
    }


    public File repoRoot() {
        return repoRoot;
    }
    public File cacheGenieRoot() {
        return cachegenie;
    }
}
