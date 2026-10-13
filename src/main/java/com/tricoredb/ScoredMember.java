package com.tricoredb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.LinkedHashMap;
import java.util.Map;

/** One scored member of a sorted set, as {@code cacheZAdd} takes it. */
public record ScoredMember(double score, byte[] member) {

    public ScoredMember {
        if (member == null) {
            throw new IllegalArgumentException("a scored member needs a member");
        }
    }

    /** A member given as a string, encoded UTF-8. */
    public static ScoredMember ofText(double score, String member) {
        return new ScoredMember(score, member.getBytes(UTF_8));
    }

    Map<String, Object> wire() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", score);
        m.put("member", member);
        return m;
    }
}
