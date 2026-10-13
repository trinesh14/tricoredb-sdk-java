package com.tricoredb;

import java.util.Map;

/**
 * A graph constraint.
 *
 * @param name     the constraint name
 * @param label    the label it applies to
 * @param property the constrained property
 * @param kind     unique or required
 * @param target   node or edge
 */
public record GraphConstraint(String name, String label, String property, String kind, String target) {

    static GraphConstraint from(Map<String, Object> m) {
        return new GraphConstraint(
                Wire.str(m, "name"),
                Wire.str(m, "label"),
                Wire.str(m, "property"),
                Wire.str(m, "kind"),
                Wire.str(m, "target"));
    }
}
