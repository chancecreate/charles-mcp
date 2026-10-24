package io.github.charlesmcp.agent.bridge;

import io.github.charlesmcp.agent.json.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Minimal NDJSON client for tests. */
public final class TestClient implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader in;
    private final OutputStream out;

    public TestClient(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(10_000);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = socket.getOutputStream();
    }

    public void sendRaw(String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public Map<String, Object> send(Map<String, Object> msg) throws IOException {
        sendRaw(Json.write(msg));
        return read();
    }

    public Map<String, Object> read() throws IOException {
        String line = in.readLine();
        return line == null ? null : Json.parseObject(line);
    }

    public Map<String, Object> hello(String token) throws IOException {
        return send(Json.obj("id", 1L, "op", "hello", "token", token));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> result(Map<String, Object> reply) {
        return (Map<String, Object>) reply.get("result");
    }

    @SuppressWarnings("unchecked")
    public static String errorCode(Map<String, Object> reply) {
        return (String) ((Map<String, Object>) reply.get("error")).get("code");
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
