package com.tricoredb;

import java.util.List;
import java.util.Map;

/**
 * A graph's connected components.
 *
 * @param kind       weak or strong
 * @param count      how many components the graph has
 * @param largest    the size of the largest
 * @param truncated  whether fewer components are listed than counted
 * @param components the components, largest first
 */
public record GraphComponents(
        String kind,
        long count,
        long largest,
        boolean truncated,
        List<GraphComponent> components) {

    static GraphComponents from(Map<String, Object> m) {
        return new GraphComponents(
                Wire.str(m, "kind"),
                Wire.num(m, "count"),
                Wire.num(m, "largest"),
                Wire.bool(m, "truncated"),
                Wire.records(m, "components", GraphComponent::from));
    }
}
