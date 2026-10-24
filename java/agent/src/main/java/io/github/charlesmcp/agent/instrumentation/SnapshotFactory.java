package io.github.charlesmcp.agent.instrumentation;

import io.github.charlesmcp.agent.events.TransactionSnapshot;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Copies a Charles Transaction's metadata into an immutable {@link TransactionSnapshot}.
 *
 * <p>Runs on Charles's proxy thread, so it performs only getter calls and small copies: it never
 * touches bodies, never decodes, never does IO. Header count per message is capped.
 */
public final class SnapshotFactory {
    static final int MAX_HEADERS = 200;
    static final int MAX_HEADER_VALUE_CHARS = 8192;
    static final int MAX_ERROR_CHARS = 300;

    private final CharlesApi api;

    public SnapshotFactory(CharlesApi api) {
        this.api = api;
    }

    public TransactionSnapshot capture(Object t) throws ReflectiveOperationException {
        TransactionSnapshot.Builder b = TransactionSnapshot.builder()
                .method(str(api.tx(t, "getMethod")))
                .scheme(str(api.tx(t, "getProtocol")))
                .host(str(api.tx(t, "getHost")))
                .port(intOr(api.tx(t, "getPort"), -1))
                .path(str(api.tx(t, "getPath")))
                .query(str(api.tx(t, "getQuery")))
                .protocolVersion(str(api.tx(t, "getProtocolVersion")))
                .statusCode(intOr(api.tx(t, "getResponseStatus"), 0))
                .requestHeaders(headers(api.tx(t, "getRequestHeader")))
                .responseHeaders(headers(api.tx(t, "getResponseHeader")))
                .requestSize(longOr(api.tx(t, "getRequestSize"), 0))
                .responseSize(longOr(api.tx(t, "getResponseSize"), 0))
                .requestHeaderSize(intOr(api.tx(t, "getRequestHeaderSize"), 0))
                .responseHeaderSize(intOr(api.tx(t, "getResponseHeaderSize"), 0))
                .startTimeMs(time(api.tx(t, "getStartTime")))
                .requestBeginMs(time(api.tx(t, "getRequestBeginTime")))
                .requestCompleteMs(time(api.tx(t, "getRequestCompleteTime")))
                .responseBeginMs(time(api.tx(t, "getResponseBeginTime")))
                .endTimeMs(time(api.tx(t, "getEndTime")))
                .dnsMs(boxedLong(api.tx(t, "getDnsDuration")))
                .connectMs(boxedLong(api.tx(t, "getConnectDuration")))
                .tlsHandshakeMs(boxedLong(api.tx(t, "getSslDuration")))
                .charlesStatus(StatusLabels.label(api.statusRaw(api.tx(t, "getStatus"))))
                .remoteAddress(addr(api.tx(t, "getRemoteAddress")))
                .clientAddress(addr(api.tx(t, "getClientAddress")))
                .alpnProtocol(str(api.tx(t, "getAlpnProtocol")))
                .tlsProtocol(str(api.tx(t, "getSslProtocol")))
                .cipherSuite(str(api.tx(t, "getCipherSuite")))
                .webSocket(Boolean.TRUE.equals(api.tx(t, "getWebSocket")))
                .tunnel(Boolean.TRUE.equals(api.tx(t, "getTunnel")));
        Object ex = api.tx(t, "getException");
        if (ex instanceof Throwable) {
            Throwable th = (Throwable) ex;
            b.errorType(th.getClass().getSimpleName());
            String msg = th.getMessage();
            if (msg != null && msg.length() > MAX_ERROR_CHARS) {
                msg = msg.substring(0, MAX_ERROR_CHARS) + "…";
            }
            b.errorMessage(msg);
        }
        return b.build();
    }

    public boolean hasException(Object t) throws ReflectiveOperationException {
        return api.tx(t, "getException") instanceof Throwable;
    }

    private List<TransactionSnapshot.Header> headers(Object fields) throws ReflectiveOperationException {
        if (fields == null) {
            return Collections.emptyList();
        }
        int n = Math.min(api.fieldCount(fields), MAX_HEADERS);
        List<TransactionSnapshot.Header> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = api.fieldName(fields, i);
            String value = api.fieldValue(fields, i);
            if (value != null && value.length() > MAX_HEADER_VALUE_CHARS) {
                value = value.substring(0, MAX_HEADER_VALUE_CHARS) + "…[truncated]";
            }
            out.add(new TransactionSnapshot.Header(name, value));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int intOr(Object o, int def) {
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    private static long longOr(Object o, long def) {
        return o instanceof Number ? ((Number) o).longValue() : def;
    }

    private static Long boxedLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : null;
    }

    private static Long time(Object o) {
        return o instanceof Date ? ((Date) o).getTime() : null;
    }

    private static String addr(Object o) {
        return o instanceof InetAddress ? ((InetAddress) o).getHostAddress() : null;
    }
}
