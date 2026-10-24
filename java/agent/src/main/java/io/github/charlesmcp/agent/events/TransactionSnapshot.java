package io.github.charlesmcp.agent.events;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Immutable, Charles-independent copy of a transaction's metadata at one lifecycle point.
 *
 * <p>Built on the proxy thread from cheap field reads only (no body access, no IO). Header lists are
 * copied so the snapshot never aliases mutable Charles objects.
 */
public final class TransactionSnapshot {
    /** One header line. Values are stored as Charles captured them; redaction happens on output. */
    public static final class Header {
        public final String name;
        public final String value;

        public Header(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    public final String method;
    public final String scheme;
    public final String host;
    /** Explicit port from the URL, or -1 when the scheme default applies. */
    public final int port;
    public final String path;
    public final String query;
    public final String protocolVersion;
    /** HTTP status code; 0 while no response header has been received. */
    public final int statusCode;
    public final List<Header> requestHeaders;
    public final List<Header> responseHeaders;
    public final long requestSize;
    public final long responseSize;
    public final int requestHeaderSize;
    public final int responseHeaderSize;
    public final Long startTimeMs;
    public final Long requestBeginMs;
    public final Long requestCompleteMs;
    public final Long responseBeginMs;
    public final Long endTimeMs;
    public final Long dnsMs;
    public final Long connectMs;
    public final Long tlsHandshakeMs;
    /** Charles's own status label, e.g. "complete", "failed", "blocked". */
    public final String charlesStatus;
    public final String errorType;
    public final String errorMessage;
    public final String remoteAddress;
    public final String clientAddress;
    public final String alpnProtocol;
    public final String tlsProtocol;
    public final String cipherSuite;
    public final boolean webSocket;
    public final boolean tunnel;

    private TransactionSnapshot(Builder b) {
        this.method = b.method;
        this.scheme = b.scheme;
        this.host = b.host;
        this.port = b.port;
        this.path = b.path;
        this.query = b.query;
        this.protocolVersion = b.protocolVersion;
        this.statusCode = b.statusCode;
        this.requestHeaders = b.requestHeaders == null ? Collections.emptyList() : Collections.unmodifiableList(b.requestHeaders);
        this.responseHeaders = b.responseHeaders == null ? Collections.emptyList() : Collections.unmodifiableList(b.responseHeaders);
        this.requestSize = b.requestSize;
        this.responseSize = b.responseSize;
        this.requestHeaderSize = b.requestHeaderSize;
        this.responseHeaderSize = b.responseHeaderSize;
        this.startTimeMs = b.startTimeMs;
        this.requestBeginMs = b.requestBeginMs;
        this.requestCompleteMs = b.requestCompleteMs;
        this.responseBeginMs = b.responseBeginMs;
        this.endTimeMs = b.endTimeMs;
        this.dnsMs = b.dnsMs;
        this.connectMs = b.connectMs;
        this.tlsHandshakeMs = b.tlsHandshakeMs;
        this.charlesStatus = b.charlesStatus;
        this.errorType = b.errorType;
        this.errorMessage = b.errorMessage;
        this.remoteAddress = b.remoteAddress;
        this.clientAddress = b.clientAddress;
        this.alpnProtocol = b.alpnProtocol;
        this.tlsProtocol = b.tlsProtocol;
        this.cipherSuite = b.cipherSuite;
        this.webSocket = b.webSocket;
        this.tunnel = b.tunnel;
    }

    public String url() {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) {
            sb.append(scheme).append("://");
        }
        if (host != null) {
            sb.append(host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host);
        }
        if (port > 0) {
            sb.append(':').append(port);
        }
        if (path != null) {
            sb.append(path);
        }
        if (query != null) {
            sb.append('?').append(query);
        }
        return sb.toString();
    }

    public String responseHeader(String name) {
        for (Header h : responseHeaders) {
            if (h.name != null && h.name.equalsIgnoreCase(name)) {
                return h.value;
            }
        }
        return null;
    }

    public String requestHeader(String name) {
        for (Header h : requestHeaders) {
            if (h.name != null && h.name.equalsIgnoreCase(name)) {
                return h.value;
            }
        }
        return null;
    }

    public String responseContentType() {
        String ct = responseHeader("Content-Type");
        return ct == null ? null : ct.trim().toLowerCase(Locale.ROOT);
    }

