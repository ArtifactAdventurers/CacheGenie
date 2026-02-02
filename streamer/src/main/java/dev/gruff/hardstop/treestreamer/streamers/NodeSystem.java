package dev.gruff.hardstop.treestreamer.streamers;

public  interface NodeSystem {


    public interface Node {

    }
    public interface LeafNode extends Node {

    }
    public interface ContainerNode extends Node {

        boolean hasReference(String s);
    }
}
