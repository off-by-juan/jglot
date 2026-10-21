package io.github.offbyjuan.jglot.transforms;

import io.github.offbyjuan.jglot.errors.UnsupportedError;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.optimizer.Normalize;
import io.github.offbyjuan.jglot.optimizer.Scope;
import io.github.offbyjuan.jglot.optimizer.Scopes;
import io.github.offbyjuan.jglot.util.Py;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Tree rewrites from sqlglot's {@code transforms} module, used by generators via
 * {@code applyTransforms(e, Transforms::eliminateQualify, ...)}. {@code transforms.preprocess} itself
 * is {@code GeneratorCore.applyTransforms}.
 */
public final class Transforms {
    private Transforms() {}

    /** {@code (DType.UNKNOWN, *DataType.NUMERIC_TYPES)}; read-only. */
    private static final Object[] UNKNOWN_OR_NUMERIC_TYPES;

    static {
        List<Object> types = new ArrayList<>();
        types.add(DType.UNKNOWN);
        types.addAll(DataTypes.NUMERIC_TYPES);
        UNKNOWN_OR_NUMERIC_TYPES = types.toArray();
    }

    // ------------------------------------------------------------------------------------------
    // Transforms

    /** {@code transforms.unnest_generate_date_array_using_recursive_cte(expression)}. */
    public static Expression unnestGenerateDateArrayUsingRecursiveCte(Expression expression) {
        if (expression.is(ExprType.SELECT)) {
            int count = 0;
            List<Expression> recursiveCtes = new ArrayList<>();

            for (Expression unnest : findAllLazy(expression, ExprType.UNNEST)) {
                Expression unnestParent = unnest.parent();
                if (unnestParent == null
                        || !unnestParent.is(ExprType.FROM, ExprType.JOIN)
                        || unnest.getExpressions().size() != 1
                        || !unnest.getExpressions().get(0).is(ExprType.GENERATE_DATE_ARRAY)) {
                    continue;
                }

                Expression generateDateArray = unnest.getExpressions().get(0);
                Expression start = generateDateArray.expr(Args.START);
                Expression end = generateDateArray.expr(Args.END);
                Expression step = generateDateArray.expr(Args.STEP);

                if (start == null || end == null || step == null || !step.is(ExprType.INTERVAL)) {
                    continue;
                }

                Expression alias = unnest.expr(Args.ALIAS);
                Object columnName = alias != null && alias.is(ExprType.TABLE_ALIAS)
                        ? alias.exprs(Args.COLUMNS).get(0)
                        : "date_value";

                start = Exp.cast(start, "date");
                Expression dateAdd = Exp.func("date_add", null, columnName, Exp.number(step.name()), step.get(Args.UNIT));
                Expression castDateAdd = Exp.cast(dateAdd, "date");

                String cteName = "_generated_dates" + (count != 0 ? "_" + count : "");

                Expression baseQuery = Exp.select(start.as(columnName, null, true));
                Expression recursiveQuery = Exp.select(castDateAdd)
                        .from(cteName, true)
                        .where(true, true, binop(ExprType.LTE, castDateAdd, Exp.cast(end, "date")));
                Expression cteQuery = baseQuery.union(false, true, recursiveQuery);

                Expression generateDatesQuery = Exp.select(columnName).from(cteName, true);
                unnest.replace(generateDatesQuery.subquery(cteName, true));

                recursiveCtes.add(aliasTable(
                        Expression.of(ExprType.CTE, Args.THIS, cteQuery), cteName, List.of(columnName), true));
                count += 1;
            }

            if (!recursiveCtes.isEmpty()) {
                Expression withExpression = expression.expr(Args.WITH_);
                if (withExpression == null) {
                    withExpression = Expression.of(ExprType.WITH);
                }
                withExpression.set(Args.RECURSIVE, Boolean.TRUE);
                List<Expression> ctes = new ArrayList<>(recursiveCtes);
                ctes.addAll(withExpression.getExpressions());
                withExpression.set(Args.EXPRESSIONS, ctes);
                expression.set(Args.WITH_, withExpression);
            }
        }

        return expression;
    }

    /** {@code transforms.unnest_generate_series(expression)}: unnests GENERATE_SERIES or SEQUENCE table references. */
    public static Expression unnestGenerateSeries(Expression expression) {
        Expression thisExpr = expression.getThis();
        if (expression.is(ExprType.TABLE) && thisExpr != null && thisExpr.is(ExprType.GENERATE_SERIES)) {
            Expression unnest = Expression.of(ExprType.UNNEST, Args.EXPRESSIONS, List.of(thisExpr));
            if (!expression.alias().isEmpty()) {
                return aliasTable(unnest, "_u", List.of(expression.alias()), false);
            }

            return unnest;
        }

        return expression;
    }

    /**
     * {@code transforms.eliminate_distinct_on(expression)}: converts SELECT DISTINCT ON statements to a
     * subquery with a window function.
     */
    public static Expression eliminateDistinctOn(Expression expression) {
        Expression distinct = expression.is(ExprType.SELECT) ? expression.expr(Args.DISTINCT) : null;
        if (distinct != null && Expression.isInstance(distinct.get(Args.ON), ExprType.TUPLE)) {
            String rowNumberWindowAlias = findNewName(expression.namedSelects(), "_row_number");

            List<Expression> distinctCols = new ArrayList<>(distinct.pop().expr(Args.ON).getExpressions());
            Expression window = Expression.of(ExprType.WINDOW,
                    Args.THIS, Expression.of(ExprType.ROW_NUMBER), Args.PARTITION_BY, distinctCols);

            Expression order = expression.expr(Args.ORDER);
            if (order != null) {
                window.set(Args.ORDER, order.pop());
            } else {
                List<Expression> copies = new ArrayList<>();
                for (Expression c : distinctCols) {
                    copies.add(c.copy());
                }
                window.set(Args.ORDER, Expression.of(ExprType.ORDER, Args.EXPRESSIONS, copies));
            }

            expression.select(true, false, alias(window, rowNumberWindowAlias, null, true));

            // We add aliases to the projections so that we can safely reference them in the outer query
            List<Object> newSelects = new ArrayList<>();
            Set<String> takenNames = new LinkedHashSet<>();
            takenNames.add(rowNumberWindowAlias);
            List<Expression> selects = expression.selects();
            for (Expression select : new ArrayList<>(selects.subList(0, Math.max(0, selects.size() - 1)))) {
                if (select.isStar()) {
                    newSelects = new ArrayList<>();
                    newSelects.add(Exp.star());
                    break;
                }

                if (!select.is(ExprType.ALIAS)) {
                    String outputName = select.outputName();
                    String aliasName = findNewName(takenNames, outputName.isEmpty() ? "_col" : outputName);
                    Boolean quoted = null;
                    if (select.is(ExprType.COLUMN) && select.getThis() != null
                            && select.getThis().get(Args.QUOTED) instanceof Boolean b) {
                        quoted = b;
                    }
                    select = select.replace(alias(select, aliasName, quoted, true));
                }

                takenNames.add(select.outputName());
                newSelects.add(select.get(Args.ALIAS));
            }

            return Exp.select(newSelects.toArray())
                    .from(expression.subquery("_t", false), false)
                    .where(true, false, Exp.column(rowNumberWindowAlias).eq(1));
        }

        return expression;
    }

