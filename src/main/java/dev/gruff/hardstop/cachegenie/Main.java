package dev.gruff.hardstop.cachegenie;


import dev.gruff.hardstop.treestreamer.streamers.FileSystemTreeStreamer;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyResolutionException;


import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class Main {


    public static void main(String[] args) throws Exception {


        if (args == null || args.length == 0) {
            usage();
            return;
        }

        List<String> params=new LinkedList<>();
        params.addAll(List.of(args));

        String cmd = params.remove(0).toLowerCase();
        switch (cmd) {
            case "cache":
                cache(params);
                break;
            case "index":
                index(params);
                break;
            case "list":
                list(params);
                break;
            case "update":
                update(params);
                break;
            default:
                usage();
                break;
        }

    }

    // updates items listeed in index.
    private static void update(List<String> params) throws IOException {
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
        File f=cacheGenieDir().cacheGenie;
        Files.list(f.toPath()).filter(p -> { return p.toFile().isFile() && p.toFile().getName().endsWith(".properties");})
                .map(Main::toMeta)
                .forEach(m -> {
                    Instant updated=m.m.updated();
                    Instant generated=m.m.generated();
                    boolean inLocalScope=generated.isBefore(localInstantBoundary);
                    boolean inGlobalScope=updated.isAfter(globalInstantBoundary);

                    System.out.println(m.f.getAbsolutePath()+" updated "+updated+" gen "+generated+" ils="+inLocalScope+" igs="+inGlobalScope);
                });
    }

    private record Entry(File f,Meta m){}

    private static Entry toMeta(Path f) {
        File file=f.toFile();
        return new Entry(file,Meta.load(file));
    }

    private static Result cacheGenieDir() {
        File m2=new File(System.getProperty("user.home"),".m2");
        File repo=new File(m2,"repository");
        File cacheGenie=new File(m2,"cachegenie");
        return    new Result(repo, cacheGenie);
    }

    private static void list(List<String> params) {

        Result result =cacheGenieDir();
        IndexStats is=new IndexStats();

        FileSystemTreeStreamer.builder(result.cacheGenie())
                .suppressDirectories(true)
                .build()
                .stream()
                .map(f -> toFile(f))
                .dropWhile(Objects::isNull)
                .filter(f -> { return f.getName().endsWith(".properties");})
                .map(Meta::load)
                .forEach(f -> { anzFile(result.repo(),f,is);});
    }

    private record Result(File repo, File cacheGenie) {
    }

    private static File toFile(Object f) {

        if(f instanceof File fs) return fs;
        return  null;
    }

    private static void anzFile(File repo, Meta m, IndexStats is) {

        String gpath=m.gid.replace(".","/");
        File gN=new File(repo,gpath);
        File aF=new File(gN,m.aid);
        int c=0;
        int vCount=m.versions.size();
        is.versions+=vCount;
        is.indexFiles++;
        if(m.updated() !=null) {

        }

        for(String v:m.versions.keySet()) {
            File vF=new File(aF,v);
            if(vF.exists()) {
                c++;
            }
        }

        System.out.println(m.gid+" "+m.aid+" = "+c+"/"+vCount);
    }


    private static void index(List<String> args) throws ParserConfigurationException, URISyntaxException {
        IndexBuilder ib=new IndexBuilder();
        ib.index(args);
    }

    private static void cache(List<String> args) {

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


    private static void usage() {
        System.out.println("cachegenie");
        System.out.println("cmd\n");
        System.out.println("cache: install artifacts and dependencies in local maven cache ");
        System.out.println("cache <groupid>:<artifactid>[:<version] ...");
        System.out.println("cache <groupid>:<artifactid> ... ");
        System.out.println("\n");
        System.out.println("index: local maven meta info and create meta file in .m2/cachegenie directory");
        System.out.println("index [slash-domain] ...");
        System.out.println("\n");
        System.out.println("update: examine cachegenie meta information and update meta info for out of date files ");
        System.out.println("         p1 sets which meta files to update based on their local age : default 7 days ");
        System.out.println("         p2 sets which files to update based on their global age: default is less than 365 days");
        System.out.println("         default is to consider files with a local age of more than 7 days but where the global date is less than a year");
        System.out.println("update <days-since-last-updated-locally> <days-since-meta-updated-remotely");
        System.out.println("\n");

    }
}
