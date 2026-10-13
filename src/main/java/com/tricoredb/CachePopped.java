package com.tricoredb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.List;
import java.util.Optional;

/** The element a blocking pop removed, and the list it came from. */
public record CachePopped(String key, byte[] value) {

    static Optional<CachePopped> decode(List<Optional<byte[]>> pair) {
        if (pair.size() != 2) {
            throw new ProtocolException("expected a [key, value] pair, got " + pair.size() + " values");
        }
        if (pair.get(0).isEmpty() || pair.get(1).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CachePopped(new String(pair.get(0).get(), UTF_8), pair.get(1).get()));
    }
}
