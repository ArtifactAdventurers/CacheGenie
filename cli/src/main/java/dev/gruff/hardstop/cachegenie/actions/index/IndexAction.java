package dev.gruff.hardstop.cachegenie.actions.index;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.MavenMetaData;
import dev.gruff.hardstop.cachegenie.MetaVersionSet;

import javax.xml.parsers.ParserConfigurationException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public class IndexAction {
   private CacheGenie cg;
    public IndexAction(CacheGenie cg) {
        this.cg=cg;
    }


   public void index(List<String> args) throws ParserConfigurationException, URISyntaxException {
        index(args, IndexBuilder.DEFAULT_RATE_PER_MINUTE, IndexBuilder.DEFAULT_THREADS);
    }

   public void index(List<String> args, int ratePerMinute) throws ParserConfigurationException, URISyntaxException {
        index(args, ratePerMinute, IndexBuilder.DEFAULT_THREADS);
    }

   public void index(List<String> args, int ratePerMinute, int threads) throws ParserConfigurationException, URISyntaxException {
        index(args, ratePerMinute, threads, IndexBuilder.DEFAULT_MAX_AGE);
    }

   public void index(List<String> args, int ratePerMinute, int threads, Duration maxAge) throws ParserConfigurationException, URISyntaxException {
        IndexBuilder ib=new IndexBuilder(cg, ratePerMinute, threads, maxAge);
        ib.index(args);
    }

    public MetaVersionSet versions(String gid, String aid) {
        // do we have the meta locally/
        IndexBuilder ib=new IndexBuilder(cg);
        MavenMetaData m=ib.meta(gid,aid);
        if(m==null) return new MetaVersionSet(Set.of());
        return m.versions();


    }

}