    /**
     * {@code transforms.eliminate_qualify(expression)}: converts SELECT statements that contain the
     * QUALIFY clause into subqueries, filtered equivalently.
     *
     * <p>Some dialects don't support window functions in the WHERE clause, so we need to include them
     * as projections in the subquery, in order to refer to them in the outer filter using aliases.
     * Also, if a column is referenced in the QUALIFY clause but is not selected, we need to include it
     * too, otherwise we won't be able to refer to it in the outer query's WHERE clause. Finally, if a
     * newly aliased projection is referenced in the QUALIFY clause, it will be replaced by the
     * corresponding expression to avoid creating invalid column references.
     */
    public static Expression eliminateQualify(Expression expression) {
        if (expression.is(ExprType.SELECT) && expression.has(Args.QUALIFY)) {
            Expression parent = expression.parent();
            if (parent != null && parent.is(ExprType.INSERT)
                    && parent.parent() != null && parent.parent().is(ExprType.MULTITABLE_INSERTS)) {
                // A FROM-first multi-table INSERT branch shares its parent's FROM, so it can't be wrapped
                throw new UnsupportedError("QUALIFY is not supported in a FROM-first multi-table INSERT branch");
            }

            Set<String> taken = new LinkedHashSet<>(expression.namedSelects());
            for (Expression select : new ArrayList<>(expression.selects())) {
                if (select.aliasOrName().isEmpty()) {
                    String aliasName = findNewName(taken, "_c");
                    select.replace(alias(select, aliasName, null, true));
                    taken.add(aliasName);
                }
            }

            List<Object> outerSelectArgs = new ArrayList<>();
            for (Expression select : expression.selects()) {
                outerSelectArgs.add(selectAliasOrName(select));
            }
            Expression outerSelects = Exp.select(outerSelectArgs.toArray());
            Expression qualifyFilters = expression.expr(Args.QUALIFY).pop().getThis();
            Map<String, Expression> expressionByAlias = new LinkedHashMap<>();
            for (Expression select : expression.selects()) {
                if (select.is(ExprType.ALIAS)) {
                    expressionByAlias.put(select.alias(), select.getThis());
                }
            }

            ExprType[] selectCandidates = expression.isStar()
                    ? new ExprType[] {ExprType.WINDOW}
                    : new ExprType[] {ExprType.WINDOW, ExprType.COLUMN};
            for (Expression selectCandidate : qualifyFilters.findAll(selectCandidates)) {
                if (selectCandidate.is(ExprType.WINDOW)) {
                    if (!expressionByAlias.isEmpty()) {
                        for (Expression column : findAllLazy(selectCandidate, ExprType.COLUMN)) {
                            Expression expr = expressionByAlias.get(column.name());
                            if (expr != null) {
                                column.replace(expr);
                            }
                        }
                    }

                    String aliasName = findNewName(expression.namedSelects(), "_w");
                    expression.select(true, false, alias(selectCandidate, aliasName, null, true));
                    Expression column = Exp.column(aliasName);

                    if (selectCandidate.parent() != null && selectCandidate.parent().is(ExprType.QUALIFY)) {
                        qualifyFilters = column;
                    } else {
                        selectCandidate.replace(column);
                    }
                } else if (!expression.namedSelects().contains(selectCandidate.name())
                        && selectCandidate.findAncestor(ExprType.WINDOW) == null) {
                    // A column that is only read by a window function is computed by the window
                    // itself, so projecting it in the subquery is redundant and can even produce
                    // invalid SQL, e.g. when the query is grouped
                    expression.select(true, false, selectCandidate.copy());
                }
            }

            return outerSelects.from(expression.subquery("_t", false), false).where(true, false, qualifyFilters);
        }

        return expression;
    }

    private static Object selectAliasOrName(Expression select) {
        String aliasOrName = select.aliasOrName();
        Expression identifier = select.has(Args.ALIAS) ? select.expr(Args.ALIAS) : select.getThis();
        if (identifier != null && identifier.is(ExprType.IDENTIFIER)) {
            Object quoted = identifier.get(Args.QUOTED);
            return Exp.column(aliasOrName, null, null, null, quoted instanceof Boolean b ? b : null);
        }
        return aliasOrName;
    }

    /**
     * {@code transforms.remove_precision_parameterized_types(expression)}: removes the precision from
     * parameterized types in expressions.
     */
    public static Expression removePrecisionParameterizedTypes(Expression expression) {
        for (Expression node : findAllLazy(expression, ExprType.DATA_TYPE)) {
            List<Expression> kept = new ArrayList<>();
            for (Expression e : node.getExpressions()) {
                if (!e.is(ExprType.DATA_TYPE_PARAM)) {
                    kept.add(e);
                }
            }
            node.set(Args.EXPRESSIONS, kept);
        }

        return expression;
    }

    /**
     * {@code transforms.unqualify_unnest(expression)}: removes references to unnest table aliases,
     * added by the optimizer's qualify_columns step.
     */
    public static Expression unqualifyUnnest(Expression expression) {
        if (expression.is(ExprType.SELECT)) {
            Set<String> unnestAliases = new LinkedHashSet<>();
            for (Expression unnest : Scopes.findAllInScope(expression, ExprType.UNNEST)) {
                if (unnest.parent() != null && unnest.parent().is(ExprType.FROM, ExprType.JOIN)) {
                    unnestAliases.add(unnest.alias());
                }
            }
            if (!unnestAliases.isEmpty()) {
                for (Expression column : findAllLazy(expression, ExprType.COLUMN)) {
                    Expression leftmostPart = column.parts().get(0);
                    if (leftmostPart.argKey() != Args.THIS && unnestAliases.contains(leftmostPart.thisValue())) {
                        leftmostPart.pop();
                    }
                }
            }
        }

        return expression;
    }

    /** {@code transforms.unnest_to_explode(expression)} with {@code unnest_using_arrays_zip=True}. */
    public static Expression unnestToExplode(Expression expression) {
        return unnestToExplode(expression, true);
    }

