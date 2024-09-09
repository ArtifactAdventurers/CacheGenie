package dev.gruff.hardstop.treestreamer.streamers;

import java.util.stream.Stream;

public sealed interface TreeStreamer<F> permits FileSystemTreeStreamer, URITreeStreamer {

    public Stream<F> stream();
}
