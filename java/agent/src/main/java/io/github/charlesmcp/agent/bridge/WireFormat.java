package io.github.charlesmcp.agent.bridge;

import io.github.charlesmcp.agent.events.BridgeEvent;
import io.github.charlesmcp.agent.events.TransactionRecord;
import io.github.charlesmcp.agent.events.TransactionSnapshot;
import io.github.charlesmcp.agent.redaction.HeaderRedactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.charlesmcp.agent.json.Json.obj;

/**
 * JSON shapes sent over the bridge. Versioned by {@link #SCHEMA_VERSION}; the canonical description is
 * {@code schemas/bridge-event.v1.schema.json}. Changing a field's meaning requires a version bump.
 */
public final class WireFormat {
    public static final int SCHEMA_VERSION = 1;

    private WireFormat() {
    }

    public static Map<String, Object> event(BridgeEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", SCHEMA_VERSION);
        m.put("event_id", e.eventId);
        m.put("session_id", e.sessionId);
        m.put("transaction_id", e.transactionId);
        m.put("event_type", e.type.wireName);
        m.put("timestamp_ms", e.timestampMs);
        if (e.transactionId != null) {
            m.put("request", obj("method", e.method, "url", e.url, "host", e.host));
            m.put("response", obj("status", e.statusCode == 0 ? null : e.statusCode, "content_type", e.contentType));
            m.put("timing", obj("start_ms", e.startTimeMs, "end_ms", e.endTimeMs, "duration_ms", e.durationMs));
            m.put("sizes", obj("request_bytes", e.requestSize, "response_bytes", e.responseSize));
            m.put("charles_status", e.charlesStatus);
            m.put("error_type", e.errorType);
        }
        // URLs are sent as captured; query-string redaction is applied by the sidecar before any
        // MCP client sees them (see docs/security.md).
        return m;
    }

    public static Map<String, Object> transaction(TransactionRecord rec, boolean redact, Boolean attached) {
        TransactionSnapshot s = rec.latest();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", SCHEMA_VERSION);
        m.put("transaction_id", rec.transactionId);
        m.put("session_id", rec.sessionId);
        m.put("last_event", rec.lastEvent() == null ? null : rec.lastEvent().wireName);
        m.put("last_event_id", rec.lastEventId());
        m.put("complete", rec.isTerminal());
        m.put("first_seen_ms", rec.firstSeenMs);
        m.put("updated_ms", rec.updatedMs());
        m.put("attached_to_session", attached);
        if (s != null) {
            m.put("request", obj(
                    "method", s.method,
                    "url", s.url(),
                    "scheme", s.scheme,
                    "host", s.host,
                    "port", s.port > 0 ? s.port : null,
                    "path", s.path,
                    "query", s.query,
                    "protocol_version", s.protocolVersion,
                    "headers", headers(s.requestHeaders, redact),
                    "header_bytes", s.requestHeaderSize,
                    "body_bytes", s.requestSize,
                    "content_type", s.requestHeader("Content-Type")));
            m.put("response", obj(
                    "status", s.statusCode == 0 ? null : s.statusCode,
                    "headers", headers(s.responseHeaders, redact),
                    "header_bytes", s.responseHeaderSize,
                    "body_bytes", s.responseSize,
                    "content_type", s.responseHeader("Content-Type")));
            m.put("timing", obj(
                    "start_ms", s.startTimeMs,
                    "request_begin_ms", s.requestBeginMs,
                    "request_complete_ms", s.requestCompleteMs,
                    "response_begin_ms", s.responseBeginMs,
                    "end_ms", s.endTimeMs,
                    "duration_ms", s.durationMs(),
                    "dns_ms", s.dnsMs,
                    "connect_ms", s.connectMs,
                    "tls_handshake_ms", s.tlsHandshakeMs));
            m.put("connection", obj(
                    "remote_address", s.remoteAddress,
                    "client_address", s.clientAddress,
                    "alpn", s.alpnProtocol,
                    "tls_protocol", s.tlsProtocol,
                    "cipher_suite", s.cipherSuite,
                    "websocket", s.webSocket,
                    "tunnel", s.tunnel));
            m.put("charles_status", s.charlesStatus);
            m.put("error", s.errorType == null ? null : obj("type", s.errorType, "message", s.errorMessage));
        }
        m.put("headers_redacted", redact);
        return m;
    }

    static List<Object> headers(List<TransactionSnapshot.Header> hs, boolean redact) {
        List<Object> out = new ArrayList<>(hs.size());
        for (TransactionSnapshot.Header h : hs) {
            String v = redact ? HeaderRedactor.redactValue(h.name, h.value) : h.value;
            out.add(obj("name", h.name, "value", v));
        }
        return out;
    }
}
