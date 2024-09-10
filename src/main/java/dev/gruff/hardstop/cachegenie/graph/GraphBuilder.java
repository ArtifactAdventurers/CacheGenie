package dev.gruff.hardstop.cachegenie.graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public final class GraphBuilder {


    public Set<Node> build() {

       Set<Node> roots=new HashSet<>();
        return roots;
    }

    private static class Node implements GraphNode {
        private Object base=null;
        private Node(Object o) {
            this.base=base;
        }

    }

    public class NodeConfig {
        Node root;
        private NodeConfig(Object o){
            Node n=nodes.get(o);

            if(o==null) {
                n=new Node(o);
                nodes.put(o,n);
            }
            root=n;
        }
        public NodeConfig addLink(Object o) {
           return this;
        }


        public NodeConfig addNode(Object o) {
            return GraphBuilder.this.addNode(o);
        }

        public Set<Node> build() {
            return GraphBuilder.this.build();
        }
    }
    private HashMap<Object,Node> nodes =new HashMap<>();

    public synchronized NodeConfig addNode(Object o) {

        return new NodeConfig(o);
    }



    public static GraphBuilder newInstance() {
        return new GraphBuilder();
    }

}
