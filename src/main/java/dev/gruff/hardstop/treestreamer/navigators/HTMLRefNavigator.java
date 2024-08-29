package dev.gruff.hardstop.treestreamer.navigators;

import dev.gruff.hardstop.treestreamer.ContentType;
import dev.gruff.hardstop.treestreamer.LinkReader;
import dev.gruff.hardstop.treestreamer.URIHelper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class HTMLRefNavigator implements LinkReader {




    @Override
    public LinkSetImpl parse(Link uri, InputStream in) {

        Document doc= null;
        try {
            doc = Jsoup.parse(in,"UTF8",uri.path().toASCIIString());
        } catch (IOException e) {

            return new LinkSetImpl();

        }
        Set<Link> links= doc.select("a[href]")
                .stream()
                 .map(l -> {return toLink(uri,l);})
                .dropWhile(Objects::isNull)
                .collect(Collectors.toSet());

        return new LinkSetImpl(links);

    }

    private  Link toLink(Link base,Element a) {

        if(a==null) return null;

        String title=a.attr("href");
        if(title==null) return null;
        title=title.trim();
        if(title.equals("")) return null;
        if(title.equals("../")) return null;
        if(title.equals("..")) return null;
        boolean leaf= !title.endsWith("/");

        URI lURI = URIHelper.subDirURI(base.path(), title);
        if (lURI != null) return new MyLink(lURI);
        return null;


    }

    public static class MyLink implements Link {

        private URI path;

        public MyLink(URI lURI) {

            this.path=lURI;

        }



        @Override
        public URI path() {
            return path;
        }

        @Override
        public boolean isType(ContentType contentType) {
            return false;
        }

        public String toString() {
            return "MS:"+path;
        }
    }
}
