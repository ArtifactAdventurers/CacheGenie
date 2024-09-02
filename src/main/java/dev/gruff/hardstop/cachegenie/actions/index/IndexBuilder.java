package dev.gruff.hardstop.cachegenie.actions.index;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.Meta;
import dev.gruff.hardstop.cachegenie.MetaBuilder;
import dev.gruff.hardstop.treestreamer.ContentType;
import dev.gruff.hardstop.treestreamer.URIHelper;
import dev.gruff.hardstop.treestreamer.navigators.*;
import dev.gruff.hardstop.treestreamer.streamers.URITreeSteamVisitorBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;

import static dev.gruff.hardstop.treestreamer.navigators.NavigatorPolicyBuilder.builder;

public class IndexBuilder {

  CacheGenie genie;
   final     NavigatorPolicy policy;
   final MetaBuilder mb;
    public IndexBuilder() throws  URISyntaxException {
        genie=CacheGenie.build();

        mb=MetaBuilder.newInstance();

     policy= builder()

                .rateLimit(100, Duration.ofMinutes(1))  // play nice
                .defaultReader(new HTMLRefNavigator()) // turn html docs into linksets

                .onMatch(ContentType.HTML)// when HTML
                  .and(this::checkPath)
                  .useReader(new MavenStyleHTMLRefNavigator(mb)) // parse with maven aware parser

              .build();


    }

    private boolean checkPath(Link l) {
       // System.out.println(l);
       // System.out.println(l.path());
       // System.out.println(l.path().toASCIIString());
       // System.out.println(l.path().toASCIIString().endsWith("/"));
        return l.path().toASCIIString().endsWith("/");// and it loks like a directory
    }


    private boolean noSuffix(Link u) {
        return !u.path().getPath().contains(".");
    }

    public  void index(List<String> args) {
        // traverse website

        if(args.isEmpty()) {
            args.add("");
        }

        if(args.get(0).equalsIgnoreCase("?")) {

            try {
                randomWalk();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            for (String arg : args) {
                arg = arg.trim();
                index(arg);
            }
        }

    }

    private void randomWalk() throws IOException {

        // randomise...
        File list=new File(genie.work(),"index_build");
        List<String> lines;
        if(list.exists()) {
             lines=Files.readAllLines(list.toPath());
        }
        else {
            lines=new LinkedList<>();
        }
        Set<String> toDo=new HashSet<>();
        toDo.addAll(lines);

        // todo shows what's leftto do.
        if(toDo.isEmpty()) {


            NavigatorPolicy shortPolicy = builder()
                    .rateLimit(100, Duration.ofMinutes(1))  // play nice
                    .defaultReader(new HTMLRefNavigator())
                    .maxDepth(1)
                    .build();

            URITreeSteamVisitorBuilder.newInstance(genie.base())
                    .policy(shortPolicy)
                    .select()
                    .on(Link.class)
                    .consume(l -> {
                        String f = URIHelper.file(l.path());
                       toDo.add(f);
                    })
                    .visit();

        }

        System.out.println("=== IB TODO "+toDo.size());
       Iterator<String> i=toDo.iterator();
        while(i.hasNext()) {
            String key=i.next();
            System.out.println("=== IB ==== > "+key+" ("+toDo.size());
            index(key);
            i.remove();
            writeToDo(list,toDo);

        }

    }

    private void writeToDo(File list, Set<String> toDo)  {

        try(FileWriter fw=new FileWriter(list)) {
            PrintWriter pw = new PrintWriter(fw);
            for (String s : toDo) {
                pw.println(s);
                pw.flush();
            }
        } catch(IOException ioe) {
            ioe.printStackTrace();
        }

    }

    private void index(String arg) {

        if(arg.startsWith("/")) arg=arg.substring(1);
        String url="repo1.maven.org/maven2/"+arg;
        url=url.replace("//","/");
        URI root= URI.create("https://"+url);

        URITreeSteamVisitorBuilder.newInstance(root)
                .policy(policy) // use maven repo search policy
                .select()
                    .on(Meta.class)// for fond meta files
                        .consume(m -> handleMeta(m))
                    .on(LinkSetImpl.class) // for found html links - print the paths
                        .consume(s -> s.stream().forEach(k -> System.out.println("ls:"+k.path())))
                .on(Link.class)
                .consume(l -> System.out.println("file: "+l.path().toASCIIString())) // print it
                    .otherwise()// for everything else
                        .consume(o -> {System.out.println("other:"+o);})
                .visit();

    }

    private void handleMeta(Meta m) {

            if(m.uri==null) {
                System.out.println("meta has no uri");
                return;
            }
           // System.out.println("meta "+m.uri);
           File local=toLocal(m.uri);
            if(local.exists()) {
                System.out.print(m.gid.charAt(0));
            } else {
                local.getParentFile().mkdirs();
                try {
                    m.save(local);
                } catch (IOException e) {
                   System.out.println(e);
                }
            }
    }

    private File toLocal(URI u) {
        URI rel= genie.base().relativize(u);
        String relPath=rel.getPath();
        LinkedList<String> l=new LinkedList<>();
        l.addAll(List.of(relPath.split("/")));
        l.removeLast(); // drop name
        String aid=l.removeLast();
        String gid=String.join(".",l);

        String cacheFile=gid+":"+aid+".properties";
        File c=new File(genie.cacheGenieRoot(),cacheFile);
        return c;
    }





}