    /** Wall-clock duration as Charles defines it: request start to transaction end. */
    public Long durationMs() {
        if (startTimeMs == null || endTimeMs == null) {
            return null;
        }
        return Math.max(0L, endTimeMs - startTimeMs);
    }

    /** Rough retained-heap estimate used for memory bounding. */
    public long estimatedBytes() {
        long n = 256;
        n += len(method) + len(scheme) + len(host) + len(path) + len(query) + len(errorMessage);
        for (Header h : requestHeaders) {
            n += 32 + len(h.name) + len(h.value);
        }
        for (Header h : responseHeaders) {
            n += 32 + len(h.name) + len(h.value);
        }
        return n;
    }

    private static long len(String s) {
        return s == null ? 0 : 2L * s.length() + 24;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String method;
        private String scheme;
        private String host;
        private int port = -1;
        private String path;
        private String query;
        private String protocolVersion;
        private int statusCode;
        private List<Header> requestHeaders;
        private List<Header> responseHeaders;
        private long requestSize;
        private long responseSize;
        private int requestHeaderSize;
        private int responseHeaderSize;
        private Long startTimeMs;
        private Long requestBeginMs;
        private Long requestCompleteMs;
        private Long responseBeginMs;
        private Long endTimeMs;
        private Long dnsMs;
        private Long connectMs;
        private Long tlsHandshakeMs;
        private String charlesStatus;
        private String errorType;
        private String errorMessage;
        private String remoteAddress;
        private String clientAddress;
        private String alpnProtocol;
        private String tlsProtocol;
        private String cipherSuite;
        private boolean webSocket;
        private boolean tunnel;

        public Builder method(String v) { this.method = v; return this; }
        public Builder scheme(String v) { this.scheme = v; return this; }
        public Builder host(String v) { this.host = v; return this; }
        public Builder port(int v) { this.port = v; return this; }
        public Builder path(String v) { this.path = v; return this; }
        public Builder query(String v) { this.query = v; return this; }
        public Builder protocolVersion(String v) { this.protocolVersion = v; return this; }
        public Builder statusCode(int v) { this.statusCode = v; return this; }
        public Builder requestHeaders(List<Header> v) { this.requestHeaders = v; return this; }
        public Builder responseHeaders(List<Header> v) { this.responseHeaders = v; return this; }
        public Builder requestSize(long v) { this.requestSize = v; return this; }
        public Builder responseSize(long v) { this.responseSize = v; return this; }
        public Builder requestHeaderSize(int v) { this.requestHeaderSize = v; return this; }
        public Builder responseHeaderSize(int v) { this.responseHeaderSize = v; return this; }
        public Builder startTimeMs(Long v) { this.startTimeMs = v; return this; }
        public Builder requestBeginMs(Long v) { this.requestBeginMs = v; return this; }
        public Builder requestCompleteMs(Long v) { this.requestCompleteMs = v; return this; }
        public Builder responseBeginMs(Long v) { this.responseBeginMs = v; return this; }
        public Builder endTimeMs(Long v) { this.endTimeMs = v; return this; }
        public Builder dnsMs(Long v) { this.dnsMs = v; return this; }
        public Builder connectMs(Long v) { this.connectMs = v; return this; }
        public Builder tlsHandshakeMs(Long v) { this.tlsHandshakeMs = v; return this; }
        public Builder charlesStatus(String v) { this.charlesStatus = v; return this; }
        public Builder errorType(String v) { this.errorType = v; return this; }
        public Builder errorMessage(String v) { this.errorMessage = v; return this; }
        public Builder remoteAddress(String v) { this.remoteAddress = v; return this; }
        public Builder clientAddress(String v) { this.clientAddress = v; return this; }
        public Builder alpnProtocol(String v) { this.alpnProtocol = v; return this; }
        public Builder tlsProtocol(String v) { this.tlsProtocol = v; return this; }
        public Builder cipherSuite(String v) { this.cipherSuite = v; return this; }
        public Builder webSocket(boolean v) { this.webSocket = v; return this; }
        public Builder tunnel(boolean v) { this.tunnel = v; return this; }

        public TransactionSnapshot build() {
            return new TransactionSnapshot(this);
        }
    }
}
