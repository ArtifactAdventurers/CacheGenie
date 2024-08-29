package dev.gruff.hardstop.cachegenie;

import dev.gruff.hardstop.treestreamer.ContentType;
import dev.gruff.hardstop.treestreamer.LinkReader;
import dev.gruff.hardstop.treestreamer.URIHelper;
import dev.gruff.hardstop.treestreamer.navigators.Link;
import dev.gruff.hardstop.treestreamer.navigators.LinkSetImpl;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

public final class MavenStyleHTMLRefNavigator implements LinkReader {

    private final MetaBuilder mb;

    public MavenStyleHTMLRefNavigator(MetaBuilder mb) {
        this.mb=mb;
    }

    @Override
    public Object parse(Link uri, InputStream in) {
        Document doc= null;
        try {
            doc = Jsoup.parse(in,"UTF8",uri.path().toASCIIString());
        } catch (IOException e) {
            return new LinkSetImpl();

        }

        // convert all html anchors into links
        LinkSetImpl lsi=new LinkSetImpl();
        Map<String,LinkImpl> contents=new TreeMap<>();
         doc.select("a[href]")
                .stream()
                .map(e -> toLink(uri,e))
                 .dropWhile(Objects::isNull)
                .forEach(l ->{
                    if(l!=null) {
                        lsi.addLink(l);
                        String name = l.name();
                        contents.put(name, l);
                    }
                });


        // if we encountered the metadata link then we'll
        // convet into a meta object

        Link m=contents.get("maven-metadata.xml");
        if(m!=null) {
           return toMeta(m,contents);
        } else {
            return lsi;
        }


    }


    private Meta toMeta(Link m, Map<String, LinkImpl> contents) {
       Meta meta= mb.build(m.path());
       for(String key:contents.keySet()) {
            LinkImpl li=contents.get(key);
            if(li.directory) {
                meta.setUpdated(key, contents.get(key).updated);
            }
       }
       return meta;
    }


    private  LinkImpl toLink(Link base,Element a) {
        if (a == null) return null;

        String title = null;
        Instant updated = null;

        boolean file = true;

        if (a.hasAttr("href")) {
            title = a.attr("href");
            if (title.endsWith("/")) {

                if (title.equalsIgnoreCase("../")) {
                    return null; // special case
                }
            }

            Node node = a.nextSibling();
            String text = null;
            if (node != null) {
                text = node.toString().trim();
            }

            if (text != null) {
                String[] bits = text.split(" ");
                if (bits.length > 1 && !bits[0].trim().equalsIgnoreCase("-")) {

                    // 2011-12-03T10:15:30Z
                    String date = bits[0] + "T" + bits[1] + ":00Z";
                    try {
                        updated = Instant.parse(date);
                    } catch (DateTimeParseException pe) {

                        System.out.println(text);
                    }
                }
            }
        } else {
            return null;
        }
        URI lURI = URIHelper.subDirURI(base.path(), title);
        if (lURI != null) return new LinkImpl(lURI, updated,title.endsWith("/"));
        return null;
    }

    public static class LinkImpl implements Link{

        private Instant updated;
        private URI path;
        private boolean directory;
        private final Map<String,Instant> timeStamps=new HashMap<>();


        public String toString() {
            return updated+"//"+path;
        }
       private LinkImpl(URI lURI, Instant updated, boolean directory) {
           this.path=lURI;
           this.updated=updated;
           this.directory=directory;

        }

        public Instant updated() {
           return updated;
        }
        @Override
        public URI path() {
            return path;
        }

        @Override
        public boolean isType(ContentType contentType) {
            return false;
        }


        public void addVersionInfo(LinkSetImpl ls) {

           ls.stream().filter( l -> {return !l.path().getPath().endsWith("maven-metadata.xml");
                   })
                   .map(  l -> {return (LinkImpl)l;})
                   .forEach(l -> {
                       timeStamps.put(URIHelper.file(l.path()),l.updated());
                   });
       }

        public String name() {
            String p=path.getPath();
            if(p.endsWith("/") ) {
                p = p.substring(0, p.length() - 1);
            }
                int ls=p.lastIndexOf("/");
                if(ls<0) return p;
                return p.substring(ls+1);
        }
    }


}