    /** {@code transforms.unnest_to_explode(expression, unnest_using_arrays_zip)}: converts cross join unnest into lateral view explode. */
    public static Expression unnestToExplode(Expression expression, boolean unnestUsingArraysZip) {
        if (expression.is(ExprType.SELECT)) {
            Expression from = expression.expr(Args.FROM_);

            if (from != null && from.getThis() != null && from.getThis().is(ExprType.UNNEST)) {
                Expression unnest = from.getThis();
                Expression alias = unnest.expr(Args.ALIAS);
                List<Expression> exprs = unnest.getExpressions();
                boolean hasMultiExpr = exprs.size() > 1;
                Expression thisExpr = unnestZipExprs(unnest, exprs, hasMultiExpr, unnestUsingArraysZip).get(0);

                List<Expression> columns = alias != null ? new ArrayList<>(alias.exprs(Args.COLUMNS)) : new ArrayList<>();
                Expression offset = unnest.expr(Args.OFFSET);
                if (offset != null) {
                    columns.add(0, offset.is(ExprType.IDENTIFIER) ? offset : Exp.toIdentifier("pos"));
                }

                unnest.replace(Expression.of(ExprType.TABLE,
                        Args.THIS, Expression.of(udtfType(unnest, hasMultiExpr), Args.THIS, thisExpr),
                        Args.ALIAS, alias != null
                                ? Expression.of(ExprType.TABLE_ALIAS, Args.THIS, alias.get(Args.THIS), Args.COLUMNS, columns)
                                : null));
            }

            List<Expression> joins = expression.exprs(Args.JOINS);
            for (Expression join : new ArrayList<>(joins)) {
                Expression joinExpr = join.getThis();

                boolean isLateral = joinExpr != null && joinExpr.is(ExprType.LATERAL);

                Expression unnest = isLateral ? joinExpr.getThis() : joinExpr;

                if (unnest != null && unnest.is(ExprType.UNNEST)) {
                    Expression alias = isLateral ? joinExpr.expr(Args.ALIAS) : unnest.expr(Args.ALIAS);

                    if (alias == null) {
                        throw new UnsupportedError(
                                "CROSS JOIN UNNEST to LATERAL VIEW EXPLODE transformation requires an alias");
                    }

                    List<Expression> exprs = unnest.getExpressions();
                    // The number of unnest.expressions will be changed by _unnest_zip_exprs, we need to record it here
                    boolean hasMultiExpr = exprs.size() > 1;
                    exprs = unnestZipExprs(unnest, exprs, hasMultiExpr, unnestUsingArraysZip);

                    removeJoin(expression, join);

                    List<Expression> aliasCols = new ArrayList<>(alias.exprs(Args.COLUMNS));

                    // # Handle UNNEST to LATERAL VIEW EXPLODE: Exception is raised when there are 0 or > 2 aliases
                    // Spark LATERAL VIEW EXPLODE requires single alias for array/struct and two for Map type column unlike unnest in trino/presto which can take an arbitrary amount.
                    // Refs: https://spark.apache.org/docs/latest/sql-ref-syntax-qry-select-lateral-view.html

                    if (!hasMultiExpr && aliasCols.size() != 1 && aliasCols.size() != 2) {
                        throw new UnsupportedError(
                                "CROSS JOIN UNNEST to LATERAL VIEW EXPLODE transformation requires explicit column aliases");
                    }

                    Expression offset = unnest.expr(Args.OFFSET);
                    if (offset != null) {
                        aliasCols.add(0, offset.is(ExprType.IDENTIFIER) ? offset : Exp.toIdentifier("pos"));
                    }
                    // Python mutates alias.columns in place.
                    alias.set(Args.COLUMNS, aliasCols);

                    int n = Math.min(exprs.size(), aliasCols.size());
                    for (int i = 0; i < n; i++) {
                        Expression e = exprs.get(i);
                        expression.append(Args.LATERALS, Expression.of(ExprType.LATERAL,
                                Args.THIS, Expression.of(udtfType(unnest, hasMultiExpr), Args.THIS, e),
                                Args.VIEW, Boolean.TRUE,
                                Args.ALIAS, Expression.of(ExprType.TABLE_ALIAS,
                                        Args.THIS, alias.get(Args.THIS), Args.COLUMNS, aliasCols)));
                    }
                }
            }
        }

        return expression;
    }

    private static List<Expression> unnestZipExprs(Expression u, List<Expression> unnestExprs, boolean hasMultiExpr,
            boolean unnestUsingArraysZip) {
        if (hasMultiExpr) {
            if (!unnestUsingArraysZip) {
                throw new UnsupportedError("Cannot transpile UNNEST with multiple input arrays");
            }

            // Use INLINE(ARRAYS_ZIP(...)) for multiple expressions
            List<Expression> zipExprs = new ArrayList<>();
            zipExprs.add(Exp.anonymous("ARRAYS_ZIP", unnestExprs));
            u.set(Args.EXPRESSIONS, zipExprs);
            return zipExprs;
        }
        return unnestExprs;
    }

    private static ExprType udtfType(Expression u, boolean hasMultiExpr) {
        if (u.has(Args.OFFSET)) {
            return ExprType.POSEXPLODE;
        }
        return hasMultiExpr ? ExprType.INLINE : ExprType.EXPLODE;
    }

    /** {@code joins.remove(join)} on the select's joins list. */
    private static void removeJoin(Expression select, Expression join) {
        List<Object> joins = new ArrayList<>(select.list(Args.JOINS));
        joins.remove(join);
        select.set(Args.JOINS, joins);
    }

    /** {@code transforms.explode_projection_to_unnest()}: the factory with its defaults (index_offset=0, unnest_map=False). */
    public static UnaryOperator<Expression> explodeProjectionToUnnest() {
        return explodeProjectionToUnnest(0, false);
    }

    /** {@code transforms.explode_projection_to_unnest(index_offset)}. */
    public static UnaryOperator<Expression> explodeProjectionToUnnest(int indexOffset) {
        return explodeProjectionToUnnest(indexOffset, false);
    }

    /**
     * {@code transforms.explode_projection_to_unnest(index_offset, unnest_map)}: a factory returning the
     * transform that converts explode/posexplode projections into unnests. Use as
     * {@code applyTransforms(e, Transforms.explodeProjectionToUnnest(1, false))}.
     */
    public static UnaryOperator<Expression> explodeProjectionToUnnest(int indexOffset, boolean unnestMap) {
        return expression -> explodeProjectionToUnnest(expression, indexOffset, unnestMap);
    }

