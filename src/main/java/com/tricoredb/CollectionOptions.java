package com.tricoredb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rules a collection applies to every document written into it.
 *
 * @param schema    the document shape as the server's own JSON: {@code required}
 *                  (paths), {@code fields} (path to a rule of {@code type},
 *                  {@code one_of}, {@code minimum}, {@code maximum},
 *                  {@code min_length}, {@code max_length}, {@code pattern}) and
 *                  {@code additional_fields}; {@code null} for none
 * @param defaults  path to the value a newly created document gets when it
 *                  lacks the path; {@code null} for none
 * @param versioned keep a {@code _version} counter on every document, which is
 *                  what {@code expectedVersion} compares against
 */
public record CollectionOptions(Map<String, Object> schema, Map<String, Object> defaults, boolean versioned) {

    Map<String, Object> wire() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (schema != null) {
            m.put("schema", schema);
        }
        if (defaults != null && !defaults.isEmpty()) {
            m.put("defaults", defaults);
        }
        m.put("versioned", versioned);
        return m;
    }

    static CollectionOptions from(Map<String, Object> m) {
        return new CollectionOptions(Wire.optObj(m, "schema"), Wire.optObj(m, "defaults"), Wire.bool(m, "versioned"));
    }
}
