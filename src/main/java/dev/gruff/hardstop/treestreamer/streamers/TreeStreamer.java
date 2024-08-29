package dev.gruff.hardstop.treestreamer.streamers;

import java.util.stream.Stream;

public sealed interface TreeStreamer permits FileSystemTreeStreamer, URITreeStreamer {

    public Stream<Object> stream();
}
