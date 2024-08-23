package dev.gruff.hardstop.treestreamer.streamers;

import java.util.stream.Stream;

public sealed interface TreeStreamer< P > permits FileSystemTreeStreamer, URITreeStreamer {

    public Stream<P> stream();
}
