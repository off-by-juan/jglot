package io.github.offbyjuan.jglot.transforms;

import io.github.offbyjuan.jglot.expressions.Expression;

/**
 * Tree rewrites from sqlglot's {@code transforms} module, used by generators via
 * {@code applyTransforms(e, Transforms::eliminateQualify, ...)}. TODO(port): filled in by the
 * transforms port.
 */
public final class Transforms {
    private Transforms() {}

    /** {@code transforms.ensure_bools(expression)}. */
    public static Expression ensureBools(Expression expression) {
        throw new UnsupportedOperationException("not yet ported from sqlglot: transforms.ensure_bools");
    }

    /** {@code transforms.move_ctes_to_top_level(expression)}. */
    public static Expression moveCtesToTopLevel(Expression expression) {
        throw new UnsupportedOperationException("not yet ported from sqlglot: transforms.move_ctes_to_top_level");
    }
}
