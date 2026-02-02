package dev.gruff.hardstop.cachegenie.actions.index;

import dev.gruff.hardstop.cachegenie.MavenMetaData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class IndexStatus {

    public static final String PROPERTIES = ".properties";

    private Map<String, GroupEntry> stats=new HashMap<>();
    private IndexStatus() {}

    public static IndexStatus load(File root) throws IOException {
        IndexStatus is=new IndexStatus();
        if(root==null || !root.exists()) throw new RuntimeException("root does not exist");
        if(!root.isDirectory()) throw new RuntimeException("root is not a directory");

        Files.list(root.toPath())
                .map(f -> f.toFile())
                .filter(f -> f.isFile() && f.exists() && f.getName().endsWith(PROPERTIES))
                .forEach(f -> is.add(f) );

        return is;
    }

    private void add(File f) {
        String name=f.getName();
        name=name.substring(0,name.length()-PROPERTIES.length());
        String[] bits=name.split(":");
        if(bits==null || bits.length!=2) return;
        GroupEntry s=new GroupEntry();
        s.name=name;
        s.meta= MavenMetaData.load(f);
        stats.put(name,s);
        System.out.println("add "+s.meta.toString());

    }


    private static class GroupEntry {

        public String name;
        public MavenMetaData meta;
    }
}
