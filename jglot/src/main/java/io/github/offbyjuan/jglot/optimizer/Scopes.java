package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import java.util.logging.Logger;

/** Module functions of sqlglot's {@code optimizer.scope}. */
public final class Scopes {
    private static final Logger LOGGER = Logger.getLogger("jglot");

    private Scopes() {}

    /** {@code TRAVERSABLES = (exp.Query, exp.DDL, exp.DML)}. */
    private static boolean isTraversable(Expression e) {
        return e.is(ExprType.QUERY, ExprType.DDL, ExprType.DML);
    }

    /**
     * {@code traverse_scope(expression)}: traverse an expression by its "scopes".
     *
     * <p>Scopes are returned in depth-first post-order. For each scope, its descendants occupy
     * consecutive entries in the returned list immediately before it.
     */
    public static List<Scope> traverseScope(Expression expression) {
        List<Scope> out = new ArrayList<>();
        if (expression != null && isTraversable(expression)) {
            traverseScopeImpl(new Scope(expression), out);
        }
        return out;
    }

    /** {@code build_scope(expression)}: the root scope, or null. */
    public static Scope buildScope(Expression expression) {
        List<Scope> scopes = traverseScope(expression);
        return scopes.isEmpty() ? null : scopes.get(scopes.size() - 1);
    }

    private static Scope last(List<Scope> out, int start) {
        return out.size() > start ? out.get(out.size() - 1) : null;
    }

    private static Scope newScope(Expression expression, Map<String, Object> cteSources) {
        return new Scope(expression, null, null, null, ScopeType.ROOT, null, cteSources, false);
    }

    // The Python generators below are ported as methods appending ("yielding") to `out`.
    private static void traverseScopeImpl(Scope scope, List<Scope> out) {
        Expression expression = scope.expression();

        if (expression.is(ExprType.SELECT)) {
            traverseSelect(scope, out);
        } else if (expression.is(ExprType.SET_OPERATION)) {
            traverseCtes(scope, out);
            traverseSetOperation(scope, out);
            return;
        } else if (expression.is(ExprType.SUBQUERY)) {
            if (scope.isRoot()) {
                traverseSelect(scope, out);
            } else {
                traverseSubqueries(scope, out);
            }
        } else if (expression.is(ExprType.TABLE)) {
            traverseTables(scope, out);
        } else if (expression.is(ExprType.UDTF)) {
            traverseUdtfs(scope, out);
        } else if (expression.is(ExprType.DDL)) {
            Expression ddlExpression = expression.getExpression();
            if (ddlExpression != null && ddlExpression.is(ExprType.QUERY)) {
                traverseCtes(scope, out);
                traverseScopeImpl(newScope(ddlExpression, scope.cteSources()), out);
            }
            return;
        } else if (expression.is(ExprType.DML)) {
            traverseCtes(scope, out);

            // Bare tables in relation position (e.g. UPDATE ... FROM t, DELETE / MERGE ... USING t)
            // aren't part of any query, so they're scoped as standalone tables; `_traverse_tables`
            // also picks up any joins hanging off of them
            List<Expression> relations = new ArrayList<>();
            Object from = expression.get(Args.FROM_);

            if (from instanceof Expression f && f.is(ExprType.FROM)) {
                relations.add(f.getThis());
            }

            Object using = expression.get(Args.USING);
            if (using instanceof List<?> l) {
                for (Object o : l) {
                    relations.add(o instanceof Expression e ? e : null);
                }
            } else if (using instanceof Expression u) {
                relations.add(u);
            }

            for (Expression relation : relations) {
                if (relation != null && relation.is(ExprType.TABLE)) {
                    traverseScopeImpl(newScope(relation, scope.cteSources()), out);
                }
            }

            for (Expression query : findAllInScope(expression, ExprType.QUERY)) {
                // This check ensures we don't yield the CTE/nested queries twice
                Expression queryParent = query.parent();
                if (queryParent != null && queryParent.is(ExprType.CTE, ExprType.SUBQUERY)) {
                    continue;
                }

                if (isFromOrJoin(query)) {
                    if (queryParent != null
                            && queryParent.is(ExprType.JOIN)
                            && queryParent.parent() != null
                            && queryParent.parent().is(ExprType.SUBQUERY, ExprType.TABLE)) {
                        // Scoped by the FROM-position relation (wrapper or table) it's joined to
                        continue;
                    }

                    // A query in FROM/JOIN position (e.g. UPDATE ... FROM (SELECT ...) AS s) acts
                    // like a derived table, so its scope stays rooted at the Subquery wrapper to
                    // pick up the wrapper's alias, column list and joins
                    traverseScopeImpl(newScope(query, scope.cteSources()), out);
                } else {
                    // Queries in value position (SET, WHERE, USING, ...) are scoped as subqueries,
                    // e.g. so their columns can be correlated to the DML's target table
                    traverseScopeImpl(scope.branch(query, ScopeType.SUBQUERY), out);
                }
            }
            return;
        } else {
            LOGGER.warning("Cannot traverse scope " + expression + " with type '" + expression.type().className() + "'");
            return;
        }

        out.add(scope);
    }

