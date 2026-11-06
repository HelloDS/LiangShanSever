package com.ruoyi.game.protocol;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GameJsonCodecTest {
    private final GameJsonCodec codec = new GameJsonCodec();

    @Test void preservesLegacyFieldsAndValueTypes() {
        Map<String, Object> packet = new LinkedHashMap<>();
        packet.put("state", 0);
        packet.put("uid", 100001);
        packet.put("guest", true);
        packet.put("missing", null);
        packet.put("leaveGames", new Object[0]);
        packet.put("readys", Map.of("100001", 1));
        packet.put("msg", "中文\n\"转义\"");
        var actual = codec.read(codec.write(packet));
        assertTrue(actual.get("uid").isIntegralNumber());
        assertTrue(actual.get("guest").isBoolean());
        assertTrue(actual.get("missing").isNull());
        assertTrue(actual.get("leaveGames").isArray());
        assertTrue(actual.get("readys").isObject());
        assertEquals("中文\n\"转义\"", actual.get("msg").asString());
        assertFalse(actual.has("cmd"));
        assertFalse(actual.has("data"));
    }

    @Test void internalCanonicalDigestKeepsPrecisionAndIgnoresObjectKeyOrder() {
        String first="{\"z\":[{\"b\":2,\"a\":1}],\"n\":0.123456789012345678901,\"i\":9007199254740993}";
        String second="{\"i\":9007199254740993,\"n\":0.1234567890123456789010,\"z\":[{\"a\":1,\"b\":2}]}";
        assertEquals(codec.canonicalObject(first),codec.canonicalObject(second));
        assertTrue(codec.canonicalObject(first).contains("9007199254740993"));
        assertTrue(codec.canonicalObject(first).contains("0.123456789012345678901"));
        assertNotEquals(codec.canonicalObject(first),codec.canonicalObject(first.replace("0993","0992")));
        assertThrows(IllegalArgumentException.class,()->codec.canonicalObject("[]"));
    }

    @Test void acceptsBothChannelsAndRejectsMalformedAndOversizedRequests() {
        assertEquals("resetfd", codec.request("{\"path\":\"login\",\"reqData\":{\"api\":\"user\",\"c\":\"resetfd\"}}").command());
        assertEquals("logic", codec.request("{\"path\":\"logic\",\"reqData\":{\"api\":\"role\",\"c\":\"syncCard\"}}").path());
        assertThrows(IllegalArgumentException.class, () -> codec.request("{\"path\":\"admin\",\"reqData\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> codec.read("[]"));
        assertThrows(IllegalArgumentException.class, () -> codec.read("{\"x\":\"" + "a".repeat(GameJsonCodec.MAX_BYTES) + "\"}"));
        assertThrows(RuntimeException.class, () -> codec.read("{\"x\":".repeat(40) + "0" + "}".repeat(40)));
    }
}
