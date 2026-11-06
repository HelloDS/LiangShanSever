package com.ruoyi.game.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 独立、不可变的游戏协议边界，禁止使用后台响应包装和全局 mapper。 */
public final class GameJsonCodec {
    public static final int MAX_BYTES = 256 * 1024;
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(MAX_BYTES).maxNumberLength(32).build()).build()).build();

    public JsonNode read(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("消息长度超限");
        }
        JsonNode node = mapper.readTree(json);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("请求必须是对象");
        return node;
    }

    public String write(Object value) { return mapper.writeValueAsString(value); }

    public WireRequest request(String json) {
        JsonNode root = read(json);
        String path = text(root, "path", 16);
        JsonNode data = root.get("reqData");
        if (!("login".equals(path) || "logic".equals(path)) || data == null || !data.isObject()) {
            throw new IllegalArgumentException("请求通道或数据无效");
        }
        return new WireRequest(path, text(data, "api", 48), text(data, "c", 64), data);
    }

    public static String text(JsonNode object, String field, int max) {
        JsonNode value = object.get(field);
        if (value == null || !value.isString() || value.asString().isBlank() || value.asString().length() > max) {
            throw new IllegalArgumentException("字段无效：" + field);
        }
        return value.asString();
    }

    public static Map<String, Object> error(int state, String message) {
        return Map.of("state", state, "msg", message);
    }
}
