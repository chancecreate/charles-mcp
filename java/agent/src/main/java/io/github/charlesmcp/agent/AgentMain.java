package io.github.charlesmcp.agent;

import io.github.charlesmcp.agent.config.AgentConfig;
import io.github.charlesmcp.agent.instrumentation.CharlesApi;
import io.github.charlesmcp.agent.instrumentation.CharlesHook;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry points. Two ways in, one behaviour:
 * <ul>
 *   <li>{@link Launcher} (default; works with Charles's bundled runtime, which lacks java.instrument)</li>
 *   <li>{@code -javaagent:charles-mcp-agent.jar} ({@link JavaAgentEntry}) when Charles runs on a full JDK</li>
 * </ul>
 * Neither transforms Charles bytecode: the agent waits for Charles to start and registers an ordinary
 * {@code ProxyListener} through Charles's own listener API. Startup failures are logged and swallowed
 * so Charles always starts.
 */
public final class AgentMain {
    private static final Logger LOG = Logger.getLogger("charles-mcp");
    private static volatile CharlesHook hook;

    private AgentMain() {
    }

    static void startFromLauncher(String args) {
        ClassLoader loader = AgentMain.class.getClassLoader();
        start(args, () -> {
            for (String name : CharlesApi.CONTEXT_CLASSES) {
                try {
                    // initialize=false: never run Charles static initialisers from this thread.
                    return Class.forName(name, false, loader);
                } catch (ClassNotFoundException | LinkageError e) {
                    // try the next known location
                }
            }
            return null;
        });
    }

    static synchronized void start(String args, Supplier<Class<?>> locator) {
        try {
            if (hook != null) {
                return;
            }
            AgentConfig config = AgentConfig.load(args);
            if (config.disabled) {
                return;
            }
            hook = new CharlesHook(config, locator);
            hook.startAsync();
        } catch (Throwable t) {
            try {
                LOG.log(Level.WARNING, "charles-mcp agent failed to initialise; Charles continues normally", t);
            } catch (Throwable ignored) {
                // never propagate
            }
        }
    }
}
