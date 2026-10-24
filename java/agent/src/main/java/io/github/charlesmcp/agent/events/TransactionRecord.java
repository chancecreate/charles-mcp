package io.github.charlesmcp.agent.events;

import java.lang.ref.WeakReference;

/** Latest known state of one transaction. Mutated only by the store under its lock. */
public final class TransactionRecord {
    public final String transactionId;
    public final String sessionId;
    public final long firstSeenMs;
    volatile TransactionSnapshot latest;
    volatile EventType lastEvent;
    volatile long lastEventId;
    volatile long updatedMs;
    volatile WeakReference<Object> live;

    TransactionRecord(String transactionId, String sessionId, long firstSeenMs) {
        this.transactionId = transactionId;
        this.sessionId = sessionId;
        this.firstSeenMs = firstSeenMs;
    }

    public TransactionSnapshot latest() {
        return latest;
    }

    public EventType lastEvent() {
        return lastEvent;
    }

    public long lastEventId() {
        return lastEventId;
    }

    public long updatedMs() {
        return updatedMs;
    }

    public boolean isTerminal() {
        EventType e = lastEvent;
        return e != null && e.terminal;
    }

    /** The live Charles object, or null if Charles has released it (session cleared, GC). */
    public Object liveTransaction() {
        WeakReference<Object> ref = live;
        return ref == null ? null : ref.get();
    }
}
