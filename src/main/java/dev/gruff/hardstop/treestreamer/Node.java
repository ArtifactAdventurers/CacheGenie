package dev.gruff.hardstop.treestreamer;

public interface Node {
    ContentType type();

    boolean noSuffix();

    String name();

    boolean isType(ContentType contentType);

    boolean missingSuffix();

    boolean isName(String s);
}
