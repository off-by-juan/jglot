package io.github.offbyjuan.jglot.dialects.starburst;

import io.github.offbyjuan.jglot.dialects.trino.Trino;

/**
 * Starburst (commercial Trino). Currently identical to {@link Trino}; Starburst-specific syntax and
 * functions can be added here by overriding Trino's parser and generator.
 */
public class Starburst extends Trino {
    public Starburst() {
        super("starburst", "trino");
    }
}
