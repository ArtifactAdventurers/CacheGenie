package dev.gruff.hardstop.cachegenie;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class VersionResolver {
    private static final Logger log = LoggerFactory.getLogger(VersionResolver.class);


    public Set<String> resolve(String group, String artifact) {

        String remote="https://repo1.maven.org/maven2/"+groupToURL(group)+"/"+artifactToURL(artifact)+"/maven-metadata.xml";
    log.info("version info from {}",remote);
        Document doc=getDoc(remote);
       return extractVersionInfo(doc);

    }


    private String artifactToURL(String artifact) {
            return artifact;
    }

    private String groupToURL(String group) {
            return group.replace(".","/");
    }


    public static Document getDoc(String docURL) {
        log.info("DocURL {}",docURL);
        try {
            Connection c = Jsoup.connect(docURL);
            c.ignoreHttpErrors(true);
            log.info("Connection {}",c);
            return c.get();
        } catch(IOException hse) {
                hse.printStackTrace();
            }


        return null;
    }


    public Set<String> extractVersionInfo(Document doc) {
        Set<String> versionIds=new HashSet<>();
        if (doc != null) {
            Elements plugs = doc.select("metadata plugins");
            if (plugs.isEmpty()) {

                String group = doc.select("metadata groupId").text();
                String artifact = doc.select("metadata artifactId").text();
                Elements versions=doc.select("metadata versions version");
                for(Element e:versions) {
                    String version=e.text();
                    versionIds.add(version);
                }

            }
        }
        return versionIds;
    }
}
