package dev.gruff.hardstop.cachegenie.entities;

import dev.gruff.hardstop.cachegenie.utils.ObjectChecks;

import java.util.List;
import java.util.TreeSet;

public final class ArtifactRefSet {

    private TreeSet<ArtifactRef> deps=new TreeSet<>();

    public void addAll(List<ArtifactRef> dependencies) {
        ObjectChecks.isPresent("dependencies",dependencies);
        deps.addAll(dependencies);
    }

    public List<ArtifactRef> toList() {
        return List.copyOf(deps);
    }
}
