package io.github.offbyjuan.jglot.dialects.presto;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;

/** Presto parser: a port of sqlglot's {@code sqlglot.parsers.presto}. */
public class PrestoParser extends Parser {
    public PrestoParser(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }
}
