# TreeStreamer

TreeStreamer is a Java library designed to traverse hierarchical structures—such as file systems or web pages—and process them as a lazy `Stream`. It provides a unified way to "stream a tree" of resources, handling recursion, depth limits, and content parsing.

## Key Concepts

*   **TreeStreamer**: The core interface. Implementations like `FileSystemTreeStreamer` and `URITreeStreamer` produce a `Stream<T>` of visited nodes or parsed results.
*   **NavigatorPolicy**: Controls how the tree is traversed (e.g., max depth) and how content is parsed.
*   **LinkReader**: Parses the content of a node (e.g., an HTML page) into either a set of new links to follow (`LinkSet`) or a terminal result object.
*   **RateLimiter**: Helps throttle requests when traversing external resources like websites.

## Usage

### File System Traversal

To stream files from a directory recursively:

```java
import dev.gruff.hardstop.treestreamer.streamers.FileSystemTreeStreamer;
import java.io.File;
import java.util.stream.Stream;

File rootDir = new File("/path/to/scan");

// Create a streamer that includes directories in the output
FileSystemTreeStreamer streamer = FileSystemTreeStreamer.builder(rootDir)
    .build();

// Or suppress directories to only get files
// .suppressDirectories(true)

try (Stream<File> files = streamer.stream()) {
    files.forEach(file -> System.out.println("Found: " + file.getAbsolutePath()));
}
```

### URI Traversal (Web Crawling)

To crawl a website starting from a root URI:

```java
import dev.gruff.hardstop.treestreamer.streamers.URITreeSteamVisitorBuilder;
import dev.gruff.hardstop.treestreamer.streamers.TreeStreamer;
import java.net.URI;
import java.util.stream.Stream;

URI startUrl = URI.create("https://example.com");

TreeStreamer<Object> webStreamer = URITreeSteamVisitorBuilder.builder(startUrl)
    // Configure depth, rate limits, etc. via the builder if available
    .build();

try (Stream<Object> results = webStreamer.stream()) {
    results.forEach(item -> {
        // Items can be Links (visited pages) or parsed objects
        System.out.println("Visited: " + item);
    });
}
```

*Note: The URI streamer uses Jsoup internally to fetch and parse pages.*

## Structure

*   `dev.gruff.hardstop.treestreamer`: Core interfaces (`TreeStream`, `Node`, `LinkReader`).
*   `dev.gruff.hardstop.treestreamer.streamers`: Main implementations (`FileSystemTreeStreamer`, `URITreeStreamer`).
*   `dev.gruff.hardstop.treestreamer.navigators`: Helper classes for navigation policies and link handling.
