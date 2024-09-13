package dev.gruff.hardstop.cachegenie.parsers;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.LinkedList;
import java.util.List;

public class ParserHelper {


    public static String getOnlyText(Element doc, String tag) {
        Element e=getOnly(doc,tag);
        if(e==null) return null;
        return e.getTextContent();
    }

     public static Element getOnly(Element doc, String tag) {
        List<Element> results=getAll(doc,tag);
        if(results==null || results.size()!=1) return null;
        return results.get(0);
    }
    public static List<Element> getAll(Element doc, String tag) {

        List<Element> results=new LinkedList<>();
        Node childNode = doc.getFirstChild();
        while (childNode != null) {
            if(childNode instanceof Element) {
                Element e= (Element) childNode;
                String tagName=e.getTagName();
                if(tag.equalsIgnoreCase(tagName)) results.add(e);
            }
            childNode = childNode.getNextSibling();
        }

        return results;


    }
}
