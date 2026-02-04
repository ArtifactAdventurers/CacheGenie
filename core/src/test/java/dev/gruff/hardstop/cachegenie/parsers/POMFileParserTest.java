package dev.gruff.hardstop.cachegenie.parsers;

import dev.gruff.hardstop.cachegenie.entities.POM;
import dev.gruff.hardstop.cachegenie.entities.POMStatus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
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
                "    <description>This is a test with &oslash; and &nbsp; entities.</description>\n" +
                "</project>";
        Files.writeString(entityFile.toPath(), content);

        POM pom = POMFileParser.parse(entityFile);
        assertEquals(POMStatus.OK, pom.status());
    }
}
