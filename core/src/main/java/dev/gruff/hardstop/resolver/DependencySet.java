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

    public Set<Node> getNodes() {
        return Collections.unmodifiableSet(nodes);
    }

    public Map<Node, Set<Node>> getLinks() {
        return Collections.unmodifiableMap(links);
    }

    public static class Node {
        public String gid=null;
        public String aid=null;
        public String ver=null;
        public String type=null;
        public String scope=null;

        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Node node = (Node) o;
            return Objects.equals(gid, node.gid) && Objects.equals(aid, node.aid) && Objects.equals(ver, node.ver) && Objects.equals(type, node.type) && Objects.equals(scope, node.scope);
        }

        public int hashCode() {
            return Objects.hash(gid, aid, ver, type, scope);
        }

        public String toString() {
            String main=gid+":"+aid+":"+ver;
            if(type!=null && !type.trim().isEmpty()) main=main+":"+type;
            if(scope!=null && !scope.trim().isEmpty()) main=main+" ("+scope+")";
            return main;
        }
    }

    public  class  Link {
        Node from=null;
        Node to=null;
    }

}
