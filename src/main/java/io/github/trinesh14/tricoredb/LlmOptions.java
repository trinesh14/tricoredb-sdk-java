package io.github.trinesh14.tricoredb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounds and redaction for an LLM context export.
 *
 * <p>Built through {@link #defaults()} rather than a public constructor so that
 * {@code redactSensitive} cannot default to {@code false} by omission: the safe
 * value is the one you get without asking.
 *
 * @param maxRows         cap on rows included, or {@code null} for the server's own cap
 * @param redactSensitive redact sensitive fields before export
 * @param includeSchema   include type information alongside the data
 */
public record LlmOptions(Integer maxRows, boolean redactSensitive, boolean includeSchema) {

    /** The server's own defaults: redact, no schema, no explicit cap. */
    public static LlmOptions defaults() {
        return new LlmOptions(null, true, false);
    }

    public LlmOptions withMaxRows(int rows) {
        if (rows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        return new LlmOptions(rows, redactSensitive, includeSchema);
    }

    public LlmOptions withRedactSensitive(boolean redact) {
        return new LlmOptions(maxRows, redact, includeSchema);
    }

    public LlmOptions withIncludeSchema(boolean include) {
        return new LlmOptions(maxRows, redactSensitive, include);
    }

    Map<String, Object> wire() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("max_rows", maxRows);
        m.put("redact_sensitive", redactSensitive);
        m.put("include_schema", includeSchema);
        return m;
    }
}
