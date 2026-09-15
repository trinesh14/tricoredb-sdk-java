package io.github.trinesh14.tricoredb;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A SQL result set: column names plus rows of stringified cell values (that's what the wire sends). */
public final class Rows {

    private final List<String> columns;
    private final List<List<String>> rows;

    Rows(List<String> columns, List<List<String>> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    public List<String> columns() {
        return columns;
    }

    public List<List<String>> rows() {
        return rows;
    }

    public int size() {
        return rows.size();
    }

    /** Each row as a column-name-keyed map, for callers who don't want to track column order. */
    public List<Map<String, String>> asMaps() {
        return rows.stream().map(row -> {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < columns.size() && i < row.size(); i++) {
                m.put(columns.get(i), row.get(i));
            }
            return m;
        }).toList();
    }

    @Override
    public String toString() {
        return "Rows(columns=" + columns + ", rows=" + rows.size() + ")";
    }
}
