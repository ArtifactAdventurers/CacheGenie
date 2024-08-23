package dev.gruff.hardstop.treestreamer.navigators;

import dev.gruff.hardstop.treestreamer.LinkParser;
import org.jsoup.Connection;

public sealed interface NavigatorPolicy permits AbstractNavigatorPolicy,  NavigatorPolicyBuilder.Config.MyURIPolicy {
    LinkParser handler(Connection.Response r);
}
