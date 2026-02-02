package dev.gruff.hardstop.resolver;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.DependencyNode;

import java.util.*;

public class DependencyBuilder {

    private final Map<Artifact,DependencySet.Node> nodes=new HashMap<>();
    private final Map<DependencySet.Node,Set<DependencySet.Node>> links=new HashMap<>();

    private DependencyBuilder() {

    }

    public static DependencyBuilder newInstance() {
        return new DependencyBuilder();
    }

    public DependencySet build() {
        return new DependencySet(nodes.values(),links);
    }
    public DependencyBuilder addDependencies(List<DependencyNode> dn) {

        for(DependencyNode n:dn) {
            Artifact a=n.getArtifact();
            DependencySet.Node an=nodes.get(a);
            if(an==null) {
                // not seen this
                an=toNode(a);
              //  nodes.put(a,an);
                visit(an,n);
            }


        }

        return this;


    }

    private DependencySet.Node toNode(Artifact a) {
        DependencySet.Node n=new DependencySet.Node();
        n.gid=a.getGroupId();
        n.aid=a.getArtifactId();
        n.ver=a.getVersion();
        n.type=a.getClassifier();
        return n;
    }


    private  void visit(DependencySet.Node parent,DependencyNode dn) {

        Artifact a=dn.getArtifact(); // get the artifact for the dependency

        DependencySet.Node kid=nodes.get(a);

        if(kid!=null) {
            // we dont need to visit the children (been here)
            // just add the parent link and return
            addLink(parent,kid);
            return;
        } else {
            // new visit
            kid=toNode(a);
            nodes.put(a,kid);
            addLink(parent,kid);
            for(DependencyNode k: dn.getChildren()) {
                visit(kid,k);
            }

        }


    }

    private void addLink(DependencySet.Node parent, DependencySet.Node kid) {

        Set<DependencySet.Node> kids = links.computeIfAbsent(parent, k -> new HashSet<>());
        kids.add(kid);
    }


}
