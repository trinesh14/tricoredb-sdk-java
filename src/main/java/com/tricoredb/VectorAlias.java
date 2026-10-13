package com.tricoredb;

import java.util.Map;

/**
 * A vector collection alias.
 *
 * @param alias      the alias
 * @param collection the collection it names
 */
public record VectorAlias(String alias, String collection) {

    static VectorAlias from(Map<String, Object> m) {
        return new VectorAlias(Wire.str(m, "alias"), Wire.str(m, "collection"));
    }
}
