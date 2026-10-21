package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** sqlglot's {@code optimizer.normalize_identifiers}. */
public final class NormalizeIdentifiers {
    private NormalizeIdentifiers() {}

    /** {@code normalize_identifiers(expression, dialect=dialect)}. */
    public static Expression normalizeIdentifiers(Expression expression, Dialect dialect) {
        return normalizeIdentifiers(expression, dialect, false);
    }

    /**
     * Normalize identifiers by converting them to either lower or upper case, ensuring the
     * semantics are preserved in each case (e.g. by respecting case-sensitivity).
     *
     * <p>This transformation reflects how identifiers would be resolved by the engine corresponding
     * to each SQL dialect. It's possible to make this a no-op by adding a special comment next to
     * the identifier of interest: {@code SELECT a /* sqlglot.meta case_sensitive *}{@code / FROM
     * table}.
     *
     * @param expression the expression to transform
     * @param dialect the dialect used to decide how to normalize identifiers (null: the base dialect)
     * @param storeOriginalColumnIdentifiers whether to store the original column identifiers in the
     *     meta data of the expression (key {@code dot_parts}) in case we want to undo the
     *     normalization at a later point
     */
    public static Expression normalizeIdentifiers(
            Expression expression, Dialect dialect, boolean storeOriginalColumnIdentifiers) {
        if (dialect == null) {
            dialect = Dialects.base();
        }

        for (Expression node : expression.bfs(n -> Expression.truthy(n.metaGet("case_sensitive")))) {
            if (Expression.truthy(node.metaGet("case_sensitive"))) {
                continue;
            }

            // A dot chain is recorded once, at its outermost Dot. Ancestors are visited before
            // their descendants, so none of the names it wraps have been normalized yet
            if (storeOriginalColumnIdentifiers
                    && node.is(ExprType.COLUMN, ExprType.DOT)
                    && !(node.parent() != null && node.parent().is(ExprType.DOT))) {
                if (node.is(ExprType.COLUMN)) {
                    node.meta().put("dot_parts", names(node.parts()));
                } else if (!node.isStar()) {
                    Expression root = node;
                    List<String> dotParts = new ArrayList<>();
                    while (root != null && root.is(ExprType.DOT)) {
                        Expression rhs = root.getExpression();
                        dotParts.add(rhs == null ? "" : rhs.name());
                        root = root.getThis();
                    }

                    Collections.reverse(dotParts);

                    // The chain may be rooted at a column (j.k), or at an arbitrary expression (f().k)
                    if (root != null && root.is(ExprType.COLUMN)) {
                        List<String> withColumn = names(root.parts());
                        withColumn.addAll(dotParts);
                        dotParts = withColumn;
                    }

                    if (root != null) {
                        root.meta().put("dot_parts", dotParts);
                    }
                }
            }

            if (node.is(ExprType.IDENTIFIER)) {
                dialect.normalizeIdentifier(node);
            }
        }

        return expression;
    }

    private static List<String> names(List<Expression> parts) {
        List<String> out = new ArrayList<>(parts.size());
        for (Expression p : parts) {
            out.add(p.name());
        }
        return out;
    }
}
