package com.ricodevvv.codinject;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Downloads Maven dependencies at runtime and puts them in a class loader, from Java 8 to Java 25.
 *
 * <pre>{@code
 * Codinject codinject = new Codinject(Paths.get("libs"));
 *
 * codinject.dependency("com.google.code.gson:gson:2.11.0").load();
 *
 * codinject.dependency("redis.clients:jedis:5.1.0")
 *         .relocate("redis{}clients{}jedis", "my{}plugin{}libs{}jedis")
 *         .parent(true)
 *         .load();
 * }</pre>
 *
 * By default jars go to an isolated {@link LibraryClassLoader}, which works everywhere with no tricks.
 * With {@link Dependency#parent(boolean)} they go to the {@linkplain #target(ClassLoader) target} class loader instead,
 * see {@link CodinjectAgent} for what that takes on newer Java versions.
 */
public final class Codinject implements AutoCloseable {

    /**
     * Maven Central, the only repository configured out of the box.
     */
    public static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/";

    private final Path folder;
    private final List<String> repositories = new CopyOnWriteArrayList<>(Collections.singletonList(MAVEN_CENTRAL));
    private final Map<ClassLoader, Map<String, String>> loaded = new WeakHashMap<>();
    private volatile ClassLoader target = Codinject.class.getClassLoader();
    private volatile Logger logger = Logger.getLogger("Codinject");
    private volatile boolean verifyChecksums = true;
    private volatile boolean allowUnsafe = true;
    private LibraryClassLoader classLoader;
    private Resolver resolver;
    private Relocator relocator;

    /**
     * Uses a {@code libs} folder in the working directory.
     */
    public Codinject() {
        this(Paths.get("libs"));
    }

    /**
     * Uses the given folder to keep downloads.
     *
     * @param folder where jars and POMs are saved
     */
    public Codinject(File folder) {
        this(folder.toPath());
    }

    /**
     * Uses the given folder to keep downloads.
     *
     * @param folder where jars and POMs are saved
     */
    public Codinject(Path folder) {
        this.folder = Objects.requireNonNull(folder, "folder").toAbsolutePath();
    }

    /**
     * Adds a repository at the end of the list. Maven Central is already there.
     *
     * @param url repository URL, {@code https://}, {@code http://} or {@code file:}
     * @return this instance
     */
    public Codinject repository(String url) {
        String normalized = Downloader.normalize(url);
        if (!repositories.contains(normalized)) {
            repositories.add(normalized);
        }
        return this;
    }

    /**
     * Removes every repository, Maven Central included. Handy if you only want to use a mirror.
     *
     * @return this instance
     */
    public Codinject clearRepositories() {
        repositories.clear();
        return this;
    }

    /**
     * Class loader used by {@link Dependency#parent(boolean)} and as parent of the isolated one.
     * Defaults to the loader that loaded Codinject, which is your plugin or app loader.
     *
     * @param target the class loader
     * @return this instance
     */
    public Codinject target(ClassLoader target) {
        this.target = Objects.requireNonNull(target, "target");
        return this;
    }

    /**
     * Logger for download and warning messages.
     *
     * @param logger the logger
     * @return this instance
     */
    public synchronized Codinject logger(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.resolver = null;
        return this;
    }

    /**
     * Whether downloads are checked against the {@code .sha1} published next to them. Enabled by default.
     *
     * @param verifyChecksums {@code false} to skip the check
     * @return this instance
     */
    public synchronized Codinject verifyChecksums(boolean verifyChecksums) {
        this.verifyChecksums = verifyChecksums;
        this.resolver = null;
        return this;
    }

    /**
     * Whether {@code sun.misc.Unsafe} can be used as last resort to add jars to a class loader we don't own.
     * Enabled by default. Java 24+ prints a warning when it's used; load {@link CodinjectAgent} or add
     * {@code --add-opens} to avoid it, or disable this to get an error instead.
     *
     * @param allowUnsafe {@code false} to never touch Unsafe
     * @return this instance
     */
    public Codinject allowUnsafe(boolean allowUnsafe) {
        this.allowUnsafe = allowUnsafe;
        return this;
    }

    public Path getFolder() {
        return folder;
    }

    public List<String> getRepositories() {
        return Collections.unmodifiableList(new ArrayList<>(repositories));
    }

    public ClassLoader getTarget() {
        return target;
    }

    public Logger getLogger() {
        return logger;
    }

    /**
     * The isolated class loader, created on first use with the current {@linkplain #target(ClassLoader) target} as parent.
     *
     * @return the isolated class loader
     */
    public synchronized LibraryClassLoader getClassLoader() {
        if (classLoader == null) {
            classLoader = new LibraryClassLoader(target);
        }
        return classLoader;
    }

    /**
     * Starts a dependency from Maven coordinates.
     *
     * @param coordinates {@code group:artifact:version[:classifier][@extension]}
     * @return the dependency, ready to configure and {@link Dependency#load() load}
     */
    public Dependency dependency(String coordinates) {
        return new Dependency(this, Artifact.parse(coordinates), null);
    }

    /**
     * Starts a dependency from a jar you already have. It can be relocated and loaded like any other.
     *
     * @param jar the jar file
     * @return the dependency, ready to configure and {@link Dependency#load() load}
     */
    public Dependency dependency(Path jar) {
        return new Dependency(this, null, Objects.requireNonNull(jar, "jar").toAbsolutePath());
    }

    /**
     * Resolves the full runtime tree of an artifact without loading anything.
     *
     * @param coordinates {@code group:artifact:version[:classifier][@extension]}
     * @return every artifact that would be loaded, root first
     * @throws UncheckedIOException if a POM or version can't be found
     */
    public synchronized List<Artifact> resolve(String coordinates) {
        try {
            return resolver().resolve(Artifact.parse(coordinates), Collections.<String>emptySet(), true, repositories);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Downloads a single jar, without its dependencies, and returns where it was saved.
     *
     * @param coordinates {@code group:artifact:version[:classifier][@extension]}
     * @return the downloaded file
     * @throws UncheckedIOException if it can't be downloaded
     */
    public synchronized Path download(String coordinates) {
        try {
            List<Artifact> artifacts = resolver().resolve(Artifact.parse(coordinates), Collections.<String>emptySet(), false, repositories);
            if (artifacts.isEmpty()) {
                throw new IOException(coordinates + " is a POM only artifact, there is no jar to download");
            }
            return resolver().file(artifacts.get(0), repositories);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    synchronized List<Path> load(Dependency dependency) {
        ClassLoader loader = dependency.getLoader() != null ? dependency.getLoader() : dependency.isParent() ? target : getClassLoader();
        String unlessPresent = dependency.getUnlessPresent();
        if (unlessPresent != null && isPresent(unlessPresent, loader)) {
            logger.fine("Skipping " + dependency + ", " + unlessPresent + " is already present");
            return Collections.emptyList();
        }
        try {
            Map<String, String> injected = loaded.computeIfAbsent(loader, key -> new HashMap<>());
            Map<String, Path> jars = new HashMap<>();
            List<String> keys = new ArrayList<>();
            Map<String, String> versions = new HashMap<>();

            if (dependency.getFile() != null) {
                String key = dependency.getFile().toString();
                keys.add(key);
                versions.put(key, "");
                jars.put(key, dependency.getFile());
            } else {
                Set<String> repos = new LinkedHashSet<>(dependency.getRepositories());
                repos.addAll(repositories);
                Resolver resolver = resolver();
                for (Artifact artifact : resolver.resolve(dependency.getArtifact(), dependency.getExclusions(), dependency.isTransitive(), repos)) {
                    String previous = injected.get(artifact.getKey());
                    if (previous != null) {
                        if (!previous.equals(artifact.getFileVersion())) {
                            logger.warning(artifact.getKey() + ' ' + previous + " is already loaded, ignoring " + artifact.getFileVersion());
                        }
                        continue;
                    }
                    keys.add(artifact.getKey());
                    versions.put(artifact.getKey(), artifact.getFileVersion());
                    jars.put(artifact.getKey(), resolver.file(artifact, repos));
                }
            }

            if (!dependency.getRelocations().isEmpty()) {
                for (String key : keys) {
                    jars.put(key, relocator().relocate(jars.get(key), dependency.getRelocations()));
                }
            }

            List<Path> result = new ArrayList<>();
            Injector injector = new Injector(logger, allowUnsafe);
            for (String key : keys) {
                if (injected.containsKey(key)) {
                    continue;
                }
                injector.inject(loader, jars.get(key));
                injected.put(key, versions.get(key));
                result.add(jars.get(key));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load " + dependency, e);
        }
    }

    private static boolean isPresent(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private Resolver resolver() {
        if (resolver == null) {
            resolver = new Resolver(folder, new Downloader(logger, verifyChecksums), logger);
        }
        return resolver;
    }

    private Relocator relocator() {
        if (relocator == null) {
            relocator = new Relocator(folder, this::toolJars);
        }
        return relocator;
    }

    private synchronized List<Path> toolJars() throws IOException {
        Set<String> repos = new LinkedHashSet<>(repositories);
        repos.add(MAVEN_CENTRAL);
        List<Path> jars = new ArrayList<>();
        for (String coordinates : Relocator.TOOLS) {
            jars.add(resolver().file(Artifact.parse(coordinates), repos));
        }
        return jars;
    }

    /**
     * Closes the isolated class loader and the relocation tools. Jars added to other class loaders stay there.
     *
     * @throws IOException if a class loader fails to close
     */
    @Override
    public synchronized void close() throws IOException {
        if (relocator != null) {
            relocator.close();
            relocator = null;
        }
        if (classLoader != null) {
            classLoader.close();
            classLoader = null;
        }
    }
}
