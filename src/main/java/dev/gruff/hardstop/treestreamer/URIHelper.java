package dev.gruff.hardstop.treestreamer;

import dev.gruff.hardstop.treestreamer.navigators.Link;

import java.net.URI;

public class URIHelper {


    public static URI subDirURI(URI u, String childPath) {

        StringBuilder sb=new StringBuilder();
        String scheme=u.getScheme();
        if(scheme!=null) {
            sb.append(scheme);
            sb.append("://");
        }
        String host=u.getHost();
        if(host!=null) {
            sb.append(host);
           int port=u.getPort();
           if(port>=0) {
               sb.append(":"+port);
           }
           String path=u.getPath();
           if(path==null) {
               path=childPath;
           } else {
               path = path + "/" + childPath;
           }
           path=path.replace("//","/");
            sb.append(path);

           String query=u.getQuery();
           if(query!=null) {
               sb.append("?");
               sb.append(query);
           }
           }
       try {
         return   URI.create(sb.toString());
       } catch(Exception e) {
           return null;
       }
    }

    public static String file(URI u) {
        String name=u.getPath();
        if(name==null ||name.trim().equals("")) return null;
        String[] bits=name.split("/");
        return bits[bits.length-1];
    }

    public static String relative(URI base, URI path) {
        String s=base.toASCIIString();
        String p=path.toASCIIString();
        String sub=p.substring(s.length());
        if(sub.endsWith("/")) sub=sub.substring(0,sub.length()-1);

        return sub;
    }

    public static boolean isChild(URI base,URI path) {
        String s=base.toASCIIString().trim().toLowerCase();
        String p=path.toASCIIString().trim().toLowerCase();
        if(p.length()<=s.length()) return false; //child is same size or less
        return p.startsWith(s); // child is  related to parent as it startw with same values.


    }
}
