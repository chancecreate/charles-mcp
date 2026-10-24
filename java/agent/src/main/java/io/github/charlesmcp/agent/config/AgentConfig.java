package io.github.charlesmcp.agent.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable agent configuration.
 *
 * <p>Values come from (highest precedence first): the {@code -javaagent:...=key=value,key=value} argument
 * string, then {@code -Dcharlesmcp.<key>=value} system properties, then built-in defaults. All limits
 * are clamped to safe ranges so a typo cannot unbound memory use inside Charles.
 */
public final class AgentConfig {
    public static final String PROPERTY_PREFIX = "charlesmcp.";

    /** TCP port for the loopback bridge; 0 = ephemeral (recommended). */
    public final int port;
    /** Where the discovery file (port + token) is written for the sidecar. */
    public final Path discoveryFile;
    public final int maxEvents;
    public final int maxTransactions;
    public final long maxEventAgeMillis;
    public final long maxRetainedHeaderBytes;
    public final int queueCapacity;
    public final int maxConnections;
    /** Bridge-level kill switch for body access. The sidecar is still default-deny on top of this. */
    public final boolean allowBodies;
    public final int maxBodyBytes;
    /** Redact credential-bearing header values before they leave the JVM. */
    public final boolean redactHeaders;
    /** Developer override: attach even if the Charles version / API shape does not match 4.6.2. */
    public final boolean allowUnsupported;
    /** Log lifecycle transitions (no paths, queries, headers or bodies) for hook verification. */
    public final boolean debugLifecycle;
    /** Disable the agent completely without editing Charles.ini. */
    public final boolean disabled;
    /** Optional dedicated agent log file (lifecycle debug output goes here). */
    public final Path logFile;

    private AgentConfig(Map<String, String> v) {
        this.port = intIn(v, "port", 0, 0, 65535);
        String df = v.get("discoveryFile");
        this.discoveryFile = df != null && !df.isEmpty() ? Paths.get(df) : defaultDiscoveryFile();
        this.maxEvents = intIn(v, "maxEvents", 20_000, 100, 1_000_000);
        this.maxTransactions = intIn(v, "maxTransactions", 5_000, 50, 200_000);
        this.maxEventAgeMillis = 1000L * intIn(v, "maxEventAgeSeconds", 6 * 3600, 10, 7 * 24 * 3600);
        this.maxRetainedHeaderBytes = 1024L * 1024L * intIn(v, "maxRetainedHeaderMiB", 64, 1, 1024);
        this.queueCapacity = intIn(v, "queueCapacity", 8_192, 64, 262_144);
        this.maxConnections = intIn(v, "maxConnections", 4, 1, 32);
        this.allowBodies = bool(v, "allowBodies", true);
        this.maxBodyBytes = intIn(v, "maxBodyBytes", 1024 * 1024, 0, 16 * 1024 * 1024);
        this.redactHeaders = bool(v, "redactHeaders", true);
        this.allowUnsupported = bool(v, "allowUnsupported", false);
        this.debugLifecycle = bool(v, "debugLifecycle", false);
        this.disabled = bool(v, "disabled", false);
        String lf = v.get("logFile");
        this.logFile = lf != null && !lf.isEmpty() ? Paths.get(lf) : null;
    }

    public static AgentConfig defaults() {
        return new AgentConfig(Collections.emptyMap());
    }

    public static AgentConfig fromMap(Map<String, String> values) {
        return new AgentConfig(values);
    }

    /** Builds config from the agent argument string plus {@code charlesmcp.*} system properties. */
    public static AgentConfig load(String agentArgs) {
        Map<String, String> merged = new HashMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(PROPERTY_PREFIX)) {
                merged.put(name.substring(PROPERTY_PREFIX.length()), System.getProperty(name));
            }
        }
        merged.putAll(parseArgs(agentArgs));
        return new AgentConfig(merged);
    }

    static Map<String, String> parseArgs(String agentArgs) {
        Map<String, String> m = new HashMap<>();
        if (agentArgs == null || agentArgs.trim().isEmpty()) {
            return m;
        }
        for (String part : agentArgs.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                m.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            } else if (!part.trim().isEmpty()) {
                m.put(part.trim(), "true");
            }
        }
        return m;
    }

    public static Path defaultDiscoveryFile() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isEmpty()) {
            return Paths.get(local, "charles-mcp", "bridge.json");
        }
        return Paths.get(System.getProperty("user.home"), ".charles-mcp", "bridge.json");
    }

    private static int intIn(Map<String, String> v, String key, int def, int min, int max) {
        String s = v.get(key);
        if (s == null) {
            return def;
        }
        try {
            long n = Long.parseLong(s.trim());
            return (int) Math.max(min, Math.min(max, n));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean bool(Map<String, String> v, String key, boolean def) {
        String s = v.get(key);
        if (s == null) {
            return def;
        }
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on")) {
            return true;
        }
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off")) {
            return false;
        }
        return def;
    }
}
