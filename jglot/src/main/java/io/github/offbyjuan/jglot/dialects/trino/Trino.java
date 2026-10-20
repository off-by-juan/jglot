package io.github.offbyjuan.jglot.dialects.trino;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.presto.Presto;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;

/** The Trino dialect: a port of sqlglot's {@code sqlglot.dialects.trino}. */
public class Trino extends Presto {
    public Trino() {
        this("trino", "trino");
    }

    protected Trino(String name, String dataKey) {
        super(name, dataKey);
    }

    @Override
    public Parser newParser(ParserOptions options) {
        return new TrinoParser(this, options);
    }

    @Override
    public Generator newGenerator(GeneratorOptions options) {
        return new TrinoGenerator(this, options);
    }
}
