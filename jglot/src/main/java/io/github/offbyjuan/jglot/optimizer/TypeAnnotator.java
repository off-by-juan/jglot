package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.expressions.Expression;

/** sqlglot's {@code optimizer.annotate_types}. TODO(port): filled in by the optimizer port. */
public final class TypeAnnotator {
    private TypeAnnotator() {}

    /** {@code annotate_types(expression, dialect=dialect)}: sets data types on the tree in place and returns it. */
    public static Expression annotateTypes(Expression expression, Dialect dialect) {
        throw new UnsupportedOperationException("not yet ported from sqlglot: optimizer.annotate_types");
    }
}
