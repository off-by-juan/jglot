package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.dialects.data.DialectData;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.util.Py;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * sqlglot's {@code optimizer.annotate_types}: infers the types of an expression, annotating its AST
 * accordingly. jglot never has a schema, so this is the schema-less case (sqlglot's empty
 * {@code MappingSchema}): table columns are UNKNOWN, UDFs are UNKNOWN.
 *
 * <p>Type values are passed around as {@code Object}, mirroring sqlglot's {@code DataType | DType}
 * unions: either a {@link DType} or a DATA_TYPE {@link Expression} (or null for Python's None).
 *
 * <p>An instance holds per-run caches and is not thread-safe; {@link #annotateTypes} creates one per call.
 */
public final class TypeAnnotator {
    private static final Logger LOGGER = Logger.getLogger("sqlglot");

    /** EXTRACT/DATE_PART specifiers that return BIGINT instead of INT. */
    static final Set<String> BIGINT_EXTRACT_DATE_PARTS = Set.of(
            "EPOCH_SECOND", "EPOCH_MILLISECOND", "EPOCH_MICROSECOND", "EPOCH_NANOSECOND", "NANOSECOND");

    /** Interval units that operate on date components (sqlglot's {@code helper.DATE_UNITS}). */
    static final Set<String> DATE_UNITS = Set.of("day", "week", "month", "quarter", "year", "year_month");

    final Dialect dialect;
    final DialectData dd;
    final Map<ExprType, TypeMetadata.Spec> expressionMetadata;
    final Map<DType, Set<DType>> coercesTo;

    // Caches the annotated sub-expressions, to ensure we only visit them once
    private final Set<Expression> visited = Collections.newSetFromMap(new IdentityHashMap<>());

    // Caches NULL-annotated expressions to set them to UNKNOWN after type inference is completed
    private final Map<Expression, Expression> nullExpressions = new IdentityHashMap<>();

    // Databricks and Spark >= v3 actually support NULL (i.e., VOID) as a type
    private final boolean supportsNullType;

    // Maps a SetOperation (e.g. UNION) to its projection types
    private final Map<Expression, Map<String, Object>> setopColumnTypes = new IdentityHashMap<>();

    // When set to false, this enables partial annotation by skipping already-annotated nodes
    private final boolean overwriteTypes;

    // Maps (Scope, source_name) to its column projections and types
    private final Map<Scope, Map<String, Map<String, Object>>> scopeSourceSelects = new IdentityHashMap<>();

    TypeAnnotator(Dialect dialect, boolean overwriteTypes) {
        this.dialect = dialect == null ? Dialects.base() : dialect;
        this.dd = this.dialect.data();
        this.expressionMetadata = TypeMetadata.forDialect(this.dialect);
        this.coercesTo = TypeMetadata.coercesTo(this.dialect);
        this.supportsNullType = dd.SUPPORTS_NULL_TYPE;
        this.overwriteTypes = overwriteTypes;
    }

    /** {@code annotate_types(expression, dialect=dialect)}: sets data types on the tree in place and returns it. */
    public static Expression annotateTypes(Expression expression, Dialect dialect) {
        return new TypeAnnotator(dialect, true).annotate(expression, true);
    }

    void clear() {
        visited.clear();
        nullExpressions.clear();
        setopColumnTypes.clear();
        scopeSourceSelects.clear();
    }

    // ------------------------------------------------------------------------------------------
    // Type helpers (sqlglot's DataType | DType handling)

    /** {@code DType.into_expr()} for DType values; DATA_TYPE expressions pass through; null stays null. */
    static Expression toDataType(Object type) {
        if (type == null) {
            return null;
        }
        if (type instanceof DType d) {
            return DataTypes.of(d);
        }
        return (Expression) type;
    }

    /**
     * sqlglot's {@code expression._type}: the explicitly set type annotation. jglot's {@link
     * Expression#dataType()} falls back to {@code to} for casts (sqlglot's {@code .type} property),
     * so an annotation that is the very {@code to} node is treated as unset; jglot code that annotates
     * casts stores a copy of {@code to} so that the two cases stay distinguishable.
     */
    static Expression rawType(Expression e) {
        return e.type().isDataTypeType() ? e : e.rawDataType();
    }

    /** sqlglot's {@code Expr.is_type} (uses the explicitly set type) / {@code DataType.is_type}. */
    static boolean isType(Expression e, Object... dtypes) {
        if (e == null) {
            return false;
        }
        Expression t = rawType(e);
        return t != null && DataTypes.isType(t, false, dtypes);
    }

    /** {@link #isType(Expression, Object...)} for a set of DTypes, without allocating. */
    static boolean isType(Expression e, Set<DType> dtypes) {
        if (e == null) {
            return false;
        }
        Expression t = rawType(e);
        return t != null && dataTypeIn(t, dtypes);
    }

    /** {@code DataType.is_type(*dtypes)} for plain DType targets. */
    static boolean dataTypeIn(Expression dataType, Set<DType> dtypes) {
        Object thisValue = dataType.thisValue();
        if (thisValue == DType.USERDEFINED) {
            return dtypes.contains(DType.USERDEFINED) && dataType.equals(DataTypes.of(DType.USERDEFINED));
        }
        return thisValue instanceof DType d && dtypes.contains(d);
    }

    /** {@code expression.type.this} if typed, else {@code UNKNOWN}. */
    private static Object typeThis(Expression e) {
        Expression t = e == null ? null : e.dataType();
        return t != null ? t.thisValue() : DType.UNKNOWN;
    }

    static boolean nonnull(Expression e) {
        return e != null && Boolean.TRUE.equals(e.metaGet("nonnull"));
    }

    // ------------------------------------------------------------------------------------------
    // Core

    Expression setType(Expression expression, Object targetType) {
        Expression prevType = expression.dataType();
        Expression dtype = targetType == null ? DataTypes.of(DType.UNKNOWN) : toDataType(targetType);
        expression.setDataType(dtype);
        visited.add(expression);

        Expression newType = expression.dataType();
        if (!supportsNullType && newType != null && newType.thisValue() == DType.NULL) {
            nullExpressions.put(expression, expression);
        } else if (prevType != null && prevType.thisValue() == DType.NULL) {
            nullExpressions.remove(expression);
        }

        return expression;
    }

    Expression annotate(Expression expression, boolean annotateScope) {
        // This flag is used to avoid costly scope traversals when we only care about annotating
        // non-column expressions (partial type inference), e.g., when simplifying in the optimizer
        if (annotateScope) {
            for (Scope scope : Scopes.traverseScope(expression)) {
                annotateScope(scope);
            }
        }

        // This takes care of non-traversable expressions
        annotateExpression(expression, null);

        // Replace NULL type with the default type of the targeted dialect, since the former is not an actual type;
        // it is mostly used to aid type coercion, e.g. in query set operations.
        for (Expression expr : new ArrayList<>(nullExpressions.values())) {
            setType(expr, dd.DEFAULT_NULL_TYPE);
        }

        return expression;
    }

    private Map<String, Object> getScopeSourceSelects(Scope scope, String sourceName) {
        Map<String, Map<String, Object>> byName = scopeSourceSelects.computeIfAbsent(scope, k -> new HashMap<>());
        Map<String, Object> selects = byName.get(sourceName);

        if (selects == null) {
            selects = new LinkedHashMap<>();
            Object source = scope.sources().get(sourceName);

            if (source instanceof Scope s) {
                selects = getSourceScopeSelects(s);
            } else {
                List<Expression> pivots = source instanceof Expression t && t.is(ExprType.TABLE)
                        ? t.exprs(Args.PIVOTS)
                        : scope.pivots();
                // Only the last operator in a chain carries the alias that names the
                // resulting source, so match on it and fold the whole chain in order
                if (!pivots.isEmpty() && pivots.get(pivots.size() - 1).aliasOrName().equals(sourceName)) {
                    Expression parent = pivots.get(pivots.size() - 1).parent();
                    Object parentSource = parent != null ? scope.sources().get(parent.aliasOrName()) : null;

                    if (!(parentSource instanceof Scope) && parent != null && parent.is(ExprType.TABLE)
                            && parent.text(Args.DB).isEmpty()) {
                        // A chain aliased like the CTE it reads from shadows it in
                        // `scope.sources`, so reach for the CTE's scope directly
                        parentSource = scope.cteSources().get(parent.name());
                    }

                    Map<String, Object> srcTypes;
                    if (parentSource instanceof Scope ps) {
                        srcTypes = getSourceScopeSelects(ps);
                    } else {
                        // schema.find(...) on the (empty) schema finds nothing
                        srcTypes = new LinkedHashMap<>();
                    }

                    for (Expression pivot : pivots) {
                        srcTypes = pivot.has(Args.UNPIVOT)
                                ? getUnpivotColumnTypes(pivot, srcTypes)
                                : getPivotColumnTypes(pivot, srcTypes);
                    }

                    selects = srcTypes;
                }
            }

            byName.put(sourceName, selects);
        }

        return selects;
    }

    private Map<String, Object> getSourceScopeSelects(Scope source) {
        Expression expression = source.expression();

        if (expression.is(ExprType.UDTF)) {
            List<Expression> values = new ArrayList<>();

            if (expression.is(ExprType.LATERAL)) {
                Expression t = expression.getThis();
                if (t != null && t.is(ExprType.EXPLODE)) {
                    values = new ArrayList<>();
                    values.add(t.getThis());
                }
            } else if (expression.is(ExprType.UNNEST)) {
                values = List.of(expression);
            } else if (!expression.is(ExprType.TABLE_FROM_ROWS)) {
                List<Expression> exprs = expression.getExpressions();
                values = exprs.isEmpty() ? List.of() : exprs.get(0).getExpressions();
            }

            if (values.isEmpty()) {
                return new LinkedHashMap<>();
            }

            List<String> aliasColumnNames = expression.aliasColumnNames();

            Expression expType;
            if (expression.is(ExprType.UNNEST)) {
                expType = expression.dataType();
            } else if (expression.is(ExprType.LATERAL) && expression.getThis() != null
                    && expression.getThis().is(ExprType.EXPLODE)) {
                expType = expression.getThis().dataType();
            } else {
                expType = null;
            }

            Expression structType = expType != null && DataTypes.isType(expType, false, DType.STRUCT) ? expType : null;

            Map<String, Object> out = new LinkedHashMap<>();
            if (structType != null) {
                for (Expression colDef : structType.getExpressions()) {
                    if (colDef.is(ExprType.COLUMN_DEF) && colDef.expr(Args.KIND) != null) {
                        out.put(colDef.name(), colDef.expr(Args.KIND));
                    }
                }
                return out;
            }

            for (int i = 0; i < Math.min(aliasColumnNames.size(), values.size()); i++) {
                Expression column = values.get(i);
                out.put(aliasColumnNames.get(i), column == null ? null : column.dataType());
            }
            return out;
        }

        if (expression.is(ExprType.SET_OPERATION) && (expression.has(Args.BY_NAME)
                || expression.getThis().selects().size() == expression.getExpression().selects().size())) {
            return getSetopColumnTypes(expression);
        }

        if (expression.is(ExprType.SELECTABLE)) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Expression s : expression.selects()) {
                if (s.dataType() != null) {
                    out.put(s.aliasOrName(), s.dataType());
                }
            }
            return out;
        }

        return new LinkedHashMap<>();
    }

    private void annotateScope(Scope scope) {
        // The schema is always an empty MappingSchema in jglot
        for (Expression tableColumn : scope.tableColumns()) {
            Object source = scope.sources().get(tableColumn.name());

            if (source instanceof Expression t && t.is(ExprType.TABLE)) {
                // self.schema.find(...) returns nothing for an empty schema
                continue;
            } else if (source instanceof Scope s && s.expression().is(ExprType.QUERY)) {
                Object queryType = s.expression().metaGet("query_type");
                Expression qt = queryType instanceof Expression q ? q : DataTypes.of(DType.UNKNOWN);
                if (DataTypes.isType(qt, false, DType.STRUCT)) {
                    setType(tableColumn, s.expression().meta().get("query_type"));
                }
            }
        }

        // Iterate through all the expressions of the current scope in post-order, and annotate
        annotateExpression(scope.expression(), scope);
        fixupOrderByAliases(scope);

        Expression scopeExpression = scope.expression();
        if (dd.QUERY_RESULTS_ARE_STRUCTS && scopeExpression.is(ExprType.QUERY)) {
            List<Expression> colDefs = new ArrayList<>();
            for (Expression select : scopeExpression.selects()) {
                colDefs.add(Expression.of(ExprType.COLUMN_DEF,
                        Args.THIS, Exp.toIdentifier(select.outputName()),
                        Args.KIND, select.dataType() != null ? select.dataType().copy() : null));
            }
            Expression structType = Expression.of(ExprType.DATA_TYPE,
                    Args.THIS, DType.STRUCT, Args.EXPRESSIONS, colDefs, Args.NESTED, Boolean.TRUE);

            boolean anyUnknown = false;
            for (Expression cd : structType.getExpressions()) {
                Expression kind = cd.expr(Args.KIND);
                if (kind != null && DataTypes.isType(kind, false, DType.UNKNOWN)) {
                    anyUnknown = true;
                    break;
                }
            }
            if (!anyUnknown) {
                // We don't use `setType` on purpose here. If we annotated the query directly, then
                // using it in other contexts (e.g., ARRAY(<query>)) could result in incorrect type
                // annotations, i.e., it shouldn't be interpreted as a STRUCT value.
                scopeExpression.meta().put("query_type", structType);
            }
        }
    }

    private record Frame(Expression expr, boolean childrenAnnotated) {}

    void annotateExpression(Expression expression, Scope scope) {
        ArrayList<Frame> stack = new ArrayList<>();
        stack.add(new Frame(expression, false));

        while (!stack.isEmpty()) {
            Frame frame = stack.remove(stack.size() - 1);
            Expression expr = frame.expr();

            if (visited.contains(expr)
                    || (!overwriteTypes && expr.dataType() != null && !isType(expr, DType.UNKNOWN))) {
                continue; // We've already inferred the expression's type
            }

            if (!frame.childrenAnnotated()) {
                stack.add(new Frame(expr, true));
                for (Expression child : expr.iterExpressions()) {
                    stack.add(new Frame(child, false));
                }
                continue;
            }

            String table = expr.type() == ExprType.COLUMN ? expr.text(Args.TABLE) : "";
            if (scope != null && expr.type() == ExprType.COLUMN && !table.isEmpty() && !expr.isStar()) {
                Object source = null;
                Scope sourceScope = scope;
                while (sourceScope != null && source == null) {
                    source = sourceScope.sources().get(table);
                    if (source == null) {
                        sourceScope = sourceScope.parent();
                    }
                }

                Object colType;
                if (source instanceof Expression t && t.is(ExprType.TABLE)) {
                    // schema.get_column_type(...) is UNKNOWN for an empty schema
                    Object tableColType = DataTypes.of(DType.UNKNOWN);
                    if (t.has(Args.PIVOTS)) {
                        Object pivotType = getScopeSourceSelects(sourceScope != null ? sourceScope : scope, table)
                                .get(expr.name());
                        tableColType = pivotType != null ? pivotType : DType.UNKNOWN;
                    }
                    setType(expr, tableColType);
                } else if (source != null && sourceScope != null) {
                    colType = getScopeSourceSelects(sourceScope, table).get(expr.name());
                    if (colType != null) {
                        setType(expr, colType);
                    } else if (source instanceof Scope s && s.expression().is(ExprType.UNNEST)) {
                        setType(expr, s.expression().dataType());
                    } else {
                        setType(expr, DType.UNKNOWN);
                    }
                } else if (source == null && !scope.pivots().isEmpty()
                        && (colType = getScopeSourceSelects(scope, table).get(expr.name())) != null) {
                    setType(expr, colType);
                } else {
                    setType(expr, DType.UNKNOWN);
                }

                restoreDotParts(expr);

                Expression t = expr.dataType();
                if (t != null && Boolean.FALSE.equals(t.get(Args.NULLABLE))) {
                    expr.meta().put("nonnull", Boolean.TRUE);
                }
                continue;
            }

            TypeMetadata.Spec spec = expressionMetadata.get(expr.type());

            if (spec != null && spec.annotator() != null) {
                spec.annotator().annotate(this, expr);
            } else if (spec != null && spec.hasReturns()) {
                setType(expr, spec.returns());
            } else {
                setType(expr, DType.UNKNOWN);
            }

            restoreDotParts(expr);
        }
    }

    private void restoreDotParts(Expression expr) {
        // Dot access into semi-structured values is a case sensitive data lookup, i.e. the
        // engine doesn't resolve the keys as identifiers, so we undo their normalization.
        Object dotParts = expr.metaGet("dot_parts");
        if (!Expression.truthy(dotParts) || !isType(expr, DType.JSON, DType.MAP, DType.VARIANT)) {
            if (Expression.truthy(dotParts)) {
                expr.meta().remove("dot_parts");
            }
            return;
        }

        Expression parent = expr.parent();
        for (Object part : (List<?>) dotParts) {
            if (parent == null || !parent.is(ExprType.DOT)) {
                break;
            }

            Expression identifier = parent.getExpression();
            if (identifier != null && identifier.is(ExprType.IDENTIFIER)) {
                // Rename in place to preserve the identifier's meta, e.g. token positions
                identifier.set(Args.THIS, part);
                identifier.set(Args.QUOTED, Boolean.TRUE);
            } else if (identifier != null) {
                identifier.replace(Exp.identifier(String.valueOf(part), Boolean.TRUE));
            }

            parent = parent.parent();
        }

        expr.meta().remove("dot_parts");
    }

    private void fixupOrderByAliases(Scope scope) {
        Expression query = scope.expression();
        if (!query.is(ExprType.QUERY)) {
            return;
        }

        Expression order = query.expr(Args.ORDER);
        if (order == null) {
            return;
        }

        // Build alias -> type map from fully-annotated projections (last match wins,
        // consistent with how _expand_alias_refs handles duplicate aliases).
        Map<String, Expression> aliasTypes = new HashMap<>();
        for (Expression sel : query.selects()) {
            if (sel.is(ExprType.ALIAS) && sel.getThis() != null && sel.getThis().dataType() != null
                    && !isType(sel.getThis(), DType.UNKNOWN)) {
                aliasTypes.put(sel.alias(), sel.getThis().dataType());
            }
        }

        if (aliasTypes.isEmpty()) {
            return;
        }

        for (Expression ordered : order.getExpressions()) {
            List<Expression> aliasCols = new ArrayList<>();
            for (Expression c : ordered.findAll(ExprType.COLUMN)) {
                if (c.text(Args.TABLE).isEmpty() && aliasTypes.containsKey(c.name())) {
                    aliasCols.add(c);
                }
            }
            for (Expression col : aliasCols) {
                setType(col, aliasTypes.get(col.name()));
            }

            if (!aliasCols.isEmpty()) {
                for (Expression node : ordered.bfs(n -> n.is(ExprType.SUBQUERY))) {
                    if (!node.is(ExprType.COLUMN, ExprType.LITERAL)) {
                        visited.remove(node);
                    }
                }
                annotateExpression(ordered, scope);
            }
        }
    }

    /**
     * Returns type2 if type1 can be coerced into it, otherwise type1.
     *
     * <p>If either type is parameterized (e.g. DECIMAL(18, 2) contains two parameters), we assume
     * type1 does not coerce into type2, so we also return it in this case.
     */
    Object maybeCoerce(Object type1, Object type2) {
        Object type1Value;
        if (type1 instanceof Expression t1) {
            if (!t1.getExpressions().isEmpty() || !(t1.thisValue() instanceof DType)) {
                return type1;
            }
            type1Value = t1.thisValue();
        } else {
            type1Value = type1;
        }

        Object type2Value;
        if (type2 instanceof Expression t2) {
            if (!t2.getExpressions().isEmpty() || !(t2.thisValue() instanceof DType)) {
                return type2;
            }
            type2Value = t2.thisValue();
        } else {
            type2Value = type2;
        }

        // We propagate the UNKNOWN type upwards if found
        if (type1Value == DType.UNKNOWN || type2Value == DType.UNKNOWN) {
            return DType.UNKNOWN;
        }

        if (type1Value == DType.NULL) {
            return type2Value;
        }
        if (type2Value == DType.NULL) {
            return type1Value;
        }

        Set<DType> targets = type1Value instanceof DType d ? coercesTo.get(d) : null;
        return targets != null && type2Value instanceof DType d2 && targets.contains(d2) ? type2Value : type1Value;
    }

    /**
     * Computes and returns the coerced column types for a SetOperation (UNION, INTERSECT, EXCEPT),
     * coercing types across left and right operands for all projections.
     */
    Map<String, Object> getSetopColumnTypes(Expression setop) {
        Map<String, Object> cached = setopColumnTypes.get(setop);
        if (cached != null) {
            return cached;
        }

        Map<String, Object> colTypes = new LinkedHashMap<>();

        // Validate that left and right have same number of projections (BY NAME
        // operations match columns by name, so their counts are allowed to differ)
        if (!(setop.is(ExprType.SET_OPERATION)
                && setop.getThis() != null && !setop.getThis().selects().isEmpty()
                && setop.getExpression() != null && !setop.getExpression().selects().isEmpty()
                && (setop.has(Args.BY_NAME)
                        || setop.getThis().selects().size() == setop.getExpression().selects().size()))) {
            return colTypes;
        }

        // Process a chain / sub-tree of set operations
        for (Expression setOp : setop.bfs(n -> !n.is(ExprType.SET_OPERATION, ExprType.SUBQUERY))) {
            if (!setOp.is(ExprType.SET_OPERATION)) {
                continue;
            }

            Map<String, Object> setopCols = new LinkedHashMap<>();
            if (setOp.has(Args.BY_NAME)) {
                // Columns missing from one side are filled with NULLs, so the other
                // side's type is preserved (NULL is the identity for maybeCoerce)
                Map<String, Object> rTypeBySelect = new LinkedHashMap<>();
                for (Expression s : setOp.getExpression().selects()) {
                    rTypeBySelect.put(s.aliasOrName(), s.dataType());
                }
                for (Expression s : setOp.getThis().selects()) {
                    String name = s.aliasOrName();
                    Object rType = rTypeBySelect.containsKey(name) ? rTypeBySelect.remove(name) : DType.NULL;
                    setopCols.put(name, maybeCoerce(s.dataType(), rType != null ? rType : DType.UNKNOWN));
                }
                for (Map.Entry<String, Object> entry : rTypeBySelect.entrySet()) {
                    setopCols.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : DType.UNKNOWN);
                }
            } else {
                List<Expression> ls = setOp.getThis().selects();
                List<Expression> rs = setOp.getExpression().selects();
                for (int i = 0; i < Math.min(ls.size(), rs.size()); i++) {
                    setopCols.put(ls.get(i).aliasOrName(), maybeCoerce(ls.get(i).dataType(), rs.get(i).dataType()));
                }
            }

            // Coerce intermediate results with the previously registered types, if they exist
            for (Map.Entry<String, Object> entry : setopCols.entrySet()) {
                String colName = entry.getKey();
                colTypes.put(colName, maybeCoerce(entry.getValue(),
                        colTypes.containsKey(colName) ? colTypes.get(colName) : DType.NULL));
            }
        }

        setopColumnTypes.put(setop, colTypes);
        return colTypes;
    }

    private Map<String, Object> getUnpivotColumnTypes(Expression pivot, Map<String, Object> srcTypes) {
        Map<String, Object> newTypes = new HashMap<>();

        for (Expression field : pivot.exprs(Args.FIELDS)) {
            Expression fieldCol = field.getThis();
            Expression first = Py.seqGet(field.getExpressions(), 0);

            Expression inSrc;
            Expression aliasNode = first != null && first.is(ExprType.PIVOT_ALIAS) ? first.expr(Args.ALIAS) : null;
            if (aliasNode != null) {
                newTypes.put(fieldCol.name(), aliasNode.dataType());
                inSrc = first.getThis();
            } else {
                newTypes.put(fieldCol.name(), DataTypes.of(DType.VARCHAR));
                inSrc = first;
            }

            List<Expression> inCols = inSrc != null && inSrc.is(ExprType.TUPLE)
                    ? inSrc.getExpressions() : Collections.singletonList(inSrc);
            Expression valExpr = Py.seqGet(pivot.getExpressions(), 0);
            List<Expression> valCols = valExpr != null && valExpr.is(ExprType.TUPLE)
                    ? valExpr.getExpressions() : Collections.singletonList(valExpr);
            for (int i = 0; i < Math.min(valCols.size(), inCols.size()); i++) {
                Expression valCol = valCols.get(i);
                Expression inCol = inCols.get(i);
                // A chained operator's IN-list may name columns an earlier operator
                // produced, which carry no annotation of their own
                Object inType = inCol.dataType();
                if (inType == null || DataTypes.isType((Expression) inType, false, DType.UNKNOWN)) {
                    Object src = srcTypes.get(inCol.outputName());
                    inType = src != null ? src : inType;
                }
                newTypes.put(valCol.outputName(), inType);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (String name : outputColumns(pivot, srcTypes.keySet()).keySet()) {
            Object type = newTypes.get(name);
            if (type == null) {
                type = srcTypes.get(name);
            }
            if (type != null) {
                out.put(name, type);
            }
        }
        return out;
    }

    private Map<String, Object> getPivotColumnTypes(Expression pivot, Map<String, Object> srcTypes) {
        Expression firstField = Py.seqGet(pivot.exprs(Args.FIELDS), 0);
        if (firstField == null || !firstField.is(ExprType.IN)) {
            throw new OptimizeError("Expected In expression for pivot field, got "
                    + (firstField == null ? "<class 'NoneType'>" : "<class '" + firstField.type().className() + "'>"));
        }

        List<Expression> pivotConstants = firstField.getExpressions();

        // The first agg_cols_offset entries are source columns that pass through the PIVOT unchanged;
        // the rest are the aggregated columns, one per combination of IN value and aggregate function.
        Map<String, String> outputToSrc = outputColumns(pivot, srcTypes.keySet());

        List<Expression> aggTypes = new ArrayList<>();
        for (Expression agg : pivot.getExpressions()) {
            aggTypes.add(agg.is(ExprType.ALIAS) ? agg.getThis().dataType() : agg.dataType());
        }
        int aggColsOffset = outputToSrc.size() - pivotConstants.size() * aggTypes.size();
        if (aggColsOffset < 0) {
            throw new OptimizeError("Negative pivot column offset: " + aggColsOffset);
        }

        List<String> outputNames = new ArrayList<>(outputToSrc.keySet());
        Map<String, Object> newTypes = new LinkedHashMap<>();

        for (String name : outputNames.subList(0, aggColsOffset)) {
            Object type = srcTypes.get(outputToSrc.get(name));
            if (type != null) {
                newTypes.put(name, type);
            }
        }

        List<Expression> repeated = new ArrayList<>();
        for (int i = 0; i < pivotConstants.size(); i++) {
            repeated.addAll(aggTypes);
        }
        List<String> rest = outputNames.subList(aggColsOffset, outputNames.size());
        for (int i = 0; i < Math.min(rest.size(), repeated.size()); i++) {
            if (repeated.get(i) != null) {
                newTypes.put(rest.get(i), repeated.get(i));
            }
        }

        return newTypes;
    }

    /**
     * {@code Pivot.output_columns(pre_pivot_columns)}: an ordered map of post-rename output column
     * name -> pre-rename source-side name, in the order the (UN)PIVOT produces them.
     */
    static Map<String, String> outputColumns(Expression pivot, Collection<String> prePivotColumns) {
        Set<String> excluded = new HashSet<>();
        List<String> outputs = new ArrayList<>();
        if (pivot.has(Args.UNPIVOT)) {
            List<Expression> nameColumns = new ArrayList<>();
            for (Expression field : pivot.exprs(Args.FIELDS)) {
                if (!field.is(ExprType.IN)) {
                    continue;
                }
                if (field.getThis() != null && field.getThis().is(ExprType.IDENTIFIER)) {
                    nameColumns.add(field.getThis());
                }
                for (Expression e : field.getExpressions()) {
                    for (Expression c : e.findAll(ExprType.COLUMN)) {
                        excluded.add(c.outputName());
                    }
                }
            }
            List<Expression> valueColumns = new ArrayList<>();
            for (Expression e : pivot.getExpressions()) {
                for (Expression ident : e.is(ExprType.TUPLE) ? e.getExpressions() : List.of(e)) {
                    if (ident.is(ExprType.IDENTIFIER)) {
                        valueColumns.add(ident);
                    }
                }
            }
            // T-SQL emits the value column(s) ahead of the name column, everyone else emits them after it
            List<Expression> ordered = new ArrayList<>();
            if (pivot.has(Args.VALUE_COLUMNS_FIRST)) {
                ordered.addAll(valueColumns);
                ordered.addAll(nameColumns);
            } else {
                ordered.addAll(nameColumns);
                ordered.addAll(valueColumns);
            }
            for (Expression i : ordered) {
                outputs.add(i.name());
            }
        } else {
            for (Expression c : pivot.findAll(ExprType.COLUMN)) {
                excluded.add(c.outputName());
            }
            for (Expression c : pivot.exprs(Args.COLUMNS)) {
                outputs.add(c.outputName());
            }
            if (outputs.isEmpty()) {
                for (Expression c : pivot.getExpressions()) {
                    outputs.add(c.aliasOrName());
                }
            }
        }

        Map<String, String> out = new LinkedHashMap<>();
        if (excluded.isEmpty() || outputs.isEmpty()) {
            return out;
        }

        List<String> preRename = new ArrayList<>();
        for (String c : prePivotColumns) {
            if (!excluded.contains(c)) {
                preRename.add(c);
            }
        }
        preRename.addAll(outputs);

        Expression alias = pivot.expr(Args.ALIAS);
        List<Expression> renames = alias != null ? alias.exprs(Args.COLUMNS) : List.of();

        // `PIVOT(...) AS alias(c1, c2, ...)` renames the operator's output columns
        // positionally from the front (DuckDB, Snowflake): the user's names cover
        // the leading N output columns, remaining columns keep their auto names.
        List<String> postRename;
        if (!renames.isEmpty()) {
            postRename = new ArrayList<>();
            for (Expression r : renames) {
                postRename.add(r.name());
            }
            if (postRename.size() < preRename.size()) {
                postRename.addAll(preRename.subList(postRename.size(), preRename.size()));
            }
        } else {
            postRename = preRename;
        }

        for (int i = 0; i < Math.min(postRename.size(), preRename.size()); i++) {
            out.put(postRename.get(i), preRename.get(i));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Annotators

    private static boolean isDateUnit(Expression unit) {
        return unit != null && DATE_UNITS.contains(unit.name().toLowerCase(Locale.ROOT));
    }

    private static Object coerceDateLiteral(Expression l, Expression unit) {
        String dateText = l.name();
        boolean isIsoDate = PyDates.isIsoDate(dateText);

        if (isIsoDate && isDateUnit(unit)) {
            return DType.DATE;
        }

        // An ISO date is also an ISO datetime, but not vice versa
        if (isIsoDate || PyDates.isIsoDatetime(dateText)) {
            return DType.DATETIME;
        }

        return DType.UNKNOWN;
    }

    private static Object coerceDate(Expression l, Expression unit) {
        if (isType(l, DType.DATE) && !isDateUnit(unit)) {
            return DType.DATETIME;
        }
        return l.dataType() != null ? l.dataType() : DType.UNKNOWN;
    }

    private static final Object NO_COERCION = new Object();

    /** {@code BINARY_COERCIONS[(left_type, right_type)](left, right)}, or NO_COERCION if absent. */
    private static Object binaryCoercion(Object leftType, Object rightType, Expression left, Expression right) {
        if (!(leftType instanceof DType l) || !(rightType instanceof DType r)) {
            return NO_COERCION;
        }
        if (DataTypes.TEXT_TYPES.contains(l) && r == DType.INTERVAL) {
            return coerceDateLiteral(left, right.expr(Args.UNIT));
        }
        if (l == DType.INTERVAL && DataTypes.TEXT_TYPES.contains(r)) {
            return coerceDateLiteral(right, left.expr(Args.UNIT));
        }
        // text + numeric will yield the numeric type to match most dialects' semantics. (sqlglot's
        // `l.type in NUMERIC_TYPES` compares a DataType against DType members and is always false,
        // so the lambda always picks the numeric side.)
        if (DataTypes.TEXT_TYPES.contains(l) && DataTypes.NUMERIC_TYPES.contains(r)) {
            return right.dataType();
        }
        if (DataTypes.NUMERIC_TYPES.contains(l) && DataTypes.TEXT_TYPES.contains(r)) {
            return left.dataType();
        }
        if (l == DType.DATE && r == DType.INTERVAL) {
            return coerceDate(left, right.expr(Args.UNIT));
        }
        if (l == DType.INTERVAL && r == DType.DATE) {
            return coerceDate(right, left.expr(Args.UNIT));
        }
        return NO_COERCION;
    }

    Expression annotateBinary(Expression expression) {
        Expression left = expression.expr(Args.THIS);
        Expression right = expression.expr(Args.EXPRESSION);
        if (left == null || right == null) {
            String expressionSql;
            try {
                expressionSql = expression.sql(dialect);
            } catch (RuntimeException ex) {
                expressionSql = expression.toString();
            }
            LOGGER.warning("Failed to annotate badly formed binary expression: " + expressionSql);
            setType(expression, null);
            return expression;
        }

        Object leftType = typeThis(left);
        Object rightType = typeThis(right);

        Object coerced;
        if (expression.is(ExprType.CONNECTOR, ExprType.PREDICATE)) {
            setType(expression, DType.BOOLEAN);
        } else if ((coerced = binaryCoercion(leftType, rightType, left, right)) != NO_COERCION) {
            setType(expression, coerced);
        } else {
            annotateByArgs(expression, false, false, left, right);
        }

        if (expression.is(ExprType.IS) || (nonnull(left) && nonnull(right))) {
            expression.meta().put("nonnull", Boolean.TRUE);
        }

        return expression;
    }

    Expression annotateUnary(Expression expression) {
        Expression thisExpr = expression.getThis();
        if (expression.is(ExprType.NOT)) {
            setType(expression, DType.BOOLEAN);
        } else {
            setType(expression, thisExpr != null ? thisExpr.dataType() : null);
        }

        if (nonnull(thisExpr)) {
            expression.meta().put("nonnull", Boolean.TRUE);
        }

        return expression;
    }

    Expression annotateLiteral(Expression expression) {
        if (expression.isString()) {
            setType(expression, DType.VARCHAR);
        } else if (expression.isInt()) {
            setType(expression, DType.INT);
        } else {
            setType(expression, DType.DOUBLE);
        }

        expression.meta().put("nonnull", Boolean.TRUE);

        return expression;
    }

    /**
     * {@code _annotate_by_args(expression, *args, promote=..., array=...)}: each arg is an {@link
     * io.github.offbyjuan.jglot.expressions.Arg} (looked up on {@code expression}), an expression, a
     * list of expressions, or null.
     */
    Expression annotateByArgs(Expression expression, boolean promote, boolean array, Object... args) {
        Object literalType = null;
        Object nonLiteralType = null;
        Expression nestedType = null;

        for (Object arg : args) {
            Object expressions = arg instanceof io.github.offbyjuan.jglot.expressions.Arg a ? expression.get(a) : arg;

            for (Object o : Py.ensureList(expressions)) {
                if (!(o instanceof Expression expr)) {
                    continue;
                }
                Expression exprType = expr.dataType();

                if (exprType == null || DataTypes.isType(exprType, false, DType.UNKNOWN)) {
                    setType(expression, DType.UNKNOWN);
                    return expression;
                }

                if (nestedType != null) {
                    continue;
                }

                // Stop coercing at the first nested data type found
                if (exprType.has(Args.NESTED)) {
                    nestedType = exprType;
                } else if (expr.type() == ExprType.LITERAL) {
                    literalType = maybeCoerce(literalType != null ? literalType : exprType, exprType);
                } else {
                    nonLiteralType = maybeCoerce(nonLiteralType != null ? nonLiteralType : exprType, exprType);
                }
            }
        }

        Object resultType = null;

        if (nestedType != null) {
            resultType = nestedType;
        } else if (literalType != null && nonLiteralType != null) {
            if (dd.PRIORITIZE_NON_LITERAL_TYPES) {
                Object literalThisType = literalType instanceof Expression lt ? lt.thisValue() : literalType;
                Object nonLiteralThisType = nonLiteralType instanceof Expression nt ? nt.thisValue() : nonLiteralType;
                if ((DataTypes.INTEGER_TYPES.contains(literalThisType) && DataTypes.INTEGER_TYPES.contains(nonLiteralThisType))
                        || (DataTypes.REAL_TYPES.contains(literalThisType) && DataTypes.REAL_TYPES.contains(nonLiteralThisType))) {
                    resultType = nonLiteralType;
                }
            }

            if (resultType == null) {
                resultType = maybeCoerce(nonLiteralType, literalType);
            }
        } else {
            resultType = literalType != null ? literalType : nonLiteralType != null ? nonLiteralType : DType.UNKNOWN;
        }

        setType(expression, resultType);

        if (promote) {
            Object thisType = resultType instanceof Expression rt ? rt.thisValue() : resultType;
            if (DataTypes.INTEGER_TYPES.contains(thisType)) {
                setType(expression, DType.BIGINT);
            } else if (DataTypes.FLOAT_TYPES.contains(thisType)) {
                setType(expression, DType.DOUBLE);
            }
        }

        if (array) {
            setType(expression, Expression.of(ExprType.DATA_TYPE,
                    Args.THIS, DType.ARRAY, Args.EXPRESSIONS, listOf(expression.dataType()), Args.NESTED, Boolean.TRUE));
        }

        return expression;
    }

    /** A mutable list that may hold a null entry (Python lists can hold None). */
    static List<Object> listOf(Object... items) {
        List<Object> out = new ArrayList<>(items.length);
        Collections.addAll(out, items);
        return out;
    }

    Expression annotateTimeunit(Expression expression) {
        Object thisType = typeThis(expression.getThis());
        Object datatype;
        if (DataTypes.TEXT_TYPES.contains(thisType)) {
            datatype = coerceDateLiteral(expression.getThis(), expression.unit());
        } else if (DataTypes.TEMPORAL_TYPES.contains(thisType)) {
            datatype = coerceDate(expression.getThis(), expression.unit());
        } else {
            datatype = DType.UNKNOWN;
        }

        setType(expression, datatype);
        return expression;
    }

    Expression annotateBracket(Expression expression) {
        Expression bracketArg = expression.getExpressions().get(0);
        Expression thisExpr = expression.getThis();
        Expression thisType = thisExpr.dataType();

        if (bracketArg.is(ExprType.SLICE)) {
            setType(expression, thisType);
        } else if (thisType != null && DataTypes.isType(thisType, false, DType.ARRAY)) {
            setType(expression, Py.seqGet(thisType.getExpressions(), 0));
        } else if (thisExpr.is(ExprType.MAP, ExprType.VAR_MAP) && mapKeys(thisExpr).contains(bracketArg)) {
            int index = mapKeys(thisExpr).indexOf(bracketArg);
            Expression value = Py.seqGet(mapValues(thisExpr), index);
            setType(expression, value != null ? value.dataType() : null);
        } else {
            setType(expression, DType.UNKNOWN);
        }

        return expression;
    }

    private static List<Expression> mapKeys(Expression map) {
        Expression keys = map.expr(Args.KEYS);
        return keys != null ? keys.getExpressions() : List.of();
    }

    private static List<Expression> mapValues(Expression map) {
        Expression values = map.expr(Args.VALUES);
        return values != null ? values.getExpressions() : List.of();
    }

    Expression annotateDiv(Expression expression) {
        Object leftType = typeThis(expression.expr(Args.THIS));
        Object rightType = typeThis(expression.expr(Args.EXPRESSION));

        if (expression.has(Args.TYPED)
                && DataTypes.INTEGER_TYPES.contains(leftType)
                && DataTypes.INTEGER_TYPES.contains(rightType)) {
            setType(expression, DType.BIGINT);
        } else {
            setType(expression, maybeCoerce(leftType, rightType));
            Expression t = expression.dataType();
            if (t != null && !DataTypes.REAL_TYPES.contains(t.thisValue())) {
                setType(expression, maybeCoerce(t, DType.DOUBLE));
            }
        }

        return expression;
    }

    Expression annotateDot(Expression expression) {
        setType(expression, null);

        // Propagate type from qualified UDF calls (e.g., db.my_udf(...))
        Expression right = expression.getExpression();
        if (right != null && right.is(ExprType.ANONYMOUS)) {
            setType(expression, right.dataType());
            return expression;
        }

        Expression thisType = expression.getThis() != null ? expression.getThis().dataType() : null;

        if (thisType != null && DataTypes.isType(thisType, false, DType.STRUCT)) {
            String name = right != null ? right.name() : "";
            for (Expression e : thisType.getExpressions()) {
                if (e.name().equals(name)) {
                    setType(expression, e.expr(Args.KIND));
                    break;
                }
            }
        }

        return expression;
    }

    Expression annotateExplode(Expression expression) {
        Expression inputType = expression.getThis() != null ? expression.getThis().dataType() : null;
        if (inputType != null && DataTypes.isType(inputType, false, DType.ARRAY)) {
            setType(expression, Py.seqGet(inputType.getExpressions(), 0));
        } else {
            setType(expression, null);
        }
        return expression;
    }

    Expression annotateUnnest(Expression expression) {
        Expression child = Py.seqGet(expression.getExpressions(), 0);

        Expression exprType;
        if (child != null && isType(child, DType.ARRAY)) {
            exprType = Py.seqGet(child.dataType().getExpressions(), 0);
        } else {
            exprType = null;
        }

        setType(expression, exprType);
        return expression;
    }

    Expression annotateSubquery(Expression expression) {
        // For scalar subqueries (subqueries with a single projection), infer the type
        // from that single projection. This allows type propagation in cases like:
        // SELECT (SELECT 1 AS c) AS c
        Expression query = expression.unnest();

        if (query.is(ExprType.QUERY)) {
            List<Expression> selects = query.selects();
            if (selects.size() == 1) {
                setType(expression, selects.get(0).dataType());
                return expression;
            }
        }

        setType(expression, DType.UNKNOWN);
        return expression;
    }

    private static final Object NONE = new Object();

    /** Returns a ColumnDef / DataType for the struct field, or {@code NONE} for Python's None. */
    private Object annotateStructValue(Expression expression) {
        // Case: STRUCT(key AS value)
        Expression thisExpr = null;
        Expression kind = expression.dataType();

        Expression alias = expression.expr(Args.ALIAS);
        if (alias != null) {
            thisExpr = alias.copy();
        } else if (expression.getExpression() != null) {
            // Case: STRUCT(key = value) or STRUCT(key := value)
            thisExpr = expression.getThis() != null ? expression.getThis().copy() : null;
            kind = expression.getExpression().dataType();
        } else if (expression.type() == ExprType.COLUMN) {
            // Case: STRUCT(c)
            thisExpr = expression.getThis() != null ? expression.getThis().copy() : null;
        }

        if (kind != null && DataTypes.isType(kind, false, DType.UNKNOWN)) {
            return NONE;
        }

        if (thisExpr != null) {
            return Expression.of(ExprType.COLUMN_DEF, Args.THIS, thisExpr, Args.KIND, kind);
        }

        return kind != null ? kind : NONE;
    }

    Expression annotateStruct(Expression expression) {
        List<Object> expressions = new ArrayList<>();
        for (Expression expr : expression.getExpressions()) {
            Object structFieldType = annotateStructValue(expr);
            if (structFieldType == NONE) {
                setType(expression, null);
                return expression;
            }

            expressions.add(structFieldType);
        }

        setType(expression, Expression.of(ExprType.DATA_TYPE,
                Args.THIS, DType.STRUCT, Args.EXPRESSIONS, expressions, Args.NESTED, Boolean.TRUE));
        return expression;
    }

    Expression annotateMap(Expression expression) {
        Object keys = expression.get(Args.KEYS);
        Object values = expression.get(Args.VALUES);

        Expression mapType = Expression.of(ExprType.DATA_TYPE, Args.THIS, DType.MAP);
        if (keys instanceof Expression k && k.type() == ExprType.ARRAY
                && values instanceof Expression v && v.type() == ExprType.ARRAY) {
            Expression keyType = k.dataType() != null ? Py.seqGet(k.dataType().getExpressions(), 0) : null;
            Expression valueType = v.dataType() != null ? Py.seqGet(v.dataType().getExpressions(), 0) : null;

            // sqlglot compares against DType.UNKNOWN, which only a missing element type equals
            if (keyType != null && valueType != null) {
                mapType.set(Args.EXPRESSIONS, List.of(keyType, valueType));
                mapType.set(Args.NESTED, Boolean.TRUE);
            }
        }

        setType(expression, mapType);
        return expression;
    }

    Expression annotateToMap(Expression expression) {
        Expression mapType = Expression.of(ExprType.DATA_TYPE, Args.THIS, DType.MAP);
        Expression arg = expression.getThis();
        if (isType(arg, DType.STRUCT)) {
            for (Expression coldef : arg.dataType().getExpressions()) {
                Object kind = coldef.get(Args.KIND);
                // sqlglot compares `kind != DType.UNKNOWN`, which is always true for a DataType/None
                mapType.set(Args.EXPRESSIONS, listOf(DataTypes.of(DType.VARCHAR), kind));
                mapType.set(Args.NESTED, Boolean.TRUE);
                break;
            }
        }

        setType(expression, mapType);
        return expression;
    }

    Expression annotateExtract(Expression expression) {
        String part = expression.name();
        if (part.equals("TIME")) {
            setType(expression, DType.TIME);
        } else if (part.equals("DATE")) {
            setType(expression, DType.DATE);
        } else if (BIGINT_EXTRACT_DATE_PARTS.contains(part)) {
            setType(expression, DType.BIGINT);
        } else {
            setType(expression, DType.INT);
        }
        return expression;
    }

    Expression annotateWithinGroup(Expression expression) {
        Expression thisExpr = expression.getThis();
        if (thisExpr != null && thisExpr.is(ExprType.PERCENTILE_DISC)) {
            Expression order = expression.expr(Args.EXPRESSION);
            List<Expression> orderExpressions = order != null ? order.getExpressions() : List.of();
            Object sortType = !orderExpressions.isEmpty()
                    ? orderExpressions.get(0).getThis().dataType()
                    : DType.UNKNOWN;
            setType(expression, sortType);
            return expression;
        }

        return annotateByArgs(expression, false, false, Args.THIS);
    }

    Expression annotateByArrayElement(Expression expression) {
        Expression arrayArg = expression.getThis();
        Expression arrayType = arrayArg != null ? arrayArg.dataType() : null;
        if (arrayType != null && DataTypes.isType(arrayType, false, DType.ARRAY)) {
            Expression elementType = Py.seqGet(arrayType.getExpressions(), 0);
            setType(expression, elementType != null ? elementType : DType.UNKNOWN);
        } else {
            setType(expression, DType.UNKNOWN);
        }

        return expression;
    }
}
