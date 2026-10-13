package com.tricoredb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * A sorted-set member as a range read returns it.
 *
 * @param score empty unless the read asked for scores
 */
public record CacheZMember(byte[] member, OptionalDouble score) {

    static List<CacheZMember> decode(List<Object> raw) {
        List<CacheZMember> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o instanceof Map<?, ?>) {
                Map<String, Object> m = Wire.asObject(o, "sorted-set member");
                out.add(new CacheZMember(
                        Wire.bytes(m.get("member"), "member"), OptionalDouble.of(Wire.dbl(m, "score"))));
            } else {
                out.add(new CacheZMember(Wire.bytes(o, "member"), OptionalDouble.empty()));
            }
        }
        return out;
    }
}
