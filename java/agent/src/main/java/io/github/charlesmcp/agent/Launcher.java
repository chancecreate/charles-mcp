package io.github.charlesmcp.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Wrapper main class for Charles's native launcher ({@code Charles.exe} / {@code Charles.ini}).
 *
 * <p>Charles 4.6.x ships a jlink'd Java 11 runtime <b>without</b> the {@code java.instrument} module, so
 * {@code -javaagent} cannot be used with the bundled JVM. Instead, {@code Charles.ini} is pointed at this
 * class:
 *
 * <pre>
 * classpath.2=C:\path\to\charles-mcp-agent.jar
 * main.class=io.github.charlesmcp.agent.Launcher
 * </pre>
 *
 * It starts the (daemon, fail-safe) bridge bootstrap thread and then hands control to Charles's real
 * main class with the original arguments. No Charles bytecode is modified. If anything about the
 * bridge fails, Charles still starts normally.
 */
public final class Launcher {
    public static final String CHARLES_MAIN = "com.xk72.charles.gui.MainWithClassLoader";

    private Launcher() {
    }

    public static void main(String[] args) throws Throwable {
        AgentMain.startFromLauncher(System.getProperty("charlesmcp.args"));
        String target = System.getProperty("charlesmcp.delegateMain", CHARLES_MAIN);
        Method main = Class.forName(target, true, Launcher.class.getClassLoader()).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
