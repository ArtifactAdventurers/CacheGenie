package dev.gruff.hardstop.cachegenie.actions.index;

import dev.gruff.hardstop.cachegenie.CacheGenie;

import javax.xml.parsers.ParserConfigurationException;
import java.net.URISyntaxException;
import java.util.List;

public class IndexAction {
   private CacheGenie cg;
    public IndexAction(CacheGenie cg) {
        this.cg=cg;
    }


   public void index(List<String> args) throws ParserConfigurationException, URISyntaxException {
        IndexBuilder ib=new IndexBuilder();
        ib.index(args);
    }

}
