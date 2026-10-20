package io.github.offbyjuan.jglot.parser;

import io.github.offbyjuan.jglot.dialects.Dialect;

/**
 * The SQL parser: a port of sqlglot's {@code Parser}. Dialects subclass it to override individual
 * parse methods and dispatch hooks.
 *
 * <p>Create parsers through {@link Dialect#newParser}; an instance parses one input and is not
 * thread-safe, but creating one is cheap since all tables are shared, immutable dialect data.
 */
public class Parser extends ParserMethods {
    public Parser(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }
}
