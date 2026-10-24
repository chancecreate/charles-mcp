package io.github.charlesmcp.agent.events;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Bounded, sequenced in-memory event log plus a bounded table of latest transaction state.
 *
 * <p>Bounds (all enforced on every append): max events, max event age, max transactions, and an
 * approximate retained-bytes budget for transaction metadata. Eviction is oldest-first and counted, so
 * clients can detect gaps by comparing their cursor with {@link Page#oldestEventId}.
 *
 * <p>Thread-safety: all public methods are synchronized. There is exactly one writer (the pipeline
 * worker) and a handful of readers (bridge connections), so contention is negligible and never touches
 * Charles's proxy threads.
 */
public final class EventStore {
    private final int maxEvents;
    private final long maxAgeMillis;
    private final int maxTransactions;
    private final long maxRetainedBytes;
    private final LongSupplier clock;

    private final ArrayDeque<BridgeEvent> events = new ArrayDeque<>();
    private final LinkedHashMap<String, TransactionRecord> transactions = new LinkedHashMap<>();
    private long nextEventId = 1;
    private long retainedBytes;
    private long eventBytes;

    private long eventsAppended;
    private long eventsEvicted;
    private long transactionsEvicted;

    public EventStore(int maxEvents, long maxAgeMillis, int maxTransactions, long maxRetainedBytes, LongSupplier clock) {
        this.maxEvents = maxEvents;
        this.maxAgeMillis = maxAgeMillis;
        this.maxTransactions = maxTransactions;
        this.maxRetainedBytes = maxRetainedBytes;
        this.clock = clock;
    }

    /** Appends one event and returns its assigned, strictly increasing id. */
    public synchronized long append(RawEvent raw) {
        long id = nextEventId++;
        BridgeEvent ev = new BridgeEvent(id, raw);
        events.addLast(ev);
        eventBytes += ev.estimatedBytes();
        eventsAppended++;

        if (raw.transactionId != null) {
            TransactionRecord rec = transactions.get(raw.transactionId);
            if (rec == null) {
                rec = new TransactionRecord(raw.transactionId, raw.sessionId, raw.timestampMs);
                transactions.put(raw.transactionId, rec);
            } else {
                retainedBytes -= rec.latest == null ? 0 : rec.latest.estimatedBytes();
            }
            if (raw.snapshot != null) {
                rec.latest = raw.snapshot;
                retainedBytes += raw.snapshot.estimatedBytes();
            }
            rec.lastEvent = raw.type;
            rec.lastEventId = id;
            rec.updatedMs = raw.timestampMs;
            if (raw.liveTransaction != null) {
                rec.live = raw.liveTransaction;
            }
        }
        evict();
        return id;
    }

    private void evict() {
        long cutoff = clock.getAsLong() - maxAgeMillis;
        while (!events.isEmpty() && (events.size() > maxEvents || events.peekFirst().timestampMs < cutoff)) {
            BridgeEvent gone = events.removeFirst();
            eventBytes -= gone.estimatedBytes();
            eventsEvicted++;
        }
        Iterator<Map.Entry<String, TransactionRecord>> it = transactions.entrySet().iterator();
        while (it.hasNext() && (transactions.size() > maxTransactions || retainedBytes + eventBytes > maxRetainedBytes)) {
            TransactionRecord rec = it.next().getValue();
            retainedBytes -= rec.latest == null ? 0 : rec.latest.estimatedBytes();
            rec.live = null;
            it.remove();
            transactionsEvicted++;
        }
        // If the budget is still exceeded, the event log itself must shrink too.
        while (!events.isEmpty() && retainedBytes + eventBytes > maxRetainedBytes) {
            BridgeEvent gone = events.removeFirst();
            eventBytes -= gone.estimatedBytes();
            eventsEvicted++;
        }
        // Age out transactions whose last update is older than the event horizon.
        it = transactions.entrySet().iterator();
        while (it.hasNext()) {
            TransactionRecord rec = it.next().getValue();
            if (rec.updatedMs >= cutoff) {
                break; // insertion order ~ time order; good enough and O(1) amortized
            }
            retainedBytes -= rec.latest == null ? 0 : rec.latest.estimatedBytes();
            rec.live = null;
            it.remove();
            transactionsEvicted++;
        }
    }

    /** Returns up to {@code limit} events with id greater than {@code afterEventId}. */
    public synchronized Page eventsAfter(long afterEventId, int limit) {
        evict();
        List<BridgeEvent> out = new ArrayList<>(Math.min(limit, 256));
        long oldest = events.isEmpty() ? nextEventId : events.peekFirst().eventId;
        for (BridgeEvent e : events) {
            if (e.eventId <= afterEventId) {
                continue;
            }
            if (out.size() >= limit) {
                break;
            }
            out.add(e);
        }
        long next = out.isEmpty() ? Math.max(afterEventId, oldest - 1) : out.get(out.size() - 1).eventId;
        boolean gap = afterEventId + 1 < oldest && eventsAppended > 0;
        boolean more = !out.isEmpty() && out.get(out.size() - 1).eventId < nextEventId - 1;
        return new Page(out, next, oldest, nextEventId - 1, gap, more);
    }

    public synchronized TransactionRecord transaction(String id) {
        return transactions.get(id);
    }

    public synchronized Stats stats() {
        return new Stats(events.size(), transactions.size(), eventsAppended, eventsEvicted, transactionsEvicted,
                nextEventId - 1, retainedBytes + eventBytes);
    }

    /** For tests: drop the live reference as if Charles released the transaction. */
    synchronized void clearLiveReference(String transactionId) {
        TransactionRecord rec = transactions.get(transactionId);
        if (rec != null) {
            rec.live = new WeakReference<>(null);
        }
    }

    public static final class Page {
        public final List<BridgeEvent> events;
        /** Cursor to pass as {@code after} on the next call. */
        public final long nextCursor;
        /** Smallest event id still retained (or latest+1 if empty). */
        public final long oldestEventId;
        public final long latestEventId;
        /** True if events between the requested cursor and {@link #oldestEventId} were evicted. */
        public final boolean gap;
        public final boolean hasMore;

        Page(List<BridgeEvent> events, long nextCursor, long oldestEventId, long latestEventId, boolean gap, boolean hasMore) {
            this.events = events;
            this.nextCursor = nextCursor;
            this.oldestEventId = oldestEventId;
            this.latestEventId = latestEventId;
            this.gap = gap;
            this.hasMore = hasMore;
        }
    }

    public static final class Stats {
        public final int retainedEvents;
        public final int retainedTransactions;
        public final long eventsAppended;
        public final long eventsEvicted;
        public final long transactionsEvicted;
        public final long latestEventId;
        public final long approxRetainedBytes;

        Stats(int retainedEvents, int retainedTransactions, long eventsAppended, long eventsEvicted,
              long transactionsEvicted, long latestEventId, long approxRetainedBytes) {
            this.retainedEvents = retainedEvents;
            this.retainedTransactions = retainedTransactions;
            this.eventsAppended = eventsAppended;
            this.eventsEvicted = eventsEvicted;
            this.transactionsEvicted = transactionsEvicted;
            this.latestEventId = latestEventId;
            this.approxRetainedBytes = approxRetainedBytes;
        }
    }
}
