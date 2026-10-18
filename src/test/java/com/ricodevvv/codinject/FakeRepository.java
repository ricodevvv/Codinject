package com.ricodevvv.codinject;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * Maven repository on disk, served through {@code file:} URLs, with real jars compiled on the fly.
 */
final class FakeRepository {

    private final Path root;

    FakeRepository(Path root) {
        this.root = root;
    }

    String url() {
        return root.toUri().toString();
    }

    FakeRepository pom(String coordinates, String... extra) throws IOException {
        Artifact artifact = Artifact.parse(coordinates);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project>\n<modelVersion>4.0.0</modelVersion>\n")
                .append("<groupId>").append(artifact.getGroupId()).append("</groupId>\n")
                .append("<artifactId>").append(artifact.getArtifactId()).append("</artifactId>\n")
                .append("<version>").append(artifact.getVersion()).append("</version>\n");
        for (String part : extra) {
            xml.append(part).append('\n');
        }
        xml.append("</project>\n");
        return write(artifact.getPomPath(), xml.toString());
    }

    FakeRepository rawPom(Artifact artifact, String xml) throws IOException {
        return write(artifact.getPomPath(), xml);
    }

    FakeRepository jar(String coordinates, String className) throws IOException {
        return jar(Artifact.parse(coordinates), className);
    }

    FakeRepository jar(Artifact artifact, String className) throws IOException {
        Path file = root.resolve(artifact.getPath());
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            if (className != null) {
                out.putNextEntry(new JarEntry(className.replace('.', '/') + ".class"));
                out.write(compile(className, artifact.toString()));
                out.closeEntry();
            } else {
                out.putNextEntry(new JarEntry("artifact.txt"));
                out.write(artifact.toString().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return this;
    }

    FakeRepository write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    Path file(String path) {
        return root.resolve(path);
    }

    static String dependency(String coordinates, String... extra) {
        Artifact artifact = Artifact.parse(coordinates);
        StringBuilder xml = new StringBuilder("<dependency>")
                .append("<groupId>").append(artifact.getGroupId()).append("</groupId>")
                .append("<artifactId>").append(artifact.getArtifactId()).append("</artifactId>");
        if (!"-".equals(artifact.getVersion())) {
            xml.append("<version>").append(artifact.getVersion()).append("</version>");
        }
        for (String part : extra) {
            xml.append(part);
        }
        return xml.append("</dependency>").toString();
    }

    static String dependencies(String... dependencies) {
        return "<dependencies>" + String.join("", dependencies) + "</dependencies>";
    }

    private static byte[] compile(String className, String value) throws IOException {
        int dot = className.lastIndexOf('.');
        String source = "package " + className.substring(0, dot) + ";\n"
                + "public class " + className.substring(dot + 1) + " {\n"
                + "    public static String value() { return \"" + value + "\"; }\n"
                + "}\n";
        Path work = Files.createTempDirectory("codinject-compile");
        try {
            Path sourceFile = work.resolve(className.substring(dot + 1) + ".java");
            Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new IllegalStateException("Tests need a JDK, not a JRE");
            }
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int code = compiler.run(null, null, errors, "-d", work.toString(), sourceFile.toString());
            if (code != 0) {
                throw new IllegalStateException(new String(errors.toByteArray(), StandardCharsets.UTF_8));
            }
            return Files.readAllBytes(work.resolve(className.replace('.', '/') + ".class"));
        } finally {
            try (Stream<Path> files = Files.walk(work)) {
                files.sorted((a, b) -> b.compareTo(a)).forEach(path -> path.toFile().delete());
            }
        }
    }
}
