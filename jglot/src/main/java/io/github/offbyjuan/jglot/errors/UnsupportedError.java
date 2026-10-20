package io.github.offbyjuan.jglot.errors;

/**
 * Raised when the target dialect cannot express a construct or function from the source, for
 * example a known function that has no translation in the target dialect.
 */
public class UnsupportedError extends JGlotError {
    public UnsupportedError(String message) {
        super(message);
    }
}
