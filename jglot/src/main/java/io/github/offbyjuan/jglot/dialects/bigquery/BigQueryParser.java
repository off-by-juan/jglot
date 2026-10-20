package io.github.offbyjuan.jglot.dialects.bigquery;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;

/** BigQuery parser: a port of sqlglot's {@code sqlglot.parsers.bigquery}. */
public class BigQueryParser extends Parser {
    public BigQueryParser(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }
}
