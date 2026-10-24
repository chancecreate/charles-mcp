package io.github.charlesmcp.agent.instrumentation;

import io.github.charlesmcp.agent.events.EventPipeline;
import io.github.charlesmcp.agent.events.EventType;
import io.github.charlesmcp.agent.events.RawEvent;
import io.github.charlesmcp.agent.events.TransactionSnapshot;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Receives Charles proxy lifecycle callbacks (as a dynamic proxy implementing
 * {@code com.xk72.proxy.ProxyListener}) and turns them into {@link RawEvent}s.
 *
 * <p>Ordering: Charles keeps listeners in a stably-sorted list where un-ordered listeners sort last, and
 * the session recorder is registered first, at ProxyManager construction. We register later, so for
 * every callback the recorder has <em>already</em> updated the Transaction we read.
 *
 * <p>Safety: every callback is wrapped in a catch-all; the handler never throws into Charles, never
 * blocks (queue offer is non-blocking), and does no IO.
 */
public final class LifecycleListener implements InvocationHandler {
    private static final Logger LOG = Logger.getLogger("charles-mcp");
    /** Our per-ProxyEvent attribute key (a unique object, so it can never collide with Charles keys). */
    static final Object HANDLE_KEY = new Object() {
        @Override
        public String toString() {
            return "io.github.charlesmcp.TransactionHandle";
        }
    };

    private final CharlesApi api;
    private final SnapshotFactory snapshots;
    private final EventPipeline pipeline;
    private final LongSupplier clock;
    private final boolean debugLifecycle;
    private final AtomicLong nextTransactionId = new AtomicLong(1);
    private final AtomicLong callbackErrors = new AtomicLong();
    private final AtomicLong duplicateTerminalsSuppressed = new AtomicLong();
    private final AtomicLong ignoredOwnTraffic = new AtomicLong();
    private volatile Object proxyManager;
    private volatile int ownBridgePort = -1;

    // Session identity: cache the last seen session object (weakly) and its generated id.
    private volatile WeakReference<Object> lastSession = new WeakReference<>(null);
    private volatile String lastSessionId = null;
    private final Object sessionLock = new Object();

    /** Per-transaction state stored on the ProxyEvent. */
    static final class Handle {
        final WeakReference<Object> transaction;
        final String id;
        final AtomicBoolean terminal = new AtomicBoolean();

        Handle(Object transaction, String id) {
            this.transaction = new WeakReference<>(transaction);
            this.id = id;
        }
    }

    public LifecycleListener(CharlesApi api, EventPipeline pipeline, LongSupplier clock, boolean debugLifecycle) {
        this.api = api;
        this.snapshots = new SnapshotFactory(api);
        this.pipeline = pipeline;
        this.clock = clock;
        this.debugLifecycle = debugLifecycle;
    }

    /** Creates the dynamic proxy that Charles will call. */
    public Object asProxyListener() {
        return Proxy.newProxyInstance(api.loader, new Class<?>[]{api.proxyListenerInterface}, this);
    }

