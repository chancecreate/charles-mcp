package io.github.charlesmcp.agent.redaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeaderRedactorTest {

    @Test
    void redactsCredentialHeaders() {
        assertEquals("Bearer [REDACTED]", HeaderRedactor.redactValue("Authorization", "Bearer abc.def.ghi"));
        assertEquals("Basic [REDACTED]", HeaderRedactor.redactValue("proxy-authorization", "Basic dXNlcjpwYXNz"));
        assertEquals("[REDACTED]", HeaderRedactor.redactValue("Cookie", "a=1; b=2"));
        assertEquals("[REDACTED]", HeaderRedactor.redactValue("Set-Cookie", "sid=xyz; HttpOnly"));
        assertEquals("[REDACTED]", HeaderRedactor.redactValue("X-API-Key", "k"));
        assertEquals("[REDACTED]", HeaderRedactor.redactValue("X-Shopify-Access-Token", "k"));
        assertEquals("[REDACTED]", HeaderRedactor.redactValue("x-client-secret", "k"));
    }

    @Test
    void keepsOrdinaryHeaders() {
        assertEquals("application/json", HeaderRedactor.redactValue("Content-Type", "application/json"));
        assertEquals("gzip", HeaderRedactor.redactValue("Accept-Encoding", "gzip"));
    }

    @Test
    void schemeOnlyAuthorizationIsFullyRedacted() {
        assertEquals("[REDACTED]", HeaderRedactor.redactValue("Authorization", "opaque-token-without-scheme"));
    }
}
