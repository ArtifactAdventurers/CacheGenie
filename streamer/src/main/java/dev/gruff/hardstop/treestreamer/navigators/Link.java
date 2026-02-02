package dev.gruff.hardstop.treestreamer.navigators;

import dev.gruff.hardstop.treestreamer.ContentType;

import java.net.URI;

public interface Link{


    URI path();


    boolean isType(ContentType contentType);
}
