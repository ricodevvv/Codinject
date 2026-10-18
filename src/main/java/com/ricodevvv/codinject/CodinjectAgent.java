package com.ricodevvv.codinject;

import java.lang.instrument.Instrumentation;

/**
 * Optional Java agent. With an {@link Instrumentation} at hand Codinject can open {@code java.base} to itself
 * and add jars to the system class loader the official way, so it never needs {@code sun.misc.Unsafe}
 * and the JVM stays quiet on Java 24+.
 * <p>
 * Three ways to get it: run with {@code -javaagent:your.jar}, put {@code Launcher-Agent-Class} in the manifest
 * of your runnable jar (Java 9+), or call {@link #install(Instrumentation)} from your own agent.
 */
public final class CodinjectAgent {

    private static volatile Instrumentation instrumentation;

    private CodinjectAgent() {
    }

    /**
     * Entry point for {@code -javaagent}.
     *
     * @param args            agent arguments, ignored
     * @param instrumentation the instrumentation given by the JVM
     */
    public static void premain(String args, Instrumentation instrumentation) {
        install(instrumentation);
    }

    /**
     * Entry point for {@code Launcher-Agent-Class} and agents attached at runtime.
     *
     * @param args            agent arguments, ignored
     * @param instrumentation the instrumentation given by the JVM
     */
    public static void agentmain(String args, Instrumentation instrumentation) {
        install(instrumentation);
    }

    /**
     * Hands an instrumentation instance to Codinject, useful when you already have your own agent.
     *
     * @param instrumentation the instrumentation to use
     */
    public static void install(Instrumentation instrumentation) {
        CodinjectAgent.instrumentation = instrumentation;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }
}
