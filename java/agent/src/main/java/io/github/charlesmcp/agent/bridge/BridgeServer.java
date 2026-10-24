package io.github.charlesmcp.agent.bridge;

import io.github.charlesmcp.agent.config.AgentConfig;
import io.github.charlesmcp.agent.events.BridgeEvent;
import io.github.charlesmcp.agent.events.EventStore;
import io.github.charlesmcp.agent.events.TransactionRecord;
import io.github.charlesmcp.agent.instrumentation.BodyReader;
import io.github.charlesmcp.agent.json.Json;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static io.github.charlesmcp.agent.json.Json.obj;

/**
 * Authenticated, loopback-only, newline-delimited JSON server.
 *
 * <ul>
 *   <li>Binds to {@code 127.0.0.1} only (explicitly, not {@code getLoopbackAddress()}, because Charles
 *   runs with {@code preferIPv6Addresses=true}).</li>
 *   <li>The first message on every connection must be {@code hello} carrying the 256-bit per-process
 *   token (constant-time compare) within {@link #AUTH_TIMEOUT_MS}; otherwise the connection is closed.</li>
 *   <li>Request lines are capped at {@link #MAX_REQUEST_BYTES}; concurrent connections are capped.</li>
 *   <li>Read-only: there are no operations that change Charles state.</li>
 * </ul>
 */
public final class BridgeServer {
    private static final Logger LOG = Logger.getLogger("charles-mcp");
    public static final int PROTOCOL_VERSION = 1;
    static final int MAX_REQUEST_BYTES = 64 * 1024;
    static final int AUTH_TIMEOUT_MS = 5_000;
    static final int IDLE_TIMEOUT_MS = 30 * 60_000;
    static final int MAX_EVENTS_PER_PAGE = 1_000;
    static final byte[] LOOPBACK_V4 = {127, 0, 0, 1};

    private final AgentConfig config;
    private final BridgeBackend backend;
    private final String token;
    private final String bridgeVersion;
    private final Semaphore connectionSlots;
    private final AtomicLong authFailures = new AtomicLong();
    private final AtomicLong rejectedConnections = new AtomicLong();
    private final AtomicLong activeConnections = new AtomicLong();
    private final AtomicLong totalConnections = new AtomicLong();
    private volatile ServerSocket server;
    private volatile boolean running;

    public BridgeServer(AgentConfig config, BridgeBackend backend, String bridgeVersion) {
        this(config, backend, bridgeVersion, newToken());
    }

    BridgeServer(AgentConfig config, BridgeBackend backend, String bridgeVersion, String token) {
        this.config = config;
        this.backend = backend;
        this.bridgeVersion = bridgeVersion;
        this.token = token;
        this.connectionSlots = new Semaphore(config.maxConnections);
    }

    static String newToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    public String token() {
        return token;
    }

    public int port() {
        ServerSocket s = server;
        return s == null ? -1 : s.getLocalPort();
    }

    public InetAddress bindAddress() throws IOException {
        return InetAddress.getByAddress(LOOPBACK_V4);
    }

