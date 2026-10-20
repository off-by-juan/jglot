package io.github.offbyjuan.jglot.dialects.presto;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;

/** The Presto dialect: a port of sqlglot's {@code sqlglot.dialects.presto}. */
public class Presto extends Dialect {
    public Presto() {
        this("presto", "presto");
    }

    protected Presto(String name, String dataKey) {
        super(name, dataKey);
    }

    @Override
    public Parser newParser(ParserOptions options) {
        return new PrestoParser(this, options);
    }

    @Override
    public Generator newGenerator(GeneratorOptions options) {
        return new PrestoGenerator(this, options);
    }
}
