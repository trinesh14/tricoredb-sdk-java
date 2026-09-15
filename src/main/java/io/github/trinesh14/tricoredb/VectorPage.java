package io.github.trinesh14.tricoredb;

import java.util.List;

/**
 * One page of a vector listing.
 *
 * @param truncated more items remain past this page — the server clamps
 *                  {@code limit}, so a caller that ignores this can silently
 *                  export a partial collection
 * @param total     items in the collection, not in this page
 */
public record VectorPage(String collection, List<VectorItem> vectors, boolean truncated, long total) {
}