    public void bind(Object proxyManager, int bridgePort) {
        this.proxyManager = proxyManager;
        this.ownBridgePort = bridgePort;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if (method.getDeclaringClass() == Object.class) {
            switch (name) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                default:
                    return "charles-mcp-lifecycle-listener";
            }
        }
        EventType type = mapCallback(name);
        if (type == null || args == null || args.length == 0 || args[0] == null) {
            return null; // high-frequency callbacks (body chunks, ws frames) return immediately
        }
        try {
            onCallback(type, args[0]);
        } catch (Throwable t) {
            callbackErrors.incrementAndGet();
            if (callbackErrors.get() <= 10) {
                LOG.log(Level.WARNING, "charles-mcp listener error (ignored, Charles unaffected): " + t.getClass().getName());
            }
        }
        return null;
    }

    static EventType mapCallback(String name) {
        switch (name) {
            case "receivedRequestURL":
                return EventType.REQUEST_CREATED;
            case "receivedRequestHeader":
                return EventType.REQUEST_HEADERS_READY;
            case "receivedRequest":
                return EventType.REQUEST_COMPLETE;
            case "receivedResponseHeader":
                return EventType.RESPONSE_HEADERS_READY;
            case "transactionComplete":
                return EventType.RESPONSE_COMPLETE; // may be re-classified as FAILED below
            case "transactionException":
            case "transactionBlocked":
                return EventType.FAILED;
            case "cancelTransaction":
                return EventType.REMOVED;
            default:
                return null;
        }
    }

    private void onCallback(EventType type, Object proxyEvent) throws ReflectiveOperationException {
        Object txn = api.eventAttribute(proxyEvent, CharlesApi.RECORDER_TRANSACTION_ATTRIBUTE);
        Handle handle = (Handle) asHandle(api.eventAttribute(proxyEvent, HANDLE_KEY));

        if (type == EventType.REMOVED) {
            // Charles has already detached the transaction; report only ones we have seen.
            if (handle != null && handle.terminal.compareAndSet(false, true)) {
                emit(EventType.REMOVED, handle, null, null);
            }
            return;
        }
        if (!api.transactionClass.isInstance(txn)) {
            return; // not recorded (recording off, DONT_RECORD, or not yet created by the recorder)
        }
        if (handle == null || handle.transaction.get() != txn) {
            // First sighting of this Transaction (also covers Charles replacing it on a session switch).
            handle = new Handle(txn, "t" + nextTransactionId.getAndIncrement());
            api.setEventAttribute(proxyEvent, HANDLE_KEY, handle);
            if (isOwnTraffic(txn)) {
                handle.terminal.set(true);
                ignoredOwnTraffic.incrementAndGet();
                return;
            }
            if (type != EventType.REQUEST_CREATED) {
                emit(EventType.REQUEST_CREATED, handle, txn, snapshots.capture(txn));
            }
        }
        if (handle.terminal.get()) {
            if (type.terminal) {
                duplicateTerminalsSuppressed.incrementAndGet();
            }
            return;
        }
        if (type == EventType.RESPONSE_COMPLETE && snapshots.hasException(txn)) {
            type = EventType.FAILED;
        }
        if (type.terminal && !handle.terminal.compareAndSet(false, true)) {
            duplicateTerminalsSuppressed.incrementAndGet();
            return;
        }
        emit(type, handle, txn, snapshots.capture(txn));
    }

    private static Object asHandle(Object o) {
        return o instanceof Handle ? o : null;
    }

    private boolean isOwnTraffic(Object txn) throws ReflectiveOperationException {
        int port = ownBridgePort;
        if (port <= 0) {
            return false;
        }
        Object host = api.tx(txn, "getHost");
        Object p = api.tx(txn, "getPort");
        if (!(p instanceof Number) || ((Number) p).intValue() != port || host == null) {
            return false;
        }
        String h = host.toString().toLowerCase(Locale.ROOT);
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1") || h.equals("[::1]");
    }

    private void emit(EventType type, Handle handle, Object txn, TransactionSnapshot snap) {
        long now = clock.getAsLong();
        String sessionId = sessionId();
        pipeline.offer(new RawEvent(type, now, sessionId, handle.id, snap, txn));
        if (debugLifecycle) {
            // Deliberately no path, query, headers or bodies.
            LOG.info(String.format(Locale.ROOT, "charles-mcp lifecycle ts=%d session=%s txn=%s event=%s method=%s host=%s status=%s charles_status=%s",
                    now, sessionId, handle.id, type.wireName,
                    snap == null ? "-" : snap.method, snap == null ? "-" : snap.host,
                    snap == null ? "-" : Integer.toString(snap.statusCode), snap == null ? "-" : snap.charlesStatus));
        }
    }

    /** Returns a stable id for the current recording session, emitting session_changed on change. */
    String sessionId() {
        Object pm = proxyManager;
        Object current = null;
        if (pm != null) {
            try {
                current = api.currentSession(pm);
            } catch (ReflectiveOperationException | RuntimeException e) {
                current = null;
            }
        }
        if (current == null) {
            String last = lastSessionId;
            return last == null ? "none" : last;
        }
        if (lastSession.get() == current) {
            return lastSessionId;
        }
        synchronized (sessionLock) {
            if (lastSession.get() == current) {
                return lastSessionId;
            }
            String id = "s-" + UUID.randomUUID().toString().substring(0, 8);
            lastSession = new WeakReference<>(current);
            lastSessionId = id;
            // Emitted before the transaction event that revealed the change, so clients see it first.
            pipeline.offer(new RawEvent(EventType.SESSION_CHANGED, clock.getAsLong(), id, null, null, null));
            return id;
        }
    }

    public String currentSessionIdOrNull() {
        return lastSessionId;
    }

    public long callbackErrors() {
        return callbackErrors.get();
    }

    public long duplicateTerminalsSuppressed() {
        return duplicateTerminalsSuppressed.get();
    }

    public long ignoredOwnTraffic() {
        return ignoredOwnTraffic.get();
    }

    public long transactionsSeen() {
        return nextTransactionId.get() - 1;
    }
}