    /** The body of the transform returned by {@link #explodeProjectionToUnnest(int, boolean)}. */
    public static Expression explodeProjectionToUnnest(Expression expression, int indexOffset, boolean unnestMap) {
        if (expression.is(ExprType.SELECT)) {
            Set<String> takenSelectNames = new LinkedHashSet<>(expression.namedSelects());
            Set<String> takenSourceNames = new LinkedHashSet<>(scopeReferenceNames(newScope(expression)));

            List<Expression> arrays = new ArrayList<>();
            String seriesAlias = newName(takenSelectNames, "pos");
            Expression series = aliasTable(
                    Expression.of(ExprType.UNNEST, Args.EXPRESSIONS, List.of(
                            Expression.of(ExprType.GENERATE_SERIES, Args.START, Exp.number(indexOffset)))),
                    newName(takenSourceNames, "_u"),
                    List.of(seriesAlias),
                    true);

            // we use list here because expression.selects is mutated inside the loop
            for (Expression select : new ArrayList<>(expression.selects())) {
                Expression explode = select.find(ExprType.EXPLODE);

                if (explode != null) {
                    if (unnestMap
                            && explode.type() == ExprType.EXPLODE
                            && explode.getThis().isType(DType.MAP)
                            && (select == explode || select.is(ExprType.ALIASES))) {
                        Object mapKeyAlias;
                        Object mapValueAlias;
                        if (select.is(ExprType.ALIASES)) {
                            List<Expression> aliases = select.getExpressions();
                            if (aliases.size() != 2) {
                                throw new IllegalArgumentException("expected 2 aliases, got " + aliases.size());
                            }
                            mapKeyAlias = aliases.get(0);
                            mapValueAlias = aliases.get(1);
                        } else {
                            mapKeyAlias = newName(takenSelectNames, "key");
                            mapValueAlias = newName(takenSelectNames, "value");
                        }
                        String mapUnnestSource = newName(takenSourceNames, "_u");

                        Expression mapKeySelect = select.replace(
                                Exp.column(mapKeyAlias, mapUnnestSource).as(mapKeyAlias, null, true));

                        List<Expression> expressions = new ArrayList<>(expression.getExpressions());
                        expressions.add(
                                indexOf(expressions, mapKeySelect) + 1,
                                Exp.column(mapValueAlias, mapUnnestSource).as(mapValueAlias, null, true));
                        expression.set(Args.EXPRESSIONS, expressions);

                        Expression unnest = aliasTable(
                                Expression.of(ExprType.UNNEST, Args.EXPRESSIONS, List.of(explode.getThis().copy())),
                                mapUnnestSource,
                                List.of(mapKeyAlias, mapValueAlias),
                                true);
                        if (expression.has(Args.FROM_)) {
                            expression.join(unnest, null, null, "CROSS", null, false);
                        } else {
                            expression.from(unnest, false);
                        }

                        continue;
                    }

                    Object posAlias = "";
                    Object explodeAlias = "";

                    Expression alias;
                    if (select.is(ExprType.ALIAS)) {
                        explodeAlias = select.get(Args.ALIAS);
                        alias = select;
                    } else if (select.is(ExprType.ALIASES)) {
                        posAlias = select.getExpressions().get(0);
                        explodeAlias = select.getExpressions().get(1);
                        alias = select.replace(alias(select.getThis(), "", null, false));
                    } else {
                        alias = select.replace(alias(select, "", null, true));
                        explode = alias.find(ExprType.EXPLODE);
                        if (explode == null) {
                            throw new AssertionError();
                        }
                    }

                    boolean isPosexplode = explode.is(ExprType.POSEXPLODE);
                    Expression explodeArg = explode.getThis();

                    if (explode.is(ExprType.EXPLODE_OUTER)) {
                        Expression bracket = Expression.of(ExprType.BRACKET,
                                Args.THIS, explodeArg.copy(), Args.EXPRESSIONS, List.of(Exp.convert(0)));
                        bracket.set(Args.SAFE, Boolean.TRUE);
                        bracket.set(Args.OFFSET, Boolean.TRUE);
                        explodeArg = Exp.func(
                                "IF",
                                null,
                                Exp.func("ARRAY_SIZE", null,
                                        Exp.func("COALESCE", null, explodeArg.copy(), Expression.of(ExprType.ARRAY)))
                                        .eq(0),
                                Exp.array(List.of(bracket)),
                                explodeArg.copy());
                    }

                    // This ensures that we won't use [POS]EXPLODE's argument as a new selection
                    if (explodeArg.is(ExprType.COLUMN)) {
                        takenSelectNames.add(explodeArg.outputName());
                    }

                    String unnestSourceAlias = newName(takenSourceNames, "_u");

                    if (!Expression.truthy(explodeAlias)) {
                        explodeAlias = newName(takenSelectNames, "col");

                        if (isPosexplode) {
                            posAlias = newName(takenSelectNames, "pos");
                        }
                    }

                    if (!Expression.truthy(posAlias)) {
                        posAlias = newName(takenSelectNames, "pos");
                    }

                    alias.set(Args.ALIAS, Exp.toIdentifier(explodeAlias));

                    Expression seriesTableAlias = series.expr(Args.ALIAS).getThis();
                    Expression column = Expression.of(ExprType.IF,
                            Args.THIS, Exp.column(seriesAlias, seriesTableAlias)
                                    .eq(Exp.column(posAlias, unnestSourceAlias)),
                            Args.TRUE, Exp.column(explodeAlias, unnestSourceAlias));

                    explode.replace(column);

                    if (isPosexplode) {
                        List<Expression> expressions = new ArrayList<>(expression.getExpressions());
                        expressions.add(
                                indexOf(expressions, alias) + 1,
                                Expression.of(ExprType.IF,
                                        Args.THIS, Exp.column(seriesAlias, seriesTableAlias)
                                                .eq(Exp.column(posAlias, unnestSourceAlias)),
                                        Args.TRUE, Exp.column(posAlias, unnestSourceAlias))
                                        .as(posAlias, null, true));
                        expression.set(Args.EXPRESSIONS, expressions);
                    }

                    if (arrays.isEmpty()) {
                        if (expression.has(Args.FROM_)) {
                            expression.join(series, null, null, "CROSS", null, false);
                        } else {
                            expression.from(series, false);
                        }
                    }

                    Expression size = Expression.of(ExprType.ARRAY_SIZE, Args.THIS, explodeArg.copy());
                    arrays.add(size);

                    // trino doesn't support left join unnest with on conditions
                    // if it did, this would be much simpler
                    expression.join(
                            aliasTable(
                                    Expression.of(ExprType.UNNEST,
                                            Args.EXPRESSIONS, List.of(explodeArg.copy()),
                                            Args.OFFSET, Exp.toIdentifier(posAlias)),
                                    unnestSourceAlias,
                                    List.of(explodeAlias),
                                    true),
                            null, null, "CROSS", null, false);

                    if (indexOffset != 1) {
                        size = binop(ExprType.SUB, size, 1);
                    }

                    expression.where(true, false,
                            Exp.column(seriesAlias, seriesTableAlias)
                                    .eq(Exp.column(posAlias, unnestSourceAlias))
                                    .or(true, binop(ExprType.GT, Exp.column(seriesAlias, seriesTableAlias), size)
                                            .and(true, Exp.column(posAlias, unnestSourceAlias).eq(size))));
                }
            }

            if (!arrays.isEmpty()) {
                Expression end = Expression.of(ExprType.GREATEST,
                        Args.THIS, arrays.get(0), Args.EXPRESSIONS, new ArrayList<>(arrays.subList(1, arrays.size())));

                if (indexOffset != 1) {
                    end = binop(ExprType.SUB, end, 1 - indexOffset);
                }
                series.getExpressions().get(0).set(Args.END, end);
            }
        }

        return expression;
    }

    private static String newName(Set<String> names, String name) {
        name = findNewName(names, name);
        names.add(name);
        return name;
    }

    /** {@code transforms.add_within_group_for_percentiles(expression)}. */
    public static Expression addWithinGroupForPercentiles(Expression expression) {
        if (expression.is(ExprType.PERCENTILE_CONT, ExprType.PERCENTILE_DISC)
                && !(expression.parent() != null && expression.parent().is(ExprType.WITHIN_GROUP))
                && expression.getExpression() != null) {
            Expression column = expression.getThis().pop();
            expression.set(Args.THIS, expression.getExpression().pop());
            Expression order = Expression.of(ExprType.ORDER,
                    Args.EXPRESSIONS, List.of(Expression.of(ExprType.ORDERED, Args.THIS, column)));
            expression = Expression.of(ExprType.WITHIN_GROUP, Args.THIS, expression, Args.EXPRESSION, order);
        }

        return expression;
    }

    /** {@code transforms.remove_within_group_for_percentiles(expression)}. */
    public static Expression removeWithinGroupForPercentiles(Expression expression) {
        if (expression.is(ExprType.WITHIN_GROUP)
                && expression.getThis() != null
                && expression.getThis().is(ExprType.PERCENTILE_CONT, ExprType.PERCENTILE_DISC)
                && expression.getExpression() != null
                && expression.getExpression().is(ExprType.ORDER)) {
            Expression quantile = expression.getThis().getThis();
            Expression inputValue = expression.find(ExprType.ORDERED).getThis();
            return expression.replace(Expression.of(ExprType.APPROX_QUANTILE,
                    Args.THIS, inputValue, Args.QUANTILE, quantile));
        }

        return expression;
    }

