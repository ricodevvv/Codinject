package com.ricodevvv.codinject;

import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Walks the POMs to find the runtime dependency tree and downloads the files into the local folder,
 * using the same layout as {@code ~/.m2/repository}. When a POM says it was published with Gradle module
 * metadata, the {@code .module} file is used instead, like Gradle itself does.
 * <p>
 * Conflicts are solved like Maven: the declaration closest to the root wins.
 */
final class Resolver {

    private static final Set<String> SCOPES = new HashSet<>(Arrays.asList("compile", "runtime"));
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList("jar", "bundle"));
    private static final int MAX_DEPTH = 32;

    private final Path folder;
    private final Downloader downloader;
    private final Logger logger;
    private final boolean gradleMetadata;
    private final Map<Artifact, Artifact> versions = new HashMap<>();
    private final Map<Artifact, Pom> inherited = new HashMap<>();
    private final Map<Artifact, Pom> effective = new HashMap<>();
    private final Map<Artifact, GradleModule> modules = new HashMap<>();
    private final Map<String, String> origins = new HashMap<>();

    Resolver(Path folder, Downloader downloader, Logger logger, boolean gradleMetadata) {
        this.folder = folder;
        this.downloader = downloader;
        this.logger = logger;
        this.gradleMetadata = gradleMetadata;
    }

    /**
     * Resolves the artifact and, when asked, everything it needs at runtime.
     *
     * @param root         the requested artifact
     * @param exclusions   {@code group:artifact} patterns to leave out, {@code *} works as a wildcard
     * @param transitive   {@code false} to only resolve the root
     * @param repositories repositories to look into, in order
     * @return the artifacts that have a file to load, root first
     */
    List<Artifact> resolve(Artifact root, Collection<String> exclusions, boolean transitive, Collection<String> repositories) throws IOException {
        Set<String> repos = new LinkedHashSet<>(repositories);
        Set<String> resolved = new HashSet<>();
        List<Artifact> result = new ArrayList<>();
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(root, new LinkedHashSet<>(exclusions)));
        Pom rootPom = null;

        while (!queue.isEmpty()) {
            Node node = queue.poll();
            if (!resolved.add(node.artifact.getKey())) {
                continue;
            }
            Component component = component(concrete(node.artifact, repos), repos, 0);
            Artifact artifact = component.artifact;
            if (!artifact.getKey().equals(node.artifact.getKey()) && !resolved.add(artifact.getKey())) {
                continue;
            }
            Pom pom = component.pom;
            if (rootPom == null) {
                rootPom = pom;
            }
            if (component.hasFile) {
                result.add(artifact);
            }
            if (!transitive) {
                break;
            }
            for (String repository : pom.repositories) {
                if (repository.startsWith("https://")) {
                    repos.add(Downloader.normalize(repository));
                }
            }
            for (Pom.Dependency dependency : pom.dependencies) {
                if (!includes(dependency) || excluded(node.exclusions, dependency)) {
                    continue;
                }
                Pom.Dependency managed = rootPom.managed(dependency);
                String version = managed != null && managed.version != null ? managed.version : dependency.version;
                if (version == null) {
                    logger.warning("Skipping " + dependency.groupId + ':' + dependency.artifactId + " from " + artifact + ", it has no version");
                    continue;
                }
                Set<String> nested = new LinkedHashSet<>(node.exclusions);
                nested.addAll(dependency.exclusions);
                queue.add(new Node(new Artifact(dependency.groupId, dependency.artifactId, version, dependency.classifier, "jar"), nested));
            }
        }
        return result;
    }

    /**
     * Gets the file of an already resolved artifact, downloading it if it's not in the local folder yet.
     */
    Path file(Artifact artifact, Collection<String> repositories) throws IOException {
        Path file = folder.resolve(artifact.getPath());
        if (Files.exists(file)) {
            return file;
        }
        if (fetch(artifact, artifact.getPath(), file, repositories) == null) {
            throw new IOException("Could not find " + artifact + " in " + repositories);
        }
        return file;
    }

    private static boolean includes(Pom.Dependency dependency) {
        return (dependency.scope == null || SCOPES.contains(dependency.scope))
                && (dependency.type == null || TYPES.contains(dependency.type))
                && !"true".equals(dependency.optional)
                && dependency.groupId != null
                && dependency.artifactId != null;
    }

    static boolean excluded(Collection<String> exclusions, Pom.Dependency dependency) {
        for (String exclusion : exclusions) {
            int colon = exclusion.indexOf(':');
            String group = colon < 0 ? exclusion : exclusion.substring(0, colon);
            String artifact = colon < 0 ? "*" : exclusion.substring(colon + 1);
            if (matches(group, dependency.groupId) && matches(artifact, dependency.artifactId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String pattern, String value) {
        if (pattern.equals("*")) {
            return true;
        }
        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(value);
    }

    private Artifact concrete(Artifact artifact, Set<String> repos) throws IOException {
        Artifact cached = versions.get(artifact);
        if (cached != null) {
            return cached;
        }
        Artifact result = artifact;
        if (Versions.isDynamic(artifact.getVersion()) || Versions.isRange(artifact.getVersion())) {
            result = artifact.withVersion(pickVersion(artifact, repos));
        }
        if (result.isSnapshot()) {
            result = snapshot(result, repos);
        }
        versions.put(artifact, result);
        return result;
    }

    private String pickVersion(Artifact artifact, Set<String> repos) throws IOException {
        String path = artifact.getGroupId().replace('.', '/') + '/' + artifact.getArtifactId() + "/maven-metadata.xml";
        List<String> available = new ArrayList<>();
        String release = null;
        String latest = null;
        for (String repository : repos) {
            Element metadata = metadata(repository, path);
            Element versioning = Xml.child(metadata, "versioning");
            if (versioning == null) {
                continue;
            }
            release = highest(release, Xml.text(versioning, "release"));
            latest = highest(latest, Xml.text(versioning, "latest"));
            for (Element version : Xml.children(Xml.child(versioning, "versions"), "version")) {
                available.add(version.getTextContent().trim());
            }
        }

        String requested = artifact.getVersion();
        String picked;
        if ("latest".equalsIgnoreCase(requested)) {
            picked = latest;
            for (String version : available) {
                picked = highest(picked, version);
            }
        } else if ("release".equalsIgnoreCase(requested)) {
            picked = release;
            for (String version : available) {
                if (!version.endsWith("-SNAPSHOT")) {
                    picked = highest(picked, version);
                }
            }
        } else {
            picked = Versions.pick(requested, available);
            if (picked == null && available.isEmpty()) {
                picked = Versions.lowerBound(requested);
            }
        }
        if (picked == null) {
            throw new IOException("Could not find a version of " + artifact.getGroupId() + ':' + artifact.getArtifactId() + " matching '" + requested + "' in " + repos);
        }
        logger.fine("Picked " + picked + " for " + artifact);
        return picked;
    }

    private static String highest(String current, String candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || Versions.compare(candidate, current) > 0 ? candidate : current;
    }

    private Artifact snapshot(Artifact artifact, Set<String> repos) throws IOException {
        for (String repository : repos) {
            Element versioning = Xml.child(metadata(repository, artifact.getDirectory() + "/maven-metadata.xml"), "versioning");
            if (versioning == null) {
                continue;
            }
            for (Element entry : Xml.children(Xml.child(versioning, "snapshotVersions"), "snapshotVersion")) {
                if (artifact.getExtension().equals(Xml.text(entry, "extension"))
                        && Objects.equals(artifact.getClassifier(), Xml.text(entry, "classifier"))) {
                    origins.put(artifact.getDirectory(), repository);
                    return artifact.withSnapshotVersion(Xml.text(entry, "value"));
                }
            }
            Element snapshot = Xml.child(versioning, "snapshot");
            String timestamp = Xml.text(snapshot, "timestamp");
            String build = Xml.text(snapshot, "buildNumber");
            if (timestamp != null && build != null) {
                String base = artifact.getVersion().substring(0, artifact.getVersion().length() - "SNAPSHOT".length());
                origins.put(artifact.getDirectory(), repository);
                return artifact.withSnapshotVersion(base + timestamp + '-' + build);
            }
        }
        return artifact;
    }

    private Element metadata(String repository, String path) {
        try {
            byte[] data = downloader.read(repository, path);
            return data == null ? null : Xml.parse(data, repository + path);
        } catch (IOException e) {
            logger.fine("Could not read " + repository + path + ": " + e);
            return null;
        }
    }

    private Component component(Artifact artifact, Set<String> repos, int depth) throws IOException {
        Pom pom = effective(artifact, repos, 0);
        if (gradleMetadata && (pom.gradleMetadata || pom.missing) && artifact.getClassifier() == null && "jar".equals(artifact.getExtension())) {
            Component component = gradle(artifact, repos, depth);
            if (component != null) {
                return component;
            }
        }
        return new Component(artifact, !"pom".equals(pom.packaging) || !"jar".equals(artifact.getExtension()), pom);
    }

    private Component gradle(Artifact artifact, Set<String> repos, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("Too many available-at redirections at " + artifact);
        }
        GradleModule module = module(artifact, repos);
        Map<String, Object> variant = module == null ? null : module.runtimeVariant(Injector.JAVA_VERSION);
        if (variant == null) {
            return null;
        }
        Artifact target = GradleModule.availableAt(variant);
        if (target != null) {
            return component(concrete(target, repos), repos, depth + 1);
        }

        Pom pom = Pom.empty(artifact);
        pom.missing = false;
        pom.management.addAll(GradleModule.constraints(variant));
        for (Pom.Dependency platform : GradleModule.dependencies(variant, true)) {
            for (Pom.Dependency managed : platform(platform, repos)) {
                if (pom.managed(managed) == null) {
                    pom.management.add(managed);
                }
            }
        }
        for (Pom.Dependency dependency : GradleModule.dependencies(variant, false)) {
            Pom.Dependency managed = pom.managed(dependency);
            if (dependency.version == null && managed != null) {
                dependency.version = managed.version;
            }
            pom.dependencies.add(dependency);
        }

        List<String> jars = GradleModule.jars(variant);
        if (jars.size() > 1) {
            logger.warning(artifact + " has " + jars.size() + " jars in its runtime variant, only " + jars.get(0) + " is loaded");
        }
        return new Component(jars.isEmpty() ? artifact : artifact.withFile(jars.get(0)), !jars.isEmpty(), pom);
    }

    private List<Pom.Dependency> platform(Pom.Dependency platform, Set<String> repos) throws IOException {
        if (platform.version == null) {
            return Collections.emptyList();
        }
        Artifact artifact = concrete(new Artifact(platform.groupId, platform.artifactId, platform.version, null, "pom"), repos);
        Pom pom = effective(artifact, repos, 0);
        if (pom.gradleMetadata || pom.missing) {
            GradleModule module = module(artifact, repos);
            Map<String, Object> variant = module == null ? null : module.platformVariant();
            if (variant != null) {
                return GradleModule.constraints(variant);
            }
        }
        return pom.management;
    }

    private GradleModule module(Artifact artifact, Set<String> repos) throws IOException {
        if (modules.containsKey(artifact)) {
            return modules.get(artifact);
        }
        Path file = folder.resolve(artifact.getModulePath());
        GradleModule module = null;
        if (Files.exists(file) || fetch(artifact, artifact.getModulePath(), file, repos) != null) {
            module = GradleModule.parse(file);
        }
        modules.put(artifact, module);
        return module;
    }

    private Pom effective(Artifact artifact, Set<String> repos, int depth) throws IOException {
        Pom cached = effective.get(artifact);
        if (cached != null) {
            return cached;
        }
        if (depth > MAX_DEPTH) {
            throw new IOException("Too many nested POM imports at " + artifact);
        }
        Pom raw = inherited(artifact, repos, 0);
        Map<String, String> properties = new HashMap<>(raw.properties);
        for (String prefix : new String[] {"project.", "pom.", ""}) {
            put(properties, prefix + "groupId", raw.groupId);
            put(properties, prefix + "artifactId", raw.artifactId);
            put(properties, prefix + "version", raw.version);
        }
        if (raw.parent != null) {
            put(properties, "project.parent.groupId", raw.parent.getGroupId());
            put(properties, "project.parent.artifactId", raw.parent.getArtifactId());
            put(properties, "project.parent.version", raw.parent.getVersion());
            put(properties, "parent.version", raw.parent.getVersion());
        }

        Pom pom = new Pom();
        pom.groupId = raw.groupId;
        pom.artifactId = raw.artifactId;
        pom.version = raw.version;
        pom.packaging = Pom.interpolate(raw.packaging, properties);
        pom.parent = raw.parent;
        pom.gradleMetadata = raw.gradleMetadata;
        pom.missing = raw.missing;
        pom.properties.putAll(properties);
        pom.repositories.addAll(raw.repositories);

        List<Pom.Dependency> imports = new ArrayList<>();
        for (Pom.Dependency dependency : raw.management) {
            Pom.Dependency interpolated = dependency.interpolate(properties);
            if ("import".equals(interpolated.scope) && "pom".equals(interpolated.type)) {
                imports.add(interpolated);
            } else {
                pom.management.add(interpolated);
            }
        }
        for (Pom.Dependency bom : imports) {
            Artifact bomArtifact = concrete(new Artifact(bom.groupId, bom.artifactId, bom.version, null, "pom"), repos);
            for (Pom.Dependency managed : effective(bomArtifact, repos, depth + 1).management) {
                if (pom.managed(managed) == null) {
                    pom.management.add(managed);
                }
            }
        }

        for (Pom.Dependency dependency : raw.dependencies) {
            Pom.Dependency interpolated = dependency.interpolate(properties);
            Pom.Dependency managed = pom.managed(interpolated);
            if (managed != null) {
                if (interpolated.version == null) {
                    interpolated.version = managed.version;
                }
                if (interpolated.scope == null) {
                    interpolated.scope = managed.scope;
                }
                if (interpolated.optional == null) {
                    interpolated.optional = managed.optional;
                }
                interpolated.exclusions.addAll(managed.exclusions);
            }
            pom.dependencies.add(interpolated);
        }
        effective.put(artifact, pom);
        return pom;
    }

    private static void put(Map<String, String> properties, String key, String value) {
        if (value != null) {
            properties.put(key, value);
        }
    }

    private Pom inherited(Artifact artifact, Set<String> repos, int depth) throws IOException {
        Pom cached = inherited.get(artifact);
        if (cached != null) {
            return cached;
        }
        if (depth > MAX_DEPTH) {
            throw new IOException("Too many parent POMs at " + artifact);
        }
        Pom pom = read(artifact, repos);
        if (pom == null) {
            logger.fine("No POM found for " + artifact + ", assuming it has no dependencies");
            pom = Pom.empty(artifact);
        } else if (pom.parent != null) {
            pom = pom.inherit(inherited(concrete(pom.parent, repos), repos, depth + 1));
        }
        inherited.put(artifact, pom);
        return pom;
    }

    private Pom read(Artifact artifact, Set<String> repos) throws IOException {
        Path file = folder.resolve(artifact.getPomPath());
        if (!Files.exists(file) && fetch(artifact, artifact.getPomPath(), file, repos) == null) {
            return null;
        }
        return Pom.parse(file);
    }

    private String fetch(Artifact artifact, String path, Path file, Collection<String> repositories) throws IOException {
        Set<String> ordered = new LinkedHashSet<>();
        String origin = origins.get(artifact.getDirectory());
        if (origin != null) {
            ordered.add(origin);
        }
        ordered.addAll(repositories);

        IOException failure = null;
        for (String repository : ordered) {
            try {
                if (downloader.download(repository, path, file)) {
                    origins.put(artifact.getDirectory(), repository);
                    return repository;
                }
            } catch (IOException e) {
                if (failure == null) {
                    failure = new IOException("Could not download " + path + ": " + e.getMessage(), e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return null;
    }

    private static final class Component {

        final Artifact artifact;
        final boolean hasFile;
        final Pom pom;

        Component(Artifact artifact, boolean hasFile, Pom pom) {
            this.artifact = artifact;
            this.hasFile = hasFile;
            this.pom = pom;
        }
    }

    private static final class Node {

        final Artifact artifact;
        final Set<String> exclusions;

        Node(Artifact artifact, Set<String> exclusions) {
            this.artifact = artifact;
            this.exclusions = exclusions;
        }
    }
}
