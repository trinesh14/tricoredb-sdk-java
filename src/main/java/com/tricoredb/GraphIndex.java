package com.tricoredb;

import java.util.List;
import java.util.Map;

/**
 * A graph node index.
 *
 * @param name       the index name
 * @param label      the label it is limited to, when there is one; {@code null}
 *                   otherwise
 * @param properties the indexed properties
 */
public record GraphIndex(String name, String label, List<String> properties) {

    static GraphIndex from(Map<String, Object> m) {
        return new GraphIndex(Wire.str(m, "name"), Wire.str(m, "label"), Wire.strings(m, "properties"));
    }
}
