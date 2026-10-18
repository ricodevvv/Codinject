package com.ricodevvv.codinject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A dependency waiting to be loaded. Configure it with the chained methods and finish with {@link #load()}.
 *
 * <pre>{@code
 * codinject.dependency("redis.clients:jedis:5.1.0")
 *         .relocate("redis{}clients{}jedis", "my{}plugin{}libs{}jedis")
 *         .exclude("org.slf4j:*")
 *         .parent(true)
 *         .load();
 * }</pre>
 */
public final class Dependency {

    private final Codinject codinject;
    private final Artifact artifact;
    private final Path file;
    private final List<String> repositories = new ArrayList<>();
    private final Map<String, String> relocations = new LinkedHashMap<>();
    private final Set<String> exclusions = new LinkedHashSet<>();
    private boolean transitive = true;
    private boolean parent;
    private ClassLoader loader;
    private String unlessPresent;

    Dependency(Codinject codinject, Artifact artifact, Path file) {
        this.codinject = codinject;
        this.artifact = artifact;
        this.file = file;
    }

    /**
     * Adds a repository just for this dependency. It's checked before the global ones.
     *
     * @param url repository URL, like {@code https://jitpack.io/}
     * @return this dependency
     */
    public Dependency repository(String url) {
        repositories.add(Downloader.normalize(url));
        return this;
    }

    /**
     * Moves a package to another one inside the loaded jars, transitive ones included.
     * <p>
     * You can write {@code {}} instead of dots, like {@code "com{}google{}gson"}. That stops Gradle Shadow
     * or Maven Shade from rewriting the string when they relocate your own jar.
     *
     * @param from original package
     * @param to   new package
     * @return this dependency
     */
    public Dependency relocate(String from, String to) {
        relocations.put(from.replace("{}", "."), to.replace("{}", "."));
        return this;
    }

    /**
     * Same as {@link #relocate(String, String)} for every entry of the map.
     *
     * @param relocations original package to new package
     * @return this dependency
     */
    public Dependency relocate(Map<String, String> relocations) {
        relocations.forEach(this::relocate);
        return this;
    }

    /**
     * Leaves out transitive dependencies that match {@code group:artifact}. Both sides take {@code *},
     * and a trailing {@code *} works as a prefix, like {@code org.slf4j:*} or {@code io.netty:netty-*}.
     *
     * @param pattern the pattern to exclude
     * @return this dependency
     */
    public Dependency exclude(String pattern) {
        exclusions.add(Objects.requireNonNull(pattern, "pattern"));
        return this;
    }

    /**
     * Whether to also load what this dependency needs at runtime. Enabled by default.
     *
     * @param transitive {@code false} to load only this artifact
     * @return this dependency
     */
    public Dependency transitive(boolean transitive) {
        this.transitive = transitive;
        return this;
    }

    /**
     * Loads into the target class loader of {@link Codinject} instead of the isolated one.
     *
     * @param parent {@code true} to use the target class loader
     * @return this dependency
     * @see Codinject#target(ClassLoader)
     */
    public Dependency parent(boolean parent) {
        this.parent = parent;
        return this;
    }

    /**
     * Loads into a specific class loader. Wins over {@link #parent(boolean)}.
     *
     * @param loader the class loader to add the jars to
     * @return this dependency
     */
    public Dependency into(ClassLoader loader) {
        this.loader = loader;
        return this;
    }

    /**
     * Skips the whole thing when this class can already be loaded, handy when the server or platform
     * might ship the library already.
     *
     * @param className fully qualified class name; {@code {}} works as a dot here too
     * @return this dependency
     */
    public Dependency unlessPresent(String className) {
        this.unlessPresent = className.replace("{}", ".");
        return this;
    }

    /**
     * Downloads, relocates and loads the dependency.
     *
     * @return the jars that ended in the class loader, already relocated if you asked for it
     * @throws java.io.UncheckedIOException if something can't be downloaded, relocated or added
     */
    public List<Path> load() {
        return codinject.load(this);
    }

    Artifact getArtifact() {
        return artifact;
    }

    Path getFile() {
        return file;
    }

    List<String> getRepositories() {
        return Collections.unmodifiableList(repositories);
    }

    Map<String, String> getRelocations() {
        return Collections.unmodifiableMap(relocations);
    }

    Set<String> getExclusions() {
        return Collections.unmodifiableSet(exclusions);
    }

    boolean isTransitive() {
        return transitive;
    }

    boolean isParent() {
        return parent;
    }

    ClassLoader getLoader() {
        return loader;
    }

    String getUnlessPresent() {
        return unlessPresent;
    }

    @Override
    public String toString() {
        return artifact != null ? artifact.toString() : file.toString();
    }
}
