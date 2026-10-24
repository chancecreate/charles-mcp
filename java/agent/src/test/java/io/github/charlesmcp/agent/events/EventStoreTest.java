package io.github.charlesmcp.agent.events;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventStoreTest {
    private final AtomicLong clock = new AtomicLong(10_000);

    private RawEvent ev(EventType t, String txn) {
        TransactionSnapshot s = TransactionSnapshot.builder().method("GET").scheme("http").host("h.test").path("/" + txn)
                .requestHeaders(Collections.singletonList(new TransactionSnapshot.Header("X", "y"))).build();
        return new RawEvent(t, clock.get(), "s1", txn, s, new Object());
    }

    @Test
    void cursorPaginationReturnsEachEventExactlyOnce() {
        EventStore store = new EventStore(1000, 3_600_000, 1000, 1L << 30, clock::get);
        for (int i = 0; i < 25; i++) {
            store.append(ev(EventType.REQUEST_CREATED, "t" + i));
        }
        long cursor = 0;
        int seen = 0;
        long lastId = 0;
        while (true) {
            EventStore.Page p = store.eventsAfter(cursor, 10);
            if (p.events.isEmpty()) {
                break;
            }
            for (BridgeEvent e : p.events) {
                assertTrue(e.eventId > lastId);
                lastId = e.eventId;
                seen++;
            }
            assertFalse(p.gap);
            cursor = p.nextCursor;
        }
        assertEquals(25, seen);
        assertEquals(25, cursor);
        EventStore.Page tail = store.eventsAfter(cursor, 10);
        assertTrue(tail.events.isEmpty());
        assertEquals(25, tail.nextCursor, "cursor stays put when caught up");
        assertFalse(tail.hasMore);
    }

    @Test
    void evictionByCountIsBoundedAndReportsGap() {
        EventStore store = new EventStore(100, 3_600_000, 1000, 1L << 30, clock::get);
        for (int i = 0; i < 250; i++) {
            store.append(ev(EventType.REQUEST_CREATED, "t" + i));
        }
        EventStore.Stats st = store.stats();
        assertEquals(100, st.retainedEvents);
        assertEquals(150, st.eventsEvicted);
        EventStore.Page p = store.eventsAfter(0, 1000);
        assertTrue(p.gap, "client at cursor 0 missed evicted events");
        assertEquals(151, p.oldestEventId);
        assertEquals(100, p.events.size());
        assertFalse(store.eventsAfter(200, 10).gap);
    }

    @Test
    void evictionByAge() {
        EventStore store = new EventStore(1000, 60_000, 1000, 1L << 30, clock::get);
        store.append(ev(EventType.REQUEST_CREATED, "old"));
        clock.addAndGet(120_000);
        store.append(ev(EventType.REQUEST_CREATED, "new"));
        EventStore.Page p = store.eventsAfter(0, 10);
        assertEquals(1, p.events.size());
        assertEquals("new", p.events.get(0).transactionId);
        assertNull(store.transaction("old"), "aged-out transaction metadata is released");
    }

    @Test
    void transactionTableIsBoundedAndReleasesLiveReferences() {
        EventStore store = new EventStore(10_000, 3_600_000, 10, 1L << 30, clock::get);
        for (int i = 0; i < 30; i++) {
            store.append(ev(EventType.REQUEST_CREATED, "t" + i));
        }
        assertEquals(10, store.stats().retainedTransactions);
        assertEquals(20, store.stats().transactionsEvicted);
        assertNull(store.transaction("t0"));
        assertTrue(store.transaction("t29") != null);
    }

    @Test
    void byteBudgetIsEnforced() {
        EventStore store = new EventStore(10_000, 3_600_000, 10_000, 64 * 1024, clock::get);
        for (int i = 0; i < 2000; i++) {
            store.append(ev(EventType.REQUEST_CREATED, "t" + i));
        }
        assertTrue(store.stats().approxRetainedBytes <= 64 * 1024 + 4096, "retained " + store.stats().approxRetainedBytes);
    }

    @Test
    void repeatedUpdatesKeepOneRecordWithLatestState() {
        EventStore store = new EventStore(1000, 3_600_000, 1000, 1L << 30, clock::get);
        store.append(ev(EventType.REQUEST_CREATED, "t1"));
        store.append(ev(EventType.RESPONSE_HEADERS_READY, "t1"));
        long id = store.append(ev(EventType.RESPONSE_COMPLETE, "t1"));
        TransactionRecord rec = store.transaction("t1");
        assertEquals(EventType.RESPONSE_COMPLETE, rec.lastEvent());
        assertEquals(id, rec.lastEventId());
        assertTrue(rec.isTerminal());
        assertEquals(1, store.stats().retainedTransactions);
    }

    @Test
    void liveReferenceIsWeak() {
        EventStore store = new EventStore(1000, 3_600_000, 1000, 1L << 30, clock::get);
        store.append(ev(EventType.REQUEST_CREATED, "t1"));
        store.clearLiveReference("t1");
        assertNull(store.transaction("t1").liveTransaction());
    }
}
