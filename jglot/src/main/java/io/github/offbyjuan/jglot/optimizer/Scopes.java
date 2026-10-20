package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import java.util.List;

/** Scope helpers from sqlglot's {@code optimizer.scope} module. TODO(port): filled in by the optimizer port. */
public final class Scopes {
    private Scopes() {}

    /** {@code find_all_in_scope(expression, expression_types, bfs=True)}. */
    public static List<Expression> findAllInScope(Expression expression, ExprType... types) {
        throw new UnsupportedOperationException("not yet ported from sqlglot: optimizer.scope.find_all_in_scope");
    }

    /** {@code find_in_scope(expression, expression_types, bfs=True)}. */
    public static Expression findInScope(Expression expression, ExprType... types) {
        throw new UnsupportedOperationException("not yet ported from sqlglot: optimizer.scope.find_in_scope");
    }

    /** {@code traverse_scope(expression)}. */
    public static List<Scope> traverseScope(Expression expression) {
        throw new UnsupportedOperationException("not yet ported from sqlglot: optimizer.scope.traverse_scope");
    }
}
