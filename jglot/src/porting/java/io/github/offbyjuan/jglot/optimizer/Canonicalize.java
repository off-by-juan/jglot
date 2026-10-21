package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.expressions.Exp;
import java.util.function.Consumer;

/** Parts of sqlglot's {@code optimizer.canonicalize}. */
public final class Canonicalize {
    private Canonicalize() {}

    /**
     * {@code ensure_bools(expression, _replace_int_predicate)}, as used by {@code canonicalize}:
     * replaces integer-typed predicates of this node with {@code x <> 0}.
     */
    public static Expression ensureBools(Expression expression) {
        return ensureBools(expression, Canonicalize::replaceIntPredicate);
    }

    /** {@code ensure_bools(expression, replace_func)}. */
    public static Expression ensureBools(Expression expression, Consumer<Expression> replaceFunc) {
        if (expression.is(ExprType.CONNECTOR)) {
            replaceFunc.accept(expression.getThis());
            replaceFunc.accept(expression.getExpression());
        } else if (expression.is(ExprType.NOT)) {
            replaceFunc.accept(expression.getThis());
            // We can't replace num in CASE x WHEN num ..., because it's not the full predicate
        } else if (expression.is(ExprType.IF)
                && !(expression.parent() != null
                        && expression.parent().is(ExprType.CASE)
                        && expression.parent().has(Args.THIS))) {
            replaceFunc.accept(expression.getThis());
        } else if (expression.is(ExprType.WHERE, ExprType.HAVING)) {
            replaceFunc.accept(expression.getThis());
        }

        return expression;
    }

    // this was originally designed for presto, there is a similar transform for tsql
    // this is different in that it only operates on int types, this is because
    // presto has a boolean type whereas tsql doesn't (people use bits)
    // with y as (select true as x) select x = 0 FROM y -- illegal presto query
    private static void replaceIntPredicate(Expression expression) {
        if (expression.is(ExprType.COALESCE)) {
            for (Expression child : expression.iterExpressions()) {
                replaceIntPredicate(child);
            }
        } else {
            Expression type = expression.dataType();
            if (type != null && type.thisValue() != null && DataTypes.INTEGER_TYPES.contains(type.thisValue())) {
                expression.replace(expression.neq(Exp.number(0)));
            }
        }
    }
}
