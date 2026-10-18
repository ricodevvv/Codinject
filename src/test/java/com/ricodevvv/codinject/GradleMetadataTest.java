package com.ricodevvv.codinject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.ricodevvv.codinject.FakeRepository.dependencies;
import static com.ricodevvv.codinject.FakeRepository.dependency;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleMetadataTest {

    private static final String MARKER = "<!-- do_not_remove: published-with-gradle-metadata -->";

    @TempDir
    Path temp;

    FakeRepository repo;
    Codinject codinject;

    @BeforeEach
    void setUp() {
        repo = new FakeRepository(temp.resolve("repo"));
        codinject = new Codinject(temp.resolve("libs")).clearRepositories().repository(repo.url());
    }

    @AfterEach
    void tearDown() throws IOException {
        codinject.close();
    }

    @Test
    void picksTheJvmRuntimeVariant() throws Exception {
        repo.pom("test:lib:1.0", MARKER, dependencies(dependency("test:from-pom:1.0")));
        repo.write("test/lib/1.0/lib-1.0.module", module("test", "lib", "1.0",
                variant("apiElements", "\"org.gradle.usage\": \"java-api\", \"org.gradle.category\": \"library\"",
                        "[\"lib-1.0.jar\"]", deps("test:api-only:1.0"), "[]"),
                variant("androidRuntimeElements", runtime() + ", \"org.gradle.jvm.environment\": \"android\"",
                        "[\"../1.0-android/lib-1.0-android.jar\"]", deps("test:android-only:1.0"), "[]"),
                variant("futureRuntimeElements", runtime() + ", \"org.gradle.jvm.version\": 99",
                        "[\"lib-1.0-future.jar\"]", deps("test:future-only:1.0"), "[]"),
                variant("jreRuntimeElements", runtime() + ", \"org.gradle.jvm.version\": \"8\", \"org.gradle.jvm.environment\": \"standard-jvm\"",
                        "[\"../1.0-jre/lib-1.0-jre.jar\"]",
                        "[" + dep("test:from-module", "{\"requires\": \"1.0\"}", "\"excludes\": [{\"group\": \"test\", \"module\": \"unwanted\"}]")
                                + ", " + dep("test:managed", "{}", null) + "]",
                        "[" + dep("test:managed", "{\"requires\": \"2.0\"}", null) + "]")));
        repo.pom("test:from-module:1.0", dependencies(dependency("test:unwanted:1.0"), dependency("test:wanted:1.0")));
        repo.write("test/lib/1.0-jre/lib-1.0-jre.jar", "");

        List<Artifact> artifacts = codinject.resolve("test:lib:1.0");

        assertEquals(Arrays.asList("test:lib:1.0", "test:from-module:1.0", "test:managed:2.0", "test:wanted:1.0"), names(artifacts));
        assertEquals("test/lib/1.0-jre/lib-1.0-jre.jar", artifacts.get(0).getPath());
        assertEquals("lib-1.0-jre.jar", codinject.download("test:lib:1.0").getFileName().toString());
    }

    @Test
    void followsAvailableAtLikeKotlinMultiplatform() throws Exception {
        repo.pom("test:kmp:1.0", MARKER);
        repo.write("test/kmp/1.0/kmp-1.0.module", module("test", "kmp", "1.0",
                "{\"name\": \"jsRuntimeElements\", \"attributes\": {\"org.gradle.usage\": \"kotlin-runtime\", \"org.jetbrains.kotlin.platform.type\": \"js\"},"
                        + " \"available-at\": {\"url\": \"../../kmp-js/1.0/kmp-js-1.0.module\", \"group\": \"test\", \"module\": \"kmp-js\", \"version\": \"1.0\"}}",
                "{\"name\": \"jvmRuntimeElements\", \"attributes\": {" + runtime() + ", \"org.jetbrains.kotlin.platform.type\": \"jvm\"},"
                        + " \"available-at\": {\"url\": \"../../kmp-jvm/1.0/kmp-jvm-1.0.module\", \"group\": \"test\", \"module\": \"kmp-jvm\", \"version\": \"1.0\"}}"));
        repo.pom("test:kmp-jvm:1.0", MARKER);
        repo.write("test/kmp-jvm/1.0/kmp-jvm-1.0.module", module("test", "kmp-jvm", "1.0",
                variant("jvmRuntimeElements-published", runtime() + ", \"org.jetbrains.kotlin.platform.type\": \"jvm\"",
                        "[\"kmp-jvm-1.0.jar\"]", deps("test:stdlib:2.0"), "[]")));
        repo.jar("test:kmp-jvm:1.0", "demo.kmp.Hello").jar("test:stdlib:2.0", null);

        codinject.dependency("test:kmp:1.0").load();

        assertEquals(Arrays.asList("test:kmp-jvm:1.0", "test:stdlib:2.0"), names(codinject.resolve("test:kmp:1.0")));
        assertEquals("test:kmp-jvm:1.0", Class.forName("demo.kmp.Hello", true, codinject.getClassLoader()).getMethod("value").invoke(null));
    }

    @Test
    void usesPlatformConstraintsForMissingVersions() throws Exception {
        repo.pom("test:platform:1.0", MARKER, "<packaging>pom</packaging>");
        repo.write("test/platform/1.0/platform-1.0.module", module("test", "platform", "1.0",
                "{\"name\": \"apiElements\", \"attributes\": {\"org.gradle.category\": \"platform\", \"org.gradle.usage\": \"java-runtime\"},"
                        + " \"dependencyConstraints\": [" + dep("test:aligned", "{\"requires\": \"3.0\"}", null) + "]}"));
        repo.pom("test:bom:1.0", "<packaging>pom</packaging>", "<dependencyManagement>"
                + dependencies(dependency("test:from-bom:4.0")) + "</dependencyManagement>");
        repo.pom("test:app:1.0", MARKER);
        repo.write("test/app/1.0/app-1.0.module", module("test", "app", "1.0",
                variant("runtimeElements", runtime(), "[\"app-1.0.jar\"]", "["
                        + dep("test:platform", "{\"requires\": \"1.0\"}", "\"attributes\": {\"org.gradle.category\": \"platform\"}") + ", "
                        + dep("test:bom", "{\"requires\": \"1.0\"}", "\"attributes\": {\"org.gradle.category\": \"platform\"}") + ", "
                        + dep("test:aligned", "{}", null) + ", "
                        + dep("test:from-bom", "{}", null) + "]", "[]")));

        assertEquals(Arrays.asList("test:app:1.0", "test:aligned:3.0", "test:from-bom:4.0"), names(codinject.resolve("test:app:1.0")));
    }

    @Test
    void explainsWhenTheLibraryNeedsANewerJava() throws IOException {
        repo.pom("test:modern:1.0", MARKER);
        repo.write("test/modern/1.0/modern-1.0.module", module("test", "modern", "1.0",
                variant("runtimeElements", runtime() + ", \"org.gradle.jvm.version\": 99", "[\"modern-1.0.jar\"]", "[]", "[]")));

        UncheckedIOException error = assertThrows(UncheckedIOException.class, () -> codinject.resolve("test:modern:1.0"));

        assertTrue(error.getCause().getMessage().contains("needs Java 99 or newer"), error.getCause().getMessage());
    }

    @Test
    void canBeTurnedOff() throws IOException {
        repo.pom("test:lib:1.0", MARKER, dependencies(dependency("test:from-pom:1.0")));
        repo.write("test/lib/1.0/lib-1.0.module", module("test", "lib", "1.0",
                variant("runtimeElements", runtime(), "[\"lib-1.0.jar\"]", deps("test:from-module:1.0"), "[]")));

        assertEquals(Arrays.asList("test:lib:1.0", "test:from-pom:1.0"), names(codinject.gradleMetadata(false).resolve("test:lib:1.0")));
    }

    @Test
    void ignoresModulesWithoutTheMarker() throws IOException {
        repo.pom("test:lib:1.0", dependencies(dependency("test:from-pom:1.0")));
        repo.write("test/lib/1.0/lib-1.0.module", module("test", "lib", "1.0",
                variant("runtimeElements", runtime(), "[\"lib-1.0.jar\"]", deps("test:from-module:1.0"), "[]")));

        assertEquals(Arrays.asList("test:lib:1.0", "test:from-pom:1.0"), names(codinject.resolve("test:lib:1.0")));
    }

    private static String runtime() {
        return "\"org.gradle.usage\": \"java-runtime\", \"org.gradle.category\": \"library\", \"org.gradle.libraryelements\": \"jar\"";
    }

    private static String module(String group, String module, String version, String... variants) {
        return "{\"formatVersion\": \"1.1\", \"component\": {\"group\": \"" + group + "\", \"module\": \"" + module + "\", \"version\": \"" + version + "\"},"
                + " \"createdBy\": {\"gradle\": {\"version\": \"8.10\"}}, \"variants\": [" + String.join(", ", variants) + "]}";
    }

    private static String variant(String name, String attributes, String files, String dependencies, String constraints) {
        StringBuilder fileList = new StringBuilder("[");
        for (String url : files.substring(1, files.length() - 1).split(",")) {
            String trimmed = url.trim();
            if (!trimmed.isEmpty()) {
                fileList.append(fileList.length() > 1 ? ", " : "").append("{\"name\": ").append(trimmed).append(", \"url\": ").append(trimmed).append('}');
            }
        }
        fileList.append(']');
        return "{\"name\": \"" + name + "\", \"attributes\": {" + attributes + "}, \"dependencies\": " + dependencies
                + ", \"dependencyConstraints\": " + constraints + ", \"files\": " + fileList + "}";
    }

    private static String deps(String... coordinates) {
        List<String> entries = new ArrayList<>();
        for (String coordinate : coordinates) {
            Artifact artifact = Artifact.parse(coordinate);
            entries.add(dep(artifact.getGroupId() + ':' + artifact.getArtifactId(), "{\"requires\": \"" + artifact.getVersion() + "\"}", null));
        }
        return "[" + String.join(", ", entries) + "]";
    }

    private static String dep(String module, String version, String extra) {
        String[] parts = module.split(":");
        return "{\"group\": \"" + parts[0] + "\", \"module\": \"" + parts[1] + "\", \"version\": " + version + (extra == null ? "" : ", " + extra) + "}";
    }

    private static List<String> names(List<Artifact> artifacts) {
        List<String> names = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            names.add(artifact.toString());
        }
        return names;
    }
}
