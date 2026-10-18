package com.ricodevvv.codinject;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Adds jars to class loaders we don't own. It tries the cleanest option first and only falls back
 * to {@code sun.misc.Unsafe} when nothing else works:
 * <ol>
 *     <li>A public {@code addURL}, like {@link LibraryClassLoader} has.</li>
 *     <li>{@link Instrumentation#appendToSystemClassLoaderSearch(JarFile)} for the system class loader.</li>
 *     <li>Plain reflection, when the JDK package is open to us: always on Java 8, on newer versions with
 *     {@code --add-opens} or after opening it through the agent.</li>
 *     <li>The JDK trusted lookup taken with {@code sun.misc.Unsafe}. Works up to Java 25,
 *     but Java 24+ prints a deprecation warning the first time.</li>
 * </ol>
 */
final class Injector {

    static final int JAVA_VERSION = javaVersion();

    private static final String HINT = "Start the JVM with --add-opens java.base/java.net=ALL-UNNAMED"
            + " --add-opens java.base/jdk.internal.loader=ALL-UNNAMED, load Codinject as a Java agent,"
            + " or leave allowUnsafe enabled";

    private static MethodHandles.Lookup trusted;

    private final Logger logger;
    private final boolean allowUnsafe;

    Injector(Logger logger, boolean allowUnsafe) {
        this.logger = logger;
        this.allowUnsafe = allowUnsafe;
    }

    void inject(ClassLoader loader, Path jar) throws IOException {
        URL url = jar.toUri().toURL();
        Instrumentation instrumentation = instrumentation();
        boolean quietReflection = JAVA_VERSION < 9 || JAVA_VERSION >= 16 || !allowUnsafe;
        IOException failure = new IOException("Could not add " + jar.getFileName() + " to " + loader + ". " + HINT);

        if (attempt(failure, "public addURL", () -> direct(loader, url))
                || attempt(failure, "instrumentation", () -> instrumented(loader, jar, instrumentation))
                || (quietReflection && attempt(failure, "reflection", () -> reflective(loader, url, instrumentation)))
                || (allowUnsafe && attempt(failure, "trusted lookup", () -> trusted(loader, url)))) {
            return;
        }
        throw failure;
    }

    private boolean attempt(IOException failure, String name, Strategy strategy) {
        try {
            if (strategy.run()) {
                logger.fine("Jar added through " + name);
                return true;
            }
        } catch (InvocationTargetException e) {
            failure.addSuppressed(e.getCause());
        } catch (Throwable t) {
            failure.addSuppressed(t);
        }
        return false;
    }

    private static boolean direct(ClassLoader loader, URL url) throws ReflectiveOperationException {
        if (loader instanceof LibraryClassLoader) {
            ((LibraryClassLoader) loader).addURL(url);
            return true;
        }
        Method method;
        try {
            method = loader.getClass().getMethod("addURL", URL.class);
        } catch (NoSuchMethodException e) {
            return false;
        }
        method.invoke(loader, url);
        return true;
    }

    private static boolean instrumented(ClassLoader loader, Path jar, Instrumentation instrumentation) throws IOException {
        if (instrumentation == null || loader != ClassLoader.getSystemClassLoader()) {
            return false;
        }
        instrumentation.appendToSystemClassLoaderSearch(new JarFile(jar.toFile()));
        return true;
    }

    private static boolean reflective(ClassLoader loader, URL url, Instrumentation instrumentation) throws ReflectiveOperationException {
        if (loader instanceof URLClassLoader) {
            if (!open(URLClassLoader.class, instrumentation)) {
                return false;
            }
            Method addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addUrl.setAccessible(true);
            addUrl.invoke(loader, url);
            return true;
        }
        Field field = ucpField(loader.getClass());
        if (field == null || !open(field.getDeclaringClass(), instrumentation)) {
            return false;
        }
        field.setAccessible(true);
        Object ucp = field.get(loader);
        if (!open(ucp.getClass(), instrumentation)) {
            return false;
        }
        Method addUrl = ucp.getClass().getMethod("addURL", URL.class);
        addUrl.setAccessible(true);
        addUrl.invoke(ucp, url);
        return true;
    }

    private static boolean trusted(ClassLoader loader, URL url) throws Throwable {
        MethodHandles.Lookup lookup = trustedLookup();
        MethodType addUrl = MethodType.methodType(void.class, URL.class);
        if (loader instanceof URLClassLoader) {
            lookup.findVirtual(URLClassLoader.class, "addURL", addUrl).invoke((URLClassLoader) loader, url);
            return true;
        }
        Field field = ucpField(loader.getClass());
        if (field == null) {
            return false;
        }
        Object ucp = lookup.findGetter(field.getDeclaringClass(), field.getName(), field.getType()).invoke(loader);
        lookup.findVirtual(ucp.getClass(), "addURL", addUrl).invoke(ucp, url);
        return true;
    }

    private static synchronized MethodHandles.Lookup trustedLookup() throws ReflectiveOperationException {
        if (trusted == null) {
            Field implLookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Object base = unsafeType.getMethod("staticFieldBase", Field.class).invoke(unsafe, implLookup);
            long offset = (Long) unsafeType.getMethod("staticFieldOffset", Field.class).invoke(unsafe, implLookup);
            trusted = (MethodHandles.Lookup) unsafeType.getMethod("getObject", Object.class, long.class).invoke(unsafe, base, offset);
        }
        return trusted;
    }

    private static Field ucpField(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField("ucp");
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /**
     * Checks that the package of {@code type} is open to Codinject, opening it through the agent when possible.
     */
    private static boolean open(Class<?> type, Instrumentation instrumentation) throws ReflectiveOperationException {
        if (JAVA_VERSION < 9) {
            return true;
        }
        Class<?> moduleType = Class.forName("java.lang.Module");
        Method getModule = Class.class.getMethod("getModule");
        Method isOpen = moduleType.getMethod("isOpen", String.class, moduleType);
        Object target = getModule.invoke(type);
        Object self = getModule.invoke(Injector.class);
        String name = type.getName();
        String pkg = name.substring(0, name.lastIndexOf('.'));
        if ((Boolean) isOpen.invoke(target, pkg, self)) {
            return true;
        }
        if (instrumentation == null) {
            return false;
        }
        Map<String, Set<Object>> opens = Collections.singletonMap(pkg, Collections.singleton(self));
        Instrumentation.class
                .getMethod("redefineModule", moduleType, Set.class, Map.class, Map.class, Set.class, Map.class)
                .invoke(instrumentation, target, Collections.emptySet(), Collections.emptyMap(), opens, Collections.emptySet(), Collections.emptyMap());
        return (Boolean) isOpen.invoke(target, pkg, self);
    }

    private static Instrumentation instrumentation() {
        try {
            return CodinjectAgent.getInstrumentation();
        } catch (LinkageError e) {
            return null;
        }
    }

    private static int javaVersion() {
        String version = System.getProperty("java.specification.version");
        return Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version);
    }

    private interface Strategy {
        boolean run() throws Throwable;
    }
}
