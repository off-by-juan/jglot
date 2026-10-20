package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.expressions.Expression;

/** Parts of sqlglot's {@code optimizer.canonicalize}. TODO(port): filled in by the optimizer port. */
public final class Canonicalize {
    private Canonicalize() {}

    /** {@code ensure_bools(expression)}. */
    public static Expression ensureBools(Expression expression) {
        throw new UnsupportedOperationException("not yet ported from sqlglot: optimizer.canonicalize.ensure_bools");
    }
}
