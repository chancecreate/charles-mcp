package io.github.charlesmcp.agent.bridge;

import io.github.charlesmcp.agent.config.AgentConfig;
import io.github.charlesmcp.agent.events.EventStore;
import io.github.charlesmcp.agent.events.EventType;
import io.github.charlesmcp.agent.events.RawEvent;
import io.github.charlesmcp.agent.events.TransactionSnapshot;
import io.github.charlesmcp.agent.instrumentation.BodyReader;
import io.github.charlesmcp.agent.json.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeServerTest {
    private BridgeServer server;
    private final EventStore store = new EventStore(1000, 3_600_000, 1000, 1L << 30, System::currentTimeMillis);

    private BridgeServer start(Map<String, String> cfg) throws IOException {
        AgentConfig config = AgentConfig.fromMap(cfg);
        server = new BridgeServer(config, new BridgeBackend() {
            @Override
            public EventStore store() {
                return store;
            }

            @Override
            public Map<String, Object> status() {
                return Json.obj("agent_state", "active");
            }

            @Override
            public BodyReader.Body readBody(Object live, boolean response, int maxBytes) {
                return null;
            }

            @Override
            public Boolean attachedToSession(Object live) {
                return true;
            }
        }, "test", "a".repeat(64));
        server.start();
        return server;
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void bindsToIpv4LoopbackOnly() throws Exception {
        start(Collections.emptyMap());
        assertEquals(InetAddress.getByName("127.0.0.1"), server.bindAddress());
        assertTrue(server.port() > 0);
        // Not reachable via any non-loopback interface address of this machine.
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a.isLoopbackAddress() || a.isLinkLocalAddress()) {
                    continue;
                }
                try (Socket s = new Socket()) {
                    s.connect(new java.net.InetSocketAddress(a, server.port()), 300);
                    throw new AssertionError("bridge reachable on " + a);
                } catch (IOException expected) {
                    // refused / timed out: good
                }
            }
        }
    }

    @Test
    void rejectsMissingOrWrongToken() throws Exception {
        start(Collections.emptyMap());
        try (TestClient c = new TestClient(server.port())) {
            Map<String, Object> r = c.send(Json.obj("id", 1L, "op", "status"));
            assertEquals(false, r.get("ok"));
            assertEquals("unauthorized", TestClient.errorCode(r));
            assertNull(c.read(), "connection closed after failed auth");
        }
        try (TestClient c = new TestClient(server.port())) {
            assertEquals("unauthorized", TestClient.errorCode(c.hello("b".repeat(64))));
        }
        try (TestClient c = new TestClient(server.port())) {
            assertEquals("unauthorized", TestClient.errorCode(c.hello(null)));
        }
        try (TestClient c = new TestClient(server.port())) {
            c.sendRaw("not json");
            assertEquals("bad_request", TestClient.errorCode(c.read()));
        }
        assertTrue(server.authFailures() >= 4);
    }

    @Test
    void tokenComparisonIsExact() throws Exception {
        start(Collections.emptyMap());
        assertTrue(server.tokenMatches("a".repeat(64)));
        assertFalse(server.tokenMatches("a".repeat(63)));
        assertFalse(server.tokenMatches("A".repeat(64)));
        assertFalse(server.tokenMatches(42L));
    }

    @Test
    void authenticatedClientCanPageEventsAndFetchTransactions() throws Exception {
        start(Collections.emptyMap());
        TransactionSnapshot snap = TransactionSnapshot.builder().method("GET").scheme("https").host("x.test").path("/a")
                .statusCode(200)
                .requestHeaders(List.of(new TransactionSnapshot.Header("Authorization", "Bearer secret-value"),
                        new TransactionSnapshot.Header("Accept", "*/*")))
                .build();
        for (int i = 0; i < 5; i++) {
            store.append(new RawEvent(EventType.REQUEST_CREATED, System.currentTimeMillis(), "s1", "t" + i, snap, null));
        }
        try (TestClient c = new TestClient(server.port())) {
            assertEquals(true, c.hello("a".repeat(64)).get("ok"));
            Map<String, Object> page = TestClient.result(c.send(Json.obj("id", 2L, "op", "events", "after", 0L, "limit", 3L)));
            assertEquals(3, ((List<?>) page.get("events")).size());
            assertEquals(3L, page.get("next_cursor"));
            assertEquals(true, page.get("has_more"));
            page = TestClient.result(c.send(Json.obj("id", 3L, "op", "events", "after", 3L, "limit", 100L)));
            assertEquals(2, ((List<?>) page.get("events")).size());

            Map<String, Object> tx = TestClient.result(c.send(Json.obj("id", 4L, "op", "transaction", "transaction_id", "t1")));
            String headers = Json.write(((Map<?, ?>) tx.get("request")).get("headers"));
            assertFalse(headers.contains("secret-value"), "credentials never leave the JVM by default");
            assertTrue(headers.contains("Bearer [REDACTED]"));
            assertEquals(false, ((Map<?, ?>) tx.get("connection")).get("websocket"));

            assertEquals("not_found", TestClient.errorCode(c.send(Json.obj("id", 5L, "op", "transaction", "transaction_id", "zzz"))));
            assertEquals("unknown_op", TestClient.errorCode(c.send(Json.obj("id", 6L, "op", "clear_session"))));
            Map<String, Object> st = TestClient.result(c.send(Json.obj("id", 7L, "op", "status")));
            assertEquals("active", st.get("agent_state"));
        }
    }

    @Test
    void bodiesCanBeDisabledAtTheBridge() throws Exception {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("allowBodies", "false");
        start(cfg);
        store.append(new RawEvent(EventType.RESPONSE_COMPLETE, System.currentTimeMillis(), "s1", "t1",
                TransactionSnapshot.builder().build(), new Object()));
        try (TestClient c = new TestClient(server.port())) {
            c.hello("a".repeat(64));
            Map<String, Object> tx = TestClient.result(c.send(Json.obj("id", 2L, "op", "transaction", "transaction_id", "t1",
                    "include_response_body", true)));
            Map<?, ?> body = (Map<?, ?>) tx.get("response_body");
            assertEquals(false, body.get("available"));
            assertEquals("disabled_by_bridge_config", body.get("reason"));
        }
    }

    @Test
    void oversizedRequestIsRejected() throws Exception {
        start(Collections.emptyMap());
        try (TestClient c = new TestClient(server.port())) {
            c.hello("a".repeat(64));
            c.sendRaw("{\"op\":\"ping\",\"pad\":\"" + "x".repeat(BridgeServer.MAX_REQUEST_BYTES + 10) + "\"}");
            assertEquals("request_too_large", TestClient.errorCode(c.read()));
        }
    }

    @Test
    void connectionLimitIsEnforced() throws Exception {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("maxConnections", "2");
        start(cfg);
        try (TestClient a = new TestClient(server.port()); TestClient b = new TestClient(server.port())) {
            a.hello("a".repeat(64));
            b.hello("a".repeat(64));
            try (TestClient c = new TestClient(server.port())) {
                assertEquals("too_many_connections", TestClient.errorCode(c.read()));
            }
        }
    }
}