    private static void traverseSelect(Scope scope, List<Scope> out) {
        traverseCtes(scope, out);
        traverseTables(scope, out);
        traverseSubqueries(scope, out);
    }

    private static void traverseSetOperation(Scope scope, List<Scope> out) {
        Scope prevScope = null;
        List<Scope> setOpScopeStack = new ArrayList<>();
        setOpScopeStack.add(scope);

        Expression setOp = scope.expression();
        // Access the args directly instead of the left/right properties, because set operation
        // operands aren't guaranteed to be Query nodes, e.g. in VALUES (1) UNION ALL SELECT 1
        List<Expression> expressionStack = new ArrayList<>();
        expressionStack.add(setOp.getExpression());
        expressionStack.add(setOp.getThis());

        while (!expressionStack.isEmpty()) {
            Expression expression = expressionStack.remove(expressionStack.size() - 1);
            Scope setOpScope = setOpScopeStack.get(setOpScopeStack.size() - 1);

            Scope newScope = setOpScope.branch(
                    expression, ScopeType.SET_OPERATION, null, null, null, setOpScope.outerColumns());

            if (expression.is(ExprType.SET_OPERATION)) {
                traverseCtes(newScope, out);

                setOpScopeStack.add(newScope);
                expressionStack.add(expression.getExpression());
                expressionStack.add(expression.getThis());
                continue;
            }

            int start = out.size();
            traverseScopeImpl(newScope, out);
            Scope branchScope = last(out, start);

            if (branchScope == null) {
                throw new OptimizeError("Cannot build a scope for set operation operand: " + expression.sql(Dialects.base()));
            }

            if (prevScope != null) {
                setOpScopeStack.remove(setOpScopeStack.size() - 1);
                List<Scope> pair = new ArrayList<>(2);
                pair.add(prevScope);
                pair.add(branchScope);
                setOpScope.setSetOperationScopes(pair);
                prevScope = setOpScope;

                out.add(setOpScope);
            } else {
                prevScope = branchScope;
            }
        }
    }

    private static void traverseCtes(Scope scope, List<Scope> out) {
        Map<String, Object> sources = new LinkedHashMap<>();

        for (Expression cte : scope.ctes()) {
            String cteName = cte.alias();

            // if the scope is a recursive cte, it must be in the form of base_case UNION recursive.
            // thus the recursive scope is the first section of the union.
            Expression with = scope.expression().expr(Args.WITH_);
            if (with != null && with.has(Args.RECURSIVE)) {
                Expression union = cte.getThis();

                if (union != null && union.is(ExprType.SET_OPERATION)) {
                    sources.put(cteName, scope.branch(union.getThis(), ScopeType.CTE));
                }
            }

            int start = out.size();
            traverseScopeImpl(
                    scope.branch(cte.getThis(), ScopeType.CTE, null, sources, null, cte.aliasColumnNames()),
                    out);
            Scope childScope = last(out, start);

            // append the final child_scope yielded
            if (childScope != null) {
                sources.put(cteName, childScope);
                scope.cteScopes().add(childScope);
            }
        }

        scope.sources().putAll(sources);
        scope.cteSources().putAll(sources);
    }

