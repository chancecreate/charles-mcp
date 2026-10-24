package io.github.charlesmcp.agent.json;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void roundTrip() {
        Map<String, Object> m = Json.obj("s", "a\"b\\c\n\t", "n", 42L, "d", 1.5, "b", true, "z", null,
                "l", Arrays.asList(1L, "x"), "o", Json.obj("k", "v"));
        Map<String, Object> back = Json.parseObject(Json.write(m));
        assertEquals("a\"b\\c\n\t", back.get("s"));
        assertEquals(42L, back.get("n"));
        assertEquals(1.5, back.get("d"));
        assertEquals(true, back.get("b"));
        assertTrue(back.containsKey("z"));
        assertEquals(Arrays.asList(1L, "x"), back.get("l"));
        assertEquals("v", ((Map<?, ?>) back.get("o")).get("k"));
    }

    @Test
    void escapesControlAndSeparatorCharacters() {
        String out = Json.write("\u0001\u2028");
        assertEquals("\"\\u0001\\u2028\"", out);
        // lone surrogate becomes U+FFFD rather than producing invalid UTF-8 downstream
        assertEquals("\"\\ufffd\"", Json.write("\ud800"));
        assertEquals("\"\ud83d\ude00\"", Json.write("\ud83d\ude00"));
    }

    @Test
    void unicodeEscapesParse() {
        assertEquals("é", Json.parse("\"\\u00e9\""));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : List.of("", "{", "{\"a\":}", "[1,]", "{\"a\" 1}", "tru", "\"unterminated", "{} x", "01x", "\"\u0001\"")) {
            assertThrows(Json.JsonException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    void rejectsExcessiveNesting() {
        String deep = "[".repeat(Json.MAX_DEPTH + 5) + "]".repeat(Json.MAX_DEPTH + 5);
        assertThrows(Json.JsonException.class, () -> Json.parse(deep));
    }

    @Test
    void rejectsHugeNumbers() {
        assertThrows(Json.JsonException.class, () -> Json.parse("1".repeat(40)));
    }
}
