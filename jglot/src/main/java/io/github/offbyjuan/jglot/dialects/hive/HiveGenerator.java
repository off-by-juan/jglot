package io.github.offbyjuan.jglot.dialects.hive;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;

/** Hive generator: a port of sqlglot's {@code sqlglot.generators.hive}. */
public class HiveGenerator extends Generator {
    public HiveGenerator(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }
}
