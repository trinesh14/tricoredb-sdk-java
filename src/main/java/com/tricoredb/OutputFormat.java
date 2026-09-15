package com.tricoredb;

/** How the server should render an export. */
public enum OutputFormat {
    /** TriCoreDB's native representation. */
    NATIVE("native"),
    /** Standard JSON. */
    JSON("json"),
    /** TOON: a token-oriented rendering for LLM context windows. */
    TOON("toon"),
    /** Human-readable Markdown. */
    MARKDOWN("markdown");

    private final String wire;

    OutputFormat(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }
}
