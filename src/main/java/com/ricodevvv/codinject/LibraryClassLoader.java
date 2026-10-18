package com.ricodevvv.codinject;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Isolated class loader where Codinject puts dependencies by default. Adding jars here needs no tricks
 * at all, so it works the same from Java 8 to Java 25.
 */
public class LibraryClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    /**
     * Creates an empty loader on top of the given parent.
     *
     * @param parent the parent class loader
     */
    public LibraryClassLoader(ClassLoader parent) {
        super(new URL[0], parent);
    }

    @Override
    public void addURL(URL url) {
        super.addURL(url);
    }
}
