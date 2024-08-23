package dev.gruff.hardstop.cachegenie;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

class Meta {

    public String gid = "";
    public String aid = "";
    public List<String> versions;
    public String latest = "";
    public String release = "";
    public String updated = "";
    public Status status=Status.unknown;
    public String name() {
        return gid + ":" + aid;
    }


    public void save(File f) throws IOException {

        if(f==null) {
            System.out.println("no  file");
            return;
        }

        Properties p=new Properties();
        p.setProperty("gid",gid);
        p.setProperty("aid",aid);
        p.setProperty("release",release);
        p.setProperty("latest",latest);
        p.setProperty("updated", updated);
        String vlist=String.join(" ",versions);
        p.setProperty("versions",vlist);
        p.setProperty("meta",Instant.now().toString());
        p.store(new FileWriter(f),""+Instant.now());

        System.out.println("saved "+f.getAbsolutePath());
    }

    public String toString() {
        StringBuilder sb=new StringBuilder();
        sb.append("gid="+gid+"/aid="+aid+"/status="+status+"/versions="+versions.toString());
        return sb.toString();
    }

    public static Meta load(File f) {
        Meta meta=new Meta();
        Properties p=new Properties();
        try( FileReader fr=new FileReader(f)) {
            p.load(fr);
            meta.status= Status.has_properties;
            meta.gid=p.getProperty("gid");
            meta.aid=p.getProperty("aid");
            meta.latest=p.getProperty("latest");
            meta.release=p.getProperty("release");
            meta.updated =p.getProperty("updated");
            meta.versions=List.of(p.getProperty("versions","").split(" "));
            //
        } catch(IOException fne) {
            meta.status= Status.corrupted_propoerties;
        }

        return meta;
    }

   public static enum Status {
        unknown, has_properties, corrupted_propoerties
    }
}
