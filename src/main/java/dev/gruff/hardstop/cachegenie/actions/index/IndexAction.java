package dev.gruff.hardstop.cachegenie.actions.index;

import dev.gruff.hardstop.cachegenie.CacheGenie;
import dev.gruff.hardstop.cachegenie.Meta;
import dev.gruff.hardstop.cachegenie.MetaVersionSet;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class IndexAction {
   private CacheGenie cg;
    public IndexAction(CacheGenie cg) {
        this.cg=cg;
    }


   public void index(List<String> args) throws ParserConfigurationException, URISyntaxException {
        IndexBuilder ib=new IndexBuilder(cg);
        ib.index(args);
    }

    public MetaVersionSet versions(String gid, String aid) {
        // do we have the meta locally/
        IndexBuilder ib=new IndexBuilder(cg);
        Meta m=ib.meta(gid,aid);
        if(m==null) return new MetaVersionSet(Set.of());
        return m.versions();


    }

}