    /**
     * {@code transforms.add_recursive_cte_column_names(expression)}: uses projection output names in
     * recursive CTE definitions to define the CTEs' columns.
     */
    public static Expression addRecursiveCteColumnNames(Expression expression) {
        if (expression.is(ExprType.WITH) && expression.has(Args.RECURSIVE)) {
            Supplier<String> nextName = nameSequence("_c_");

            for (Expression cte : expression.getExpressions()) {
                Expression cteAlias = cte.expr(Args.ALIAS);
                if (cteAlias.exprs(Args.COLUMNS).isEmpty()) {
                    Expression query = cte.getThis();
                    if (query.is(ExprType.SET_OPERATION)) {
                        query = query.getThis();
                    }

                    List<Expression> columns = new ArrayList<>();
                    for (Expression s : query.selects()) {
                        String aliasOrName = s.aliasOrName();
                        columns.add(Exp.toIdentifier(aliasOrName.isEmpty() ? nextName.get() : aliasOrName));
                    }
                    cteAlias.set(Args.COLUMNS, columns);
                }
            }
        }

        return expression;
    }

    /** {@code transforms.epoch_cast_to_ts(expression)}: replaces 'epoch' in casts by the equivalent date literal. */
    public static Expression epochCastToTs(Expression expression) {
        if (expression.is(ExprType.CAST, ExprType.TRY_CAST)
                && Py.lower(expression.name()).equals("epoch")
                && expression.expr(Args.TO) != null
                && DataTypes.TEMPORAL_TYPES.contains(expression.expr(Args.TO).thisValue())) {
            expression.getThis().replace(Exp.string("1970-01-01 00:00:00"));
        }

        return expression;
    }

    /** {@code transforms.eliminate_semi_and_anti_joins(expression)}: converts SEMI and ANTI joins into EXISTS forms. */
    public static Expression eliminateSemiAndAntiJoins(Expression expression) {
        if (expression.is(ExprType.SELECT)) {
            for (Expression join : new ArrayList<>(expression.exprs(Args.JOINS))) {
                Expression on = join.expr(Args.ON);
                String kind = Py.upper(join.text(Args.KIND));
                if (on != null && (kind.equals("SEMI") || kind.equals("ANTI"))) {
                    Expression subquery = Exp.select("1").from(join.getThis(), true).where(true, true, on);
                    Expression exists = Expression.of(ExprType.EXISTS, Args.THIS, subquery);
                    if (kind.equals("ANTI")) {
                        exists = exists.not(false);
                    }

                    join.pop();
                    expression.where(true, false, exists);
                }
            }
        }

        return expression;
    }

    /**
     * {@code transforms.eliminate_full_outer_join(expression)}: converts a query with a single FULL
     * OUTER join to a union of identical queries that use LEFT/RIGHT OUTER joins instead.
     */
    public static Expression eliminateFullOuterJoin(Expression expression) {
        if (expression.is(ExprType.SELECT)) {
            List<Expression> joins = expression.exprs(Args.JOINS);
            List<Integer> fullOuterIndexes = new ArrayList<>();
            for (int i = 0; i < joins.size(); i++) {
                if (Py.upper(joins.get(i).text(Args.SIDE)).equals("FULL")) {
                    fullOuterIndexes.add(i);
                }
            }

            if (fullOuterIndexes.size() == 1) {
                Expression expressionCopy = expression.copy();
                int index = fullOuterIndexes.get(0);
                Expression fullOuterJoin = joins.get(index);

                String[] tables = {expression.expr(Args.FROM_).aliasOrName(), fullOuterJoin.aliasOrName()};
                Expression joinConditions = fullOuterJoin.expr(Args.ON);
                if (joinConditions == null) {
                    List<Expression> eqs = new ArrayList<>();
                    for (Expression col : fullOuterJoin.exprs(Args.USING)) {
                        eqs.add(Exp.column(col, tables[0]).eq(Exp.column(col, tables[1])));
                    }
                    joinConditions = and(eqs, true);
                }

                fullOuterJoin.set(Args.SIDE, "left");
                Expression antiJoinClause = Exp.select("1")
                        .from(expression.get(Args.FROM_), true)
                        .where(true, true, joinConditions);
                expressionCopy.exprs(Args.JOINS).get(index).set(Args.SIDE, "right");
                expressionCopy = expressionCopy.where(true, true,
                        Expression.of(ExprType.EXISTS, Args.THIS, antiJoinClause).not(true));

                // exp.union(expression, expression_copy, copy=False, distinct=False)
                Expression union = Expression.of(ExprType.UNION,
                        Args.THIS, expression, Args.EXPRESSION, expressionCopy, Args.DISTINCT, Boolean.FALSE);
                for (var arg : List.of(Args.WITH_, Args.ORDER, Args.LIMIT, Args.OFFSET)) {
                    Object value = expression.get(arg);
                    if (Expression.truthy(value)) {
                        expression.set(arg, null);
                        expressionCopy.set(arg, null);
                        union.set(arg, value);
                    }
                }
                return union;
            }
        }

        return expression;
    }

    /**
     * {@code transforms.move_ctes_to_top_level(expression)}.
     *
     * <p>Some dialects (e.g. Hive, T-SQL, Spark prior to version 3) only allow CTEs to be defined at
     * the top-level, so for example queries like
     * {@code SELECT * FROM (WITH t(c) AS (SELECT 1) SELECT * FROM t) AS subq} are invalid in those
     * dialects. This transformation can be used to ensure all CTEs are moved to the top level so that
     * the final SQL code is valid from a syntax standpoint. (Name clashes are not handled.)
     */
    public static Expression moveCtesToTopLevel(Expression expression) {
        Expression topLevelWith = expression.expr(Args.WITH_);
        for (Expression innerWith : findAllLazy(expression, ExprType.WITH)) {
            if (innerWith.parent() == expression) {
                continue;
            }

            if (topLevelWith == null) {
                topLevelWith = innerWith.pop();
                expression.set(Args.WITH_, topLevelWith);
            } else {
                if (innerWith.has(Args.RECURSIVE)) {
                    topLevelWith.set(Args.RECURSIVE, Boolean.TRUE);
                }

                Expression parentCte = innerWith.findAncestor(ExprType.CTE);
                innerWith.pop();

                List<Expression> expressions = new ArrayList<>(topLevelWith.getExpressions());
                if (parentCte != null) {
                    int i = indexOf(expressions, parentCte);
                    expressions.addAll(i, innerWith.getExpressions());
                } else {
                    expressions.addAll(innerWith.getExpressions());
                }
                topLevelWith.set(Args.EXPRESSIONS, expressions);
            }
        }

        return expression;
    }

    /** {@code transforms.ensure_bools(expression)}: converts numeric values used in conditions into explicit boolean expressions. */
    public static Expression ensureBools(Expression expression) {
        for (Expression node : expression.walk()) {
            canonicalizeEnsureBools(node, Transforms::ensureBool);
        }

        return expression;
    }

    private static void ensureBool(Expression node) {
        if (node.isNumber()
                || (!node.is(ExprType.SUBQUERY_PREDICATE) && node.isType(UNKNOWN_OR_NUMERIC_TYPES))
                || (node.is(ExprType.COLUMN) && node.dataType() == null)) {
            node.replace(node.neq(0));
        }
    }

