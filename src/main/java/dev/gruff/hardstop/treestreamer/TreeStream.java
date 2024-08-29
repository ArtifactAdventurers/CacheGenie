package dev.gruff.hardstop.treestreamer;

import java.util.stream.Stream;

public interface TreeStream<X> {

    public void handle(Stream<X> s);
}
