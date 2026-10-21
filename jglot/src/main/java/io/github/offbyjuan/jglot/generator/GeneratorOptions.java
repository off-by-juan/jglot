package io.github.offbyjuan.jglot.generator;

import io.github.offbyjuan.jglot.errors.ErrorLevel;

/**
 * SQL generation options. Use {@link #builder()} to customise.
 *
 * <p>Unlike sqlglot (which warns by default), jglot defaults {@code unsupportedLevel} to {@link
 * ErrorLevel#RAISE}: translating a construct or known function the target dialect cannot express
 * fails loudly instead of silently producing SQL with different meaning.
 */
public record GeneratorOptions(
        boolean pretty,
        Object identify,
        boolean normalize,
        int pad,
        int indent,
        Object normalizeFunctions,
        ErrorLevel unsupportedLevel,
        int maxUnsupported,
        boolean leadingComma,
        int maxTextWidth,
        boolean comments,
        boolean strictFunctions) {

    public static final GeneratorOptions DEFAULT = builder().build();

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.pretty = pretty;
        b.identify = identify;
        b.normalize = normalize;
        b.pad = pad;
        b.indent = indent;
        b.normalizeFunctions = normalizeFunctions;
        b.unsupportedLevel = unsupportedLevel;
        b.maxUnsupported = maxUnsupported;
        b.leadingComma = leadingComma;
        b.maxTextWidth = maxTextWidth;
        b.comments = comments;
        b.strictFunctions = strictFunctions;
        return b;
    }

    public static final class Builder {
        private boolean pretty;
        private Object identify = Boolean.FALSE;
        private boolean normalize;
        private int pad = 2;
        private int indent = 2;
        private Object normalizeFunctions;
        private ErrorLevel unsupportedLevel = ErrorLevel.RAISE;
        private int maxUnsupported = 3;
        private boolean leadingComma;
        private int maxTextWidth = 80;
        private boolean comments = true;
        private boolean strictFunctions = true;

        /** Format the output over multiple lines (intended for debugging). */
        public Builder pretty(boolean v) {
            pretty = v;
            return this;
        }

        /** When to quote identifiers: {@code false}, {@code true}, {@code "safe"} or {@code "unsafe"}. */
        public Builder identify(Object v) {
            identify = v;
            return this;
        }

        public Builder normalize(boolean v) {
            normalize = v;
            return this;
        }

        public Builder pad(int v) {
            pad = v;
            return this;
        }

        public Builder indent(int v) {
            indent = v;
            return this;
        }

        /** {@code "upper"}/{@code true}, {@code "lower"}, or {@code false}; null uses the dialect's default. */
        public Builder normalizeFunctions(Object v) {
            normalizeFunctions = v;
            return this;
        }

        public Builder unsupportedLevel(ErrorLevel v) {
            unsupportedLevel = v;
            return this;
        }

        public Builder maxUnsupported(int v) {
            maxUnsupported = v;
            return this;
        }

        public Builder leadingComma(boolean v) {
            leadingComma = v;
            return this;
        }

        public Builder maxTextWidth(int v) {
            maxTextWidth = v;
            return this;
        }

        public Builder comments(boolean v) {
            comments = v;
            return this;
        }

        /**
         * Treat a known function as unsupported when the target dialect has no specific rendering
         * for it and doesn't itself recognise its canonical name (default true). sqlglot has no
         * such check: it prints the canonical name, which may not exist in the target engine.
         * Turn this off for exact sqlglot parity.
         */
        public Builder strictFunctions(boolean v) {
            strictFunctions = v;
            return this;
        }

        public GeneratorOptions build() {
            return new GeneratorOptions(pretty, identify, normalize, pad, indent, normalizeFunctions,
                    unsupportedLevel, maxUnsupported, leadingComma, maxTextWidth, comments, strictFunctions);
        }
    }
}
