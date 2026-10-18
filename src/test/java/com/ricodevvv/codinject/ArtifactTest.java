package com.ricodevvv.codinject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactTest {

    @Test
    void parsesPlainCoordinates() {
        Artifact artifact = Artifact.parse("com.google.code.gson:gson:2.11.0");
        assertEquals("com.google.code.gson", artifact.getGroupId());
        assertEquals("gson", artifact.getArtifactId());
        assertEquals("2.11.0", artifact.getVersion());
        assertNull(artifact.getClassifier());
        assertEquals("jar", artifact.getExtension());
        assertEquals("com/google/code/gson/gson/2.11.0/gson-2.11.0.jar", artifact.getPath());
        assertEquals("com/google/code/gson/gson/2.11.0/gson-2.11.0.pom", artifact.getPomPath());
    }

    @Test
    void parsesClassifierAndExtension() {
        Artifact artifact = Artifact.parse("io.netty:netty-transport-native-epoll:4.1.100.Final:linux-x86_64@jar");
        assertEquals("linux-x86_64", artifact.getClassifier());
        assertEquals("io/netty/netty-transport-native-epoll/4.1.100.Final/netty-transport-native-epoll-4.1.100.Final-linux-x86_64.jar", artifact.getPath());
        assertEquals("io.netty:netty-transport-native-epoll:linux-x86_64", artifact.getKey());

        Artifact zip = Artifact.parse("org.example:bundle:1.0@zip");
        assertEquals("org/example/bundle/1.0/bundle-1.0.zip", zip.getPath());
        assertEquals("org.example:bundle:1.0@zip", zip.toString());
    }

    @Test
    void snapshotsUseTheTimestampInFileNames() {
        Artifact artifact = Artifact.parse("org.example:lib:1.0-SNAPSHOT").withSnapshotVersion("1.0-20260101.120000-3");
        assertTrue(artifact.isSnapshot());
        assertEquals("org/example/lib/1.0-SNAPSHOT/lib-1.0-20260101.120000-3.jar", artifact.getPath());
    }

    @Test
    void rejectsBrokenCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> Artifact.parse("gson"));
        assertThrows(IllegalArgumentException.class, () -> Artifact.parse("com.google.code.gson:gson"));
        assertThrows(IllegalArgumentException.class, () -> Artifact.parse("com.google.code.gson::2.11.0"));
        assertThrows(IllegalArgumentException.class, () -> Artifact.parse("a:b:c:d:e"));
    }
}
