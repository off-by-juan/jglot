package io.github.offbyjuan.jglot.dialects.hive;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;

/** Hive parser: a port of sqlglot's {@code sqlglot.parsers.hive}. */
public class HiveParser extends Parser {
    public HiveParser(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }
}
