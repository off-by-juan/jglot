package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * sqlglot's {@code optimizer.scope.Scope}: a selection scope.
 *
 * <ul>
 *   <li>{@code expression}: root expression of this scope
 *   <li>{@code sources}: mapping of source name to either a Table expression or another Scope
 *       instance. For example: {@code SELECT * FROM x} gives {@code {"x": Table(this="x")}},
 *       {@code SELECT * FROM (SELECT ...) AS y} gives {@code {"y": Scope(...)}}. Values are
 *       {@code Object}: an {@link Expression} or a {@link Scope}.
 *   <li>{@code lateralSources}: sources from laterals
 *   <li>{@code cteSources}: sources from CTEs
 *   <li>{@code outerColumns}: if this is a derived table or CTE, and the outer query defines a
 *       column list for the alias of this scope, this is that list of columns
 *   <li>{@code parent}: parent scope; {@code scopeType}: type of this scope, relative to its parent
 *   <li>{@code subqueryScopes}, {@code cteScopes}, {@code derivedTableScopes}, {@code udtfScopes}:
 *       child scopes; {@code tableScopes}: derived table + UDTF scopes in definition order;
 *       {@code setOperationScopes}: the left and right child scopes of a set operation
 * </ul>
 */
public class Scope {
    /** A selected source: the node in FROM/JOIN and the source it resolves to (a Table or a Scope). */
    public record SelectedSource(Expression node, Object source) {}

    private Expression expression;
    private final Map<String, Object> sources;
    private final Map<String, Object> lateralSources;
    private final Map<String, Object> cteSources;
    private final List<String> outerColumns;
    private final Scope parent;
    private final ScopeType scopeType;
    private final List<Scope> subqueryScopes = new ArrayList<>();
    private final List<Scope> derivedTableScopes = new ArrayList<>();
    private final List<Scope> tableScopes = new ArrayList<>();
    private final List<Scope> cteScopes = new ArrayList<>();
    private List<Scope> setOperationScopes = new ArrayList<>();
    private final List<Scope> udtfScopes = new ArrayList<>();
    private final boolean canBeCorrelated;

    private boolean collected;
    private boolean scansAllSubscopeColumns;
    private List<Expression> rawColumns;
    private List<Expression> tableColumnsList;
    private List<Expression> starsList;
    private List<Expression> derivedTablesList;
    private List<Expression> udtfsList;
    private List<Expression> tablesList;
    private List<Expression> orderedSources;
    private List<Expression> ctesList;
    private List<Expression> subqueriesList;
    private List<Expression> joinHintsList;
    private List<Expression> comprehensionsList;
    private Set<String> semiAntiJoinTables;
    private Set<Expression> columnIndexSet;
    private Map<String, SelectedSource> selectedSourcesMap;
    private List<Expression> columnsList;
    private List<Expression> externalColumnsList;
    private List<Expression> localColumnsList;
    private List<Expression> pivotsList;
    private List<Map.Entry<String, Expression>> referencesList;

    public Scope(Expression expression) {
        this(expression, null, null, null, ScopeType.ROOT, null, null, false);
    }

    /**
     * Full constructor. As in sqlglot, non-empty {@code sources}/{@code lateralSources}/{@code
     * cteSources} maps are used as-is (not copied).
     */
    public Scope(
            Expression expression,
            Map<String, Object> sources,
            List<String> outerColumns,
            Scope parent,
            ScopeType scopeType,
            Map<String, Object> lateralSources,
            Map<String, Object> cteSources,
            boolean canBeCorrelated) {
        this.expression = expression;
        this.sources = sources != null && !sources.isEmpty() ? sources : new LinkedHashMap<>();
        this.lateralSources = lateralSources != null && !lateralSources.isEmpty() ? lateralSources : new LinkedHashMap<>();
        this.cteSources = cteSources != null && !cteSources.isEmpty() ? cteSources : new LinkedHashMap<>();
        this.sources.putAll(this.lateralSources);
        this.sources.putAll(this.cteSources);
        this.outerColumns = outerColumns != null && !outerColumns.isEmpty() ? outerColumns : new ArrayList<>();
        this.parent = parent;
        this.scopeType = scopeType == null ? ScopeType.ROOT : scopeType;
        this.canBeCorrelated = canBeCorrelated;
        clearCache();
    }

