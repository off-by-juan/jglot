package io.github.offbyjuan.jglot.errors;

/** Raised when tokenizing fails. */
public class TokenError extends JGlotError {
    public TokenError(String message) {
        super(message);
    }

    public TokenError(String message, Throwable cause) {
        super(message, cause);
    }
}
