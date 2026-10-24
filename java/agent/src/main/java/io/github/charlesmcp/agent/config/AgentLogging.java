package io.github.charlesmcp.agent.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Optional dedicated log file ({@code charlesmcp.logFile}). Attached only after Charles has initialised,
 * because Charles calls {@code LogManager.readConfiguration()} during startup, which removes handlers
 * that were added earlier.
 */
public final class AgentLogging {
    private static volatile boolean configured;

    private AgentLogging() {
    }

    public static synchronized void configure(Path file) {
        if (configured || file == null) {
            return;
        }
        Logger log = Logger.getLogger("charles-mcp");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            FileHandler fh = new FileHandler(file.toString(), 10 * 1024 * 1024, 1, true);
            fh.setFormatter(new SimpleFormatter());
            fh.setLevel(Level.ALL);
            log.addHandler(fh);
            log.setLevel(Level.INFO);
            configured = true;
        } catch (IOException | RuntimeException e) {
            log.warning("charles-mcp: could not open log file: " + e.getClass().getSimpleName());
        }
    }
}
