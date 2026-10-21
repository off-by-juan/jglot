/**
 * jglot: a SQL parser and dialect transpiler, ported from sqlglot.
 *
 * <p>Start with {@link io.github.offbyjuan.jglot.JGlot} for one-call translation, or
 * {@link io.github.offbyjuan.jglot.dialects.Dialects} to look up a dialect and parse or generate
 * with it. Packages not exported here (dialect implementations, generated data tables, transforms,
 * optimizer helpers and utilities) are internal and may change in any release.
 */
module io.github.offbyjuan.jglot {
    requires java.logging;

    exports io.github.offbyjuan.jglot;
    exports io.github.offbyjuan.jglot.dialects;
    exports io.github.offbyjuan.jglot.errors;
    exports io.github.offbyjuan.jglot.expressions;
    exports io.github.offbyjuan.jglot.generator;
    exports io.github.offbyjuan.jglot.parser;
    exports io.github.offbyjuan.jglot.tokens;
}
