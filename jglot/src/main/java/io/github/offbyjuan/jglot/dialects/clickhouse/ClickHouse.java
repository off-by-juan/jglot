package io.github.offbyjuan.jglot.dialects.clickhouse;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;

/** The ClickHouse dialect: a port of sqlglot's {@code sqlglot.dialects.clickhouse}. */
public class ClickHouse extends Dialect {
    public ClickHouse() {
        this("clickhouse", "clickhouse");
    }

    protected ClickHouse(String name, String dataKey) {
        super(name, dataKey);
    }

    @Override
    public Parser newParser(ParserOptions options) {
        return new ClickHouseParser(this, options);
    }

    @Override
    public Generator newGenerator(GeneratorOptions options) {
        return new ClickHouseGenerator(this, options);
    }
}
