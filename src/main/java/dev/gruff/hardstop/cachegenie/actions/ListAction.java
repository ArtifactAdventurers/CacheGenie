package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.actions.index.IndexStats;
import dev.gruff.hardstop.cachegenie.Meta;
import dev.gruff.hardstop.treestreamer.streamers.FileSystemTreeStreamer;

import java.io.File;
import java.util.List;
import java.util.Objects;

public class ListAction {
    private CacheGenie cg;
    public ListAction(CacheGenie cg) {
        this.cg=cg;
    }


    public void list(List<String> params) {


        IndexStats is=new IndexStats();

        FileSystemTreeStreamer.builder(cg.cacheGenieRoot())
                .suppressDirectories(true)
                .build()
                .stream()
                .map(f -> toFile(f))
                .dropWhile(Objects::isNull)
                .filter(f -> { return f.getName().endsWith(".properties");})
                .map(Meta::load)
                .forEach(f -> { anzFile(cg.repoRoot(),f,is);});
    }


    private  void anzFile(File repo, Meta m, IndexStats is) {

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


    private  File toFile(Object f) {

        if(f instanceof File fs) return fs;
        return  null;
    }
}
