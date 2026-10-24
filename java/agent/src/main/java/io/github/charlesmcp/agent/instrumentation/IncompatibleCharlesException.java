package io.github.charlesmcp.agent.instrumentation;

/** Thrown when the running Charles does not match the API shape this agent was verified against. */
public final class IncompatibleCharlesException extends Exception {
    private static final long serialVersionUID = 1L;

    public IncompatibleCharlesException(String message) {
        super(message);
    }
}
