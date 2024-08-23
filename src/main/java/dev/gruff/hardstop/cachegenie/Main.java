package dev.gruff.hardstop.cachegenie;


import dev.gruff.hardstop.treestreamer.streamers.FileSystemTreeStreamer;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyResolutionException;


import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.List;

public class Main {


    public static void main(String[] args) throws ParserConfigurationException, URISyntaxException {


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
    private static void update(List<String> params) {

    }

    private static void list(List<String> params) {
        File m2=new File(System.getProperty("user.home"),".m2");
        File repo=new File(m2,"repository");
        File cacheGenie=new File(m2,"cachegenie");
        IndexStats is=new IndexStats();

        FileSystemTreeStreamer.builder(cacheGenie)
                .suppressDirectories(true)
                .build()
                .stream()
                .filter(f -> { return f.getName().endsWith(".properties");})
                .map(Meta::load)
                .forEach(f -> { anzFile(repo,f,is);});
    }

    private static void anzFile(File repo, Meta m, IndexStats is) {

        String gpath=m.gid.replace(".","/");
        File gN=new File(repo,gpath);
        File aF=new File(gN,m.aid);
        int c=0;
        int vCount=m.versions.size();
        is.versions+=vCount;
        is.indexFiles++;
        if(m.updated !=null) {

        }

        for(String v:m.versions) {
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
        System.out.println("params");
        System.out.println("cache <groupid>:<artifactid>[:<version] ...");
        System.out.println("cache <groupid>:<artifactid> ... ");
        System.out.println("index [slash-domain] ...");


    }
}