    // ------------------------------------------------------------------------------------------
    // Plain attributes

    public Expression expression() {
        return expression;
    }

    public void setExpression(Expression expression) {
        this.expression = expression;
    }

    /** Mapping of source name to a Table {@link Expression} or a {@link Scope} (mutable). */
    public Map<String, Object> sources() {
        return sources;
    }

    public Map<String, Object> lateralSources() {
        return lateralSources;
    }

    public Map<String, Object> cteSources() {
        return cteSources;
    }

    public List<String> outerColumns() {
        return outerColumns;
    }

    public Scope parent() {
        return parent;
    }

    public ScopeType scopeType() {
        return scopeType;
    }

    public List<Scope> subqueryScopes() {
        return subqueryScopes;
    }

    public List<Scope> derivedTableScopes() {
        return derivedTableScopes;
    }

    public List<Scope> tableScopes() {
        return tableScopes;
    }

    public List<Scope> cteScopes() {
        return cteScopes;
    }

    public List<Scope> setOperationScopes() {
        return setOperationScopes;
    }

    /** Alias of {@link #setOperationScopes()} (sqlglot's former {@code union_scopes}). */
    public List<Scope> unionScopes() {
        return setOperationScopes;
    }

    public void setSetOperationScopes(List<Scope> scopes) {
        this.setOperationScopes = scopes;
    }

    public List<Scope> udtfScopes() {
        return udtfScopes;
    }

    public boolean canBeCorrelated() {
        return canBeCorrelated;
    }

    // ------------------------------------------------------------------------------------------
    // Caches

    /** Invalidate the column-classification caches after columns are qualified in place. */
    public void clearColumnCache() {
        columnsList = null;
        externalColumnsList = null;
        localColumnsList = null;
    }

    public void clearCache() {
        collected = false;
        scansAllSubscopeColumns = false;
        rawColumns = new ArrayList<>();
        tableColumnsList = new ArrayList<>();
        starsList = new ArrayList<>();
        derivedTablesList = new ArrayList<>();
        udtfsList = new ArrayList<>();
        tablesList = new ArrayList<>();
        orderedSources = new ArrayList<>();
        ctesList = new ArrayList<>();
        subqueriesList = new ArrayList<>();
        joinHintsList = new ArrayList<>();
        comprehensionsList = new ArrayList<>();
        semiAntiJoinTables = new HashSet<>();
        columnIndexSet = identitySet();
        selectedSourcesMap = null;
        columnsList = null;
        externalColumnsList = null;
        localColumnsList = null;
        pivotsList = null;
        referencesList = null;
    }

    private static Set<Expression> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** Branch from the current scope to a new, inner scope. */
    public Scope branch(
            Expression expression,
            ScopeType scopeType,
            Map<String, Object> sources,
            Map<String, Object> cteSources,
            Map<String, Object> lateralSources,
            List<String> outerColumns) {
        Map<String, Object> mergedCte = new LinkedHashMap<>(this.cteSources);
        if (cteSources != null) {
            mergedCte.putAll(cteSources);
        }
        return new Scope(
                Scopes.unnest(expression),
                sources != null && !sources.isEmpty() ? new LinkedHashMap<>(sources) : null,
                outerColumns,
                this,
                scopeType,
                lateralSources != null && !lateralSources.isEmpty() ? new LinkedHashMap<>(lateralSources) : null,
                mergedCte,
                this.canBeCorrelated || scopeType == ScopeType.SUBQUERY || scopeType == ScopeType.UDTF);
    }

    public Scope branch(Expression expression, ScopeType scopeType) {
        return branch(expression, scopeType, null, null, null, null);
    }

