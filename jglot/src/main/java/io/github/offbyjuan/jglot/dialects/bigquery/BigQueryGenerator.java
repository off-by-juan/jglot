package io.github.offbyjuan.jglot.dialects.bigquery;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;

/** BigQuery generator: a port of sqlglot's {@code sqlglot.generators.bigquery}. */
public class BigQueryGenerator extends Generator {
    public BigQueryGenerator(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }
}
