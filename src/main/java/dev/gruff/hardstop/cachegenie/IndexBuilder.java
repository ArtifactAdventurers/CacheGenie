package dev.gruff.hardstop.cachegenie;

import dev.gruff.hardstop.treestreamer.ContentType;
import dev.gruff.hardstop.treestreamer.navigators.Link;
import dev.gruff.hardstop.treestreamer.navigators.MavenStyleHTMLRefNavigator;
import dev.gruff.hardstop.treestreamer.navigators.NavigatorPolicy;
import dev.gruff.hardstop.treestreamer.streamers.URITreeSteamBuilder;
import dev.gruff.hardstop.treestreamer.Node;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.*;

import static dev.gruff.hardstop.treestreamer.navigators.NavigatorPolicyBuilder.builder;

public class IndexBuilder {

    final URI base;

    final DocumentBuilder docBuilder;
   final File cachegenie;
   final     NavigatorPolicy policy;

    public IndexBuilder() throws ParserConfigurationException, URISyntaxException {
         base=new URI("https://repo1.maven.org/maven2/");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        docBuilder = factory.newDocumentBuilder();
        File m2=new File(System.getProperty("user.home"),".m2");
        cachegenie =new File(m2,"cachegenie");
        cachegenie.mkdirs();

     policy= builder()
                // when its a html file and the name has no suffix
                // assume its a diretory listing
                .rateLimit(100, Duration.ofMinutes(1))
                .when(u -> u.isType(ContentType.HTML)) // when we encouinter  an html file
                .and(Node::noSuffix)// without a suffix in the path element
                .useNavigator(new MavenStyleHTMLRefNavigator()) //use the standard href extractor

                .build();


    }
    public  void index(List<String> args) {
        // traverse website

        if(args.isEmpty()) {
            args.add("");
        }

        for(String arg:args) {
            arg=arg.trim();
            index(arg);
        }


    }

    private void index(String arg) {

        if(arg.startsWith("/")) arg=arg.substring(1);
        String url="repo1.maven.org/maven2/"+arg;
        url=url.replace("//","/");
        URI root= URI.create("https://"+url);

        URITreeSteamBuilder.builder(root)
                .policy(policy)
                .build()
                .stream()
                .filter(f -> f.path().toASCIIString().endsWith("maven-metadata.xml"))
                .filter(f -> !alreadyCached(f.path()))
                .map(this::toMeta)
                .dropWhile(Objects::isNull)
                // .limit(100)
                .forEach(f -> {
                    try {
                        if(f==null) return;
                        String name=f.name()+".properties";
                        File pFile=new File(cachegenie,name);
                        f.save(pFile);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private boolean alreadyCached(URI u) {

            URI rel= base.relativize(u);
            String relPath=rel.getPath();
            LinkedList<String> l=new LinkedList<>();
            l.addAll(List.of(relPath.split("/")));
            l.removeLast(); // drop name
            String aid=l.removeLast();
            String gid=String.join(".",l);

            String cacheFile=gid+":"+aid+".properties";
            File c=new File(cachegenie,cacheFile);
            boolean r=c.exists();
            if(r) System.out.print(gid.charAt(0));
            return r;


    }


    private  Meta toMeta(Link<URI, Object> l) {
        URI u=l.path();
        MavenStyleHTMLRefNavigator.MavenLink m= (MavenStyleHTMLRefNavigator.MavenLink) l.data();

        return toDepsList(docBuilder,u,m);
    }


    private  Meta toDepsList(DocumentBuilder db, URI u, MavenStyleHTMLRefNavigator.MavenLink data) {
        try (InputStream is=u.toURL().openStream()) {
            Document xmldoc = db.parse(is);

            Element root= xmldoc.getDocumentElement();

            Element gid=getKid(root,"groupId");
            if(gid==null) return null;
            Element aid=getKid(root,"artifactId");
            if(aid==null) return null;

            Meta m=new Meta();
            m.gid=gid.getTextContent();
            m.aid=aid.getTextContent();
            m.versions=new LinkedList<>();
            Element vers=getKid(root,"versioning");
            if(vers!=null) {

                Element latest=getKid(vers,"latest");
                if(latest!=null) m.latest=latest.getTextContent();

                Element release=getKid(vers,"release");
                if(release!=null) m.release=release.getTextContent();

                Element lastUpdated=getKid(vers,"lastUpdated");
                if(lastUpdated!=null) m.updated =lastUpdated.getTextContent();

                Element vlist=getKid(vers,"versions");
                if(vlist!=null) {

                    NodeList versions=vlist.getElementsByTagName("version");
                    for(int i=0;i<versions.getLength();i++) {
                        String v=versions.item(i).getTextContent();
                        m.versions.add(v);
                    }
                    return m;
                }
            }
        } catch (MalformedURLException e) {
            System.out.println(e);
        } catch (IOException e) {
            System.out.println(e);
        } catch (SAXException e) {
            System.out.println(e);
        }

        return null;
    }

    private Element getKid(Element root, String tag) {

        NodeList nl=root.getElementsByTagName(tag);
        if(nl.getLength() == 0) return null;
        return (Element) nl.item(0);

    }


}

