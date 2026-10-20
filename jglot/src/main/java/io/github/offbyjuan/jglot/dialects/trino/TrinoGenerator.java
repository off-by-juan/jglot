package io.github.offbyjuan.jglot.dialects.trino;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.presto.PrestoGenerator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;

/** Trino generator: a port of sqlglot's {@code sqlglot.generators.trino}. */
public class TrinoGenerator extends PrestoGenerator {
    public TrinoGenerator(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }
}
