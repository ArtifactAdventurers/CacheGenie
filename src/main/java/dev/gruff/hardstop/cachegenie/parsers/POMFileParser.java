package dev.gruff.hardstop.cachegenie.parsers;

import dev.gruff.hardstop.cachegenie.entities.ArtifactRef;
import dev.gruff.hardstop.cachegenie.entities.POM;
import dev.gruff.hardstop.cachegenie.entities.POMStatus;
import dev.gruff.hardstop.cachegenie.utils.FileChecks;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import java.io.File;
import java.util.LinkedList;
import java.util.List;

import static dev.gruff.hardstop.cachegenie.parsers.ParserHelper.*;


public class POMFileParser {




    private static final DocumentBuilder builder=build();
    private static final Transformer transformer=createTransformer();

    private static Transformer createTransformer() {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer t;
        try {
            t = transformerFactory.newTransformer();
        } catch (TransformerConfigurationException e) {
            throw new RuntimeException(e);
        }
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        //  t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

        return t;
    }


    public static POM parse(File pom) {

        FileChecks.checkFileExists("pom",pom);

        POM s;
        ArtifactRef ref;
        List<ArtifactRef> dependencies =new LinkedList<>();
        POMStatus status;

        if(pom.exists()==false)  return parseFailed(POMStatus.NO_CACHE_FILE);
        if(pom.isFile()==false)  return parseFailed(POMStatus.WRONG_CACHE_TYPE);

        Document doc= parseXML(pom);
        if(doc==null) return parseFailed(POMStatus.XML_ERROR);

        // validate ..
        Element root= doc.getDocumentElement();
        if(root==null) return parseFailed(POMStatus.NO_ROOT);

        String tagName=root.getTagName();

        if(!tagName.equalsIgnoreCase("project")) return parseFailed(POMStatus.INCORRECT_ROOT_TAG);
        // got project
       
        Element groupID= getOnly(root,"groupId");  // group id
        
        if(groupID==null) {
            Element parent= getOnly(root,"parent");
            if(parent!=null) {
                groupID= getOnly(parent,"groupid");
            }
            if(groupID==null) return parseFailed(POMStatus.NO_GROUPID);

        }


        String groupIDRef=groupID.getTextContent();

        Element artifact= getOnly(root,"artifactid"); // artifact
        if(artifact==null) return parseFailed(POMStatus.NO_ARTIFACTID);
        
        String artifactIdRef =artifact.getTextContent();
        
        Element version= getOnly(root,"version"); // version
        if(version==null) {
            Element parent= getOnly(root,"parent");
            if(parent!=null) {
                version= getOnly(parent,"version");
            }
            if(version==null) return parseFailed(POMStatus.NO_VERSION);
        }
        String versionRef=version.getTextContent();

        // parse dependenceis ..
        Element dep= getOnly(root,"dependencies"); // desp
        if(dep!=null) {
            List<Element> deps= getAll(dep,"dependency");
            if(deps!=null) {
                for(Element d:deps) {
                        String[] data=new String[3];
                        data[0]=getOnlyText(d,"groupid");
                        data[1]=getOnlyText(d,"artifactid");
                        data[2]=getOnlyText(d,"version");
                        ArtifactRef ar=ArtifactRef.create(data[0],data[1],data[2]);
                        dependencies.add(ar);

                }

            }
        }

        ref=ArtifactRef.create(groupIDRef,artifactIdRef,versionRef);

        return POM.create(ref,dependencies);
    }

    private static POM parseFailed(POMStatus pomStatus) {
        return POM.create(pomStatus);
    }


    public  static org.w3c.dom.Document parseXML(File base) {
        try {
            return builder.parse(base);
        }
        catch (Exception e) {

            System.out.println("error "+e.getMessage()+" in "+base.getAbsolutePath());
        }
        return null;
    }

    private static DocumentBuilder build() {


        DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();
        try {
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
    }
}
