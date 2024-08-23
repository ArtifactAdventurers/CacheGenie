package dev.gruff.hardstop.treestreamer.navigators;

import dev.gruff.hardstop.treestreamer.LinkParser;
import dev.gruff.hardstop.treestreamer.URIHelper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class HTMLRefNavigator implements LinkParser<URI,Object> {




    @Override
    public LinkSet<URI,Object> parse(Link<URI,Object> uri, InputStream in) {

        Document doc= null;
        try {
            doc = Jsoup.parse(in,"UTF8",uri.path().toASCIIString());
        } catch (IOException e) {

            return new LinkSet<>();

        }
        Set<Link<URI,Object>> links= doc.select("a[href]")
                .stream()
                 .map(l -> {return toLink(uri,l);})
                .dropWhile(Objects::isNull)
                .collect(Collectors.toSet());

        return new LinkSet<>(links);

    }

    private  Link<URI,Object> toLink(Link<URI,Object> base,Element a) {

        if(a==null) return null;

        String title=a.attr("href");
        if(title==null) return null;
        title=title.trim();
        if(title.equals("")) return null;
        if(title.equals("../")) return null;
        if(title.equals("..")) return null;

        URI lURI = URIHelper.subDirURI(base.path(), title);
        if (lURI != null) return new MyLink(lURI);
        return null;


    }

    public static class MyLink implements Link<URI,Object> {

        private URI path;
        public MyLink(URI lURI) {
            this.path=lURI;
        }

        @Override
        public URI path() {
            return path;
        }

        @Override
        public Object data() {
            return null;
        }

        @Override
        public int compareTo(Link<URI, Object> o) {
            return 0;
        }
    }
}
