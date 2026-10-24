package io.github.charlesmcp.agent.redaction;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * First line of redaction, applied inside the JVM before header values ever reach the socket.
 *
 * <p>The Python sidecar applies a second, configurable pass (headers, query strings, JSON body fields).
 * This class intentionally only handles header values, which are the highest-value secrets and the
 * cheapest to recognise reliably.
 */
public final class HeaderRedactor {
    public static final String REDACTED = "[REDACTED]";

    private static final Set<String> EXACT = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "set-cookie2",
            "x-api-key",
            "api-key",
            "apikey",
            "x-auth-token",
            "x-access-token",
            "x-refresh-token",
            "x-csrf-token",
            "x-xsrf-token",
            "x-amz-security-token",
            "x-goog-api-key",
            "ocp-apim-subscription-key",
            "x-session-id",
            "x-client-secret",
            "www-authenticate",
            "proxy-authenticate"
    )));

    /** Substrings that mark a header as credential-bearing (e.g. {@code X-Shopify-Access-Token}). */
    private static final String[] FRAGMENTS = {"token", "secret", "password", "passwd", "api-key", "apikey", "session", "signature", "credential"};

    private HeaderRedactor() {
    }

    public static boolean isSensitive(String headerName) {
        if (headerName == null) {
            return false;
        }
        String n = headerName.trim().toLowerCase(Locale.ROOT);
        if (EXACT.contains(n)) {
            return true;
        }
        for (String f : FRAGMENTS) {
            if (n.contains(f)) {
                return true;
            }
        }
        return false;
    }

    public static String redactValue(String headerName, String value) {
        if (value == null) {
            return null;
        }
        if (!isSensitive(headerName)) {
            return value;
        }
        // Keep the auth scheme (e.g. "Bearer") because it is useful and not secret.
        String n = headerName.trim().toLowerCase(Locale.ROOT);
        if (n.equals("authorization") || n.equals("proxy-authorization")) {
            int sp = value.indexOf(' ');
            if (sp > 0 && sp < 32) {
                return value.substring(0, sp) + " " + REDACTED;
            }
        }
        return REDACTED;
    }
}
