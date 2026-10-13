package com.tricoredb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What one command of a pipeline, transaction or script answered.
 *
 * @param ok    whether the command succeeded
 * @param error the server's message for a failed command, otherwise {@code null}
 * @param data  the response payload the command would have had on its own,
 *              still tagged: {@code {"Json": ...}}, {@code {"CacheValue": ...}}
 */
public record CacheOpResult(boolean ok, String error, Object data) {

    /** The bytes of a {@code CacheValue} answer; empty on a miss or any other payload. */
    public Optional<byte[]> value() {
        if (data instanceof Map<?, ?> m && m.get("CacheValue") != null) {
            return Optional.of(Wire.bytes(m.get("CacheValue"), "cache value"));
        }
        return Optional.empty();
    }

    /** The fields of a {@code Json} answer, or {@code null} for any other payload. */
    public Map<String, Object> json() {
        if (data instanceof Map<?, ?> m && m.get("Json") instanceof Map<?, ?>) {
            return Wire.asObject(m.get("Json"), "batch result");
        }
        return null;
    }

    static List<CacheOpResult> decode(List<Object> raw) {
        List<CacheOpResult> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            Map<String, Object> m = Wire.asObject(o, "batch result");
            if (m.containsKey("ok")) {
                out.add(new CacheOpResult(true, null, m.get("ok")));
            } else if (m.containsKey("error")) {
                out.add(new CacheOpResult(false, String.valueOf(m.get("error")), null));
            } else {
                throw new ProtocolException("expected each batch result to carry `ok` or `error`");
            }
        }
        return out;
    }
}
