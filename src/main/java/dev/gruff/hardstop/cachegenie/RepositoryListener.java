package dev.gruff.hardstop.cachegenie;

import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.RepositoryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RepositoryListener extends AbstractRepositoryListener {

    private final Resolver r;
    public RepositoryListener(Resolver r) {
        this.r=r;
    }
    private static final Logger log = LoggerFactory.getLogger(RepositoryListener.class);


    @Override
    public void artifactDescriptorMissing(RepositoryEvent repositoryEvent) {
        log.debug(repositoryEvent.getArtifact()+" missing");

    }

    @Override
    public void artifactResolving(RepositoryEvent repositoryEvent) {
        log.debug(repositoryEvent.getArtifact()+" resolving");
    }

    @Override
    public void artifactResolved(RepositoryEvent repositoryEvent) {
        log.debug(repositoryEvent.getArtifact()+" resolved");
    }

}
