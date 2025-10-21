package dev.gruff.hardstop.cachegenie.actions;

import dev.gruff.hardstop.cachegenie.CacheGenie;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;

public final class CreateDBAction {

    private static interface VersionInfo {

    }
    private static class VersionRecord implements VersionInfo {
        String gid;
        String aid;
        String version;
        String date;
    }

    private static class VersionRange implements VersionInfo {
        String gid;
        String aid;
        String startDate;
        String endDate;
        int count;
        String metaUrl;
    }


    private final CacheGenie cg;
    public CreateDBAction(CacheGenie cg) {
        this.cg=cg;
    }

    public void create() throws IOException {
        System.out.println("Create CacheGenie Repo Index DB");
        File root=cg.cacheGenieRoot();
        Path path=root.toPath();
        File db=new File(root,"index.db");
        File vrdb=new File(root,"range.db");

        try(Stream<Path> stream= Files.list(path);
            FileWriter fw=new FileWriter(db);
            FileWriter rw=new FileWriter(vrdb)) {

            PrintWriter pw=new PrintWriter(fw);
            pw.println("gid,aid,version,date");
            PrintWriter vrpw=new PrintWriter(rw);
            vrpw.println("gid,aid,startdate,enddate,count,uri");


            stream.dropWhile(p -> { return !p.toFile().getName().endsWith(".properties");})
                    .map(CreateDBAction::loadProps)
                    .filter(Objects::nonNull)
                    .flatMap(CreateDBAction::versions)
                    .forEach(p -> {
                        if(p instanceof VersionRecord vr) {
                            pw.println(vr.gid+","+vr.aid+","+vr.version+","+vr.date);
                        }
                        if(p instanceof VersionRange vr) {
                            vrpw.println(vr.gid+","+vr.aid+","+vr.startDate+","+vr.endDate+","+vr.count+","+vr.metaUrl);
                        }


            });

            pw.flush();
            pw.close();
            vrpw.flush();
            vrpw.close();
        }

    }

    private static Properties loadProps(Path f) {
        Properties p=new Properties();
        File file=f.toFile();
        if(!file.exists()) return null;
        if(!file.isFile()) return null;
        if(!file.getName().endsWith(".properties")) return null;

        try (FileReader fw = new FileReader(file)) {
            p.load(fw);
            fw.close();;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return p;
    }

    private static Stream<VersionInfo> versions(Properties p) {

        String gid=p.getProperty("meta.gid");
        String aid=p.getProperty("meta.aid");
        String v=p.getProperty("meta.versions");
        if(v==null) {
            return Stream.empty();
        }
        String[] parts=v.split(" ");

        VersionRange vr=new VersionRange();
        vr.aid=aid;
        vr.gid=gid;
        vr.metaUrl=p.getProperty("meta.uri");

        List<VersionInfo> versions=new LinkedList<>();

        System.out.println("gid="+gid+" aid="+aid+" versions="+parts.length);

        vr.count=0;

        for(int i=0;i<parts.length;i++) {
            String vname=parts[i];
            VersionRecord vi=new VersionRecord();
            vi.gid=gid;
            vi.aid=aid;
            vi.version=vname;
            vi.date=p.getProperty("version."+(i+1)+".updated");
            if(vi.date!=null && !vi.date.equals("null")) {
                vi.date=vi.date.substring(0,vi.date.indexOf('T'));;
                versions.add(vi);
                vr.endDate=vi.date;
                if(vr.startDate==null) vr.startDate=vi.date;
                vr.count++;
            }
        }

        versions.add(vr);

        return versions.stream();


    }
}
