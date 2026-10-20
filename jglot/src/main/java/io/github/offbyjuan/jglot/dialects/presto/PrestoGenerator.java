package io.github.offbyjuan.jglot.dialects.presto;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;

/** Presto generator: a port of sqlglot's {@code sqlglot.generators.presto}. */
public class PrestoGenerator extends Generator {
    public PrestoGenerator(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }
}
