package io.github.trinesh14.tricoredb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One statement of a transaction script, with optional bound arguments.
 *
 * @see TriCore#transaction(List, String)
 */
public record SqlStatement(String sql, List<Object> args) {

    public SqlStatement {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("a statement needs non-empty SQL");
        }
        // An ArrayList copy rather than List.copyOf: a bound NULL argument is
        // legitimate, and List.copyOf refuses nulls outright.
        args = args == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(args));
    }

    /** A statement with {@code ?} placeholders bound from {@code args}. */
    public static SqlStatement of(String sql, Object... args) {
        return new SqlStatement(sql, args == null ? List.of() : Arrays.asList(args));
    }

    /** The statement with its arguments rendered in. */
    public String render() {
        return args.isEmpty() ? sql : SqlParams.bind(sql, args);
    }

    /**
     * Assemble {@code BEGIN; …; COMMIT} from a list of statements.
     *
     * <p>Caller-supplied transaction control is refused rather than passed
     * through: nesting a second {@code BEGIN}, or committing early, changes what
     * the script means in a way the caller almost certainly did not intend.
     */
    static String script(List<SqlStatement> statements) {
        return script(statements, true);
    }

    /**
     * The same script with every statement's {@code ?} placeholders left in
     * place, to be bound server-side against {@link #scriptArgs}.
     */
    static String parameterizedScript(List<SqlStatement> statements) {
        return script(statements, false);
    }

    /**
     * Every statement's arguments, concatenated in statement order — the flat,
     * positional list the server binds a multi-statement script against.
     */
    static List<Object> scriptArgs(List<SqlStatement> statements) {
        List<Object> out = new ArrayList<>();
        if (statements != null) {
            for (SqlStatement s : statements) {
                if (s != null) {
                    out.addAll(s.args());
                }
            }
        }
        return out;
    }

    private static String script(List<SqlStatement> statements, boolean renderArgs) {
        if (statements == null || statements.isEmpty()) {
            throw new IllegalArgumentException("a transaction needs at least one statement");
        }
        StringBuilder out = new StringBuilder("BEGIN; ");
        for (int i = 0; i < statements.size(); i++) {
            SqlStatement s = statements.get(i);
            if (s == null) {
                throw new IllegalArgumentException("statement list must not contain null");
            }
            String text = (renderArgs ? s.render() : s.sql()).trim();
            while (text.endsWith(";")) {
                text = text.substring(0, text.length() - 1).trim();
            }
            if (text.isEmpty()) {
                throw new IllegalArgumentException("each transaction statement must be non-empty SQL");
            }
            String first = text.split("\\s+")[0].toUpperCase(Locale.ROOT);
            if (first.equals("BEGIN") || first.equals("START")
                    || first.equals("COMMIT") || first.equals("ROLLBACK")) {
                throw new IllegalArgumentException(
                        "transaction() brackets the script itself — remove the `" + first + "` statement");
            }
            if (i > 0) {
                out.append("; ");
            }
            out.append(text);
        }
        return out.append("; COMMIT").toString();
    }
}
