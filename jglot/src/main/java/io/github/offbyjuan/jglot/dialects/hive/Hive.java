package io.github.offbyjuan.jglot.dialects.hive;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;

/** The Hive dialect: a port of sqlglot's {@code sqlglot.dialects.hive}. */
public class Hive extends Dialect {
    public Hive() {
        this("hive", "hive");
    }

    protected Hive(String name, String dataKey) {
        super(name, dataKey);
    }

    @Override
    public Parser newParser(ParserOptions options) {
        return new HiveParser(this, options);
    }

    @Override
    public Generator newGenerator(GeneratorOptions options) {
        return new HiveGenerator(this, options);
    }
}
