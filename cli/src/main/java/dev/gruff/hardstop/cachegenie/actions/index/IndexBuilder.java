package dev.gruff.hardstop.cachegenie.actions.index;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import dev.gruff.hardstop.cachegenie.MavenMetaDataFactory;
import dev.gruff.hardstop.cachegenie.graph.MetaRepository;
import dev.gruff.hardstop.cachegenie.utils.Progress;
import dev.gruff.hardstop.treestreamer.ContentType;
import dev.gruff.hardstop.treestreamer.URIHelper;
import dev.gruff.hardstop.treestreamer.navigators.*;
import dev.gruff.hardstop.treestreamer.streamers.URITreeSteamVisitorBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;

import static dev.gruff.hardstop.treestreamer.navigators.NavigatorPolicyBuilder.builder;

public class IndexBuilder {
    private static final Logger log = LoggerFactory.getLogger(IndexBuilder.class);

    CacheGenie genie;
    final NavigatorPolicy policy;
    final MavenMetaDataFactory mb;
    final MetaRepository metaRepo;
    private final Progress progress = Progress.start("Index", 50, 2000L);

    public IndexBuilder(CacheGenie cg) {
        genie = cg;
        mb = MavenMetaDataFactory.newInstance();
        metaRepo = new MetaRepository(cg.cacheGenieRoot());

        policy = builder()

                .rateLimit(100, Duration.ofMinutes(1))  // play nice
                .defaultReader(new HTMLRefNavigator()) // turn html docs into linksets

                .onMatch(ContentType.HTML)// when HTML
                .and(IndexBuilder::checkPath)
                .useReader(new MavenStyleHTMLRefNavigator(mb)) // parse with maven-aware parser

                .build();


    }

    private static boolean checkPath(Link l) {

        return l.path().toASCIIString().endsWith("/");// and it looks like a directory
    }


    private boolean noSuffix(Link u) {
        return !u.path().getPath().contains(".");
    }

    /**
     * List entries of full or partial GAVs
     *
     * so   XXX   XXX:XXX  XXX:XXX:XXX
     *
     * @param args
     */
    public void index(List<String> args) {
        // traverse website

        if (args.isEmpty()) {
            args.add("");
        }

        if (args.get(0).equalsIgnoreCase("?")) {

            try {
                randomWalk();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            for (String arg : args) {
                arg = arg.trim();
                arg=arg.replace(":","/");
                arg=arg.replace(".","/");
                index(arg);
            }
        }

        progress.done();
    }

    private void randomWalk() throws IOException {

        // randomise...
        File list = new File(genie.work(), "index_build");
        List<String> lines;
        if (list.exists()) {
            lines = Files.readAllLines(list.toPath());
        } else {
            lines = new LinkedList<>();
        }
        Set<String> toDo = new HashSet<>();
        toDo.addAll(lines);

        // todo shows what's leftto do.
        if (toDo.isEmpty()) {


            NavigatorPolicy shortPolicy = builder()
                    .rateLimit(100, Duration.ofMinutes(1))  // play nice
                    .defaultReader(new HTMLRefNavigator())
                    .maxDepth(2)
                    .build();

            URITreeSteamVisitorBuilder.newInstance(genie.base())
                    .policy(shortPolicy)
                    .select()
                    .on(Link.class)
                    .consume(l -> {
                        String f = URIHelper.relative(genie.base(), l.path());
                        String[] bits = f.split("/");
                        if (bits.length > 1) {
                            toDo.add(f);
                            System.out.println("group " + f);
                        }
                    })
                    .visit();

        }

        System.out.println("=== IB TODO " + toDo.size());
        Iterator<String> i = toDo.iterator();
        while (i.hasNext()) {
            String key = i.next();
            System.out.println("=== IB ==== > " + key + " (" + toDo.size());
            index(key);
            i.remove();
            writeToDo(list, toDo);

        }

    }

    private void writeToDo(File list, Set<String> toDo) {

        try (FileWriter fw = new FileWriter(list)) {
            PrintWriter pw = new PrintWriter(fw);
            for (String s : toDo) {
                pw.println(s);
                pw.flush();
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }

    }

    private void index(String arg) {

        log.info("indexing {}", arg);
        if (arg.startsWith("/")) arg = arg.substring(1);
        String url = "repo1.maven.org/maven2/" + arg + "/";
        url = url.replace("//", "/");
        URI root = URI.create("https://" + url);
        log.debug("root {}", root);

        URITreeSteamVisitorBuilder.newInstance(root)
                .policy(policy)
                .select()
                .on(MavenMetaData.class)
                .consume(m -> handleMeta(m))
                .on(LinkSetImpl.class)
                .consume(s -> {
                    // if (log.isTraceEnabled()) s.stream().forEach(k -> log.trace("ls: {}", k.path()));
                })
                .on(Link.class)
                .consume(l -> log.debug("file link: {}", l.path().toASCIIString()))
                .otherwise()
                .consume(o -> {
                    // log.trace("other: {}", o);
                })
                .visit();

    }

    private void handleMeta(MavenMetaData m) {

        if (m.uri == null) {
            log.warn("meta has no uri");
            return;
        }
        progress.tick(m.gid + ":" + m.aid);
        log.debug("meta link: {}", m.uri);
        metaRepo.save(m);
    }

    public MavenMetaData meta(String gid, String aid) {

        if (metaRepo.exists(gid, aid)) {
            MavenMetaData cached = metaRepo.load(gid, aid);
            if (cached != null) return cached;
        }

        URI file = URIHelper.subDirURI(genie.base(), gid.replace(".", "/") + "/" + aid);

        NavigatorPolicy shortPolicy = builder()
                .rateLimit(100, Duration.ofMinutes(1))  // play nice
                .defaultReader(new MavenStyleHTMLRefNavigator(mb))
                .maxDepth(1)
                .build();

        final List<MavenMetaData> metas = new LinkedList<>();
        URITreeSteamVisitorBuilder.newInstance(file)
                .policy(shortPolicy)
                .select()
                .on(MavenMetaData.class)
                .consume(m -> {
                    log.debug("read {}", m);
                    handleMeta(m); // save it
                    metas.add(m);
                })
                .otherwise()
                  .consume(o -> {
                      // ignore other things like links found during meta discovery
                  })
                .visit();

        if (metas.isEmpty()) return null;
        return metas.get(0);
    }
}

