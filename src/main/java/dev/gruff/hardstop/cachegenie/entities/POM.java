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

    static POM create(ArtifactRef ref, List<ArtifactRef> dependencies) {
        ObjectChecks.isPresent("artifactID",ref);
        ObjectChecks.isPresent("dependencies",dependencies);

        return new POMImpl(ref,dependencies,POMStatus.OK);
    }

    static POM create(POMStatus pomStatus) {
        return new POMImpl(pomStatus);
    }

    ArtifactRef artifact();

    public static POM create(File f) {
        FileChecks.checkFileExistsIsFileOfType(f,".pom");
        return POMFileParser.parse(f);
    }

    final class  POMImpl implements POM {

         private final POMStatus status;
         private ArtifactRef ref=ArtifactRef.MISSING_REF;
         private ArtifactRefSet deps=new ArtifactRefSet();


        private POMImpl(POMStatus pomStatus) {
            status=pomStatus;
        }

        private POMImpl(ArtifactRef ref, List<ArtifactRef> dependencies, POMStatus pomStatus) {
            status=pomStatus;
            this.ref=ref;
            deps.addAll(dependencies);
        }

        @Override
        public ArtifactRef artifact() {
            return ref;
        }

    }
}
