package io.github.offbyjuan.jglot.dialects;

import io.github.offbyjuan.jglot.expressions.Expression;

/** JSON path parsing for {@link Dialect#toJsonPath}: a port of sqlglot's {@code jsonpath} module. */
final class JsonPathSupport {
    private JsonPathSupport() {}

    static Expression toJsonPath(Dialect dialect, Expression path) {
        throw new UnsupportedOperationException("not yet ported from sqlglot: jsonpath");
    }
}
