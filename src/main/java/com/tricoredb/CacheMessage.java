package com.tricoredb;

import java.util.Map;

/**
 * One published message.
 *
 * @param id      the message id, increasing
 * @param channel the channel it was published to
 * @param message the message text
 * @param atMs    when it was published, in Unix milliseconds
 */
public record CacheMessage(long id, String channel, String message, long atMs) {

    static CacheMessage from(Map<String, Object> m) {
        return new CacheMessage(
                Wire.num(m, "id"),
                Wire.str(m, "channel"),
                Wire.str(m, "message"),
                Wire.num(m, "at_ms"));
    }
}
