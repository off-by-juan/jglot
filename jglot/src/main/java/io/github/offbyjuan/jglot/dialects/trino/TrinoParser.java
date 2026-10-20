package io.github.offbyjuan.jglot.dialects.trino;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.presto.PrestoParser;
import io.github.offbyjuan.jglot.parser.ParserOptions;

/** Trino parser: a port of sqlglot's {@code sqlglot.parsers.trino}. */
public class TrinoParser extends PrestoParser {
    public TrinoParser(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }
}
