package dev.gruff.hardstop.treestreamer.navigators;

import java.net.URI;

public interface Link<P,T>{

    P path();
    T data();
    int compareTo(Link<P,T> o);
}
