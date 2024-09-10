package dev.gruff.hardstop.cachegenie.graph;

import org.junit.Test;

public class TestGraphBuilder {

    @Test
    public void simpleBuilderTest() {

        GraphBuilder gb=GraphBuilder.newInstance();
        gb.addNode("A")
           .addNode("B")
           .addLink("A")
                .build();


    }
}
