package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.expressions.Expression;

/** sqlglot's {@code optimizer.simplify}. TODO(port): filled in by the optimizer port. */
public final class Simplify {
    private Simplify() {}

    /** {@code simplify(expression, dialect=dialect)}. */
    public static Expression simplify(Expression expression, Dialect dialect) {
        throw new UnsupportedOperationException("not yet ported from sqlglot: optimizer.simplify");
    }
}
