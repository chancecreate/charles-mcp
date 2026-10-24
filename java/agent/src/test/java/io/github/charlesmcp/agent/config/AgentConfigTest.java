package io.github.charlesmcp.agent.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {

    @Test
    void defaultsAreSafe() {
        AgentConfig c = AgentConfig.defaults();
        assertEquals(0, c.port, "ephemeral port by default");
        assertTrue(c.redactHeaders);
        assertFalse(c.allowUnsupported);
        assertFalse(c.debugLifecycle);
        assertTrue(c.maxBodyBytes <= 16 * 1024 * 1024);
    }

    @Test
    void parsesAgentArgs() {
        Map<String, String> m = AgentConfig.parseArgs("maxEvents=500, debugLifecycle ,port=0");
        assertEquals("500", m.get("maxEvents"));
        assertEquals("true", m.get("debugLifecycle"));
    }

    @Test
    void clampsOutOfRangeValues() {
        Map<String, String> v = new HashMap<>();
        v.put("maxEvents", "999999999999");
        v.put("queueCapacity", "1");
        v.put("maxBodyBytes", "-5");
        v.put("maxConnections", "nonsense");
        AgentConfig c = AgentConfig.fromMap(v);
        assertEquals(1_000_000, c.maxEvents);
        assertEquals(64, c.queueCapacity);
        assertEquals(0, c.maxBodyBytes);
        assertEquals(4, c.maxConnections);
    }

    @Test
    void booleanParsing() {
        Map<String, String> v = new HashMap<>();
        v.put("redactHeaders", "off");
        v.put("allowBodies", "0");
        v.put("disabled", "yes");
        AgentConfig c = AgentConfig.fromMap(v);
        assertFalse(c.redactHeaders);
        assertFalse(c.allowBodies);
        assertTrue(c.disabled);
    }
}
