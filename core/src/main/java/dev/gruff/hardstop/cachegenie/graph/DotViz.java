package dev.gruff.hardstop.cachegenie.graph;

import dev.gruff.hardstop.resolver.DependencySet;

import java.io.PrintStream;

public class DotViz {

    public static void viz(PrintStream ps, DependencySet ds) {
        ps.println("digraph dependencies {");
        ps.println("concentrate=true");
        ds.links( (f,t) -> {
            ps.println("\""+f.toString()+"\" -> \""+t.toString()+"\";");
        });
        ps.println("}");
    }
}
