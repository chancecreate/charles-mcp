package io.github.charlesmcp.agent.events;

/**
 * A sequenced event as served to clients. Holds only a compact summary (not full headers) so that a
 * large event history stays cheap; full detail lives in {@link TransactionRecord}.
 */
public final class BridgeEvent {
    public final long eventId;
    public final EventType type;
    public final long timestampMs;
    public final String sessionId;
    public final String transactionId;
    // Summary fields (null for session-level events)
    public final String method;
    public final String url;
    public final String host;
    public final int statusCode;
    public final String contentType;
    public final Long startTimeMs;
    public final Long endTimeMs;
    public final Long durationMs;
    public final long requestSize;
    public final long responseSize;
    public final String charlesStatus;
    public final String errorType;

    BridgeEvent(long eventId, RawEvent raw) {
        this.eventId = eventId;
        this.type = raw.type;
        this.timestampMs = raw.timestampMs;
        this.sessionId = raw.sessionId;
        this.transactionId = raw.transactionId;
        TransactionSnapshot s = raw.snapshot;
        if (s != null) {
            this.method = s.method;
            this.url = s.url();
            this.host = s.host;
            this.statusCode = s.statusCode;
            this.contentType = s.responseContentType();
            this.startTimeMs = s.startTimeMs;
            this.endTimeMs = s.endTimeMs;
            this.durationMs = s.durationMs();
            this.requestSize = s.requestSize;
            this.responseSize = s.responseSize;
            this.charlesStatus = s.charlesStatus;
            this.errorType = s.errorType;
        } else {
            this.method = null;
            this.url = null;
            this.host = null;
            this.statusCode = 0;
            this.contentType = null;
            this.startTimeMs = null;
            this.endTimeMs = null;
            this.durationMs = null;
            this.requestSize = 0;
            this.responseSize = 0;
            this.charlesStatus = null;
            this.errorType = null;
        }
    }

    long estimatedBytes() {
        return 160 + (url == null ? 0 : 2L * url.length()) + (host == null ? 0 : 2L * host.length());
    }
}
