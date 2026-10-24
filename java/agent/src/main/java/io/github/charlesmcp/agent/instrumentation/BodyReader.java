package io.github.charlesmcp.agent.instrumentation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads at most {@code maxBytes} of a request/response body from a live Transaction.
 *
 * <p>Only ever called on a bridge connection thread, on explicit client request — never on a Charles
 * proxy or GUI thread. Prefers Charles's decoded stream (gzip/deflate/br removed); falls back to the raw
 * stream if decoding fails. Reads {@code maxBytes + 1} to detect truncation without materializing more.
 */
public final class BodyReader {
    private final CharlesApi api;

    public BodyReader(CharlesApi api) {
        this.api = api;
    }

    public static final class Body {
        public final byte[] bytes;
        public final boolean truncated;
        public final boolean decoded;
        public final String contentEncoding;
        /** Body size as Charles recorded it on the wire (may differ from decoded length). */
        public final long capturedSize;

        Body(byte[] bytes, boolean truncated, boolean decoded, String contentEncoding, long capturedSize) {
            this.bytes = bytes;
            this.truncated = truncated;
            this.decoded = decoded;
            this.contentEncoding = contentEncoding;
            this.capturedSize = capturedSize;
        }
    }

    /** @return null if the transaction has no body of that kind (yet). */
    public Body read(Object txn, boolean response, int maxBytes) throws ReflectiveOperationException {
        String which = response ? "Response" : "Request";
        Object enc = api.tx(txn, "get" + which + "ContentEncoding");
        Object size = api.tx(txn, "get" + which + "Size");
        long captured = size instanceof Number ? ((Number) size).longValue() : -1;

        InputStream in = null;
        boolean decoded = true;
        try {
            in = (InputStream) api.tx(txn, "getDecoded" + which + "BodyStream");
        } catch (ReflectiveOperationException | RuntimeException decodeFailure) {
            in = null;
        }
        if (in == null) {
            decoded = false;
            in = (InputStream) api.tx(txn, "get" + which + "BodyStream");
        }
        if (in == null) {
            return null;
        }
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes + 1, 64 * 1024));
            byte[] buf = new byte[8192];
            int remaining = maxBytes + 1;
            while (remaining > 0) {
                int n = s.read(buf, 0, Math.min(buf.length, remaining));
                if (n < 0) {
                    break;
                }
                out.write(buf, 0, n);
                remaining -= n;
            }
            byte[] all = out.toByteArray();
            boolean truncated = all.length > maxBytes;
            byte[] bytes = truncated ? java.util.Arrays.copyOf(all, maxBytes) : all;
            return new Body(bytes, truncated, decoded && enc != null, enc == null ? null : enc.toString(), captured);
        } catch (IOException e) {
            return new Body(new byte[0], false, false, enc == null ? null : enc.toString(), captured);
        }
    }

    /** True while the transaction is still attached to a session tree (false after "Clear Session"). */
    public boolean attachedToSession(Object txn) throws ReflectiveOperationException {
        Object node = api.tx(txn, "getParent");
        int guard = 0;
        while (node != null && guard++ < 64) {
            if (api.sessionClass.isInstance(node)) {
                return true;
            }
            // getParent is declared on ModelNode, so the resolved Method works for Path/Host nodes too.
            node = api.tx(node, "getParent");
        }
        return false;
    }
}
