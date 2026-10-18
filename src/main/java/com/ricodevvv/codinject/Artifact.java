package com.ricodevvv.codinject;

import java.util.Objects;

/**
 * Maven coordinates of a single artifact, written as {@code group:artifact:version[:classifier][@extension]}.
 * <p>
 * The version can be a fixed one, a {@code -SNAPSHOT}, {@code latest}, {@code release} or a Maven range
 * like {@code [1.2,2.0)}.
 */
public final class Artifact {

    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String classifier;
    private final String extension;
    private final String snapshotVersion;

    /**
     * Creates the coordinates by hand.
     *
     * @param groupId    group id, like {@code com.google.code.gson}
     * @param artifactId artifact id, like {@code gson}
     * @param version    version, range, {@code latest} or {@code release}
     * @param classifier classifier or {@code null}
     * @param extension  file extension, {@code jar} when {@code null}
     */
    public Artifact(String groupId, String artifactId, String version, String classifier, String extension) {
        this(groupId, artifactId, version, classifier, extension, null);
    }

    private Artifact(String groupId, String artifactId, String version, String classifier, String extension, String snapshotVersion) {
        this.groupId = require(groupId, "groupId");
        this.artifactId = require(artifactId, "artifactId");
        this.version = require(version, "version");
        this.classifier = classifier == null || classifier.isEmpty() ? null : classifier;
        this.extension = extension == null || extension.isEmpty() ? "jar" : extension;
        this.snapshotVersion = snapshotVersion;
    }

    private static String require(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value.trim();
    }

    /**
     * Parses coordinates like {@code group:artifact:version}, {@code group:artifact:version:classifier}
     * or any of them ending with {@code @extension}.
     *
     * @param coordinates the coordinates to parse
     * @return the parsed artifact
     * @throws IllegalArgumentException if the text doesn't look like Maven coordinates
     */
    public static Artifact parse(String coordinates) {
        String text = Objects.requireNonNull(coordinates, "coordinates").trim();
        String extension = null;
        int at = text.lastIndexOf('@');
        if (at >= 0) {
            extension = text.substring(at + 1);
            text = text.substring(0, at);
        }
        String[] parts = text.split(":", -1);
        if (parts.length < 3 || parts.length > 4) {
            throw new IllegalArgumentException("Expected group:artifact:version[:classifier][@extension] but got '" + coordinates + "'");
        }
        try {
            return new Artifact(parts[0], parts[1], parts[2], parts.length == 4 ? parts[3] : null, extension);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid coordinates '" + coordinates + "': " + e.getMessage());
        }
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public String getClassifier() {
        return classifier;
    }

    public String getExtension() {
        return extension;
    }

    /**
     * Version used in the file name. For snapshots it's the timestamped one picked from the repository,
     * for everything else it's the same as {@link #getVersion()}.
     *
     * @return the version that goes in the file name
     */
    public String getFileVersion() {
        return snapshotVersion != null ? snapshotVersion : version;
    }

    public boolean isSnapshot() {
        return version.endsWith("-SNAPSHOT");
    }

    /**
     * Identity of the artifact without the version, used to detect the same library twice.
     *
     * @return {@code group:artifact} plus {@code :classifier} when there is one
     */
    public String getKey() {
        return groupId + ':' + artifactId + (classifier == null ? "" : ':' + classifier);
    }

    /**
     * Same coordinates with another version.
     *
     * @param version the new version
     * @return a new artifact
     */
    public Artifact withVersion(String version) {
        return new Artifact(groupId, artifactId, version, classifier, extension);
    }

    Artifact withSnapshotVersion(String snapshotVersion) {
        return new Artifact(groupId, artifactId, version, classifier, extension, snapshotVersion);
    }

    String getDirectory() {
        return groupId.replace('.', '/') + '/' + artifactId + '/' + version;
    }

    String getPath() {
        return getDirectory() + '/' + artifactId + '-' + getFileVersion() + (classifier == null ? "" : '-' + classifier) + '.' + extension;
    }

    String getPomPath() {
        return getDirectory() + '/' + artifactId + '-' + getFileVersion() + ".pom";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Artifact)) {
            return false;
        }
        Artifact other = (Artifact) o;
        return groupId.equals(other.groupId)
                && artifactId.equals(other.artifactId)
                && version.equals(other.version)
                && Objects.equals(classifier, other.classifier)
                && extension.equals(other.extension)
                && Objects.equals(snapshotVersion, other.snapshotVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version, classifier, extension, snapshotVersion);
    }

    @Override
    public String toString() {
        return groupId + ':' + artifactId + ':' + version
                + (classifier == null ? "" : ':' + classifier)
                + ("jar".equals(extension) ? "" : '@' + extension);
    }
}
