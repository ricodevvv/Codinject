package com.ricodevvv.codinject;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Gradle module metadata, the {@code .module} file Gradle publishes next to the POM. It describes variants
 * (api, runtime, sources, Android, each Kotlin target...) and Codinject picks the one a plain JVM would use at runtime.
 */
final class GradleModule {

    static final String MARKER = "published-with-gradle-metadata";

    private final String group;
    private final String module;
    private final String version;
    private final List<Map<String, Object>> variants = new ArrayList<>();

    private GradleModule(String group, String module, String version) {
        this.group = group;
        this.module = module;
        this.version = version;
    }

    static GradleModule parse(Path file) throws IOException {
        Map<String, Object> root = object(Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
        String format = string(root.get("formatVersion"));
        if (format == null || !format.startsWith("1.")) {
            throw new IOException("Unsupported Gradle metadata format " + format + " in " + file);
        }
        Map<String, Object> component = object(root.get("component"));
        GradleModule module = new GradleModule(string(component.get("group")), string(component.get("module")), string(component.get("version")));
        for (Object variant : array(root.get("variants"))) {
            module.variants.add(object(variant));
        }
        return module;
    }

    /**
     * Picks the runtime variant for a standard JVM: a library with {@code java-runtime} usage, not Android,
     * not a Kotlin JS or native target, not an optional feature, and built for this Java version or older.
     * Among the ones left it prefers non shadowed jars and the newest bytecode.
     *
     * @param javaVersion the Java version we're running on
     * @return the variant or {@code null} if the module has no runtime variant for the JVM
     * @throws IOException if every runtime variant needs a newer Java
     */
    Map<String, Object> runtimeVariant(int javaVersion) throws IOException {
        Map<String, Object> best = null;
        int bestScore = -1;
        int required = Integer.MAX_VALUE;
        for (Map<String, Object> variant : variants) {
            Map<String, Object> attributes = object(variant.get("attributes"));
            if (!isOneOf(attributes.get("org.gradle.usage"), "java-runtime", "java-runtime-jars")
                    || !isOneOf(attributes.get("org.gradle.category"), null, "library")
                    || !isOneOf(attributes.get("org.gradle.libraryelements"), null, "jar")
                    || !isOneOf(attributes.get("org.gradle.jvm.environment"), null, "standard-jvm")
                    || !isOneOf(attributes.get("org.jetbrains.kotlin.platform.type"), null, "jvm")
                    || !hasDefaultCapability(variant)) {
                continue;
            }
            int jvm = jvmVersion(attributes.get("org.gradle.jvm.version"));
            if (jvm > javaVersion) {
                required = Math.min(required, jvm);
                continue;
            }
            int score = jvm + ("shadowed".equals(string(attributes.get("org.gradle.dependency.bundling"))) ? 0 : 1000);
            if (score > bestScore) {
                best = variant;
                bestScore = score;
            }
        }
        if (best == null && required != Integer.MAX_VALUE) {
            throw new IOException(group + ':' + module + ':' + version + " needs Java " + required + " or newer, but this is Java " + javaVersion);
        }
        return best;
    }

    /**
     * Picks the variant a platform (Gradle's BOM) uses to publish its constraints.
     *
     * @return the variant or {@code null} if this module isn't a platform
     */
    Map<String, Object> platformVariant() {
        Map<String, Object> fallback = null;
        for (Map<String, Object> variant : variants) {
            Map<String, Object> attributes = object(variant.get("attributes"));
            if (!isOneOf(attributes.get("org.gradle.category"), "platform", "enforced-platform")) {
                continue;
            }
            if ("java-runtime".equals(string(attributes.get("org.gradle.usage")))) {
                return variant;
            }
            fallback = variant;
        }
        return fallback;
    }

    static Artifact availableAt(Map<String, Object> variant) {
        Map<String, Object> target = object(variant.get("available-at"));
        if (target.isEmpty()) {
            return null;
        }
        return new Artifact(string(target.get("group")), string(target.get("module")), string(target.get("version")), null, "jar");
    }

    static List<String> jars(Map<String, Object> variant) {
        List<String> jars = new ArrayList<>();
        for (Object file : array(variant.get("files"))) {
            String url = string(object(file).get("url"));
            if (url != null && url.endsWith(".jar")) {
                jars.add(url);
            }
        }
        return jars;
    }

    /**
     * Converts the variant dependencies to POM style ones.
     *
     * @param platforms {@code true} to get only the platform dependencies, {@code false} to get the regular ones
     */
    static List<Pom.Dependency> dependencies(Map<String, Object> variant, boolean platforms) {
        List<Pom.Dependency> result = new ArrayList<>();
        for (Object entry : array(variant.get("dependencies"))) {
            Map<String, Object> dependency = object(entry);
            Object category = object(dependency.get("attributes")).get("org.gradle.category");
            if (isOneOf(category, "platform", "enforced-platform") == platforms) {
                result.add(convert(dependency));
            }
        }
        return result;
    }

    static List<Pom.Dependency> constraints(Map<String, Object> variant) {
        List<Pom.Dependency> result = new ArrayList<>();
        for (Object entry : array(variant.get("dependencyConstraints"))) {
            result.add(convert(object(entry)));
        }
        return result;
    }

    private static Pom.Dependency convert(Map<String, Object> entry) {
        Pom.Dependency dependency = new Pom.Dependency();
        dependency.groupId = string(entry.get("group"));
        dependency.artifactId = string(entry.get("module"));
        Map<String, Object> version = object(entry.get("version"));
        for (String key : new String[] {"strictly", "requires", "prefer"}) {
            if (dependency.version == null) {
                dependency.version = string(version.get(key));
            }
        }
        for (Object exclude : array(entry.get("excludes"))) {
            Map<String, Object> exclusion = object(exclude);
            String group = string(exclusion.get("group"));
            String module = string(exclusion.get("module"));
            dependency.exclusions.add((group == null ? "*" : group) + ':' + (module == null ? "*" : module));
        }
        Map<String, Object> selector = object(object(entry.get("thirdPartyCompatibility")).get("artifactSelector"));
        dependency.classifier = string(selector.get("classifier"));
        String extension = string(selector.get("extension"));
        dependency.type = extension == null || "jar".equals(extension) ? null : extension;
        return dependency;
    }

    private boolean hasDefaultCapability(Map<String, Object> variant) {
        List<Object> capabilities = array(variant.get("capabilities"));
        if (capabilities.isEmpty()) {
            return true;
        }
        for (Object entry : capabilities) {
            Map<String, Object> capability = object(entry);
            if (group.equals(string(capability.get("group"))) && module.equals(string(capability.get("name")))) {
                return true;
            }
        }
        return false;
    }

    private static int jvmVersion(Object value) {
        String text = string(value);
        if (text == null) {
            return 0;
        }
        try {
            return Integer.parseInt(text.startsWith("1.") ? text.substring(2) : text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isOneOf(Object value, String... options) {
        String text = string(value);
        for (String option : options) {
            if (option == null ? text == null : option.equals(text)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }

    private static String string(Object value) {
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        return value == null ? null : value.toString();
    }
}
