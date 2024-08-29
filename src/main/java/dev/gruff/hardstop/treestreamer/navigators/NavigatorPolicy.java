package dev.gruff.hardstop.treestreamer.navigators;

import dev.gruff.hardstop.treestreamer.LinkReader;
import org.jsoup.Connection;

public sealed interface NavigatorPolicy permits AbstractNavigatorPolicy {
    LinkReader handler(Connection.Response r);
}
