package io.github.offbyjuan.jglot.dialects.postgres;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;

/** Postgres generator: a port of sqlglot's {@code sqlglot.generators.postgres}. */
public class PostgresGenerator extends Generator {
    public PostgresGenerator(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }
}
