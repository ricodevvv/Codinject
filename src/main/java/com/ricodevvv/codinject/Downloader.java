package com.ricodevvv.codinject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;

/**
 * Plain {@link URLConnection} downloads with timeouts, safe redirects, SHA-1 checks and atomic writes.
 * Works with {@code https://}, {@code http://} and {@code file:} repositories.
 */
final class Downloader {

    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 30_000;
    private static final int MAX_REDIRECTS = 5;
    private static final String USER_AGENT = "Codinject (Java " + System.getProperty("java.version") + ")";

    private final Logger logger;
    private final boolean verify;

    Downloader(Logger logger, boolean verify) {
        this.logger = logger;
        this.verify = verify;
    }

    /**
     * Reads a small file, like {@code maven-metadata.xml}, into memory.
     *
     * @return the content or {@code null} when the repository doesn't have it
     */
    byte[] read(String repository, String path) throws IOException {
        try (InputStream in = open(url(repository, path))) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out, null);
            return out.toByteArray();
        }
    }

    /**
     * Downloads {@code path} from the repository into {@code target}, checking its {@code .sha1} when published.
     *
     * @return {@code false} when the repository doesn't have the file
     */
    boolean download(String repository, String path, Path target) throws IOException {
        URL url = url(repository, path);
        try (InputStream in = open(url)) {
            if (in == null) {
                return false;
            }
            logger.info("Downloading " + url);
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
            try {
                MessageDigest digest = sha1();
                try (OutputStream out = Files.newOutputStream(temp)) {
                    copy(in, out, digest);
                }
                if (verify) {
                    check(repository, path, hex(digest.digest()));
                }
                move(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
            return true;
        }
    }

    private void check(String repository, String path, String actual) throws IOException {
        byte[] data = read(repository, path + ".sha1");
        if (data == null) {
            logger.fine("No checksum published for " + path);
            return;
        }
        String expected = new String(data, StandardCharsets.US_ASCII).trim().split("\\s+")[0];
        if (!expected.matches("[0-9a-fA-F]{40}")) {
            logger.warning("Ignoring malformed checksum for " + path);
            return;
        }
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IOException("Checksum mismatch for " + repository + path + ": expected " + expected + " but got " + actual);
        }
    }

    private InputStream open(URL url) throws IOException {
        URL current = url;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            URLConnection connection = current.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (!(connection instanceof HttpURLConnection)) {
                try {
                    return connection.getInputStream();
                } catch (FileNotFoundException | NoSuchFileException e) {
                    return null;
                }
            }
            HttpURLConnection http = (HttpURLConnection) connection;
            http.setInstanceFollowRedirects(false);
            int code = http.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = http.getHeaderField("Location");
                http.disconnect();
                if (location == null) {
                    throw new IOException("Redirect without location from " + current);
                }
                URL next = resolve(current, location);
                if ("https".equals(current.getProtocol()) && !"https".equals(next.getProtocol())) {
                    throw new IOException("Refusing to follow redirect from " + current + " to " + next);
                }
                current = next;
                continue;
            }
            if (code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE) {
                http.disconnect();
                return null;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                http.disconnect();
                throw new IOException("HTTP " + code + " from " + current);
            }
            return http.getInputStream();
        }
        throw new IOException("Too many redirects from " + url);
    }

    static URL url(String repository, String path) throws IOException {
        try {
            return new URI(normalize(repository) + path).toURL();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("Invalid repository URL " + repository, e);
        }
    }

    static String normalize(String repository) {
        String url = repository.trim();
        return url.endsWith("/") ? url : url + '/';
    }

    private static URL resolve(URL base, String location) throws IOException {
        try {
            return base.toURI().resolve(location).toURL();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("Invalid redirect from " + base + " to " + location, e);
        }
    }

    private static void copy(InputStream in, OutputStream out, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (digest != null) {
                digest.update(buffer, 0, read);
            }
        }
    }

    static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (!Files.exists(target)) {
                throw e;
            }
        }
    }

    static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is missing from this JVM", e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }
}
