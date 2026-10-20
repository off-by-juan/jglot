package io.github.offbyjuan.jglot.dialects.clickhouse;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;

/** ClickHouse generator: a port of sqlglot's {@code sqlglot.generators.clickhouse}. */
public class ClickHouseGenerator extends Generator {
    public ClickHouseGenerator(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }
}
