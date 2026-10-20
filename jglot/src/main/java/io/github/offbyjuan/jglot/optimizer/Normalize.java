package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.expressions.Expression;

/** sqlglot's {@code optimizer.normalize}. TODO(port): filled in by the optimizer port. */
public final class Normalize {
    private Normalize() {}

    /** {@code normalize(expression, dnf=..., max_distance=128)}. */
    public static Expression normalize(Expression expression, boolean dnf) {
        throw new UnsupportedOperationException("not yet ported from sqlglot: optimizer.normalize.normalize");
    }

    /** {@code normalized(expression, dnf=...)}. */
    public static boolean normalized(Expression expression, boolean dnf) {
        throw new UnsupportedOperationException("not yet ported from sqlglot: optimizer.normalize.normalized");
    }
}