    /**
     * {@code sqlglot.optimizer.canonicalize.ensure_bools(expression, replace_func)}, ported inline
     * because {@code Canonicalize.ensureBools} only exposes the one-argument form. Rewire to
     * {@code Canonicalize.ensureBools(node, replaceFunc)} once that overload exists.
     */
    private static Expression canonicalizeEnsureBools(Expression expression, Consumer<Expression> replaceFunc) {
        if (expression.is(ExprType.CONNECTOR)) {
            replaceFunc.accept(expression.getThis());
            replaceFunc.accept(expression.getExpression());
        } else if (expression.is(ExprType.NOT)) {
            replaceFunc.accept(expression.getThis());
            // We can't replace num in CASE x WHEN num ..., because it's not the full predicate
        } else if (expression.is(ExprType.IF)
                && !(expression.parent() != null && expression.parent().is(ExprType.CASE)
                        && expression.parent().has(Args.THIS))) {
            replaceFunc.accept(expression.getThis());
        } else if (expression.is(ExprType.WHERE, ExprType.HAVING)) {
            replaceFunc.accept(expression.getThis());
        }

        return expression;
    }

    /** {@code transforms.unqualify_columns(expression)}: pops the table, db and catalog parts off every column. */
    public static Expression unqualifyColumns(Expression expression) {
        for (Expression column : findAllLazy(expression, ExprType.COLUMN)) {
            // We only wanna pop off the table, db, catalog args
            List<Expression> parts = column.parts();
            for (Expression part : parts.subList(0, Math.max(0, parts.size() - 1))) {
                part.pop();
            }
        }

        return expression;
    }

    /**
     * {@code transforms.unqualify_pivot_fields(expression)}: some dialects only accept simple column
     * names in a (UN)PIVOT's FOR clause and IN-list (Oracle raises ORA-01748), even though the
     * aggregate itself may stay qualified.
     */
    public static Expression unqualifyPivotFields(Expression expression) {
        if (expression.is(ExprType.PIVOT)) {
            List<Expression> fields = new ArrayList<>();
            for (Expression field : expression.exprs(Args.FIELDS)) {
                fields.add(unqualifyColumns(field));
            }
            expression.set(Args.FIELDS, fields);
        }

        return expression;
    }

    /** {@code transforms.remove_unique_constraints(expression)}. */
    public static Expression removeUniqueConstraints(Expression expression) {
        expression.assertIs(ExprType.CREATE);
        for (Expression constraint : findAllLazy(expression, ExprType.UNIQUE_COLUMN_CONSTRAINT)) {
            Expression parent = constraint.parent();
            (parent != null && parent.is(ExprType.COLUMN_CONSTRAINT, ExprType.CONSTRAINT) ? parent : constraint).pop();
        }

        return expression;
    }

    /** {@code transforms.ctas_with_tmp_tables_to_create_tmp_view(expression)} with the identity storage provider. */
    public static Expression ctasWithTmpTablesToCreateTmpView(Expression expression) {
        return ctasWithTmpTablesToCreateTmpView(expression, e -> e);
    }

    /** {@code transforms.ctas_with_tmp_tables_to_create_tmp_view(expression, tmp_storage_provider)}. */
    public static Expression ctasWithTmpTablesToCreateTmpView(Expression expression,
            UnaryOperator<Expression> tmpStorageProvider) {
        expression.assertIs(ExprType.CREATE);
        Expression properties = expression.expr(Args.PROPERTIES);
        boolean temporary = false;
        if (properties != null) {
            for (Expression prop : properties.getExpressions()) {
                if (prop.is(ExprType.TEMPORARY_PROPERTY)) {
                    temporary = true;
                    break;
                }
            }
        }

        // CTAS with temp tables map to CREATE TEMPORARY VIEW
        if ("TABLE".equals(createKind(expression)) && temporary) {
            if (expression.getExpression() != null) {
                return Expression.of(ExprType.CREATE,
                        Args.KIND, "TEMPORARY VIEW",
                        Args.THIS, expression.getThis(),
                        Args.EXPRESSION, expression.getExpression());
            }
            return tmpStorageProvider.apply(expression);
        }

        return expression;
    }

    /**
     * {@code transforms.move_schema_columns_to_partitioned_by(expression)}: in Hive, the PARTITIONED BY
     * property acts as an extension of a table's schema. When the PARTITIONED BY value is an array of
     * column names, they are transformed into a schema. The corresponding columns are removed from the
     * create statement.
     */
    public static Expression moveSchemaColumnsToPartitionedBy(Expression expression) {
        expression.assertIs(ExprType.CREATE);
        Expression schema = expression.getThis();
        String kind = createKind(expression);
        boolean isPartitionable = "TABLE".equals(kind) || "VIEW".equals(kind);

        if (schema != null && schema.is(ExprType.SCHEMA) && isPartitionable) {
            Expression prop = expression.find(ExprType.PARTITIONED_BY_PROPERTY);
            if (prop != null && prop.getThis() != null && !prop.getThis().is(ExprType.SCHEMA)) {
                Set<String> columns = new LinkedHashSet<>();
                for (Expression v : prop.getThis().getExpressions()) {
                    columns.add(Py.upper(v.name()));
                }
                List<Expression> schemaExprs = schema.getExpressions();
                List<Expression> partitions = new ArrayList<>();
                for (Expression col : schemaExprs) {
                    if (columns.contains(Py.upper(col.name()))) {
                        partitions.add(col);
                    }
                }
                List<Expression> remaining = new ArrayList<>();
                for (Expression e : schemaExprs) {
                    if (!partitions.contains(e)) {
                        remaining.add(e);
                    }
                }
                schema.set(Args.EXPRESSIONS, remaining);
                prop.replace(Expression.of(ExprType.PARTITIONED_BY_PROPERTY,
                        Args.THIS, Expression.of(ExprType.SCHEMA, Args.EXPRESSIONS, partitions)));
                expression.set(Args.THIS, schema);
            }
        }

        return expression;
    }

    /**
     * {@code transforms.move_partitioned_by_to_schema_columns(expression)}: Spark 3 supports both
     * "HIVEFORMAT" and "DATASOURCE" formats for CREATE TABLE; sqlglot uses DATASOURCE for Spark 3.
     */
    public static Expression movePartitionedByToSchemaColumns(Expression expression) {
        expression.assertIs(ExprType.CREATE);
        Expression prop = expression.find(ExprType.PARTITIONED_BY_PROPERTY);
        if (prop != null && prop.getThis() != null && prop.getThis().is(ExprType.SCHEMA)) {
            boolean allColumnDefs = true;
            for (Expression e : prop.getThis().getExpressions()) {
                if (!(e.is(ExprType.COLUMN_DEF) && e.has(Args.KIND))) {
                    allColumnDefs = false;
                    break;
                }
            }
            if (allColumnDefs) {
                List<Expression> ids = new ArrayList<>();
                for (Expression e : prop.getThis().getExpressions()) {
                    ids.add(Exp.toIdentifier(e.get(Args.THIS)));
                }
                Expression propThis = Expression.of(ExprType.TUPLE, Args.EXPRESSIONS, ids);
                Expression schema = expression.getThis();
                for (Expression e : new ArrayList<>(prop.getThis().getExpressions())) {
                    schema.append(Args.EXPRESSIONS, e);
                }
                prop.set(Args.THIS, propThis);
            }
        }

        return expression;
    }

    /** {@code transforms.struct_kv_to_alias(expression)}: converts struct arguments to aliases, e.g. STRUCT(1 AS y). */
    public static Expression structKvToAlias(Expression expression) {
        if (expression.is(ExprType.STRUCT)) {
            List<Expression> expressions = new ArrayList<>();
            for (Expression e : expression.getExpressions()) {
                expressions.add(e.is(ExprType.PROPERTY_EQ) ? alias(e.getExpression(), e.getThis(), null, true) : e);
            }
            expression.set(Args.EXPRESSIONS, expressions);
        }

        return expression;
    }

