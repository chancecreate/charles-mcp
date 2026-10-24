package io.github.charlesmcp.agent.instrumentation;

import com.xk72.charles.model.Session;
import com.xk72.charles.model.Transaction;
import com.xk72.proxy.Fields;
import com.xk72.proxy.ProxyEvent;
import io.github.charlesmcp.agent.events.BridgeEvent;
import io.github.charlesmcp.agent.events.EventType;
import io.github.charlesmcp.agent.events.TransactionRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleListenerTest {

    private static List<EventType> types(FakeCharles fc) {
        List<EventType> out = new ArrayList<>();
        for (BridgeEvent e : fc.store.eventsAfter(0, 1000).events) {
            out.add(e.type);
        }
        return out;
    }

    @Test
    void fullLifecycleProducesOrderedEventsWithOneTerminal() throws Exception {
        FakeCharles fc = new FakeCharles();
        fc.flow().created("GET", "api.example.test", "/v1/items")
                .requestHeaders(new Fields().add("Accept", "application/json"))
                .requestDone()
                .responseHeaders(200, new Fields().add("Content-Type", "application/json"))
                .responseBody("{\"ok\":true}".getBytes())
                .complete();
        fc.flush();
        assertEquals(List.of(EventType.SESSION_CHANGED, EventType.REQUEST_CREATED, EventType.REQUEST_HEADERS_READY,
                EventType.REQUEST_COMPLETE, EventType.RESPONSE_HEADERS_READY, EventType.RESPONSE_COMPLETE), types(fc));
        List<BridgeEvent> evs = fc.store.eventsAfter(0, 100).events;
        for (int i = 1; i < evs.size(); i++) {
            assertTrue(evs.get(i).eventId > evs.get(i - 1).eventId, "event ids strictly increase");
        }
        BridgeEvent last = evs.get(evs.size() - 1);
        assertEquals(200, last.statusCode);
        assertEquals("application/json", last.contentType);
        assertEquals("http://api.example.test/v1/items", last.url);
    }

    @Test
    void responseHeadersArriveAsynchronouslyAndUpdateLatestSnapshot() throws Exception {
        FakeCharles fc = new FakeCharles();
        FakeCharles.Flow f = fc.flow().created("GET", "h.test", "/").requestHeaders(new Fields().add("A", "1")).requestDone();
        fc.flush();
        String id = fc.store.eventsAfter(0, 100).events.get(1).transactionId;
        TransactionRecord rec = fc.store.transaction(id);
        assertEquals(0, rec.latest().statusCode, "no response yet");
        assertTrue(rec.latest().responseHeaders.isEmpty());
        assertFalse(rec.isTerminal());

        f.responseHeaders(404, new Fields().add("Content-Type", "text/plain"));
        fc.flush();
        assertEquals(404, fc.store.transaction(id).latest().statusCode);
        assertFalse(fc.store.transaction(id).isTerminal());

        f.responseBody(new byte[]{1, 2}).complete();
        fc.flush();
        assertTrue(fc.store.transaction(id).isTerminal());
        assertEquals(EventType.RESPONSE_COMPLETE, fc.store.transaction(id).lastEvent());
    }

    @Test
    void failureThenCompletionReportsExactlyOneTerminalEvent() throws Exception {
        FakeCharles fc = new FakeCharles();
        FakeCharles.Flow f = fc.flow().created("GET", "down.test", "/").requestHeaders(new Fields());
        f.fail(new IOException("Connection refused"));
        f.complete(); // Charles may also signal completion after an exception
        fc.flush();
        List<EventType> t = types(fc);
        assertEquals(1, t.stream().filter(e -> e.terminal).count());
        assertEquals(EventType.FAILED, t.get(t.size() - 1));
        assertEquals(1, fc.listener.duplicateTerminalsSuppressed());
        TransactionRecord rec = fc.store.transaction(fc.store.eventsAfter(0, 10).events.get(1).transactionId);
        assertEquals("IOException", rec.latest().errorType);
    }

    @Test
    void completionWithExceptionIsClassifiedAsFailed() throws Exception {
        FakeCharles fc = new FakeCharles();
        FakeCharles.Flow f = fc.flow().created("GET", "x.test", "/");
        f.txn.exception = new IllegalStateException("reset");
        f.complete();
        fc.flush();
        List<EventType> t = types(fc);
        assertEquals(EventType.FAILED, t.get(t.size() - 1));
    }

    @Test
    void unrecordedTrafficIsIgnored() throws Exception {
        FakeCharles fc = new FakeCharles();
        ProxyEvent ev = new ProxyEvent(); // no recorder attribute: recording off / DONT_RECORD
        fc.mux().receivedRequestURL(ev);
        fc.mux().transactionComplete(ev);
        fc.flush();
        assertEquals(List.of(), types(fc));
        assertEquals(0, fc.listener.transactionsSeen());
    }

    @Test
    void firstSightingMidLifecycleSynthesizesCreatedOnce() throws Exception {
        FakeCharles fc = new FakeCharles();
        // e.g. a CONNECT tunnel the recorder only materialises later
        ProxyEvent ev = new ProxyEvent();
        Transaction t = new Transaction();
        ev.setAttribute(CharlesApi.RECORDER_TRANSACTION_ATTRIBUTE, t);
        fc.mux().receivedResponseHeader(ev);
        fc.mux().transactionComplete(ev);
        fc.flush();
        assertEquals(List.of(EventType.SESSION_CHANGED, EventType.REQUEST_CREATED, EventType.RESPONSE_HEADERS_READY,
                EventType.RESPONSE_COMPLETE), types(fc));
    }

    @Test
    void replacedTransactionGetsNewIdentity() throws Exception {
        FakeCharles fc = new FakeCharles();
        FakeCharles.Flow f = fc.flow().created("GET", "t.test", "/");
        // Charles replaces the Transaction on a session switch for tunnels
        Transaction replacement = new Transaction();
        f.event.setAttribute(CharlesApi.RECORDER_TRANSACTION_ATTRIBUTE, replacement);
        fc.mux().transactionComplete(f.event);
        fc.flush();
        List<BridgeEvent> evs = fc.store.eventsAfter(0, 100).events;
        assertEquals("t1", evs.get(1).transactionId);
        assertEquals("t2", evs.get(evs.size() - 1).transactionId);
        assertEquals(2, fc.listener.transactionsSeen());
    }

    @Test
    void cancelEmitsRemovedOnlyForKnownTransactions() throws Exception {
        FakeCharles fc = new FakeCharles();
        fc.mux().cancelTransaction(new ProxyEvent()); // unknown: nothing
        FakeCharles.Flow f = fc.flow().created("GET", "c.test", "/");
        fc.mux().cancelTransaction(f.event);
        fc.mux().cancelTransaction(f.event);
        fc.flush();
        assertEquals(List.of(EventType.SESSION_CHANGED, EventType.REQUEST_CREATED, EventType.REMOVED), types(fc));
    }

    @Test
    void sessionSwitchEmitsSessionChanged() throws Exception {
        FakeCharles fc = new FakeCharles();
        fc.flow().created("GET", "a.test", "/").complete();
        fc.context.getProxyManager().a(new Session());
        fc.flow().created("GET", "b.test", "/").complete();
        fc.flush();
        List<BridgeEvent> evs = fc.store.eventsAfter(0, 100).events;
        long changes = evs.stream().filter(e -> e.type == EventType.SESSION_CHANGED).count();
        assertEquals(2, changes);
        assertFalse(evs.get(1).sessionId.equals(evs.get(evs.size() - 1).sessionId));
    }

    @Test
    void listenerNeverThrowsIntoCharles() throws Exception {
        FakeCharles fc = new FakeCharles();
        FakeCharles.Flow f = fc.flow();
        f.txn.throwOnGetHost = true;
        f.event.setAttribute(CharlesApi.RECORDER_TRANSACTION_ATTRIBUTE, f.txn);
        fc.mux().receivedRequestURL(f.event); // must not throw
        fc.mux().transactionComplete(f.event);
        assertTrue(fc.listener.callbackErrors() >= 1);
    }

    @Test
    void highFrequencyCallbacksAreIgnoredCheaply() throws Exception {
        FakeCharles fc = new FakeCharles();
        FakeCharles.Flow f = fc.flow().created("GET", "s.test", "/");
        for (int i = 0; i < 10_000; i++) {
            fc.mux().receivingResponseBody(f.event, new byte[0], 0);
        }
        fc.flush();
        assertEquals(2, fc.store.stats().eventsAppended, "body chunks produce no events");
    }

    @Test
    void ownBridgeTrafficIsExcluded() throws Exception {
        FakeCharles fc = new FakeCharles();
        fc.listener.bind(fc.context.getProxyManager(), 5555);
        FakeCharles.Flow f = fc.flow();
        f.txn.port = 5555;
        f.created("GET", "127.0.0.1", "/");
        f.complete();
        fc.flush();
        assertEquals(1, fc.listener.ignoredOwnTraffic());
        assertTrue(types(fc).stream().noneMatch(e -> e == EventType.REQUEST_CREATED));
    }

    @Test
    void registrationIsVerifiedByEffect() throws Exception {
        FakeCharles fc = new FakeCharles();
        assertTrue(fc.api.isRegistered(fc.context.getProxyManager(), fc.proxyListener));
        assertNotNull(fc.api.currentSession(fc.context.getProxyManager()));
    }
}
