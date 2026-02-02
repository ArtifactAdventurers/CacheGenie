package dev.gruff.hardstop.cachegenie.entities;

import dev.gruff.hardstop.cachegenie.utils.FileChecks;
import dev.gruff.hardstop.cachegenie.utils.ObjectChecks;

import java.io.File;

public sealed interface ArtifactRef extends Comparable<ArtifactRef> permits ArtifactRef.ArtifactRefImpl {

   public static final ArtifactRef MISSING_REF = new ArtifactRefImpl();

    static ArtifactRef create(String groupIDRef, String artifactIdRef, String versionRef) {
        GroupId gid=GroupId.createWithDefault(groupIDRef);
        ArtifactId aid=ArtifactId.createWithDefault(artifactIdRef);
        Version ver=Version.createWithDefault(versionRef);
        return create(gid,aid,ver);

    }
    static ArtifactRef create(String groupIDRef, String artifactIdRef, String versionRef,File code) {
        GroupId gid=GroupId.createWithDefault(groupIDRef);
        ArtifactId aid=ArtifactId.createWithDefault(artifactIdRef);
        Version ver=Version.createWithDefault(versionRef);
        return create(gid,aid,ver,code);

    }

    static ArtifactRef create(GroupId gid,ArtifactId aid,Version vers) {
        return new ArtifactRefImpl(aid,gid,vers);
    }
    static ArtifactRef create(GroupId gid,ArtifactId aid,Version vers,File code) {
        return new ArtifactRefImpl(aid,gid,vers,code);
    }


    public GroupId groupID();

    public ArtifactId artifactID();

    public Version version();

    public File code();


    public String value();


    public final class ArtifactRefImpl implements  ArtifactRef {

        private GroupId groupID=GroupId.EMPTY_GROUPID;
        private ArtifactId artifactID=ArtifactId.EMPTY_ARTIFACTID;
        private Version version=Version.EMPTY_VERSION;
        private File code;

        private ArtifactRefImpl() {

        }

        private ArtifactRefImpl(ArtifactId aid, GroupId gid, Version vers,File code) {
            FileChecks.checkFileExists("code",code);
            ObjectChecks.isPresent("artifactID",aid);
            ObjectChecks.isPresent("groupID",gid);
            ObjectChecks.isPresent("version",vers);
            this.groupID=gid;
            this.artifactID=aid;
            this.version=vers;
            this.code=code;
        }
       private ArtifactRefImpl(ArtifactId aid, GroupId gid, Version vers) {
           ObjectChecks.isPresent("artifactID",aid);
           ObjectChecks.isPresent("groupID",gid);
           ObjectChecks.isPresent("version",vers);
           this.groupID=gid;
           this.artifactID=aid;
           this.version=vers;
        }

        @Override
        public GroupId groupID() {
            return groupID;
        }

        @Override
        public ArtifactId artifactID() {
            return artifactID;
        }

        @Override
        public Version version() {
            return version;
        }

        @Override
        public File code() {
            return code;
        }

        @Override
        public String value() {
            return groupID.value()+":"+artifactID.value()+":"+version.value();
        }

        @Override
        public int compareTo(ArtifactRef o) {
           if(o==null) return 1;
           if(o==this) return 0;

           String ov=o.value();
           if(ov==null) return 1;
           return ov.compareTo(value());
        }
    }
}