    private static boolean isCollectible(Expression node) {
        return node.is(ExprType.COLUMN, ExprType.DOT, ExprType.TABLE)
                || node.is(ExprType.QUERY, ExprType.UDTF, ExprType.CTE)
                || node.is(ExprType.STAR, ExprType.TABLE_COLUMN, ExprType.JOIN_HINT)
                || node.is(ExprType.COMPREHENSION);
    }

    private void collect() {
        tablesList = new ArrayList<>();
        orderedSources = new ArrayList<>();
        ctesList = new ArrayList<>();
        subqueriesList = new ArrayList<>();
        derivedTablesList = new ArrayList<>();
        udtfsList = new ArrayList<>();
        rawColumns = new ArrayList<>();
        tableColumnsList = new ArrayList<>();
        starsList = new ArrayList<>();
        joinHintsList = new ArrayList<>();
        comprehensionsList = new ArrayList<>();
        semiAntiJoinTables = new HashSet<>();
        columnIndexSet = identitySet();

        // The inner query of a Subquery-rooted scope is scoped as a derived table by
        // `_traverse_tables`, so it must not also be collected as a subquery
        Expression innerQuery = expression.is(ExprType.SUBQUERY) ? Scopes.unnest(expression) : null;

        for (Expression node : walk()) {
            // Most nodes (identifiers, literals, operators etc.) aren't collectible, so a
            // single isinstance gate lets them skip the classification chain below.
            if (node == expression || !isCollectible(node)) {
                continue;
            }

            Expression nodeParent = node.parent();
            if (node.is(ExprType.DOT) && node.isStar()) {
                starsList.add(node);
            } else if (node.type() == ExprType.COLUMN) {
                columnIndexSet.add(node);

                if (node.getThis() != null && node.getThis().is(ExprType.STAR)) {
                    starsList.add(node);
                } else {
                    rawColumns.add(node);
                }
            } else if (node.is(ExprType.TABLE) && !(nodeParent != null && nodeParent.is(ExprType.JOIN_HINT))) {
                if (nodeParent != null && nodeParent.is(ExprType.JOIN) && isSemiOrAntiJoin(nodeParent)) {
                    semiAntiJoinTables.add(node.aliasOrName());
                }

                tablesList.add(node);
                orderedSources.add(node);
            } else if (node.is(ExprType.JOIN_HINT)) {
                joinHintsList.add(node);
            } else if (node.type() == ExprType.LATERAL
                    || (node.is(ExprType.UDTF) && nodeParent != null && nodeParent.is(ExprType.FROM, ExprType.JOIN))) {
                udtfsList.add(node);
                orderedSources.add(node);
            } else if (node.is(ExprType.CTE)) {
                ctesList.add(node);
            } else if (Scopes.isDerivedTable(node) && Scopes.isFromOrJoin(node)) {
                derivedTablesList.add(node);
                orderedSources.add(node);
            } else if (node.is(ExprType.SELECT, ExprType.SET_OPERATION)
                    && !Scopes.isFromOrJoin(node)
                    && node != innerQuery) {
                subqueriesList.add(node);
            } else if (node.is(ExprType.TABLE_COLUMN)) {
                tableColumnsList.add(node);
            } else if (node.is(ExprType.COMPREHENSION)) {
                comprehensionsList.add(node);
            } else if (node.is(ExprType.STAR)
                    && (node.has(Args.EXCEPT_) || !(nodeParent != null && nodeParent.is(ExprType.COUNT)))) {
                scansAllSubscopeColumns = true;
            }
        }

        collected = true;
    }

    private static boolean isSemiOrAntiJoin(Expression join) {
        String kind = join.text(Args.KIND).toUpperCase(java.util.Locale.ROOT);
        return kind.equals("SEMI") || kind.equals("ANTI");
    }

    private void ensureCollected() {
        if (!collected) {
            collect();
        }
    }

    public Iterable<Expression> walk(Predicate<Expression> prune) {
        return Scopes.walkInScope(expression, prune);
    }

    public Iterable<Expression> walk() {
        return walk(null);
    }

    public Expression find(ExprType... expressionTypes) {
        return Scopes.findInScope(expression, expressionTypes);
    }