    public void start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(false);
        s.bind(new java.net.InetSocketAddress(bindAddress(), config.port), 16);
        this.server = s;
        this.running = true;
        Thread t = new Thread(this::acceptLoop, "charles-mcp-bridge-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            ServerSocket s = server;
            if (s != null) {
                s.close();
            }
        } catch (IOException ignored) {
            // closing
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket sock;
            try {
                sock = server.accept();
            } catch (IOException e) {
                if (running) {
                    LOG.warning("charles-mcp bridge accept failed: " + e.getClass().getSimpleName());
                }
                continue;
            }
            totalConnections.incrementAndGet();
            if (!sock.getInetAddress().isLoopbackAddress()) {
                // Defense in depth; cannot happen with a 127.0.0.1 bind.
                closeQuietly(sock);
                rejectedConnections.incrementAndGet();
                continue;
            }
            if (!connectionSlots.tryAcquire()) {
                rejectedConnections.incrementAndGet();
                try {
                    sock.setSoTimeout(1000);
                    writeLine(sock.getOutputStream(), error(null, "too_many_connections", "connection limit reached"));
                } catch (IOException ignored) {
                    // best effort
                }
                closeQuietly(sock);
                continue;
            }
            Thread t = new Thread(() -> {
                activeConnections.incrementAndGet();
                try {
                    serve(sock);
                } finally {
                    activeConnections.decrementAndGet();
                    connectionSlots.release();
                    closeQuietly(sock);
                }
            }, "charles-mcp-bridge-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void serve(Socket sock) {
        try {
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(AUTH_TIMEOUT_MS);
            InputStream in = new java.io.BufferedInputStream(sock.getInputStream(), 8192);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 64 * 1024);
            boolean authed = false;
            while (running) {
                String line;
                try {
                    line = readLine(in);
                } catch (SocketTimeoutException e) {
                    if (!authed) {
                        authFailures.incrementAndGet();
                        writeLine(out, error(null, "unauthorized", "authentication timeout"));
                    }
                    return;
                } catch (RequestTooLargeException e) {
                    writeLine(out, error(null, "request_too_large", "request exceeds " + MAX_REQUEST_BYTES + " bytes"));
                    return;
                }
                if (line == null) {
                    return;
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                Map<String, Object> req;
                try {
                    req = Json.parseObject(line);
                } catch (Json.JsonException e) {
                    writeLine(out, error(null, "bad_request", "invalid JSON"));
                    if (!authed) {
                        authFailures.incrementAndGet();
                        return;
                    }
                    continue;
                }
                Object id = req.get("id");
                String op = req.get("op") instanceof String ? (String) req.get("op") : "";
                if (!authed) {
                    if (!"hello".equals(op) || !tokenMatches(req.get("token"))) {
                        authFailures.incrementAndGet();
                        sleepQuietly(250); // slow down guessing; token is 256-bit anyway
                        writeLine(out, error(id, "unauthorized", "missing or invalid token"));
                        return;
                    }
                    authed = true;
                    sock.setSoTimeout(IDLE_TIMEOUT_MS);
                    writeLine(out, ok(id, obj("protocol", PROTOCOL_VERSION, "bridge_version", bridgeVersion)));
                    continue;
                }
                writeLine(out, handle(op, id, req));
            }
        } catch (SocketException e) {
            // client went away
        } catch (IOException e) {
            LOG.fine("charles-mcp bridge connection closed: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            LOG.warning("charles-mcp bridge handler error: " + e.getClass().getName());
        }
    }

    boolean tokenMatches(Object presented) {
        if (!(presented instanceof String)) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), ((String) presented).getBytes(StandardCharsets.UTF_8));
    }

