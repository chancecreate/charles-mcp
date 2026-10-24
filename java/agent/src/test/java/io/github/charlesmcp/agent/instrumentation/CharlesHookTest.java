package io.github.charlesmcp.agent.instrumentation;

import com.xk72.charles.CharlesContext;
import com.xk72.charles.model.Session;
import com.xk72.charles.model.Transaction;
import com.xk72.proxy.Fields;
import com.xk72.proxy.ProxyEvent;
import io.github.charlesmcp.agent.bridge.TestClient;
import io.github.charlesmcp.agent.config.AgentConfig;
import io.github.charlesmcp.agent.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end against test doubles: bootstrap → listener → pipeline → store → authenticated socket. */
class CharlesHookTest {

    private CharlesHook startHook(Path dir, String version, Map<String, String> extra) throws Exception {
        CharlesContext ctx = new CharlesContext(version);
        ctx.getProxyManager().a(new Session());
        CharlesContext.setInstanceForTest(ctx);
        Map<String, String> cfg = new HashMap<>(extra);
        cfg.put("discoveryFile", dir.resolve("bridge.json").toString());
        cfg.put("maxBodyBytes", "64");
        CharlesHook hook = new CharlesHook(AgentConfig.fromMap(cfg), () -> CharlesContext.class);
        hook.run();
        return hook;
    }

    @Test
    void endToEndCaptureOverTheBridge(@TempDir Path dir) throws Exception {
        CharlesHook hook = startHook(dir, "4.6.2", Map.of());
        assertEquals("active", hook.state());
        Map<String, Object> disc = Json.parseObject(new String(Files.readAllBytes(dir.resolve("bridge.json")), StandardCharsets.UTF_8));
        assertEquals("127.0.0.1", disc.get("host"));
        String token = (String) disc.get("token");
        assertEquals(64, token.length());

        // Simulate Charles recording one gzip JSON exchange.
        CharlesContext ctx = CharlesContext.getInstance();
        ProxyEvent ev = new ProxyEvent();
        Transaction t = new Transaction();
        t.host = "api.test";
        t.path = "/v1";
        t.requestHeader = new Fields().add("Cookie", "sid=topsecret");
        t.responseStatus = 200;
        t.responseHeader = new Fields().add("Content-Type", "application/json").add("Content-Encoding", "gzip");
        byte[] json = "{\"token\":\"abc\",\"pad\":\"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"}"
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(gz)) {
            g.write(json);
        }
        t.responseBody = gz.toByteArray();
        t.responseSize = t.responseBody.length;
        t.responseContentEncoding = "gzip";
        ev.setAttribute(CharlesApi.RECORDER_TRANSACTION_ATTRIBUTE, t);
        t.setParentForTest(ctx.getProxyManager().q());
        ctx.getProxyManager().p().receivedRequestURL(ev);
        ctx.getProxyManager().p().receivedResponseHeader(ev);
        ctx.getProxyManager().p().transactionComplete(ev);
        hook.pipeline().flushForTest();

        try (TestClient c = new TestClient(((Number) disc.get("port")).intValue())) {
            assertEquals(true, c.hello(token).get("ok"));
            Map<String, Object> page = TestClient.result(c.send(Json.obj("id", 2L, "op", "events", "after", 0L)));
            List<?> events = (List<?>) page.get("events");
            Map<?, ?> last = (Map<?, ?>) events.get(events.size() - 1);
            assertEquals("response_complete", last.get("event_type"));
            String tid = (String) last.get("transaction_id");

            Map<String, Object> tx = TestClient.result(c.send(Json.obj("id", 3L, "op", "transaction", "transaction_id", tid,
                    "include_response_body", true, "max_body_bytes", 1_000_000L)));
            assertFalse(Json.write(tx).contains("topsecret"));
            assertEquals(true, tx.get("attached_to_session"));
            Map<?, ?> body = (Map<?, ?>) tx.get("response_body");
            assertEquals(true, body.get("available"));
            assertEquals(true, body.get("decoded"), "gzip decoded via Charles's decoded stream");
            assertEquals(true, body.get("truncated"), "bridge caps at configured maxBodyBytes (64)");
            assertEquals(64L, body.get("returned_bytes"));
            String head = new String(Base64.getDecoder().decode((String) body.get("base64")), StandardCharsets.UTF_8);
            assertTrue(head.startsWith("{\"token\""));

            // Session cleared in Charles → transaction detached, metadata still served.
            t.setParentForTest(null);
            tx = TestClient.result(c.send(Json.obj("id", 4L, "op", "transaction", "transaction_id", tid)));
            assertEquals(false, tx.get("attached_to_session"));

            Map<String, Object> st = TestClient.result(c.send(Json.obj("id", 5L, "op", "status")));
            assertEquals("4.6.2", st.get("charles_version"));
            assertEquals(true, st.get("recording"));
        }
        hook.shutdown();
        assertFalse(Files.exists(dir.resolve("bridge.json")), "discovery file removed on shutdown");
        assertFalse(ctx.getProxyManager().p().c(null));
        assertEquals(0, ctx.getProxyManager().p().size(), "listener unregistered on shutdown");
    }

    @Test
    void unsupportedVersionFailsClosed(@TempDir Path dir) throws Exception {
        CharlesContext ctx = new CharlesContext("4.7.0");
        CharlesContext.setInstanceForTest(ctx);
        Map<String, String> cfg = new HashMap<>();
        cfg.put("discoveryFile", dir.resolve("bridge.json").toString());
        CharlesHook hook = new CharlesHook(AgentConfig.fromMap(cfg), () -> CharlesContext.class);
        hook.runSafely();
        assertEquals("unsupported", hook.state());
        assertFalse(Files.exists(dir.resolve("bridge.json")));
        assertEquals(0, ctx.getProxyManager().p().size(), "nothing registered with Charles");
    }

    @Test
    void developerOverrideAllowsOtherVersions(@TempDir Path dir) throws Exception {
        CharlesHook hook = startHook(dir, "6.0.0", Map.of("allowUnsupported", "true"));
        assertEquals("active", hook.state());
        hook.shutdown();
    }

    @Test
    void versionPolicy() {
        for (String ok : new String[]{"4.6", "4.6.2", "4.6.8"}) {
            assertTrue(CharlesApi.isSupportedVersion(ok), ok);
        }
        for (String bad : new String[]{"4.5.6", "4.7.0", "5.2.2", "6.0.0", "46.2", "", null}) {
            assertFalse(CharlesApi.isSupportedVersion(bad), String.valueOf(bad));
        }
        assertTrue(CharlesApi.isTestedVersion("4.6.8"));
        assertFalse(CharlesApi.isTestedVersion("4.6.5"));
    }

    @Test
    void reportsWhetherVersionWasTested(@TempDir Path dir) throws Exception {
        CharlesHook hook = startHook(dir, "4.6.5", Map.of());
        assertEquals("active", hook.state());
        assertEquals(false, hook.status().get("charles_version_tested"));
        assertEquals(CharlesApi.SUPPORTED_VERSIONS, hook.status().get("supported_charles_versions"));
        hook.shutdown();
    }
}
