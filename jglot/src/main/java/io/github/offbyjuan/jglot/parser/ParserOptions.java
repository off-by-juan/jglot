package io.github.offbyjuan.jglot.parser;

import io.github.offbyjuan.jglot.errors.ErrorLevel;

/**
 * Parser options.
 *
 * @param errorLevel how errors are reported (default {@link ErrorLevel#IMMEDIATE})
 * @param errorMessageContext characters of SQL context to include in error messages
 * @param maxErrors maximum number of messages in a raised error (for {@link ErrorLevel#RAISE})
 * @param maxNodes maximum number of AST nodes, or -1 for no limit
 */
public record ParserOptions(ErrorLevel errorLevel, int errorMessageContext, int maxErrors, int maxNodes) {
    public static final ParserOptions DEFAULT = new ParserOptions(ErrorLevel.IMMEDIATE, 100, 3, -1);

    public ParserOptions withErrorLevel(ErrorLevel level) {
        return new ParserOptions(level, errorMessageContext, maxErrors, maxNodes);
    }

    public ParserOptions withMaxNodes(int max) {
        return new ParserOptions(errorLevel, errorMessageContext, maxErrors, max);
    }
}
