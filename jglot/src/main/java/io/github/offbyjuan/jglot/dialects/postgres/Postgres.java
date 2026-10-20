package io.github.offbyjuan.jglot.dialects.postgres;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;

/** The Postgres dialect: a port of sqlglot's {@code sqlglot.dialects.postgres}. */
public class Postgres extends Dialect {
    public Postgres() {
        this("postgres", "postgres");
    }

    protected Postgres(String name, String dataKey) {
        super(name, dataKey);
    }

    @Override
    public Parser newParser(ParserOptions options) {
        return new PostgresParser(this, options);
    }

    @Override
    public Generator newGenerator(GeneratorOptions options) {
        return new PostgresGenerator(this, options);
    }
}
