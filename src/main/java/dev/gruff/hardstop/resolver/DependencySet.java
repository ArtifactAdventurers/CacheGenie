package dev.gruff.hardstop.resolver;

import java.util.*;


public class DependencySet {

    public interface LinkVisitor {
        public void apply(Node a,Node b);
    }
    private Set<Node> nodes=new HashSet<>();
    private Map<Node,Set<Node>> links=new HashMap<>();

    public DependencySet(Collection<Node> nodes, Map<Node, Set<Node>> links) {
        this.nodes.addAll(nodes);
        this.links.putAll(links);
    }

    public void addNode(Node node) {
        nodes.add(node);
    }

    public  void links(LinkVisitor f) {
        links.keySet().forEach(parent -> {

            Set<Node> kids=links.get(parent);
            kids.forEach(kid -> {

                f.apply(parent,kid);
            });
        });
    }

    public static class Node {
        String gid=null;
        String aid=null;
        String ver=null;
        String type=null;

        public String toString() {
            String main=gid+":"+aid+":"+ver;
            if(type!=null && !type.trim().isEmpty()) main=main+":"+type;
            return main;
        }
    }

    public  class  Link {
        Node from=null;
        Node to=null;
    }

}
