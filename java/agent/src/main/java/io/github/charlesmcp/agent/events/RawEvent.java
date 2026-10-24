package io.github.charlesmcp.agent.events;

import java.lang.ref.WeakReference;

/**
 * What the proxy thread hands to the pipeline: an event type, identifiers, an immutable snapshot and a
 * <em>weak</em> reference to the live Charles transaction (used later, off-thread, for bounded body reads).
 * The weak reference means we never keep a transaction alive after Charles discards it.
 */
public final class RawEvent {
    public final EventType type;
    public final long timestampMs;
    public final String sessionId;
    /** Null for session-level events. */
    public final String transactionId;
    /** Null for session-level events. */
    public final TransactionSnapshot snapshot;
    public final WeakReference<Object> liveTransaction;

    public RawEvent(EventType type, long timestampMs, String sessionId, String transactionId,
                    TransactionSnapshot snapshot, Object liveTransaction) {
        this.type = type;
        this.timestampMs = timestampMs;
        this.sessionId = sessionId;
        this.transactionId = transactionId;
        this.snapshot = snapshot;
        this.liveTransaction = liveTransaction == null ? null : new WeakReference<>(liveTransaction);
    }
}