    public List<Expression> findAll(ExprType... expressionTypes) {
        return Scopes.findAllInScope(expression, expressionTypes);
    }

    /**
     * Replace {@code old} with {@code new}. This can be used instead of {@code Expression.replace}
     * to ensure the {@code Scope} is kept up-to-date.
     */
    public void replace(Expression old, Expression newNode) {
        old.replace(newNode);
        clearCache();
    }

    // ------------------------------------------------------------------------------------------
    // Collected properties

    /** List of tables in this scope. */
    public List<Expression> tables() {
        ensureCollected();
        return tablesList;
    }

    /** List of comprehensions in this scope. */
    public List<Expression> comprehensions() {
        ensureCollected();
        return comprehensionsList;
    }

    /** List of CTEs in this scope. */
    public List<Expression> ctes() {
        ensureCollected();
        return ctesList;
    }

    /** List of derived tables in this scope, e.g. {@code SELECT * FROM (SELECT ...)}. */
    public List<Expression> derivedTables() {
        ensureCollected();
        return derivedTablesList;
    }

    /** List of "User Defined Tabular Functions" in this scope. */
    public List<Expression> udtfs() {
        ensureCollected();
        return udtfsList;
    }

    /** List of subqueries in this scope, e.g. {@code SELECT * FROM x WHERE a IN (SELECT ...)}. */
    public List<Expression> subqueries() {
        ensureCollected();
        return subqueriesList;
    }

    public boolean scansAllSubscopeColumns() {
        ensureCollected();
        return scansAllSubscopeColumns;
    }

    /** List of star expressions (columns or dots) in this scope. */
    public List<Expression> stars() {
        ensureCollected();
        return starsList;
    }

    /** Set (by identity) of the column nodes that belong to this scope's expression. */
    public Set<Expression> columnIndex() {
        ensureCollected();
        return columnIndexSet;
    }

    /**
     * List of columns in this scope: the Column instances in this scope, plus any Columns that
     * reference this scope from correlated subqueries.
     */
    public List<Expression> columns() {
        if (columnsList == null) {
            ensureCollected();
            List<Expression> cols = rawColumns;

            List<Expression> externalColumns = new ArrayList<>();
            for (Scope scope : subqueryScopes) {
                externalColumns.addAll(scope.externalColumns());
            }
            for (Scope scope : udtfScopes) {
                externalColumns.addAll(scope.externalColumns());
            }
            for (Scope dts : derivedTableScopes) {
                if (dts.canBeCorrelated) {
                    externalColumns.addAll(dts.externalColumns());
                }
            }

            Expression expr = expression;
            Set<String> namedSelects = expr.is(ExprType.QUERY) ? new HashSet<>(expr.namedSelects()) : new HashSet<>();

            List<Expression> all = new ArrayList<>(cols.size() + externalColumns.size());
            all.addAll(cols);
            all.addAll(externalColumns);

            columnsList = new ArrayList<>();
            for (Expression column : all) {
                Expression ancestor = column.findAncestor(
                        ExprType.SELECT,
                        ExprType.QUALIFY,
                        ExprType.ORDER,
                        ExprType.CLUSTER,
                        ExprType.HAVING,
                        ExprType.HINT,
                        ExprType.TABLE,
                        ExprType.STAR,
                        ExprType.DISTINCT);
                if (ancestor == null
                        || !column.text(Args.TABLE).isEmpty()
                        || ancestor.is(ExprType.SELECT)
                        || (ancestor.is(ExprType.TABLE)
                                && !(ancestor.getThis() != null && ancestor.getThis().is(ExprType.FUNC)))
                        || (ancestor.is(ExprType.ORDER, ExprType.CLUSTER, ExprType.DISTINCT)
                                && ((ancestor.parent() != null && ancestor.parent().is(ExprType.WINDOW, ExprType.WITHIN_GROUP))
                                        || !(ancestor.parent() != null && ancestor.parent().is(ExprType.SELECT))
                                        || !namedSelects.contains(column.name())))
                        || (ancestor.is(ExprType.STAR) && column.argKey() != Args.EXCEPT_)) {
                    columnsList.add(column);
                }
            }
        }

        return columnsList;
    }