    Map<String, Object> handle(String op, Object id, Map<String, Object> req) {
        try {
            switch (op) {
                case "ping":
                    return ok(id, obj("pong", true));
                case "status":
                    return ok(id, status());
                case "events":
                    return ok(id, events(req));
                case "transaction":
                    return transaction(id, req);
                case "hello":
                    return ok(id, obj("protocol", PROTOCOL_VERSION, "bridge_version", bridgeVersion));
                default:
                    return error(id, "unknown_op", "unsupported operation");
            }
        } catch (RuntimeException e) {
            return error(id, "internal_error", e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>(backend.status());
        EventStore.Stats st = backend.store().stats();
        m.put("protocol", PROTOCOL_VERSION);
        m.put("bridge_version", bridgeVersion);
        m.put("events", obj(
                "retained", st.retainedEvents,
                "appended", st.eventsAppended,
                "evicted", st.eventsEvicted,
                "latest_event_id", st.latestEventId));
        m.put("transactions", obj("retained", st.retainedTransactions, "evicted", st.transactionsEvicted));
        m.put("approx_retained_bytes", st.approxRetainedBytes);
        m.put("bridge", obj(
                "active_connections", activeConnections.get(),
                "total_connections", totalConnections.get(),
                "rejected_connections", rejectedConnections.get(),
                "auth_failures", authFailures.get()));
        m.put("limits", obj(
                "max_events", config.maxEvents,
                "max_transactions", config.maxTransactions,
                "max_event_age_seconds", config.maxEventAgeMillis / 1000,
                "max_body_bytes", config.maxBodyBytes,
                "bodies_allowed", config.allowBodies,
                "headers_redacted", config.redactHeaders));
        return m;
    }

    private Map<String, Object> events(Map<String, Object> req) {
        long after = asLong(req.get("after"), 0);
        int limit = (int) Math.max(1, Math.min(MAX_EVENTS_PER_PAGE, asLong(req.get("limit"), 200)));
        EventStore.Page page = backend.store().eventsAfter(after, limit);
        List<Object> evs = new ArrayList<>(page.events.size());
        for (BridgeEvent e : page.events) {
            evs.add(WireFormat.event(e));
        }
        return obj(
                "events", evs,
                "next_cursor", page.nextCursor,
                "oldest_event_id", page.oldestEventId,
                "latest_event_id", page.latestEventId,
                "gap", page.gap,
                "has_more", page.hasMore);
    }

    private Map<String, Object> transaction(Object id, Map<String, Object> req) {
        Object tid = req.get("transaction_id");
        if (!(tid instanceof String) || ((String) tid).length() > 64) {
            return error(id, "bad_request", "transaction_id required");
        }
        TransactionRecord rec = backend.store().transaction((String) tid);
        if (rec == null) {
            return error(id, "not_found", "unknown or evicted transaction");
        }
        Object live = rec.liveTransaction();
        Boolean attached = live == null ? Boolean.FALSE : backend.attachedToSession(live);
        Map<String, Object> m = WireFormat.transaction(rec, config.redactHeaders, attached);

        boolean wantReq = Boolean.TRUE.equals(req.get("include_request_body"));
        boolean wantResp = Boolean.TRUE.equals(req.get("include_response_body"));
        if (wantReq || wantResp) {
            int max = (int) Math.max(0, Math.min(config.maxBodyBytes, asLong(req.get("max_body_bytes"), 64 * 1024)));
            if (wantReq) {
                m.put("request_body", body(live, false, max));
            }
            if (wantResp) {
                m.put("response_body", body(live, true, max));
            }
        }
        return ok(id, m);
    }

    private Map<String, Object> body(Object live, boolean response, int max) {
        if (!config.allowBodies) {
            return obj("available", false, "reason", "disabled_by_bridge_config");
        }
        if (live == null) {
            return obj("available", false, "reason", "released_by_charles");
        }
        try {
            BodyReader.Body b = backend.readBody(live, response, max);
            if (b == null) {
                return obj("available", false, "reason", "no_body");
            }
            return obj(
                    "available", true,
                    "base64", Base64.getEncoder().encodeToString(b.bytes),
                    "returned_bytes", b.bytes.length,
                    "captured_bytes", b.capturedSize,
                    "truncated", b.truncated,
                    "decoded", b.decoded,
                    "content_encoding", b.contentEncoding);
        } catch (Exception e) {
            return obj("available", false, "reason", "read_error", "error_type", e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------------------------------

    static Map<String, Object> ok(Object id, Map<String, Object> result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("ok", true);
        m.put("result", result);
        return m;
    }

    static Map<String, Object> error(Object id, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("ok", false);
        m.put("error", obj("code", code, "message", message));
        return m;
    }

    private static long asLong(Object o, long def) {
        return o instanceof Number ? ((Number) o).longValue() : def;
    }

    private static final class RequestTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /** Reads one LF-terminated UTF-8 line, enforcing the size cap. Returns null on EOF. */
    static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(256);
        while (true) {
            int c = in.read();
            if (c < 0) {
                return buf.size() == 0 ? null : buf.toString("UTF-8");
            }
            if (c == '\n') {
                return buf.toString("UTF-8");
            }
            if (buf.size() >= MAX_REQUEST_BYTES) {
                throw new RequestTooLargeException();
            }
            buf.write(c);
        }
    }

    static void writeLine(OutputStream out, Map<String, Object> msg) throws IOException {
        out.write(Json.write(msg).getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.flush();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long authFailures() {
        return authFailures.get();
    }
}
