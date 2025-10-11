package dev.gruff.hardstop.cachegenie;

import org.junit.Test;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

import static org.junit.Assert.*;

public class MetaFactoryTest {

    private File writeTempXml(String xml) throws Exception {
        File f = File.createTempFile("maven-metadata", ".xml");
        f.deleteOnExit();
        Files.writeString(f.toPath(), xml, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    public void testCreateParsesValidMetadata() throws Exception {
        String xml = "" +
                "<metadata>" +
                "  <groupId>com.example</groupId>" +
                "  <artifactId>demo</artifactId>" +
                "  <versioning>" +
                "    <latest>1.2.0</latest>" +
                "    <release>1.1.0</release>" +
                "    <lastUpdated>20241012153237</lastUpdated>" +
                "    <versions>" +
                "      <version>1.0.0</version>" +
                "      <version>1.1.0</version>" +
                "      <version>1.2.0</version>" +
                "    </versions>" +
                "  </versioning>" +
                "</metadata>";

        File f = writeTempXml(xml);
        URI uri = f.toURI();

        MavenMetaDataFactory factory = MavenMetaDataFactory.newInstance();
        MavenMetaData meta = factory.create(uri);

        assertNotNull("Meta should not be null for valid XML", meta);
        assertEquals("com.example", meta.gid);
        assertEquals("demo", meta.aid);

        // versioning block
        assertEquals("1.2.0", meta.latest);
        assertEquals("1.1.0", meta.release);
        assertNotNull("Updated timestamp should be parsed", meta.updated());
        assertEquals("Updated timestamp should match converted format",
                Instant.parse("2024-10-12T15:32:37.00Z"), meta.updated());

        // versions map populated
        assertTrue(meta.versions.containsKey("1.0.0"));
        assertTrue(meta.versions.containsKey("1.1.0"));
        assertTrue(meta.versions.containsKey("1.2.0"));

        // Per-version updated is intentionally left null by MetaFactory
        assertNull(meta.versions.get("1.0.0").date());
        assertNull(meta.versions.get("1.1.0").date());
        assertNull(meta.versions.get("1.2.0").date());
    }

    @Test
    public void testCreateReturnsNullWhenMissingRequiredFields() throws Exception {
        // Missing artifactId
        String xmlMissingAid = "" +
                "<metadata>" +
                "  <groupId>com.example</groupId>" +
                "  <versioning>" +
                "    <latest>1.0.0</latest>" +
                "  </versioning>" +
                "</metadata>";

        File f1 = writeTempXml(xmlMissingAid);
        MavenMetaData meta1 = MavenMetaDataFactory.newInstance().create(f1.toURI());
        assertNull("MetaFactory should return null when artifactId is missing", meta1);

        // Missing groupId
        String xmlMissingGid = "" +
                "<metadata>" +
                "  <artifactId>demo</artifactId>" +
                "</metadata>";
        File f2 = writeTempXml(xmlMissingGid);
        MavenMetaData meta2 = MavenMetaDataFactory.newInstance().create(f2.toURI());
        assertNull("MetaFactory should return null when groupId is missing", meta2);
    }
}
