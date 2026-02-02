package dev.gruff.hardstop.cachegenie;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;

public class CacheGenie {

    private URI remoteRepo;
    private File cachegenie;
    private File repoRoot;
    private File cacheWork;
    private File m2;

    private CacheGenie() {

    }

    private CacheGenie(File cache, URI repo) {
        remoteRepo =repo;
        m2=cache;
        update();

    }

    private void update() {
        repoRoot=new File(m2,"repository");
        cachegenie =new File(m2,"cachegenie");
        cachegenie.mkdirs();
        cacheWork=new File(cachegenie,"work");
        cacheWork.mkdirs();
    }

    public static CacheGenie build() throws URISyntaxException {
        CacheGenie cg=new CacheGenie();
        cg.remoteRepo =new URI("https://repo1.maven.org/maven2/");

        cg.m2=new File(System.getProperty("user.home"),".m2");
        cg.update();


        return cg;
    }

    public static File defaultM2() {
        return new  File(System.getProperty("user.home"),".m2");

    }

    public static URI defaultRemoteRepo() {
        try {
            return new URI("https://repo1.maven.org/maven2/");
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static CacheGenie build(File cache, URI repo) {
        return new CacheGenie(cache,repo);

    }

    public URI base() {
        return remoteRepo;
    }

    public File work() {
        return cacheWork;
    }
    public File repoRoot() {
        return repoRoot;
    }
    public File cacheGenieRoot() {
        return cachegenie;
    }

    /* Returns a list of all the available versions for the given
    gid/aid
     */
    public void versions(String gid, String aid) {
    }
}
