package io.github.offbyjuan.jglot.dialects.bigquery;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;

/** The BigQuery dialect: a port of sqlglot's {@code sqlglot.dialects.bigquery}. */
public class BigQuery extends Dialect {
    public BigQuery() {
        this("bigquery", "bigquery");
    }

    protected BigQuery(String name, String dataKey) {
        super(name, dataKey);
    }

    @Override
    public Parser newParser(ParserOptions options) {
        return new BigQueryParser(this, options);
    }

    @Override
    public Generator newGenerator(GeneratorOptions options) {
        return new BigQueryGenerator(this, options);
    }
}
