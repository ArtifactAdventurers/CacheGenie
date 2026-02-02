package dev.gruff.hardstop.treestreamer;

import dev.gruff.hardstop.treestreamer.navigators.Link;

import java.io.InputStream;

public interface LinkReader {

        public  Object  parse(Link uri, InputStream in);
}
