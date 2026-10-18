package com.ricodevvv.codinject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.ricodevvv.codinject.FakeRepository.dependencies;
import static com.ricodevvv.codinject.FakeRepository.dependency;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolverTest {

    @TempDir
    Path temp;

    FakeRepository repo;
    Codinject codinject;

    @BeforeEach
    void setUp() throws IOException {
        repo = new FakeRepository(temp.resolve("repo"));
        codinject = new Codinject(temp.resolve("libs")).clearRepositories().repository(repo.url());
    }

    @Test
    void resolvesTheRuntimeTreeLikeMaven() throws IOException {
        repo.pom("test:bom:1.0", "<packaging>pom</packaging>", "<dependencyManagement>" + dependencies(
                dependency("test:lib-b:2.0")) + "</dependencyManagement>");
        repo.pom("test:parent:1.0", "<packaging>pom</packaging>",
                "<properties><a.version>1.0</a.version></properties>",
                "<dependencyManagement>" + dependencies(
                        dependency("test:bom:1.0", "<type>pom</type><scope>import</scope>")) + "</dependencyManagement>");
        repo.rawPom(Artifact.parse("test:app:1.0"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<parent><groupId>test</groupId><artifactId>parent</artifactId><version>1.0</version></parent>"
                + "<artifactId>app</artifactId>"
                + dependencies(
                dependency("test:lib-a:${a.version}", "<exclusions><exclusion><groupId>test</groupId><artifactId>lib-x</artifactId></exclusion></exclusions>"),
                dependency("test:lib-b:-"),
                dependency("test:lib-common:1.5"),
                dependency("test:lib-sibling:${project.version}"),
                dependency("test:lib-test:1.0", "<scope>test</scope>"),
                dependency("test:lib-provided:1.0", "<scope>provided</scope>"),
                dependency("test:lib-optional:1.0", "<optional>true</optional>"))
                + "</project>");
        repo.pom("test:lib-a:1.0", dependencies(dependency("test:lib-common:1.0"), dependency("test:lib-x:1.0")));
        repo.pom("test:lib-b:2.0", dependencies(dependency("test:lib-common:2.0", "<scope>runtime</scope>")));

        assertEquals(Arrays.asList("test:app:1.0", "test:lib-a:1.0", "test:lib-b:2.0", "test:lib-common:1.5", "test:lib-sibling:1.0"),
                names(codinject.resolve("test:app:1.0")));
    }

    @Test
    void appliesRequestExclusions() throws IOException {
        repo.pom("test:app:1.0", dependencies(dependency("org.slf4j:slf4j-api:2.0.0"), dependency("test:lib:1.0")));
        repo.jar("test:app:1.0", null).jar("test:lib:1.0", null);

        List<Path> loaded = codinject.dependency("test:app:1.0").exclude("org.slf4j:*").load();

        assertEquals(2, loaded.size());
    }

    @Test
    void skipsJarsForPomPackaging() throws IOException {
        repo.pom("test:platform:1.0", "<packaging>pom</packaging>", dependencies(dependency("test:lib:1.0")));
        assertEquals(Arrays.asList("test:lib:1.0"), names(codinject.resolve("test:platform:1.0")));
    }

    @Test
    void picksLatestReleaseAndRanges() throws IOException {
        repo.write("test/ver/maven-metadata.xml", "<metadata><groupId>test</groupId><artifactId>ver</artifactId><versioning>"
                + "<latest>2.1-SNAPSHOT</latest><release>2.0</release><versions>"
                + "<version>1.0</version><version>1.5</version><version>2.0</version><version>2.1-SNAPSHOT</version>"
                + "</versions></versioning></metadata>");

        assertEquals("2.1-SNAPSHOT", codinject.resolve("test:ver:latest").get(0).getVersion());
        assertEquals("2.0", codinject.resolve("test:ver:release").get(0).getVersion());
        assertEquals("1.5", codinject.resolve("test:ver:[1.0,2.0)").get(0).getVersion());
    }

    @Test
    void resolvesTimestampedSnapshots() throws IOException {
        Artifact snapshot = Artifact.parse("test:snap:1.0-SNAPSHOT").withSnapshotVersion("1.0-20260101.120000-3");
        repo.write("test/snap/1.0-SNAPSHOT/maven-metadata.xml", "<metadata><versioning><snapshot>"
                + "<timestamp>20260101.120000</timestamp><buildNumber>3</buildNumber></snapshot>"
                + "<snapshotVersions><snapshotVersion><extension>jar</extension><value>1.0-20260101.120000-3</value></snapshotVersion>"
                + "<snapshotVersion><extension>pom</extension><value>1.0-20260101.120000-3</value></snapshotVersion>"
                + "</snapshotVersions></versioning></metadata>");
        repo.jar(snapshot, null);

        Path jar = codinject.download("test:snap:1.0-SNAPSHOT");

        assertEquals("snap-1.0-20260101.120000-3.jar", jar.getFileName().toString());
    }

    @Test
    void checksSha1() throws IOException {
        repo.jar("test:good:1.0", null).jar("test:bad:1.0", null);
        Path good = repo.file("test/good/1.0/good-1.0.jar");
        repo.write("test/good/1.0/good-1.0.jar.sha1", Downloader.hex(Downloader.sha1().digest(Files.readAllBytes(good))) + "  good-1.0.jar");
        repo.write("test/bad/1.0/bad-1.0.jar.sha1", "0000000000000000000000000000000000000000");

        assertTrue(Files.exists(codinject.download("test:good:1.0")));
        UncheckedIOException error = assertThrows(UncheckedIOException.class, () -> codinject.download("test:bad:1.0"));
        assertTrue(error.getCause().getMessage().contains("Checksum mismatch"), error.getCause().getMessage());
        assertTrue(Files.notExists(temp.resolve("libs/test/bad/1.0/bad-1.0.jar")));
    }

    @Test
    void failsClearlyWhenSomethingIsMissing() {
        UncheckedIOException error = assertThrows(UncheckedIOException.class, () -> codinject.download("test:missing:1.0"));
        assertTrue(error.getCause().getMessage().contains("Could not find test:missing:1.0"), error.getCause().getMessage());
    }

    private static List<String> names(List<Artifact> artifacts) {
        List<String> names = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            names.add(artifact.toString());
        }
        return names;
    }
}
