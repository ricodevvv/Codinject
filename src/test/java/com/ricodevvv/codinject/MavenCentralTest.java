package com.ricodevvv.codinject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real downloads from Maven Central. Skipped when there's no network.
 */
class MavenCentralTest {

    @TempDir
    Path temp;

    Codinject codinject;

    @BeforeEach
    void setUp() {
        assumeTrue(online(), "Maven Central is not reachable");
        codinject = new Codinject(temp);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (codinject != null) {
            codinject.close();
        }
    }

    @Test
    void resolvesJedisWithItsTransitiveDependencies() {
        List<String> keys = new ArrayList<>();
        for (Artifact artifact : codinject.resolve("redis.clients:jedis:5.1.0")) {
            keys.add(artifact.getKey());
        }
        assertEquals("redis.clients:jedis", keys.get(0));
        assertTrue(keys.contains("org.slf4j:slf4j-api"), keys.toString());
        assertTrue(keys.contains("org.apache.commons:commons-pool2"), keys.toString());
        assertTrue(keys.contains("org.json:json"), keys.toString());
        assertTrue(keys.contains("com.google.code.gson:gson"), keys.toString());
    }

    @Test
    void resolvesThroughParentsAndBoms() {
        List<String> keys = new ArrayList<>();
        for (Artifact artifact : codinject.resolve("com.fasterxml.jackson.core:jackson-databind:2.17.2")) {
            keys.add(artifact.toString());
        }
        assertTrue(keys.contains("com.fasterxml.jackson.core:jackson-annotations:2.17.2"), keys.toString());
        assertTrue(keys.contains("com.fasterxml.jackson.core:jackson-core:2.17.2"), keys.toString());
    }

    @Test
    void loadsAndRelocatesGson() throws Exception {
        codinject.dependency("com.google.code.gson:gson:2.11.0")
                .transitive(false)
                .relocate("com{}google{}gson", "my{}libs{}gson")
                .load();

        Class<?> gsonType = Class.forName("my.libs.gson.Gson", true, codinject.getClassLoader());
        Object gson = gsonType.getConstructor().newInstance();
        assertEquals("[1,2,3]", gsonType.getMethod("toJson", Object.class).invoke(gson, new int[] {1, 2, 3}));
    }

    @Test
    void picksLatestFromMetadata() {
        Artifact artifact = codinject.resolve("com.google.code.gson:gson:release").get(0);
        assertTrue(Versions.compare(artifact.getVersion(), "2.11.0") >= 0, artifact.toString());
    }

    private static boolean online() {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(Codinject.MAVEN_CENTRAL).openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            connection.setRequestMethod("HEAD");
            return connection.getResponseCode() < 500;
        } catch (IOException e) {
            return false;
        }
    }
}