    /**
     * We represent (tbl1 JOIN tbl2) as a Subquery, but it's not really a "derived table", as it
     * doesn't introduce a new scope. If an alias is present, it shadows all names under the
     * Subquery, so that's one exception to this rule.
     */
    static boolean isDerivedTable(Expression expression) {
        return expression.is(ExprType.SUBQUERY)
                && (!expression.alias().isEmpty()
                        || (expression.getThis() != null && expression.getThis().is(ExprType.SELECT, ExprType.SET_OPERATION)));
    }

    /** Determine if {@code expression} is the FROM or JOIN clause of a SELECT statement. */
    static boolean isFromOrJoin(Expression expression) {
        Expression parent = expression.parent();

        // Subqueries can be arbitrarily nested
        while (parent != null && parent.type() == ExprType.SUBQUERY) {
            parent = parent.parent();
        }

        return parent != null && (parent.type() == ExprType.FROM || parent.type() == ExprType.JOIN);
    }

    private static String findNewName(Map<String, Object> taken, String base) {
        if (!taken.containsKey(base)) {
            return base;
        }

        int i = 2;
        String newName = base + "_" + i;
        while (taken.containsKey(newName)) {
            i++;
            newName = base + "_" + i;
        }

        return newName;
    }

    private static void addJoins(List<Expression> expressions, Expression node) {
        for (Expression join : node.exprs(Args.JOINS)) {
            expressions.add(join.getThis());
        }
    }

    private static void traverseTables(Scope scope, List<Scope> out) {
        Map<String, Object> sources = new LinkedHashMap<>();
        Expression scopeExpression = scope.expression();

        // Traverse FROMs, JOINs, and LATERALs in the order they are defined
        List<Expression> expressions = new ArrayList<>();
        Expression from = scopeExpression.expr(Args.FROM_);
        if (from != null) {
            expressions.add(from.getThis());
        }

        addJoins(expressions, scopeExpression);

        if (scopeExpression.is(ExprType.TABLE, ExprType.SUBQUERY)) {
            // A Subquery-rooted scope, e.g., the FROM clause of a DML statement, a DDL source or
            // a parenthesized query like (SELECT ...) LIMIT 1, scopes its own inner query as a
            // derived table
            expressions.add(scopeExpression);
        }

        expressions.addAll(scopeExpression.exprs(Args.LATERALS));

        for (int i = 0; i < expressions.size(); i++) {
            Expression expression = expressions.get(i);
            if (expression == null) {
                continue;
            }
            if (expression.is(ExprType.FINAL)) {
                expression = expression.getThis();
                if (expression == null) {
                    continue;
                }
            }
            if (expression.is(ExprType.TABLE)) {
                String tableName = expression.name();
                String sourceName = expression.aliasOrName();

                if (scope.sources().containsKey(tableName) && expression.text(Args.DB).isEmpty()) {
                    // This is a reference to a parent source (e.g. a CTE), not an actual table, unless
                    // it is pivoted, because then we get back a new table and hence a new source.
                    List<Expression> pivots = expression.exprs(Args.PIVOTS);
                    if (!pivots.isEmpty()) {
                        sources.put(pivots.get(pivots.size() - 1).alias(), expression);
                    } else {
                        sources.put(sourceName, scope.sources().get(tableName));
                    }
                } else if (sources.containsKey(sourceName)) {
                    sources.put(findNewName(sources, tableName), expression);
                } else {
                    sources.put(sourceName, expression);
                }

                // Make sure to not include the joins twice
                if (expression != scopeExpression) {
                    addJoins(expressions, expression);
                }

                continue;
            }

            if (!expression.is(ExprType.DERIVED_TABLE)) {
                continue;
            }

            Expression node = expression;
            Map<String, Object> lateralSources;
            ScopeType scopeType;
            List<Scope> scopes;

            if (expression.is(ExprType.UDTF)) {
                lateralSources = sources;
                scopeType = ScopeType.UDTF;
                scopes = scope.udtfScopes();
            } else if (isDerivedTable(expression)) {
                lateralSources = null;
                scopeType = ScopeType.DERIVED_TABLE;
                scopes = scope.derivedTableScopes();
                if (node != scopeExpression) {
                    // The scope expression's own joins were already added above
                    addJoins(expressions, node);
                }
            } else {
                // Makes sure we check for possible sources in nested table constructs
                expressions.add(node.getThis());
                if (node != scopeExpression) {
                    addJoins(expressions, node);
                }
                continue;
            }

            int start = out.size();
            traverseScopeImpl(
                    scope.branch(node, scopeType, null, null, lateralSources, node.aliasColumnNames()),
                    out);
            Scope childScope = last(out, start);

            if (childScope != null) {
                // Tables without aliases will be set as ""
                // This shouldn't be a problem once qualify_columns runs, as it adds aliases on everything.
                // Until then, this means that only a single, unaliased derived table is allowed (rather,
                // the latest one wins.
                sources.put(getSourceAlias(node), childScope);

                // append the final child_scope yielded
                scopes.add(childScope);
                scope.tableScopes().add(childScope);
            }
        }

        scope.sources().putAll(sources);
    }

