package dev.gruff.hardstop.cachegenie;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

public class MavenMetaData {
    private static final Logger log = LoggerFactory.getLogger(MavenMetaData.class);

    public MetaVersionSet versions() {

        MetaVersionSet mv=new MetaVersionSet(versions.values());
        return mv;

    }

    public static class Version {
        String version;
       Instant updated;


       public Instant date() {
           return updated;
       }
       public String value() {
           return version;
       }
    }

    private MavenMetaData() {

    }
    public MavenMetaData(URI u) {
        this.uri=u;
    }
    public URI uri;
    public String gid = "";
    public String aid = "";
    public Map<String,Version> versions=new TreeMap<>();
    public Set<String> missingPoms = new TreeSet<>();
    public String latest = "";
    public String release = "";
    private Instant updated;
    private Instant generated;
    public Status status=Status.unknown;
    public String name() {
        return gid + ":" + aid;
    }


    public Instant updated() {
        return updated;
    }


    public Instant generated() {
        return generated;
    }
    public void updated(String s) {
        updated=toInstant(s);
    }

    public void save(File f) throws IOException {

        if(f==null) {
            log.warn("no file provided for saving meta data");
            return;
        }

        Properties p=new Properties();
        if(uri!=null) p.setProperty("meta.uri",uri.toASCIIString());
        p.setProperty("meta.gid",gid);
        p.setProperty("meta.aid",aid);
        p.setProperty("meta.release",release);
        p.setProperty("meta.latest",latest);
        p.setProperty("meta.updated", String.valueOf(updated));
        List<String> versionNames=new LinkedList<>();
        versionNames.addAll(versions.keySet());
        String vlist=String.join(" ",versionNames);
        p.setProperty("meta.versions",vlist);

        if (!missingPoms.isEmpty()) {
            p.setProperty("meta.missing.poms", String.join(" ", missingPoms));
        }

        int c=1;
        for(String v:versionNames) {
            p.setProperty("version."+c+".updated", String.valueOf(versions.get(v).updated));
            c++;
        }

        p.setProperty("meta.generated",Instant.now().toString());
        try (FileWriter fw = new FileWriter(f)) {
            p.store(fw, "" + Instant.now());
        }

        log.info("saved {}", f.getAbsolutePath());
    }

    public String toString() {
        StringBuilder sb=new StringBuilder();
        sb.append("gid="+gid+"/aid="+aid+"/status="+status+"/versions="+versions.toString());
        return sb.toString();
    }

    public static MavenMetaData load(File f) {
        MavenMetaData meta=new MavenMetaData();
        Properties p=new Properties();
        try( FileReader fr=new FileReader(f)) {
            p.load(fr);
            if(p.keySet().size()==0) {
                log.warn("nodata {}", f.getAbsolutePath());
                meta.status= Status.corrupted_propoerties;
                return meta;
            }
            meta.status= Status.has_properties;
            meta.uri=toURI(p);
            meta.gid=p.getProperty("meta.gid");
            meta.aid=p.getProperty("meta.aid");
            meta.latest=p.getProperty("meta.latest");
            meta.release=p.getProperty("meta.release");
            meta.generated=toInstant(p.getProperty("meta.generated"));
            meta.updated =toInstant(p.getProperty("meta.updated"));
            String missingPomsStr = p.getProperty("meta.missing.poms", "");
            if (!missingPomsStr.isEmpty()) {
                meta.missingPoms.addAll(Arrays.asList(missingPomsStr.split(" ")));
            }
            String[] vnames=p.getProperty("meta.versions","").split(" ");
            meta.versions=new TreeMap<>();
            for(int i=0;i<vnames.length;i++) {
                String v=vnames[i];
                String updated=p.getProperty("version."+(i+1)+".updated","");
                Version vers=new Version();
                vers.version=v;
                vers.updated=toInstant(updated);
                if(meta.updated==null) meta.updated=vers.updated;
                meta.versions.put(v,vers);
            }

            //
        } catch(IOException fne) {
            log.warn("corrupted {}", f.getAbsolutePath());
            meta.status= Status.corrupted_propoerties;
        }

        return meta;
    }

    public static MavenMetaData loadJSON(File f) {
        try {
            String content = Files.readString(f.toPath());
            MavenMetaData meta = new MavenMetaData();
            meta.gid = extractJSON(content, "groupId");
            meta.aid = extractJSON(content, "artifactId");
            meta.versions = new TreeMap<>();
            meta.status = Status.has_properties;

            // Very simplistic JSON parsing for versions
            int versionsIdx = content.indexOf("\"versions\": [");
            if (versionsIdx != -1) {
                int start = versionsIdx + 13;
                int end = content.lastIndexOf("]");
                String versionsPart = content.substring(start, end);
                String[] versionObjects = versionsPart.split("\\},");
                for (String obj : versionObjects) {
                    String vVal = extractJSON(obj, "version");
                    if (vVal != null) {
                        Version v = new Version();
                        v.version = vVal;
                        String published = extractJSON(obj, "published");
                        if (published != null && !"null".equals(published)) {
                            try {
                                v.updated = Instant.parse(published);
                            } catch (Exception e) {
                                log.warn("Failed to parse published date '{}' for version {}: {}", published, vVal, e.getMessage());
                            }
                        }
                        meta.versions.put(vVal, v);
                        String missingPom = extractJSON(obj, "missingPom");
                        if ("true".equals(missingPom)) meta.markPomAsMissing(vVal);
                    }
                }
            }
            return meta;
        } catch (Exception e) {
            log.error("Failed to load JSON meta {}: {}", f.getAbsolutePath(), e.getMessage());
            return null;
        }
    }

    private static String extractJSON(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":");
        if (idx == -1) return null;
        int start = json.indexOf("\"", idx + key.length() + 3);
        if (start == -1) {
            // might be boolean or null
            int valStart = idx + key.length() + 3;
            int comma = json.indexOf(",", valStart);
            if (comma == -1) comma = json.indexOf("}", valStart);
            if (comma == -1) comma = json.indexOf("\n", valStart);
            if (comma == -1) return null;
            return json.substring(valStart, comma).trim();
        }
        int end = json.indexOf("\"", start + 1);
        return json.substring(start + 1, end);
    }

    private static Instant toInstant(String updated) {
       try {

           if(updated.endsWith("Z")==false) {

               updated = updated.substring(0, 4)
                       + "-" + updated.substring(4, 6)
                       + "-" + updated.substring(6, 8)
                       + "T" + updated.substring(8, 10)
                       + ":" + updated.substring(10, 12)
                       + ":" + updated.substring(12, 14) + ".00Z";
           }
           //System.out.println(df);
           return Instant.parse(updated);

       } catch(Exception e) {
           return null;
       }
    }

    private static URI toURI(Properties p) {
        try {
            return URI.create(p.getProperty("uri", ""));
        } catch(Exception e) {
            return null;
        }
    }

    public void setUpdated(String key, Instant updated) {
        Version v=versions.get(key);
        if(v==null) {
            v=new Version();
            v.version=key;
            versions.put(key,v);
        }
        v.updated=updated;
    }

    public boolean isMissingPom(String version) {
        return missingPoms.contains(version);
    }

    public void markPomAsMissing(String version) {
        missingPoms.add(version);
    }

    public void markPomAsFound(String version) {
        missingPoms.remove(version);
    }

    public static enum Status {
        unknown, has_properties, corrupted_propoerties
    }
}
