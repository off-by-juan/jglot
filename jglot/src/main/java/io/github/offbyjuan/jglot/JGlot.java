package io.github.offbyjuan.jglot;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.errors.ParseError;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.parser.ParserOptions;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry points for parsing and translating SQL between dialects.
 *
 * <pre>{@code
 * String trino = JGlot.transpileOne("SELECT a::INT FROM t", "postgres", "trino");
 * // SELECT CAST(a AS INTEGER) FROM t
 * }</pre>
 *
 * <p>All methods are thread-safe. By default, generation raises {@link
 * io.github.offbyjuan.jglot.errors.UnsupportedError} when the target dialect cannot express a
 * construct or known function (see {@link GeneratorOptions}); functions the parser does not know
 * are passed through unchanged.
 */
public final class JGlot {
    private JGlot() {}

    /** Translates every statement in {@code sql} from dialect {@code read} to dialect {@code write}. */
    public static List<String> transpile(String sql, String read, String write) {
        return transpile(sql, Dialects.get(read), Dialects.get(write), GeneratorOptions.DEFAULT);
    }

    public static List<String> transpile(String sql, Dialect read, Dialect write, GeneratorOptions options) {
        List<String> out = new ArrayList<>();
        for (Expression e : read.parse(sql)) {
            out.add(e == null ? "" : write.newGenerator(options).generate(e, false));
        }
        return out;
    }

    /** Translates a single statement. */
    public static String transpileOne(String sql, String read, String write) {
        List<String> all = transpile(sql, read, write);
        if (all.size() != 1) {
            throw new ParseError("Expected exactly one statement, got " + all.size());
        }
        return all.get(0);
    }

    /** Parses every statement in {@code sql}. */
    public static List<Expression> parse(String sql, String dialect) {
        return Dialects.get(dialect).parse(sql);
    }

    /** Parses a single statement. */
    public static Expression parseOne(String sql, String dialect) {
        return parseOne(sql, Dialects.get(dialect), ParserOptions.DEFAULT);
    }

    public static Expression parseOne(String sql, Dialect dialect, ParserOptions options) {
        List<Expression> all = dialect.parse(sql, options);
        if (all.isEmpty() || all.get(0) == null) {
            throw new ParseError("No expression was parsed from '" + sql + "'");
        }
        if (all.size() > 1) {
            throw new ParseError("Expected exactly one statement, got " + all.size());
        }
        return all.get(0);
    }

    /** Renders {@code expression} as SQL in {@code dialect}. */
    public static String generate(Expression expression, String dialect) {
        return Dialects.get(dialect).generate(expression);
    }
}
