package com.tricoredb;

import java.util.List;
import java.util.Map;

/**
 * The messages a poll found.
 *
 * @param messages the messages, oldest first
 * @param next     the id to pass as after on the next poll
 */
public record CachePollResult(List<CacheMessage> messages, long next) {

    static CachePollResult from(Map<String, Object> m) {
        return new CachePollResult(Wire.records(m, "messages", CacheMessage::from), Wire.num(m, "next"));
    }
}
