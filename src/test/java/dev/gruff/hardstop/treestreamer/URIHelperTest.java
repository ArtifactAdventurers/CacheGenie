package dev.gruff.hardstop.treestreamer;

import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.*;

public class URIHelperTest {

    @Test
    public void subDirURI_joinsPathUnderBase() {
        URI base = URI.create("http://example.com/root/");
        URI result = URIHelper.subDirURI(base, "child/file.txt");
        assertNotNull(result);
        assertEquals("http://example.com/root/child/file.txt", result.toASCIIString());
    }

    @Test
    public void subDirURI_normalizesDotSegments() {
        URI base = URI.create("http://example.com/root/");
        URI result = URIHelper.subDirURI(base, "./a/../b");
        assertNotNull(result);
        assertEquals("http://example.com/root/b", result.toASCIIString());
    }

    @Test
    public void subDirURI_leadingSlashResolvesFromRoot() {
        URI base = URI.create("http://example.com/root/");
        URI result = URIHelper.subDirURI(base, "/x");
        assertNotNull(result);
        assertEquals("http://example.com/x", result.toASCIIString());
    }

    @Test
    public void subDirURI_nullOrBlankInputsReturnNull() {
        assertNull(URIHelper.subDirURI(null, "child"));
        assertNull(URIHelper.subDirURI(URI.create("http://example.com/"), null));
        assertNull(URIHelper.subDirURI(URI.create("http://example.com/"), " "));
    }

    @Test
    public void file_returnsLastSegment() {
        assertEquals("b.txt", URIHelper.file(URI.create("http://host/a/b.txt")));
    }

    @Test
    public void file_trailingSlashYieldsEmptyString() {
        assertEquals("", URIHelper.file(URI.create("http://host/a/b/")));
    }

    @Test
    public void file_nullUriReturnsNull() {
        assertNull(URIHelper.file(null));
    }

    @Test
    public void relative_childPathTrimTrailingSlash() {
        URI base = URI.create("http://x/root/");
        URI child = URI.create("http://x/root/a/b/");
        assertEquals("a/b", URIHelper.relative(base, child));
    }

    @Test
    public void relative_notUnderBaseFallsBackToAbsoluteAscii() {
        URI base = URI.create("http://x/base/");
        URI child = URI.create("http://other/elsewhere/path");
        assertEquals(child.toASCIIString(), URIHelper.relative(base, child));
    }

    @Test
    public void relative_nullInputsReturnNull() {
        assertNull(URIHelper.relative(null, URI.create("http://x/a")));
        assertNull(URIHelper.relative(URI.create("http://x/a"), null));
    }

    @Test
    public void isChild_trueWhenDeeperAndStartsWithBase_caseInsensitive() {
        URI base = URI.create("http://Example.com/root/");
        URI child = URI.create("http://example.com/root/dir/file");
        assertTrue(URIHelper.isChild(base, child));
    }

    @Test
    public void isChild_falseWhenEqualOrShorter() {
        URI base = URI.create("http://x/root/");
        assertFalse(URIHelper.isChild(base, URI.create("http://x/root/")));
        assertFalse(URIHelper.isChild(base, URI.create("http://x/roo")));
    }

    @Test
    public void isChild_falseWhenDifferentHostOrNull() {
        assertFalse(URIHelper.isChild(URI.create("http://x/root/"), URI.create("http://y/root/a")));
        assertFalse(URIHelper.isChild(null, URI.create("http://x/a")));
        assertFalse(URIHelper.isChild(URI.create("http://x/a"), null));
    }
}
