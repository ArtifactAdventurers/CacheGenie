package dev.gruff.hardstop.cachegenie.parsers;

import dev.gruff.hardstop.cachegenie.entities.POM;
import dev.gruff.hardstop.cachegenie.entities.POMStatus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;

public class POMFileParserTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testEmptyFile() throws IOException {
        File emptyFile = tempFolder.newFile("empty.pom");
        
        POM pom = POMFileParser.parse(emptyFile);
        assertEquals(POMStatus.EMPTY_FILE, pom.status());
    }

    @Test
    public void testNonEmptyFile() throws IOException {
        File nonEmptyFile = tempFolder.newFile("nonempty.pom");
        Files.writeString(nonEmptyFile.toPath(), "<project></project>");
        
        POM pom = POMFileParser.parse(nonEmptyFile);
        // It should not be EMPTY_FILE, it might be NO_GROUPID as mandatory fields are missing
        assertEquals(POMStatus.NO_GROUPID, pom.status());
    }

    @Test
    public void testEntitiesInPom() throws IOException {
        File entityFile = tempFolder.newFile("entity.pom");
        String content = "<project>\n" +
                "    <groupId>test</groupId>\n" +
                "    <artifactId>test-artifact</artifactId>\n" +
                "    <version>1.0</version>\n" +
                "    <description>This is a test with &oslash;, &nbsp;, and &aacute; entities.</description>\n" +
                "</project>";
        Files.writeString(entityFile.toPath(), content);

        POM pom = POMFileParser.parse(entityFile);
        assertEquals(POMStatus.OK, pom.status());
    }

    @Test
    public void testEncodingError() throws IOException {
        File encodingFile = tempFolder.newFile("encoding.pom");
        // Create a POM with some UTF-8 characters but force it to be read incorrectly if not handled right
        byte[] invalidUtf8 = new byte[] { 
                '<','p','r','o','j','e','c','t','>',
                '<','g','r','o','u','p','I','d','>','t','e','s','t','<','/','g','r','o','u','p','I','d','>',
                '<','a','r','t','i','f','a','c','t','I','d','>','t','e','s','t','<','/','a','r','t','i','f','a','c','t','I','d','>',
                '<','v','e','r','s','i','o','n','>','1','<','/','v','e','r','s','i','o','n','>',
                (byte)0xFC, // invalid utf-8 byte
                '<','/','p','r','o','j','e','c','t','>'
        };
        Files.write(encodingFile.toPath(), invalidUtf8);

        POM pom = POMFileParser.parse(encodingFile);
        assertEquals(POMStatus.OK, pom.status());
    }
}