    public List<Expression> tableColumns() {
        ensureCollected();
        return tableColumnsList;
    }

    /**
     * Mapping of nodes and sources that are actually selected from in this scope.
     *
     * <p>That is, all tables in a schema are selectable at any point. But a table only becomes a
     * selected source if it's included in a FROM or JOIN clause.
     */
    public Map<String, SelectedSource> selectedSources() {
        if (selectedSourcesMap == null) {
            Map<String, SelectedSource> result = new LinkedHashMap<>();

            for (Map.Entry<String, Expression> ref : references()) {
                String name = ref.getKey();
                Expression node = ref.getValue();
                if (semiAntiJoinTables.contains(name)) {
                    // The RHS table of SEMI/ANTI joins shouldn't be collected as a
                    // selected source
                    continue;
                }

                if (result.containsKey(name)) {
                    throw new OptimizeError("Alias already used: " + name);
                }
                if (sources.containsKey(name)) {
                    result.put(name, new SelectedSource(node, sources.get(name)));
                }
            }

            selectedSourcesMap = result;
        }
        return selectedSourcesMap;
    }

    /** {@code (name, node)} pairs of the FROM/JOIN/LATERAL sources, in walk order. */
    public List<Map.Entry<String, Expression>> references() {
        if (referencesList == null) {
            ensureCollected();
            referencesList = new ArrayList<>();

            // Sources are kept in walk order, which star expansion relies on
            for (Expression expr : orderedSources) {
                if (expr.is(ExprType.TABLE)) {
                    referencesList.add(new AbstractMap.SimpleImmutableEntry<>(expr.aliasOrName(), expr));
                } else {
                    referencesList.add(new AbstractMap.SimpleImmutableEntry<>(
                            Scopes.getSourceAlias(expr),
                            (expr.has(Args.PIVOTS) ? expr : Scopes.unnest(expr)).assertIs(ExprType.SELECTABLE)));
                }
            }
        }

        return referencesList;
    }

    /** Columns that appear to reference sources in outer scopes. */
    public List<Expression> externalColumns() {
        if (externalColumnsList == null) {
            if (expression.is(ExprType.SET_OPERATION)) {
                if (setOperationScopes.size() != 2) {
                    throw new IllegalStateException(
                            "expected 2 set operation scopes, got " + setOperationScopes.size());
                }
                Scope left = setOperationScopes.get(0);
                Scope right = setOperationScopes.get(1);
                List<Expression> out = new ArrayList<>(left.externalColumns());
                out.addAll(right.externalColumns());
                externalColumnsList = out;
            } else {
                Set<String> localSourceNames = new HashSet<>();
                for (Map.Entry<String, Expression> ref : references()) {
                    localSourceNames.add(ref.getKey());
                }
                Set<String> semiAnti = semiOrAntiJoinTables();
                List<Expression> out = new ArrayList<>();
                for (Expression c : columns()) {
                    String table = c.text(Args.TABLE);
                    if (!localSourceNames.contains(table) && !semiAnti.contains(table)) {
                        out.add(c);
                    }
                }
                externalColumnsList = out;
            }
        }

        return externalColumnsList;
    }

    /** Columns in this scope that are not external. */
    public List<Expression> localColumns() {
        if (localColumnsList == null) {
            // Compare nodes by identity: structural equality would conflate distinct
            // column nodes that happen to look the same, and is much more expensive.
            Set<Expression> externalColumnIds = identitySet();
            externalColumnIds.addAll(externalColumns());
            List<Expression> out = new ArrayList<>();
            for (Expression c : columns()) {
                if (!externalColumnIds.contains(c)) {
                    out.add(c);
                }
            }
            localColumnsList = out;
        }

        return localColumnsList;
    }

    /** Unqualified columns in the current scope. */
    public List<Expression> unqualifiedColumns() {
        List<Expression> out = new ArrayList<>();
        for (Expression c : columns()) {
            if (c.text(Args.TABLE).isEmpty()) {
                out.add(c);
            }
        }
        return out;
    }

