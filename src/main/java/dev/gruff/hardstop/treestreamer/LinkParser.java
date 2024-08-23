package dev.gruff.hardstop.treestreamer;

import dev.gruff.hardstop.treestreamer.navigators.Link;
import dev.gruff.hardstop.treestreamer.navigators.LinkSet;

import java.io.InputStream;
import java.net.URI;

public interface LinkParser<P,T> {

        public LinkSet<P,T> parse(Link<P,T> uri, InputStream in);
}
