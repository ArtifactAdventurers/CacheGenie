package dev.gruff.hardstop.cachegenie.graph;

import dev.gruff.hardstop.cachegenie.MavenMetaData;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MetaRepositoryTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private MavenMetaData sample() {
        MavenMetaData m = new MavenMetaData(URI.create("https://repo1.maven.org/maven2/org/example/demo/"));
        m.gid = "org.example";
        m.aid = "demo";
        m.latest = "1.2.0";
        m.release = "1.2.0";
        m.setUpdatedInstant(Instant.parse("2024-05-01T10:00:00Z"));
        m.setGenerated(Instant.parse("2024-05-02T11:00:00Z"));
        m.setUpdated("1.0.0", Instant.parse("2023-01-01T00:00:00Z"));
        m.setUpdated("1.1.0", Instant.parse("2023-06-01T00:00:00Z"));
        m.setUpdated("1.2.0", Instant.parse("2024-01-01T00:00:00Z"));
        m.markPomAsMissing("1.1.0");
        return m;
    }

    @Test
    public void testRoundTrip() {
        MetaRepository repo = new MetaRepository(tempFolder.getRoot());
        repo.save(sample());

        MavenMetaData loaded = repo.load("org.example", "demo");
        assertNotNull(loaded);
        assertEquals("org.example", loaded.gid);
        assertEquals("demo", loaded.aid);
        assertEquals("1.2.0", loaded.latest);
        assertEquals("1.2.0", loaded.release);
        assertEquals(Instant.parse("2024-05-01T10:00:00Z"), loaded.updated());
        assertNotNull(loaded.generated());

        Map<String, MavenMetaData.Version> byName = loaded.versions.values().stream()
                .collect(Collectors.toMap(MavenMetaData.Version::value, v -> v));
        assertEquals(3, byName.size());
        assertEquals(Instant.parse("2023-01-01T00:00:00Z"), byName.get("1.0.0").date());

        assertTrue(loaded.isMissingPom("1.1.0"));
        assertFalse(loaded.isMissingPom("1.0.0"));
    }

    @Test
    public void testUpsertReplacesVersions() {
        MetaRepository repo = new MetaRepository(tempFolder.getRoot());
        repo.save(sample());

        // Re-save with a trimmed version set; the table should mirror it.
        MavenMetaData m = new MavenMetaData(null);
        m.gid = "org.example";
        m.aid = "demo";
        m.latest = "2.0.0";
        m.setUpdated("2.0.0", Instant.parse("2025-01-01T00:00:00Z"));
        repo.save(m);

        MavenMetaData loaded = repo.load("org.example", "demo");
        assertNotNull(loaded);
        assertEquals("2.0.0", loaded.latest);
        assertEquals(1, loaded.versions.size());
        assertTrue(loaded.versions.containsKey("2.0.0"));
    }

    @Test
    public void testMarkPomFoundAndMissing() {
        MetaRepository repo = new MetaRepository(tempFolder.getRoot());
        repo.save(sample());

        repo.markPomFound("org.example", "demo", "1.1.0");
        assertFalse(repo.load("org.example", "demo").isMissingPom("1.1.0"));

        repo.markPomMissing("org.example", "demo", "1.0.0");
        assertTrue(repo.load("org.example", "demo").isMissingPom("1.0.0"));
    }

    @Test
    public void testLoadByPatternAndExists() {
        MetaRepository repo = new MetaRepository(tempFolder.getRoot());
        repo.save(sample());

        MavenMetaData other = new MavenMetaData(null);
        other.gid = "org.example";
        other.aid = "other";
        other.setUpdated("1.0.0", null);
        repo.save(other);

        assertTrue(repo.exists("org.example", "demo"));
        assertFalse(repo.exists("org.example", "missing"));

        List<MavenMetaData> all = repo.loadByPattern("org.example", null);
        assertEquals(2, all.size());

        List<MavenMetaData> one = repo.loadByPattern("org.example", "demo");
        assertEquals(1, one.size());
        assertEquals("demo", one.get(0).aid);
    }

    @Test
    public void testLoadMissingReturnsNull() {
        MetaRepository repo = new MetaRepository(tempFolder.getRoot());
        assertNull(repo.load("no.such", "thing"));
    }
}
