package dev.gruff.hardstop.cachegenie.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

public class FileChecksTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void checkFileExistsIsFileOfType_acceptsMatchingExtension_caseInsensitive() throws IOException {
        File f = tmp.newFile("sample.POM");
        // should not throw
        FileChecks.checkFileExistsIsFileOfType(f, ".pom");
        FileChecks.checkFileExistsIsFileOfType(f, "pom");
        FileChecks.checkFileExistsIsFileOfType(f, " POM ");
    }

    @Test(expected = RuntimeException.class)
    public void checkFileExistsIsFileOfType_throwsOnNullFile() {
        FileChecks.checkFileExistsIsFileOfType(null, ".pom");
    }

    @Test(expected = RuntimeException.class)
    public void checkFileExistsIsFileOfType_throwsOnNullSuffix() throws IOException {
        File f = new File("does-not-matter");
        FileChecks.checkFileExistsIsFileOfType(f, null);
    }

    @Test(expected = RuntimeException.class)
    public void checkFileExistsIsFileOfType_throwsOnBlankSuffix() throws IOException {
        File f = new File("does-not-matter");
        FileChecks.checkFileExistsIsFileOfType(f, "  ");
    }

    @Test(expected = RuntimeException.class)
    public void checkFileExistsIsFileOfType_throwsWhenFileDoesNotExist() {
        File f = new File("/path/that/should/not/exist/12345.pom");
        FileChecks.checkFileExistsIsFileOfType(f, ".pom");
    }

    @Test(expected = RuntimeException.class)
    public void checkFileExistsIsFileOfType_throwsWhenDirectoryProvided() throws IOException {
        File dir = tmp.newFolder("adir");
        FileChecks.checkFileExistsIsFileOfType(dir, ".pom");
    }

    @Test(expected = RuntimeException.class)
    public void checkFileExistsIsFileOfType_throwsWhenWrongExtension() throws IOException {
        File f = tmp.newFile("readme.txt");
        FileChecks.checkFileExistsIsFileOfType(f, ".pom");
    }

    @Test
    public void checkFileExists_passesForExistingFileAndDir() throws IOException {
        File file = tmp.newFile("a.txt");
        File dir = tmp.newFolder("d");
        // should not throw
        FileChecks.checkFileExists("file", file);
        FileChecks.checkFileExists("dir", dir);
    }

    @Test(expected = RuntimeException.class)
    public void checkFileExists_throwsOnNull() {
        FileChecks.checkFileExists("x", null);
    }

    @Test(expected = RuntimeException.class)
    public void checkFileExists_throwsOnMissing() {
        File missing = new File("/path/that/should/not/exist/xyz");
        FileChecks.checkFileExists("missing", missing);
    }
}
