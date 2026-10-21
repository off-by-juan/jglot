package io.github.offbyjuan.jglot.optimizer;

/** sqlglot's {@code optimizer.scope.ScopeType}. */
public enum ScopeType {
    ROOT,
    SUBQUERY,
    DERIVED_TABLE,
    CTE,
    SET_OPERATION,
    UDTF
}