    /**
     * {@code transforms.eliminate_join_marks(expression)}: rewrites Oracle's {@code (+)} outer-join
     * marks in WHERE into explicit LEFT JOINs. See
     * https://docs.oracle.com/cd/B19306_01/server.102/b14200/queries006.htm#sthref3178
     */
    public static Expression eliminateJoinMarks(Expression expression) {
        // we go in reverse to check the main query for left correlation
        List<Scope> scopes = new ArrayList<>(Scopes.traverseScope(expression));
        for (int s = scopes.size() - 1; s >= 0; s--) {
            Scope scope = scopes.get(s);
            Expression query = scopeExpression(scope);

            Expression where = query.expr(Args.WHERE);
            List<Expression> joins = query.exprs(Args.JOINS);

            if (where == null) {
                continue;
            }
            boolean hasJoinMark = false;
            for (Expression c : findAllLazy(where, ExprType.COLUMN)) {
                if (c.has(Args.JOIN_MARK)) {
                    hasJoinMark = true;
                    break;
                }
            }
            if (!hasJoinMark) {
                continue;
            }

            // knockout: we do not support left correlation (see point 2)
            if (scopeIsCorrelatedSubquery(scope)) {
                throw new AssertionError("Correlated queries are not supported");
            }

            // make sure we have AND of ORs to have clear join terms
            where = Normalize.normalize(where.getThis(), false);
            if (!Normalize.normalized(where, false)) {
                throw new AssertionError("Cannot normalize JOIN predicates");
            }
            // dict of {name: list of join AND conditions}
            Map<String, List<Expression>> joinsOns = new LinkedHashMap<>();
            List<Expression> conds = where.is(ExprType.AND) ? where.flattenList() : List.of(where);
            for (Expression cond : conds) {
                List<Expression> joinCols = new ArrayList<>();
                for (Expression col : cond.findAll(ExprType.COLUMN)) {
                    if (col.has(Args.JOIN_MARK)) {
                        joinCols.add(col);
                    }
                }

                Set<String> leftJoinTable = new LinkedHashSet<>();
                for (Expression col : joinCols) {
                    leftJoinTable.add(col.text(Args.TABLE));
                }
                if (leftJoinTable.isEmpty()) {
                    continue;
                }

                if (leftJoinTable.size() > 1) {
                    throw new AssertionError("Cannot combine JOIN predicates from different tables");
                }

                for (Expression col : joinCols) {
                    col.set(Args.JOIN_MARK, Boolean.FALSE);
                }

                joinsOns.computeIfAbsent(leftJoinTable.iterator().next(), k -> new ArrayList<>()).add(cond);
            }

            Map<String, Expression> oldJoins = new LinkedHashMap<>();
            for (Expression join : joins) {
                oldJoins.put(join.aliasOrName(), join);
            }
            Map<String, Expression> newJoins = new LinkedHashMap<>();
            Expression queryFrom = query.expr(Args.FROM_);

            for (Map.Entry<String, List<Expression>> entry : joinsOns.entrySet()) {
                List<Expression> predicates = entry.getValue();
                Expression joinWhat = oldJoins.getOrDefault(entry.getKey(), queryFrom).getThis().copy();
                newJoins.put(joinWhat.aliasOrName(), Expression.of(ExprType.JOIN,
                        Args.THIS, joinWhat, Args.ON, and(predicates, true), Args.KIND, "LEFT"));

                for (Expression p : predicates) {
                    while (p.parent() != null && p.parent().is(ExprType.PAREN)) {
                        p.parent().replace(p);
                    }

                    Expression parent = p.parent();
                    p.pop();
                    if (parent != null && parent.is(ExprType.BINARY)) {
                        Expression left = parent.expr(Args.THIS);
                        parent.replace(left == null ? parent.expr(Args.EXPRESSION) : left);
                    } else if (parent != null && parent.is(ExprType.WHERE)) {
                        parent.pop();
                    }
                }
            }

            if (newJoins.containsKey(queryFrom.aliasOrName())) {
                // Python iterates a set difference here (hash order); we take the first in join order.
                List<String> onlyOldJoins = new ArrayList<>();
                for (String name : oldJoins.keySet()) {
                    if (!newJoins.containsKey(name)) {
                        onlyOldJoins.add(name);
                    }
                }
                if (onlyOldJoins.isEmpty()) {
                    throw new AssertionError("Cannot determine which table to use in the new FROM clause");
                }

                String newFromName = onlyOldJoins.get(0);
                query.set(Args.FROM_, Expression.of(ExprType.FROM, Args.THIS, oldJoins.get(newFromName).getThis()));
            }

            if (!newJoins.isEmpty()) {
                for (Map.Entry<String, Expression> entry : oldJoins.entrySet()) { // preserve any other joins
                    String n = entry.getKey();
                    Expression j = entry.getValue();
                    if (!newJoins.containsKey(n) && !n.equals(query.expr(Args.FROM_).name())) {
                        if (j.text(Args.KIND).isEmpty()) {
                            j.set(Args.KIND, "CROSS");
                        }
                        newJoins.put(n, j);
                    }
                }
                query.set(Args.JOINS, new ArrayList<>(newJoins.values()));
            }
        }

        return expression;
    }

    /**
     * {@code transforms.any_to_exists(expression)}: transforms the ANY operator to Spark's EXISTS, e.g.
     * {@code 5 > ANY(tbl.col)} becomes {@code EXISTS(tbl.col, x -> x < 5)}. Only array expressions are
     * supported.
     */
    public static Expression anyToExists(Expression expression) {
        if (expression.is(ExprType.SELECT)) {
            for (Expression anyExpr : findAllLazy(expression, ExprType.ANY)) {
                Expression thisExpr = anyExpr.getThis();
                if ((thisExpr != null && thisExpr.is(ExprType.QUERY))
                        || (anyExpr.parent() != null && anyExpr.parent().is(ExprType.LIKE, ExprType.I_LIKE))) {
                    continue;
                }

                Expression binop = anyExpr.parent();
                if (binop != null && binop.is(ExprType.BINARY)) {
                    Expression lambdaArg = Exp.toIdentifier("x");
                    anyExpr.replace(lambdaArg);
                    Expression lambdaExpr = Expression.of(ExprType.LAMBDA,
                            Args.THIS, binop.copy(), Args.EXPRESSIONS, List.of(lambdaArg));
                    binop.replace(Expression.of(ExprType.EXISTS,
                            Args.THIS, thisExpr.unnest(), Args.EXPRESSION, lambdaExpr));
                }
            }
        }

        return expression;
    }

    /** {@code transforms.eliminate_window_clause(expression)}: eliminates the WINDOW query clause by inlining each named window. */
    public static Expression eliminateWindowClause(Expression expression) {
        Object windowsValue = expression.is(ExprType.SELECT) ? expression.get(Args.WINDOWS) : null;
        if (windowsValue != null) {
            List<Expression> windows = new ArrayList<>(expression.exprs(Args.WINDOWS));
            expression.set(Args.WINDOWS, null);

            Map<String, Expression> windowExpression = new LinkedHashMap<>();

            for (Expression window : windows) {
                inlineInheritedWindow(window, windowExpression);
                windowExpression.put(Py.lower(window.name()), window);
            }

            for (Expression window : Scopes.findAllInScope(expression, ExprType.WINDOW)) {
                inlineInheritedWindow(window, windowExpression);
            }
        }

        return expression;
    }

