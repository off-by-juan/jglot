package io.github.offbyjuan.jglot.generator;

import io.github.offbyjuan.jglot.dialects.Dialect;

/**
 * The SQL generator: a port of sqlglot's {@code Generator}. Dialects subclass it to override
 * individual {@code <key>Sql} methods and the {@link #transform} hook.
 *
 * <p>Create generators through {@link Dialect#newGenerator}; an instance renders one tree and is
 * not thread-safe, but creating one is cheap since all tables are shared, immutable dialect data.
 */
public class Generator extends GeneratorMethods {
    public Generator(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }
}
