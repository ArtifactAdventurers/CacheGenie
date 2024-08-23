package dev.gruff.hardstop.treestreamer.navigators;

import dev.gruff.hardstop.treestreamer.LinkParser;
import dev.gruff.hardstop.treestreamer.URIHelper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class MavenStyleHTMLRefNavigator implements LinkParser<URI, MavenStyleHTMLRefNavigator.MavenLink> {




    @Override
    public LinkSet<URI,MavenLink> parse(Link<URI,MavenLink> uri, InputStream in) {
        Document doc= null;
        try {
            doc = Jsoup.parse(in,"UTF8",uri.path().toASCIIString());
        } catch (IOException e) {
            return new LinkSet<>();

        }
        Set<Link<URI,MavenLink>> refs= doc.select("a[href]")
                .stream()
                 .map(l -> {return toLink(uri,l);})
                .dropWhile(Objects::isNull)
                .collect(Collectors.toSet());

        LinkSet<URI,MavenLink> ls=new LinkSet<>(refs);

        LinkSet<URI,MavenLink> mls=ls.select(l -> l.path() !=null && l.path().getPath().endsWith("maven-metadata.xml"));
        if(mls.size()==1){
            mls=new LinkSet<>();
            ls.addAll(mls);
        }


  return ls;

    }

    private  Link<URI,MavenLink> toLink(Link<URI,MavenLink> base,Element a) {
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
                file = false;
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
        if (lURI != null) return new LinkImpl(lURI, file, updated);
        return null;
    }

    public static class LinkImpl implements Link<URI,MavenLink> {

        private MavenLink info;
        private URI path;

       private LinkImpl(URI lURI, boolean file, Instant updated) {
           this.path=lURI;
           this.info=new MavenLink(updated);

        }

        @Override
        public URI path() {
            return path;
        }

        @Override
        public MavenLink data() {
            return info;
        }

        @Override
        public int compareTo(Link<URI, MavenLink> o) {
           if(o==null) return 1;
            return this.path.compareTo(o.path());
        }
    }

    public static class MavenLink {

        Instant updated;

        public MavenLink(Instant updated) {
            this.updated=updated;
        }
    }
}