    private static void inlineInheritedWindow(Expression window, Map<String, Expression> windowExpression) {
        Expression inheritedWindow = windowExpression.get(Py.lower(window.alias()));
        if (inheritedWindow == null) {
            return;
        }

        window.set(Args.ALIAS, null);
        for (var key : List.of(Args.PARTITION_BY, Args.ORDER, Args.SPEC)) {
            Object arg = inheritedWindow.get(key);
            if (arg instanceof Expression e) {
                window.set(key, e.copy());
            } else if (arg instanceof List<?> l) {
                // partition_by is a list: Python's list.copy() is shallow, so the nodes are shared
                window.set(key, l);
            }
        }
    }

    /**
     * {@code transforms.inherit_struct_field_names(expression)}: inherits field names from the first
     * struct in an array (BigQuery's implicit STRUCT name inheritance), making them explicit on all
     * structs by adding PropertyEQ nodes.
     */
    public static Expression inheritStructFieldNames(Expression expression) {
        if (!(expression.is(ExprType.ARRAY) && expression.has(Args.STRUCT_NAME_INHERITANCE))) {
            return expression;
        }
        Expression firstItem = Py.seqGet(expression.getExpressions(), 0);
        if (firstItem == null || !firstItem.is(ExprType.STRUCT)) {
            return expression;
        }
        for (Expression fld : firstItem.getExpressions()) {
            if (!fld.is(ExprType.PROPERTY_EQ)) {
                return expression;
            }
        }

        List<Expression> fieldNames = new ArrayList<>();
        for (Expression fld : firstItem.getExpressions()) {
            fieldNames.add(fld.getThis());
        }

        // Apply field names to subsequent structs that don't have them
        List<Expression> items = expression.getExpressions();
        for (Expression struct : new ArrayList<>(items.subList(1, items.size()))) {
            if (!struct.is(ExprType.STRUCT) || struct.getExpressions().size() != fieldNames.size()) {
                continue;
            }

            // Convert unnamed expressions to PropertyEQ with inherited names
            List<Expression> newExpressions = new ArrayList<>();
            List<Expression> structExprs = struct.getExpressions();
            for (int i = 0; i < structExprs.size(); i++) {
                Expression expr = structExprs.get(i);
                if (!expr.is(ExprType.PROPERTY_EQ)) {
                    // Create PropertyEQ: field_name := value, preserving the type from the inner expression
                    Expression propertyEq = Expression.of(ExprType.PROPERTY_EQ,
                            Args.THIS, fieldNames.get(i).copy(), Args.EXPRESSION, expr);
                    propertyEq.setDataType(expr.dataType());
                    newExpressions.add(propertyEq);
                } else {
                    newExpressions.add(expr);
                }
            }

            struct.set(Args.EXPRESSIONS, newExpressions);
        }

        return expression;
    }

    // ------------------------------------------------------------------------------------------
    // Helpers

    /** {@code exp.alias_(e, alias, quoted=quoted, copy=copy)}. */
    private static Expression alias(Expression e, Object alias, Boolean quoted, boolean copy) {
        return Exp.alias(copy ? e.copy() : e, alias, quoted);
    }

    /** {@code exp.alias_(e, alias, table=columns, copy=copy)}. */
    private static Expression aliasTable(Expression e, Object alias, List<?> columns, boolean copy) {
        return Exp.tableAlias(copy ? e.copy() : e, alias, columns);
    }

    /** {@code exp.and_(*conditions, copy=copy)}: wraps connectors only when there is more than one operand. */
    private static Expression and(List<Expression> conditions, boolean copy) {
        List<Expression> items = new ArrayList<>();
        for (Expression c : conditions) {
            if (c != null) {
                items.add(copy ? c.copy() : c);
            }
        }
        Expression acc = items.get(0);
        if (items.size() > 1) {
            acc = Exp.wrap(acc, ExprType.CONNECTOR);
        }
        for (int i = 1; i < items.size(); i++) {
            acc = Expression.of(ExprType.AND, Args.THIS, acc, Args.EXPRESSION, Exp.wrap(items.get(i), ExprType.CONNECTOR));
        }
        return acc;
    }

    /** {@code Expr._binop(klass, other)}: Python operator overloads such as {@code a <= b}, {@code a - 1}. */
    private static Expression binop(ExprType klass, Expression self, Object other) {
        Expression thisExpr = self.copy();
        Expression otherExpr = other instanceof Expression o ? o.copy() : Exp.convert(other);
        if (!thisExpr.is(klass) && !otherExpr.is(klass)) {
            thisExpr = Exp.wrap(thisExpr, ExprType.BINARY);
            otherExpr = Exp.wrap(otherExpr, ExprType.BINARY);
        }
        return Expression.of(klass, Args.THIS, thisExpr, Args.EXPRESSION, otherExpr);
    }

    /** {@code Create.kind}: the upper-cased kind string, or null. */
    private static String createKind(Expression create) {
        String kind = create.str(Args.KIND);
        return kind == null || kind.isEmpty() ? kind : Py.upper(kind);
    }

    /** Python's {@code list.index(x)} (equality-based), raising ValueError when absent. */
    private static int indexOf(List<Expression> list, Expression x) {
        int i = list.indexOf(x);
        if (i < 0) {
            throw new IllegalArgumentException(x + " is not in list");
        }
        return i;
    }

    /** {@code helper.find_new_name(taken, base)}. */
    private static String findNewName(Collection<String> taken, String base) {
        if (!taken.contains(base)) {
            return base;
        }

        int i = 2;
        String candidate = base + "_" + i;
        while (taken.contains(candidate)) {
            i += 1;
            candidate = base + "_" + i;
        }
        return candidate;
    }

    /** {@code helper.name_sequence(prefix)}: prefix0, prefix1, ... */
    private static Supplier<String> nameSequence(String prefix) {
        int[] sequence = {0};
        return () -> prefix + (sequence[0]++);
    }

    /**
     * Lazy {@code expression.find_all(*types)}: like sqlglot's generator, children are expanded only
     * after the caller has seen a node, so in-loop mutations affect the rest of the traversal.
     */
    private static Iterable<Expression> findAllLazy(Expression expression, ExprType... types) {
        return () -> new Iterator<>() {
            final Iterator<Expression> it = expression.bfs().iterator();
            Expression nextMatch;

            @Override
            public boolean hasNext() {
                while (nextMatch == null && it.hasNext()) {
                    Expression e = it.next();
                    if (e.is(types)) {
                        nextMatch = e;
                    }
                }
                return nextMatch != null;
            }

            @Override
            public Expression next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Expression e = nextMatch;
                nextMatch = null;
                return e;
            }
        };
    }

    // ------------------------------------------------------------------------------------------
    // Scope shims: rewire to the optimizer's Scope API once it is ported.

    /** {@code Scope(expression)}. */
    private static Scope newScope(Expression expression) {
        return new Scope(expression);
    }

    /** {@code [name for name, _ in scope.references]}. */
    private static List<String> scopeReferenceNames(Scope scope) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Expression> ref : scope.references()) {
            out.add(ref.getKey());
        }
        return out;
    }

    private static Expression scopeExpression(Scope scope) {
        return scope.expression();
    }

    private static boolean scopeIsCorrelatedSubquery(Scope scope) {
        return scope.isCorrelatedSubquery();
    }
}