    private static void traverseSubqueries(Scope scope, List<Scope> out) {
        for (Expression subquery : scope.subqueries()) {
            int start = out.size();
            traverseScopeImpl(scope.branch(subquery, ScopeType.SUBQUERY), out);
            Scope top = last(out, start);
            if (top != null) {
                scope.subqueryScopes().add(top);
            }
        }
    }

    private static void traverseUdtfs(Scope scope, List<Scope> out) {
        Expression scopeExpression = scope.expression();
        List<Expression> udtfExpressions;
        if (scopeExpression.is(ExprType.UNNEST)) {
            udtfExpressions = scopeExpression.getExpressions();
        } else if (scopeExpression.is(ExprType.LATERAL)) {
            udtfExpressions = new ArrayList<>();
            udtfExpressions.add(scopeExpression.getThis());
        } else {
            udtfExpressions = List.of();
        }

        Map<String, Object> sources = new LinkedHashMap<>();
        for (Expression expression : udtfExpressions) {
            if (expression != null && expression.is(ExprType.SUBQUERY)) {
                int start = out.size();
                traverseScopeImpl(
                        scope.branch(expression, ScopeType.SUBQUERY, null, null, null, expression.aliasColumnNames()),
                        out);
                Scope top = last(out, start);

                if (top != null) {
                    sources.put(getSourceAlias(expression), top);
                    scope.subqueryScopes().add(top);
                }
            }
        }

        scope.sources().putAll(sources);
    }

    /** Whether {@code node} starts a child scope (and so isn't descended into by walk_in_scope). */
    private static boolean isScopeBoundary(Expression node) {
        if (!node.is(ExprType.CTE, ExprType.QUERY)) {
            return false;
        }
        Expression parent = node.parent();
        return node.is(ExprType.CTE)
                || (parent != null && parent.is(ExprType.FROM, ExprType.JOIN) && isDerivedTable(node))
                || (parent != null && parent.is(ExprType.UDTF))
                || node.is(ExprType.SELECT, ExprType.SET_OPERATION);
    }

    /**
     * {@code walk_in_scope(expression, prune)}: visits all nodes in the syntax tree, stopping at
     * nodes that start child scopes. Lazy, like the Python generator: a node's children are read
     * after the caller has seen the node.
     *
     * @param prune returns true if the traversal should stop descending this branch (nullable)
     */
    public static Iterable<Expression> walkInScope(Expression expression, Predicate<Expression> prune) {
        return () -> new WalkIterator(expression, prune);
    }

