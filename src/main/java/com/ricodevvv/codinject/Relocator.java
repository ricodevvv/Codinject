package com.ricodevvv.codinject;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * Rewrites packages inside a jar using jar-relocator. jar-relocator and ASM are downloaded the first time
 * they're needed and live in their own class loader, so they never clash with your classes.
 * Relocated jars are cached next to the downloads, so the work happens only once.
 */
final class Relocator implements Closeable {

    static final String[] TOOLS = {
            "org.ow2.asm:asm:9.10.1",
            "org.ow2.asm:asm-commons:9.10.1",
            "org.ow2.asm:asm-tree:9.10.1",
            "me.lucko:jar-relocator:1.7"
    };

    private final Path folder;
    private final Callable<List<Path>> toolJars;
    private URLClassLoader tools;
    private Constructor<?> constructor;
    private Method run;

    Relocator(Path folder, Callable<List<Path>> toolJars) {
        this.folder = folder.resolve("relocated");
        this.toolJars = toolJars;
    }

    synchronized Path relocate(Path input, Map<String, String> relocations) throws IOException {
        Path output = folder.resolve(name(input, relocations));
        if (Files.exists(output)) {
            return output;
        }
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, output.getFileName().toString(), ".part");
        try {
            load();
            Object relocator = constructor.newInstance(input.toFile(), temp.toFile(), new HashMap<>(relocations));
            run.invoke(relocator);
            Downloader.move(temp, output);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IOException ? (IOException) cause : new IOException("Could not relocate " + input, cause);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Could not relocate " + input, e);
        } finally {
            Files.deleteIfExists(temp);
        }
        return output;
    }

    private void load() throws IOException, ReflectiveOperationException {
        if (constructor != null) {
            return;
        }
        List<Path> jars;
        try {
            jars = toolJars.call();
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not get jar-relocator", e);
        }
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) {
            urls[i] = jars.get(i).toUri().toURL();
        }
        tools = new URLClassLoader(urls, ClassLoader.getSystemClassLoader().getParent());
        Class<?> type = Class.forName("me.lucko.jarrelocator.JarRelocator", true, tools);
        constructor = type.getConstructor(File.class, File.class, Map.class);
        run = type.getMethod("run");
    }

    private static String name(Path input, Map<String, String> relocations) throws IOException {
        MessageDigest digest = Downloader.sha1();
        digest.update(input.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
        digest.update(Long.toString(Files.size(input)).getBytes(StandardCharsets.UTF_8));
        digest.update(Long.toString(Files.getLastModifiedTime(input).toMillis()).getBytes(StandardCharsets.UTF_8));
        digest.update(new TreeMap<>(relocations).toString().getBytes(StandardCharsets.UTF_8));
        String file = input.getFileName().toString();
        String base = file.endsWith(".jar") ? file.substring(0, file.length() - 4) : file;
        return base + '-' + Downloader.hex(digest.digest()).substring(0, 12) + ".jar";
    }

    @Override
    public synchronized void close() throws IOException {
        if (tools != null) {
            tools.close();
            tools = null;
            constructor = null;
            run = null;
        }
    }
}
