package io.github.offbyjuan.jglot.dialects.clickhouse;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;

/** ClickHouse parser: a port of sqlglot's {@code sqlglot.parsers.clickhouse}. */
public class ClickHouseParser extends Parser {
    public ClickHouseParser(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }
}