    public static Iterable<Expression> walkInScope(Expression expression) {
        return walkInScope(expression, null);
    }

    private static final class WalkIterator implements Iterator<Expression> {
        private final Expression root;
        private final Predicate<Expression> prune;
        private final List<Expression> stack = new ArrayList<>();
        private final ArrayDeque<Expression> nestedRoots = new ArrayDeque<>();
        private Iterator<Expression> nested;
        private Expression pending;

        WalkIterator(Expression root, Predicate<Expression> prune) {
            this.root = root;
            this.prune = prune;
            stack.add(root);
        }

        private void expandPending() {
            Expression node = pending;
            pending = null;

            // Only CTEs and Queries can start child scopes; checking that first lets all
            // other nodes (the vast majority) skip the rest of the boundary checks.
            if (node != root && isScopeBoundary(node)) {
                if (node.is(ExprType.SUBQUERY, ExprType.UDTF)) {
                    for (Expression arg : node.exprs(Args.JOINS)) {
                        nestedRoots.add(arg);
                    }
                    for (Expression arg : node.exprs(Args.LATERALS)) {
                        nestedRoots.add(arg);
                    }
                    for (Expression arg : node.exprs(Args.PIVOTS)) {
                        nestedRoots.add(arg);
                    }
                }
                return;
            }

            if (prune != null && prune.test(node)) {
                return;
            }

            List<Expression> children = node.iterExpressions();
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.add(children.get(i));
            }
        }

        @Override
        public boolean hasNext() {
            if (pending != null) {
                expandPending();
            }
            while (true) {
                if (nested != null) {
                    if (nested.hasNext()) {
                        return true;
                    }
                    nested = null;
                }
                if (nestedRoots.isEmpty()) {
                    break;
                }
                nested = walkInScope(nestedRoots.poll()).iterator();
            }
            return !stack.isEmpty();
        }

        @Override
        public Expression next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            if (nested != null) {
                return nested.next();
            }
            Expression node = stack.remove(stack.size() - 1);
            pending = node;
            return node;
        }
    }

    /**
     * {@code find_all_in_scope(expression, *expression_types)}: all nodes in this scope matching
     * at least one of the types. This does NOT traverse into subscopes.
     */
    public static List<Expression> findAllInScope(Expression expression, ExprType... types) {
        List<Expression> out = new ArrayList<>();
        for (Expression node : walkInScope(expression)) {
            if (node.is(types)) {
                out.add(node);
            }
        }
        return out;
    }

    /**
     * {@code find_in_scope(expression, *expression_types)}: the first node in this scope matching
     * at least one of the types, or null. This does NOT traverse into subscopes.
     */
    public static Expression findInScope(Expression expression, ExprType... types) {
        for (Expression node : walkInScope(expression)) {
            if (node.is(types)) {
                return node;
            }
        }
        return null;
    }

    static String getSourceAlias(Expression expression) {
        Object aliasArg = expression.get(Args.ALIAS);
        String aliasName = expression.alias();

        if (aliasName.isEmpty()
                && aliasArg instanceof Expression a
                && a.is(ExprType.TABLE_ALIAS)
                && a.exprs(Args.COLUMNS).size() == 1) {
            aliasName = a.exprs(Args.COLUMNS).get(0).name();
        }

        return aliasName;
    }

    /**
     * sqlglot's polymorphic {@code unnest()}: {@code Subquery.unnest} returns the first non-subquery;
     * every other node unwraps {@code Paren}s. (jglot's {@code Expression.unnest()} only does the
     * latter.)
     */
    static Expression unnest(Expression expression) {
        Expression e = expression;
        if (e.is(ExprType.SUBQUERY)) {
            while (e != null && e.is(ExprType.SUBQUERY)) {
                e = e.getThis();
            }
            return e;
        }
        while (e != null && e.type() == ExprType.PAREN) {
            e = e.getThis();
        }
        return e;
    }
}
