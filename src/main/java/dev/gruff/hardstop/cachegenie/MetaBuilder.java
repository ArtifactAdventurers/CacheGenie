package dev.gruff.hardstop.cachegenie;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.LinkedList;
import java.util.TreeMap;

public class MetaBuilder {

    final DocumentBuilder docBuilder;

    private MetaBuilder()  {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            docBuilder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
    }
    public static MetaBuilder newInstance() {
        return new MetaBuilder();
    }
   public  Meta build(URI u) {
            return toDepsList(docBuilder,u);
    }

    private  Meta toDepsList(DocumentBuilder db, URI u) {
        try (InputStream is=u.toURL().openStream()) {
            Document xmldoc = db.parse(is);

            Element root= xmldoc.getDocumentElement();

            Element gid=getKid(root,"groupId");
            if(gid==null) return null;
            Element aid=getKid(root,"artifactId");
            if(aid==null) return null;

            Meta m=new Meta(u);
            m.gid=getText(gid);
            m.aid=aid.getTextContent();
            m.versions=new TreeMap<>();
            Element vers=getKid(root,"versioning");
            if(vers!=null) {
                //dumpElement(vers);
                Element latest=getKid(vers,"latest");
                if(latest!=null) m.latest=latest.getTextContent();

                Element release=getKid(vers,"release");
                if(release!=null) m.release=release.getTextContent();
             //   System.out.println(m.latest+"/"+m.release+"//"+vers);
                Element lastUpdated=getKid(vers,"lastUpdated");
                if(lastUpdated!=null) m.updated(lastUpdated.getTextContent());

                Element vlist=getKid(vers,"versions");
                if(vlist!=null) {

                    NodeList versions=vlist.getElementsByTagName("version");
                    for(int i=0;i<versions.getLength();i++) {
                        String v=versions.item(i).getTextContent();
                        Meta.Version mvers=new Meta.Version();
                        mvers.version=v;
                        mvers.updated=null;
                        m.versions.put(v,mvers);
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

    private String getText(Element e) {
        if(e==null) return "";
        return e.getTextContent();
    }

    private void dumpElement(Element e) {
        System.out.println("E="+e.getTagName());
        NodeList nl=e.getChildNodes();
        for(int i=0;i<nl.getLength();i++) {
            var c=nl.item(i);
            if(c instanceof Element) {
                Element kid= (Element) c;
                System.out.println("kid ");
                dumpElement(kid);
            }


        }
    }

    private Element getKid(Element root, String tag) {

        NodeList nl=root.getElementsByTagName(tag);
        if(nl.getLength() == 0) return null;
        for(int i=0;i<nl.getLength();i++) {
            var c=nl.item(i);
            if(c instanceof Element) {
                Element kid= (Element) c;
                if(kid.getTagName().equalsIgnoreCase(tag)) return kid;

            }

        }
        return null;

    }

}
