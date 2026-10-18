package com.ricodevvv.codinject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class InjectionTest {

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
    void loadsIntoTheIsolatedClassLoaderByDefault() throws Exception {
        repo.jar("test:isolated:1.0", "demo.isolated.Hello");

        List<Path> loaded = codinject.dependency("test:isolated:1.0").load();

        assertEquals(1, loaded.size());
        assertEquals("test:isolated:1.0", call("demo.isolated.Hello", codinject.getClassLoader()));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("demo.isolated.Hello", false, getClass().getClassLoader()));
    }

    @Test
    void loadsIntoAnUrlClassLoaderWeDontOwn() throws Exception {
        repo.jar("test:foreign:1.0", "demo.foreign.Hello");
        try (URLClassLoader foreign = new URLClassLoader(new URL[0], null)) {
            codinject.dependency("test:foreign:1.0").into(foreign).load();
            assertEquals("test:foreign:1.0", call("demo.foreign.Hello", foreign));
        }
    }

    @Test
    void loadsIntoTheSystemClassLoader() throws Exception {
        repo.jar("test:system:1.0", "demo.system.Hello");

        codinject.dependency("test:system:1.0").into(ClassLoader.getSystemClassLoader()).load();

        assertEquals("test:system:1.0", call("demo.system.Hello", ClassLoader.getSystemClassLoader()));
    }

    @Test
    void loadsIntoTheTargetWithParent() throws Exception {
        repo.jar("test:target:1.0", "demo.target.Hello");
        try (URLClassLoader target = new URLClassLoader(new URL[0], null)) {
            codinject.target(target).dependency("test:target:1.0").parent(true).load();
            assertEquals("test:target:1.0", call("demo.target.Hello", target));
        }
    }

    @Test
    void refusesWithoutUnsafeWhenTheJdkIsClosed() throws Exception {
        assumeTrue(Injector.JAVA_VERSION >= 16, "java.net is open to reflection before Java 16");
        assumeTrue(CodinjectAgent.getInstrumentation() == null);
        repo.jar("test:closed:1.0", "demo.closed.Hello");
        try (URLClassLoader foreign = new URLClassLoader(new URL[0], null)) {
            Dependency dependency = codinject.allowUnsafe(false).dependency("test:closed:1.0").into(foreign);
            UncheckedIOException error = assertThrows(UncheckedIOException.class, dependency::load);
            assertTrue(error.getCause().getMessage().contains("--add-opens"), error.getCause().getMessage());
        }
    }

    @Test
    void loadsLocalJarsAndSkipsDuplicates() throws Exception {
        repo.jar("test:local:1.0", "demo.local.Hello");
        Path jar = repo.file("test/local/1.0/local-1.0.jar");

        assertEquals(1, codinject.dependency(jar).load().size());
        assertEquals(0, codinject.dependency(jar).load().size());
        assertEquals("test:local:1.0", call("demo.local.Hello", codinject.getClassLoader()));
    }

    @Test
    void skipsWhenTheClassIsAlreadyThere() {
        assertTrue(codinject.dependency("test:never-downloaded:1.0").unlessPresent("java{}lang{}String").load().isEmpty());
    }

    private static Object call(String className, ClassLoader loader) throws Exception {
        return Class.forName(className, true, loader).getMethod("value").invoke(null);
    }
}
