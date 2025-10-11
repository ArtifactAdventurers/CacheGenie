package dev.gruff.hardstop.cachegenie;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.TreeMap;

public final class MavenMetaDataFactory {

    final DocumentBuilder db;

    private MavenMetaDataFactory()  {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            // Harden parser against XXE and related attacks
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (ParserConfigurationException ignored) {}
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (ParserConfigurationException ignored) {}
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (ParserConfigurationException ignored) {}
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            db = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
    }
    public static MavenMetaDataFactory newInstance() {
        return new MavenMetaDataFactory();
    }

   public MavenMetaData create(URI u) throws IOException{

        try (InputStream is=u.toURL().openStream()) {
            Document xmldoc = db.parse(is);

            Element root= xmldoc.getDocumentElement();

            Element gid=getKid(root,"groupId");
            if(gid==null) return null;
            Element aid=getKid(root,"artifactId");
            if(aid==null) return null;

            MavenMetaData m=new MavenMetaData(u);
            m.gid=getText(gid);
            m.aid=aid.getTextContent();
            m.versions=new TreeMap<>();
            Element vers=getKid(root,"versioning");
            if(vers!=null) {
                Element latest=getKid(vers,"latest");
                if(latest!=null) m.latest=latest.getTextContent();

                Element release=getKid(vers,"release");
                if(release!=null) m.release=release.getTextContent();
                Element lastUpdated=getKid(vers,"lastUpdated");
                if(lastUpdated!=null) m.updated(lastUpdated.getTextContent());

                Element vlist=getKid(vers,"versions");
                if(vlist!=null) {

                    NodeList versions=vlist.getElementsByTagName("version");
                    for(int i=0;i<versions.getLength();i++) {
                        String v=versions.item(i).getTextContent();
                        MavenMetaData.Version mvers=new MavenMetaData.Version();
                        mvers.version=v;
                        mvers.updated=null;
                        m.versions.put(v,mvers);
                    }
                }
            }
            // Always return the meta if we found gid/aid
            return m;

        } catch (SAXException e) {
           throw new IOException(e);
        }
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
            if(c instanceof Element kid) {
                if(kid.getTagName().equalsIgnoreCase(tag)) return kid;

            }

        }
        return null;

    }

}
