package dev.gruff.hardstop.cachegenie.entities;

import dev.gruff.hardstop.cachegenie.parsers.POMFileParser;
import dev.gruff.hardstop.cachegenie.utils.FileChecks;
import dev.gruff.hardstop.cachegenie.utils.ObjectChecks;
import dev.gruff.hardstop.resolver.DependencySet;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public sealed interface POM permits  POM.POMImpl {

    static POM create(ArtifactRef ref, List<ArtifactRef> dependencies, POMStatus status, String packaging, String javaSource, String javaTarget, String javaRelease) {
        ObjectChecks.isPresent("artifactID",ref);
        ObjectChecks.isPresent("dependencies",dependencies);

        return new POMImpl(ref,dependencies,status, packaging, javaSource, javaTarget, javaRelease);
    }

    static POM create(POMStatus pomStatus) {
        return new POMImpl(pomStatus);
    }

    ArtifactRef artifact();
    POMStatus status();
    List<ArtifactRef> dependencies();
    String packaging();
    String javaSource();
    String javaTarget();
    String javaRelease();

    public static POM create(File f) {
        FileChecks.checkFileExistsIsFileOfType(f,".pom");
        return POMFileParser.parse(f);
    }

    final class  POMImpl implements POM {

         private final POMStatus status;
         private ArtifactRef ref=ArtifactRef.MISSING_REF;
         private ArtifactRefSet deps=new ArtifactRefSet();
         private String packaging = "jar";
         private String javaSource;
         private String javaTarget;
         private String javaRelease;

        private POMImpl(POMStatus pomStatus) {
            status=pomStatus;
        }

        private POMImpl(ArtifactRef ref, List<ArtifactRef> dependencies, POMStatus pomStatus) {
            status=pomStatus;
            this.ref=ref;
            deps.addAll(dependencies);
        }

        public POMImpl(ArtifactRef ref, List<ArtifactRef> dependencies, POMStatus status, String packaging, String javaSource, String javaTarget, String javaRelease) {
            this.ref = ref;
            this.deps.addAll(dependencies);
            this.status = status;
            this.packaging = packaging;
            this.javaSource = javaSource;
            this.javaTarget = javaTarget;
            this.javaRelease = javaRelease;
        }

        @Override
        public ArtifactRef artifact() {
            return ref;
        }

        @Override
        public POMStatus status() {
            return status;
        }

        @Override
        public List<ArtifactRef> dependencies() {
            return deps.toList();
        }

        @Override
        public String packaging() {
            return packaging;
        }

        @Override
        public String javaSource() {
            return javaSource;
        }

        @Override
        public String javaTarget() {
            return javaTarget;
        }

        @Override
        public String javaRelease() {
            return javaRelease;
        }

    }
}
