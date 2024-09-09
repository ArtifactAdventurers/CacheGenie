package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.api.SemanticVersion;
import dev.gruff.hardstop.api.artifacts.HSClass;
import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.Meta;
import dev.gruff.hardstop.treestreamer.streamers.FileSystemTreeStreamer;

import java.io.File;
import java.io.IOException;
import dev.gruff.hardstop.core.builder.ClassReader;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarFile;


public class StatsAction {

    private static class Counter {
        int count=0;
    }
   private CacheGenie cg;

   private int poms=0;
   private int jars=0;
    private long clazzes=0;
   private int versions=0;
   private int metas=0;
    private Map<String,Counter> semvars=new HashMap<>();
    public StatsAction(CacheGenie cg) {
        this.cg=cg;
    }

    public void stats(List<String> params) {


        FileSystemTreeStreamer.builder(cg.repoRoot())
                .suppressDirectories(true)
                .build().stream()
                .map(o -> { return (File)o;})
                .forEach(q -> stat(q));

        System.out.println("POMS="+poms);
        System.out.println("JARS="+jars);
        System.out.println("CLAZ="+clazzes);

    FileSystemTreeStreamer.builder(cg.cacheGenieRoot())

            .suppressDirectories(true)
            .build()
            .stream()
            .dropWhile(StatsAction::isFile)
            .filter(file -> { return file.getName().endsWith(".properties"); })
                .map(Meta::load)
            .dropWhile(Objects::isNull)
            .forEach(m -> {
                metas++;
               versions+=m.versions.size();
            });

        System.out.println("META="+metas);
        System.out.println("VERS="+versions);
        System.out.println("Semver ====");
        for(String sv:semvars.keySet()) {
            System.out.println(sv+"="+semvars.get(sv).count);
        }
    }

    private static boolean isFile(Object o) {
        return o instanceof File;
    }

    private void stat(File f) {
        if(f.getName().endsWith(".pom")) {
            poms++;
        }
        if(f.getName().endsWith(".jar")) {
            jars++;
            clazzes+=anzJar(f);
        }
    }

    private long anzJar(File f) {
        final long[] cz = {0};
        try (JarFile jf=new JarFile(f)) {
           jf.stream().filter(j -> j.getName().endsWith(".class")).forEach(je -> {
               cz[0]++;
               try(InputStream is=jf.getInputStream(je)) {
                  HSClass hsc= ClassReader.readClass(is);
                  updateCompilerType(hsc.compilerVersion());
               } catch(IOException ioe ) {
                   //
               }
           });
        } catch (IOException e) {
           ;// throw new RuntimeException(e);
        }

        return cz[0];

    }

    private void updateCompilerType(SemanticVersion semanticVersion) {
        Counter c=semvars.get(semanticVersion.toString());
        if(c==null) {
            c=new Counter();
            semvars.put(semanticVersion.toString(),c);
        }
       c.count++;
    }
}
