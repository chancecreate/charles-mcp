package io.github.charlesmcp.agent.instrumentation;

import com.xk72.charles.CharlesContext;
import com.xk72.charles.model.Session;
import com.xk72.charles.model.Transaction;
import com.xk72.proxy.Fields;
import com.xk72.proxy.ProxyEvent;
import com.xk72.proxy.ProxyEventStatus;
import com.xk72.proxy.n;
import io.github.charlesmcp.agent.events.EventPipeline;
import io.github.charlesmcp.agent.events.EventStore;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives the agent the way Charles does: a recorder sets the Transaction attribute, then the
 * multiplexer dispatches callbacks to every registered listener (ours included).
 */
public final class FakeCharles {
    public final CharlesContext context = new CharlesContext("4.6.2");
    public final Session session = new Session();
    public final CharlesApi api;
    public final AtomicLong clock = new AtomicLong(1_000_000);
    public final EventStore store;
    public final EventPipeline pipeline;
    public final LifecycleListener listener;
    public final Object proxyListener;

    public FakeCharles() throws Exception {
        this(64);
    }

    public FakeCharles(int queueCapacity) throws Exception {
        CharlesContext.setInstanceForTest(context);
        context.getProxyManager().a(session);
        api = CharlesApi.resolve(CharlesContext.class);
        store = new EventStore(10_000, 3_600_000, 1_000, 64L << 20, clock::get);
        pipeline = new EventPipeline(queueCapacity, store);
        listener = new LifecycleListener(api, pipeline, clock::get, false);
        listener.bind(context.getProxyManager(), 1);
        proxyListener = listener.asProxyListener();
        api.register(context.getProxyManager(), proxyListener);
    }

    public n mux() {
        return context.getProxyManager().p();
    }

    /** One in-flight request as Charles sees it. */
    public final class Flow {
        public final ProxyEvent event = new ProxyEvent();
        public final Transaction txn = new Transaction();

        public Flow created(String method, String host, String path) {
            txn.method = method;
            txn.host = host;
            txn.path = path;
            // The recorder runs before us and stores the Transaction on the event.
            event.setAttribute(CharlesApi.RECORDER_TRANSACTION_ATTRIBUTE, txn);
            txn.setParentForTest(session);
            mux().receivedRequestURL(event);
            return this;
        }

        public Flow requestHeaders(Fields f) {
            txn.requestHeader = f;
            mux().receivedRequestHeader(event);
            return this;
        }

        public Flow requestDone() {
            mux().receivedRequest(event);
            return this;
        }

        public Flow responseHeaders(int status, Fields f) {
            txn.responseStatus = status;
            txn.responseHeader = f;
            mux().receivedResponseHeader(event);
            return this;
        }

        public Flow responseBody(byte[] body) {
            txn.responseBody = body;
            txn.responseSize = body.length;
            mux().receivingResponseBody(event, body, body.length);
            mux().receivedResponse(event);
            return this;
        }

        public Flow complete() {
            txn.status = ProxyEventStatus.COMPLETE;
            txn.endTime = new java.util.Date(txn.startTime.getTime() + 25);
            mux().transactionComplete(event);
            return this;
        }

        public Flow fail(Exception e) {
            txn.exception = e;
            txn.status = ProxyEventStatus.FAILED;
            mux().transactionException(event);
            return this;
        }
    }

    public Flow flow() {
        return new Flow();
    }

    public void flush() {
        pipeline.flushForTest();
    }
}
