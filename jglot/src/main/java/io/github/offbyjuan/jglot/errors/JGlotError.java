package io.github.offbyjuan.jglot.errors;

/** Base class of all jglot errors (sqlglot's {@code SqlglotError}). */
public class JGlotError extends RuntimeException {
    public JGlotError(String message) {
        super(message);
    }

    public JGlotError(String message, Throwable cause) {
        super(message, cause);
    }
}
