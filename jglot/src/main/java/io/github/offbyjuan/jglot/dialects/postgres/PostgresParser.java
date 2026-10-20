package io.github.offbyjuan.jglot.dialects.postgres;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;

/** Postgres parser: a port of sqlglot's {@code sqlglot.parsers.postgres}. */
public class PostgresParser extends Parser {
    public PostgresParser(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }
}
