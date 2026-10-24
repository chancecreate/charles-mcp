package io.github.charlesmcp.agent.events;

/**
 * Lifecycle events emitted by the bridge. Each maps 1:1 to a named callback on Charles's public
 * {@code com.xk72.proxy.ProxyListener} interface (see docs/reverse-engineering-findings.md); we do not
 * synthesize events that Charles does not signal.
 */
public enum EventType {
    /** {@code receivedRequestURL}: Charles created the Transaction and added it to the session. */
    REQUEST_CREATED("request_created", false),
    /** {@code receivedRequestHeader}: request headers parsed. */
    REQUEST_HEADERS_READY("request_headers_ready", false),
    /** {@code receivedRequest}: request body fully received by Charles. */
    REQUEST_COMPLETE("request_complete", false),
    /** {@code receivedResponseHeader}: response status line + headers available. */
    RESPONSE_HEADERS_READY("response_headers_ready", false),
    /** {@code transactionComplete} with no exception: final state, bodies materialized. Emitted at most once. */
    RESPONSE_COMPLETE("response_complete", true),
    /** {@code transactionException} / {@code transactionBlocked}, or completion with an exception. At most once. */
    FAILED("failed", true),
    /** {@code cancelTransaction}: Charles removed the transaction from the session (e.g. ignored host). */
    REMOVED("removed", true),
    /** The recording session object changed (new session window / session switch). */
    SESSION_CHANGED("session_changed", false);

    public final String wireName;
    public final boolean terminal;

    EventType(String wireName, boolean terminal) {
        this.wireName = wireName;
        this.terminal = terminal;
    }
}