    /** Hints that exist in the scope that reference tables. */
    public List<Expression> joinHints() {
        ensureCollected();
        return joinHintsList;
    }

    public List<Expression> pivots() {
        if (pivotsList == null) {
            List<Expression> out = new ArrayList<>();
            for (Map.Entry<String, Expression> ref : references()) {
                out.addAll(ref.getValue().exprs(Args.PIVOTS));
            }
            pivotsList = out;
        }

        return pivotsList;
    }

    public Set<String> semiOrAntiJoinTables() {
        ensureCollected();
        return semiAntiJoinTables;
    }

    /** Get all columns in the current scope for a particular source. */
    public List<Expression> sourceColumns(String sourceName) {
        List<Expression> out = new ArrayList<>();
        for (Expression column : columns()) {
            if (column.text(Args.TABLE).equals(sourceName)) {
                out.add(column);
            }
        }
        return out;
    }

    /** Determine if this scope is a subquery. */
    public boolean isSubquery() {
        return scopeType == ScopeType.SUBQUERY;
    }

    /** Determine if this scope is a derived table. */
    public boolean isDerivedTable() {
        return scopeType == ScopeType.DERIVED_TABLE;
    }

    /** Determine if this scope is a set operation. */
    public boolean isSetOperation() {
        return scopeType == ScopeType.SET_OPERATION;
    }

    /** Determine if this scope is a common table expression. */
    public boolean isCte() {
        return scopeType == ScopeType.CTE;
    }

    /** Determine if this is the root scope. */
    public boolean isRoot() {
        return scopeType == ScopeType.ROOT;
    }

    /** Determine if this scope is a UDTF (User Defined Table Function). */
    public boolean isUdtf() {
        return scopeType == ScopeType.UDTF;
    }

    /** Determine if this scope is a correlated subquery. */
    public boolean isCorrelatedSubquery() {
        return canBeCorrelated && !externalColumns().isEmpty();
    }

    /** Rename a source in this scope. */
    public void renameSource(String oldName, String newName) {
        if (oldName == null) {
            oldName = "";
        }
        if (sources.containsKey(oldName)) {
            sources.put(newName, sources.remove(oldName));
        }
    }

    /** Add a source to this scope. */
    public void addSource(String name, Object source) {
        sources.put(name, source);
        clearCache();
    }

    /** Remove a source from this scope. */
    public void removeSource(String name) {
        sources.remove(name);
        clearCache();
    }

    @Override
    public String toString() {
        return "Scope<" + expression.sql(Dialects.base()) + ">";
    }

    /** Traverse the scope tree from this node: scope instances in depth-first-search post-order. */
    public List<Scope> traverse() {
        List<Scope> stack = new ArrayList<>();
        stack.add(this);
        List<Scope> result = new ArrayList<>();
        while (!stack.isEmpty()) {
            Scope scope = stack.remove(stack.size() - 1);
            result.add(scope);
            stack.addAll(scope.cteScopes);
            stack.addAll(scope.setOperationScopes);
            stack.addAll(scope.tableScopes);
            stack.addAll(scope.subqueryScopes);
        }

        Collections.reverse(result);
        return result;
    }

    /**
     * Count the number of times each scope in this tree is referenced. Keys are compared by
     * identity (sqlglot keys by {@code id(source)}); use {@code getOrDefault(source, 0)}.
     */
    public Map<Object, Integer> refCount() {
        Map<Object, Integer> scopeRefCount = new IdentityHashMap<>();

        for (Scope scope : traverse()) {
            for (SelectedSource selected : scope.selectedSources().values()) {
                scopeRefCount.merge(selected.source(), 1, Integer::sum);
            }

            for (String name : scope.semiAntiJoinTables) {
                // semi/anti join sources are not actually selected but we still need to
                // increment their ref count to avoid them being optimized away
                if (scope.sources.containsKey(name)) {
                    scopeRefCount.merge(scope.sources.get(name), 1, Integer::sum);
                }
            }
        }

        return scopeRefCount;
    }
}
