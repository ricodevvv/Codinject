package com.ricodevvv.codinject;

import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The parts of a POM that matter to resolve runtime dependencies.
 */
final class Pom {

    String groupId;
    String artifactId;
    String version;
    String packaging;
    Artifact parent;
    final Map<String, String> properties = new LinkedHashMap<>();
    final List<Dependency> dependencies = new ArrayList<>();
    final List<Dependency> management = new ArrayList<>();
    final Set<String> repositories = new LinkedHashSet<>();

    static Pom empty(Artifact artifact) {
        Pom pom = new Pom();
        pom.groupId = artifact.getGroupId();
        pom.artifactId = artifact.getArtifactId();
        pom.version = artifact.getVersion();
        return pom;
    }

    static Pom parse(Path file) throws IOException {
        Element project = Xml.parse(file);
        Pom pom = new Pom();
        pom.groupId = Xml.text(project, "groupId");
        pom.artifactId = Xml.text(project, "artifactId");
        pom.version = Xml.text(project, "version");
        pom.packaging = Xml.text(project, "packaging");

        Element parent = Xml.child(project, "parent");
        if (parent != null) {
            pom.parent = new Artifact(Xml.text(parent, "groupId"), Xml.text(parent, "artifactId"), Xml.text(parent, "version"), null, "pom");
        }
        for (Element property : Xml.children(Xml.child(project, "properties"), null)) {
            pom.properties.put(property.getNodeName(), property.getTextContent().trim());
        }
        for (Element dependency : Xml.children(Xml.child(project, "dependencies"), "dependency")) {
            pom.dependencies.add(Dependency.parse(dependency));
        }
        Element management = Xml.child(Xml.child(project, "dependencyManagement"), "dependencies");
        for (Element dependency : Xml.children(management, "dependency")) {
            pom.management.add(Dependency.parse(dependency));
        }
        for (Element repository : Xml.children(Xml.child(project, "repositories"), "repository")) {
            String url = Xml.text(repository, "url");
            if (url != null) {
                pom.repositories.add(url);
            }
        }
        return pom;
    }

    /**
     * Merges this raw POM on top of its already inherited parent, like Maven does before interpolation.
     *
     * @param parentPom the parent with its own inheritance already applied
     * @return a new POM with everything inherited
     */
    Pom inherit(Pom parentPom) {
        Pom pom = new Pom();
        pom.groupId = groupId != null ? groupId : parent.getGroupId();
        pom.artifactId = artifactId;
        pom.version = version != null ? version : parent.getVersion();
        pom.packaging = packaging;
        pom.parent = parent;
        pom.properties.putAll(parentPom.properties);
        pom.properties.putAll(properties);
        merge(pom.dependencies, parentPom.dependencies, dependencies);
        merge(pom.management, parentPom.management, management);
        pom.repositories.addAll(parentPom.repositories);
        pom.repositories.addAll(repositories);
        return pom;
    }

    private static void merge(List<Dependency> target, List<Dependency> inherited, List<Dependency> own) {
        Map<String, Dependency> merged = new LinkedHashMap<>();
        for (Dependency dependency : inherited) {
            merged.put(dependency.key(), dependency);
        }
        for (Dependency dependency : own) {
            merged.put(dependency.key(), dependency);
        }
        target.addAll(merged.values());
    }

    Dependency managed(Dependency dependency) {
        for (Dependency managed : management) {
            if (managed.groupId.equals(dependency.groupId)
                    && managed.artifactId.equals(dependency.artifactId)
                    && Objects.equals(managed.classifier, dependency.classifier)) {
                return managed;
            }
        }
        return null;
    }

    /**
     * A {@code <dependency>} entry, from {@code <dependencies>} or {@code <dependencyManagement>}.
     */
    static final class Dependency {

        String groupId;
        String artifactId;
        String version;
        String classifier;
        String type;
        String scope;
        String optional;
        final Set<String> exclusions = new LinkedHashSet<>();

        static Dependency parse(Element element) {
            Dependency dependency = new Dependency();
            dependency.groupId = Xml.text(element, "groupId");
            dependency.artifactId = Xml.text(element, "artifactId");
            dependency.version = Xml.text(element, "version");
            dependency.classifier = Xml.text(element, "classifier");
            dependency.type = Xml.text(element, "type");
            dependency.scope = Xml.text(element, "scope");
            dependency.optional = Xml.text(element, "optional");
            for (Element exclusion : Xml.children(Xml.child(element, "exclusions"), "exclusion")) {
                String group = Xml.text(exclusion, "groupId");
                String artifact = Xml.text(exclusion, "artifactId");
                dependency.exclusions.add((group == null ? "*" : group) + ':' + (artifact == null ? "*" : artifact));
            }
            return dependency;
        }

        String key() {
            return groupId + ':' + artifactId + (classifier == null ? "" : ':' + classifier) + ':' + (type == null ? "jar" : type);
        }

        Dependency interpolate(Map<String, String> properties) {
            Dependency copy = new Dependency();
            copy.groupId = Pom.interpolate(groupId, properties);
            copy.artifactId = Pom.interpolate(artifactId, properties);
            copy.version = Pom.interpolate(version, properties);
            copy.classifier = Pom.interpolate(classifier, properties);
            copy.type = Pom.interpolate(type, properties);
            copy.scope = Pom.interpolate(scope, properties);
            copy.optional = Pom.interpolate(optional, properties);
            for (String exclusion : exclusions) {
                copy.exclusions.add(Pom.interpolate(exclusion, properties));
            }
            return copy;
        }
    }

    static String interpolate(String value, Map<String, String> properties) {
        String result = value;
        for (int pass = 0; pass < 10 && result != null && result.contains("${"); pass++) {
            StringBuilder builder = new StringBuilder();
            boolean changed = false;
            int index = 0;
            while (index < result.length()) {
                int start = result.indexOf("${", index);
                int end = start < 0 ? -1 : result.indexOf('}', start);
                if (end < 0) {
                    builder.append(result, index, result.length());
                    break;
                }
                String name = result.substring(start + 2, end);
                String replacement = properties.containsKey(name) ? properties.get(name) : System.getProperty(name);
                builder.append(result, index, start);
                if (replacement == null) {
                    builder.append(result, start, end + 1);
                } else {
                    builder.append(replacement);
                    changed = true;
                }
                index = end + 1;
            }
            result = builder.toString();
            if (!changed) {
                break;
            }
        }
        return result;
    }
}
