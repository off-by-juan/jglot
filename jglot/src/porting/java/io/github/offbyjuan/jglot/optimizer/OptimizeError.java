package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.errors.JGlotError;

/** sqlglot's {@code errors.OptimizeError}. */
public class OptimizeError extends JGlotError {
    public OptimizeError(String message) {
        super(message);
    }
}
