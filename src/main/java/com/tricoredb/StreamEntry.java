package com.tricoredb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One entry from a cache stream.
 *
 * {@code fields} is authoritative — stream fields are arbitrary bytes;
 * {@link #text()} is a convenience for the common all-UTF-8 case.
 *
 * @param id the strictly increasing {@code <millis>-<seq>} id the server assigned
 */
public record StreamEntry(String id, List<CachePair> fields) {

    /** The fields decoded as UTF-8. Wrong for binary payloads; use {@link #fields()}. */
    public Map<String, String> text() {
        return CachePair.toText(fields);
    }

    static List<StreamEntry> decode(Map<String, Object> json) {
        List<StreamEntry> out = new ArrayList<>();
        for (Object o : Wire.list(json, "entries")) {
            Map<String, Object> e = Wire.asObject(o, "stream entry");
            out.add(new StreamEntry(Wire.str(e, "id"),
                    CachePair.decode(Wire.list(e, "fields"), "stream field")));
        }
        return out;
    }
}
