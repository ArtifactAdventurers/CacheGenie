package dev.gruff.hardstop.cachegenie;


import dev.gruff.hardstop.cachegenie.actions.CacheAction;
import dev.gruff.hardstop.cachegenie.actions.index.IndexAction;
import dev.gruff.hardstop.cachegenie.actions.ListAction;
import dev.gruff.hardstop.cachegenie.actions.UpdateAction;


import java.util.*;

public class Main {


    public static void main(String[] args) throws Exception {


        if (args == null || args.length == 0) {
            usage();
            return;
        }


        CacheGenie cg=new CacheGenie();
        List<String> params=new LinkedList<>();
        params.addAll(List.of(args));

        String cmd = params.remove(0).toLowerCase();
        switch (cmd) {
            case "cache":
                CacheAction ca=new CacheAction(cg);
                ca.cache(params);
                break;
            case "index":
                IndexAction ia=new IndexAction(cg);
                ia.index(params);
                break;
            case "list":
                ListAction la=new ListAction(cg);
                la.list(params);
                break;
            case "update":
                UpdateAction ua=new UpdateAction(cg);
                ua.update(params);
                break;
            default:
                usage();
                break;
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
