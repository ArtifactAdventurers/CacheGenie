package dev.gruff.hardstop.cachegenie.entities;

import dev.gruff.hardstop.cachegenie.utils.StringChecks;

public sealed interface ArtifactId {

    public static final ArtifactId EMPTY_ARTIFACTID = new ArtifactIDImpl();

    static ArtifactId createWithDefault(String artifactIdRef) {
        if(StringChecks.isNullOrEmpty(artifactIdRef)) return EMPTY_ARTIFACTID;
        return new ArtifactIDImpl(artifactIdRef);
    }

    public String value();

    public static ArtifactId create(String id) {
        return new ArtifactIDImpl(id);
    }

    final class ArtifactIDImpl implements ArtifactId {

        private String value;
        private  ArtifactIDImpl() {
            value="";
        }
        private  ArtifactIDImpl(String artifactIDRef) {
            StringChecks.checkNonNullNotEmpty("artifactID",artifactIDRef);
            this.value=artifactIDRef;
        }

        @Override
        public String value() {
            return value;
        }
    }
}
