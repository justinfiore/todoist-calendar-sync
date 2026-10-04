package todoistcaldavsync.planner.ai

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** One strict JSON root: duplicate keys and trailing non-whitespace tokens are rejected. */
final class StrictJson {
    private static final ObjectMapper MAPPER = new ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    private StrictJson() {}

    static Object parse(String json) {
        if (json == null) throw new IllegalArgumentException('JSON input is required')
        MAPPER.readValue(json, Object)
    }

    static Object parse(byte[] json) {
        if (json == null) throw new IllegalArgumentException('JSON input is required')
        MAPPER.readValue(json, Object)
    }

    static Map<String,Object> parseObject(String json) {
        Object value=parse(json)
        if (!(value instanceof Map)) throw new IllegalArgumentException('JSON root must be an object')
        value as Map<String,Object>
    }

    static Map<String,Object> parseObject(byte[] json) {
        Object value=parse(json)
        if (!(value instanceof Map)) throw new IllegalArgumentException('JSON root must be an object')
        value as Map<String,Object>
    }

    static JsonNode readTree(String json) {
        if (json == null) throw new IllegalArgumentException('JSON input is required')
        MAPPER.readTree(json)
    }

    static Object toValue(JsonNode node) { MAPPER.convertValue(node, Object) }
}
