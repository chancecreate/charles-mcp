package io.github.charlesmcp.agent;

import io.github.charlesmcp.agent.instrumentation.CharlesApi;

import java.lang.instrument.Instrumentation;

/**
 * {@code -javaagent} entry point, for running Charles on a full JDK that includes java.instrument.
 * Kept separate from {@link AgentMain} so the default {@link Launcher} path never loads a class that
 * references {@code java.lang.instrument} (absent from Charles's bundled runtime).
 */
public final class JavaAgentEntry {
    private JavaAgentEntry() {
    }

    public static void premain(String args, Instrumentation inst) {
        AgentMain.start(args, () -> findLoadedContextClass(inst));
    }

    public static void agentmain(String args, Instrumentation inst) {
        AgentMain.start(args, () -> findLoadedContextClass(inst));
    }

    /** Finds the CharlesContext class Charles itself loaded, without loading it from our thread. */
    static Class<?> findLoadedContextClass(Instrumentation inst) {
        Class<?> fallback = null;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (CharlesApi.CONTEXT_CLASSES.contains(c.getName())) {
                try {
                    if (c.getMethod("getInstance").invoke(null) != null) {
                        return c;
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    // not ready yet
                }
                fallback = c;
            }
        }
        return fallback;
    }
}
