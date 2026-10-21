package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.bigquery.BigQuery;
import io.github.offbyjuan.jglot.dialects.clickhouse.ClickHouse;
import io.github.offbyjuan.jglot.dialects.hive.Hive;
import io.github.offbyjuan.jglot.dialects.postgres.Postgres;
import io.github.offbyjuan.jglot.dialects.presto.Presto;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.util.Py;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * sqlglot's {@code sqlglot.typing} modules: the per-expression type metadata ({@code
 * EXPRESSION_METADATA}) of the base dialect and of postgres, presto/trino, bigquery, clickhouse and
 * hive, plus the dialects' {@code COERCES_TO} tables. All tables are immutable and built once.
 *
 * <p>The tables were generated from sqlglot 30.23.0 (entries are keyed by exact expression type,
 * like sqlglot's {@code EXPRESSION_METADATA.get(expr.__class__)}); dialect tables are the base
 * table plus the entries the dialect's typing module overrides.
 */
final class TypeMetadata {
    private TypeMetadata() {}

    /** An {@code "annotator"} entry: {@code annotator(self, expression)}. */
    @FunctionalInterface
    interface Annotator {
        void annotate(TypeAnnotator self, Expression e);
    }

    /**
     * A metadata entry: either an annotator, or a {@code "returns"} type (a DType, or a factory for a
     * DATA_TYPE node; a fresh node is built per use so that annotated trees never share state).
     */
    record Spec(Annotator annotator, DType returnsType, Supplier<Expression> returnsDataType) {
        boolean hasReturns() {
            return returnsType != null || returnsDataType != null;
        }

        Object returns() {
            return returnsType != null ? returnsType : returnsDataType.get();
        }
    }

    private static void a(Map<ExprType, Spec> m, ExprType t, Annotator annotator) {
        m.put(t, new Spec(annotator, null, null));
    }

    private static void r(Map<ExprType, Spec> m, ExprType t, DType returns) {
        m.put(t, new Spec(null, returns, null));
    }

    private static void t(Map<ExprType, Spec> m, ExprType t, Supplier<Expression> returns) {
        m.put(t, new Spec(null, null, returns));
    }

    // ------------------------------------------------------------------------------------------
    // DataType builders for non-trivial types

    /** {@code DataType(this=dtype, nested=False)}, as built by DataType.build for nested elements. */
    private static Expression flat(DType dtype) {
        return Expression.of(ExprType.DATA_TYPE, Args.THIS, dtype, Args.NESTED, Boolean.FALSE);
    }

    private static Expression nested(DType dtype, Expression... elements) {
        return Expression.of(ExprType.DATA_TYPE, Args.THIS, dtype, Args.EXPRESSIONS, List.of(elements),
                Args.NESTED, Boolean.TRUE);
    }

    /** {@code DataType.from_str("ARRAY<STRING>")}. */
    static Expression arrayOfText() {
        return nested(DType.ARRAY, flat(DType.TEXT));
    }

    /** {@code DataType.from_str("MAP<STRING, STRING>")}. */
    static Expression mapOfTextText() {
        return nested(DType.MAP, flat(DType.TEXT), flat(DType.TEXT));
    }

    // ------------------------------------------------------------------------------------------
    // Annotators shared by the typing modules (sqlglot/typing/__init__.py)

    /**
     * {@code annotate_by_numeric_arg}: math functions (e.g. FLOOR) that preserve numeric and unknown
     * types, and return INT otherwise.
     */
    static final Annotator BY_NUMERIC_ARG = (self, e) -> {
        Expression thisExpr = e.getThis();
        self.setType(e, isNumericOrUnknown(thisExpr) ? thisExpr.dataType() : DType.INT);
    };

    private static final Set<DType> NUMERIC_OR_UNKNOWN;

    static {
        EnumSet<DType> s = EnumSet.copyOf(DataTypes.NUMERIC_TYPES);
        s.add(DType.UNKNOWN);
        NUMERIC_OR_UNKNOWN = Collections.unmodifiableSet(s);
    }

    private static boolean isNumericOrUnknown(Expression e) {
        return TypeAnnotator.isType(e, NUMERIC_OR_UNKNOWN);
    }

    private static final Set<DType> INTEGER_OR_TEXT;

    static {
        EnumSet<DType> s = EnumSet.copyOf(DataTypes.INTEGER_TYPES);
        s.addAll(DataTypes.TEXT_TYPES);
        INTEGER_OR_TEXT = Collections.unmodifiableSet(s);
    }

    /**
     * {@code annotate_int_as_double}: math functions that return DOUBLE for integers and strings, and
     * the input type otherwise.
     */
    static final Annotator INT_AS_DOUBLE = (self, e) -> {
        Expression thisExpr = e.getThis();
        self.setType(e, TypeAnnotator.isType(thisExpr, INTEGER_OR_TEXT) ? DType.DOUBLE : thisExpr.dataType());
    };

    static final Annotator BINARY = (self, e) -> self.annotateBinary(e);
    static final Annotator UNARY = (self, e) -> self.annotateUnary(e);
    static final Annotator BY_THIS = (self, e) -> self.annotateByArgs(e, false, false, Args.THIS);
    static final Annotator BY_THIS_EXPRESSIONS = (self, e) -> self.annotateByArgs(e, false, false, Args.THIS, Args.EXPRESSIONS);
    static final Annotator BY_THIS_EXPRESSION = (self, e) -> self.annotateByArgs(e, false, false, Args.THIS, Args.EXPRESSION);
    static final Annotator BY_THIS_DEFAULT = (self, e) -> self.annotateByArgs(e, false, false, Args.THIS, Args.DEFAULT);
    static final Annotator BY_EXPRESSIONS = (self, e) -> self.annotateByArgs(e, false, false, Args.EXPRESSIONS);
    static final Annotator BY_EXPRESSION = (self, e) -> self.annotateByArgs(e, false, false, Args.EXPRESSION);
    static final Annotator BY_QUANTILE = (self, e) -> self.annotateByArgs(e, false, false, Args.QUANTILE);
    static final Annotator IF = (self, e) -> self.annotateByArgs(e, false, false, Args.TRUE, Args.FALSE);
    static final Annotator IF_PROMOTE = (self, e) -> self.annotateByArgs(e, true, false, Args.TRUE, Args.FALSE);
    static final Annotator PROMOTE_THIS_EXPRESSIONS = (self, e) -> self.annotateByArgs(e, true, false, Args.THIS, Args.EXPRESSIONS);
    static final Annotator ARRAY = (self, e) -> self.annotateByArgs(e, false, true, Args.EXPRESSIONS);
    static final Annotator ARRAY_BY_THIS = (self, e) -> self.annotateByArgs(e, false, true, Args.THIS);
    static final Annotator GENERATE_SERIES = (self, e) -> self.annotateByArgs(e, false, true, Args.START, Args.END, Args.STEP);
    static final Annotator ARRAY_ELEMENT = (self, e) -> self.annotateByArrayElement(e);
    // schema.get_udf_type(e) is UNKNOWN for jglot's empty schema
    static final Annotator ANONYMOUS = (self, e) -> self.setType(e, DType.UNKNOWN);
    static final Annotator TIMEUNIT = (self, e) -> self.annotateTimeunit(e);
    // `to` is stored as a copy so TypeAnnotator.rawType can tell an annotated cast from an unannotated one
    static final Annotator CAST = (self, e) -> {
        Expression to = e.expr(Args.TO);
        self.setType(e, to != null ? to.copy() : null);
    };
    static final Annotator MAP = (self, e) -> self.annotateMap(e);
    static final Annotator BRACKET = (self, e) -> self.annotateBracket(e);
    static final Annotator CASE = (self, e) -> {
        List<Expression> ifs = e.exprs(Args.IFS);
        Object[] args = new Object[ifs.size() + 1];
        for (int i = 0; i < ifs.size(); i++) {
            args[i] = ifs.get(i).get(Args.TRUE);
        }
        args[ifs.size()] = Args.DEFAULT;
        self.annotateByArgs(e, false, false, args);
    };
    static final Annotator BIG_INT = (self, e) -> self.setType(e, e.has(Args.BIG_INT) ? DType.BIGINT : DType.INT);
    static final Annotator DATA_TYPE = (self, e) -> { };
    static final Annotator DIV = (self, e) -> self.annotateDiv(e);
    static final Annotator DOT = (self, e) -> self.annotateDot(e);
    static final Annotator EXPLODE = (self, e) -> self.annotateExplode(e);
    static final Annotator EXTRACT = (self, e) -> self.annotateExtract(e);
    static final Annotator HEX_STRING = (self, e) -> self.setType(e, e.has(Args.IS_INTEGER) ? DType.BIGINT : DType.BINARY);
    static final Annotator GENERATE_DATE_ARRAY = (self, e) -> self.setType(e, nested(DType.ARRAY, flat(DType.DATE)));
    static final Annotator GENERATE_TIMESTAMP_ARRAY = (self, e) -> self.setType(e, nested(DType.ARRAY, flat(DType.TIMESTAMP)));
    static final Annotator LITERAL = (self, e) -> self.annotateLiteral(e);
    static final Annotator STRUCT = (self, e) -> self.annotateStruct(e);
    static final Annotator TIMESTAMP = (self, e) -> self.setType(e, e.has(Args.WITH_TZ) ? DType.TIMESTAMPTZ : DType.TIMESTAMP);
    static final Annotator TO_MAP = (self, e) -> self.annotateToMap(e);
    static final Annotator UNNEST = (self, e) -> self.annotateUnnest(e);
    static final Annotator WITHIN_GROUP = (self, e) -> self.annotateWithinGroup(e);
    static final Annotator SUBQUERY = (self, e) -> self.annotateSubquery(e);

    // ------------------------------------------------------------------------------------------
    // sqlglot/typing/postgres.py

    static final Annotator PG_MATH_FUNCTION = (self, e) -> {
        if (TypeAnnotator.isType(e.getThis(), DType.FLOAT)) {
            self.setType(e, DType.DOUBLE);
            return;
        }
        INT_AS_DOUBLE.annotate(self, e);
    };

    // ------------------------------------------------------------------------------------------
    // sqlglot/typing/presto.py

    static final Annotator PRESTO_RAND = (self, e) -> {
        if (e.getThis() != null) {
            self.annotateByArgs(e, false, false, Args.THIS);
        } else {
            self.setType(e, DType.DOUBLE);
        }
    };

    // ------------------------------------------------------------------------------------------
    // sqlglot/typing/clickhouse.py

    /** {@code DataType.build("FixedString(16)", dialect="clickhouse")}. */
    static final Annotator CH_FIXED_STRING_16 = (self, e) -> self.setType(e, Expression.of(ExprType.DATA_TYPE,
            Args.THIS, DType.FIXEDSTRING,
            Args.EXPRESSIONS, List.of(Expression.of(ExprType.DATA_TYPE_PARAM, Args.THIS, Exp.numberLiteral("16"))),
            Args.NESTED, Boolean.FALSE, Args.NULLABLE, Boolean.FALSE));

    /** {@code DataType.build("Float64", dialect="clickhouse")}. */
    static final Annotator CH_FLOAT64 = (self, e) -> self.setType(e, Expression.of(ExprType.DATA_TYPE,
            Args.THIS, DType.DOUBLE, Args.NESTED, Boolean.FALSE, Args.NULLABLE, Boolean.FALSE));

    // ------------------------------------------------------------------------------------------
    // sqlglot/typing/hive.py

    static final Annotator HIVE_FLOOR_CEIL = (self, e) -> {
        // Decimal results and numeric literals need precision inference that we don't model here.
        if (e.get(Args.DECIMALS) != null
                || TypeAnnotator.isType(e.getThis(), DType.DECIMAL, DType.UNKNOWN)
                || e.getThis().unnest().isNumber()) {
            self.setType(e, DType.UNKNOWN);
            return;
        }
        self.setType(e, DType.BIGINT);
    };

    // ------------------------------------------------------------------------------------------
    // sqlglot/typing/bigquery.py

    /**
     * {@code _annotate_date_func}: DATE_ADD / DATE_SUB / *_TRUNC return their first arg's type. For a
     * string literal first argument, BigQuery implicitly casts it to the function's own temporal type.
     */
    static final Annotator BQ_DATE_FUNC = (self, e) -> {
        Expression thisExpr = e.getThis();

        // BigQuery rejects expressions like DATE_ADD(c, ...); it requires the first argument to be a literal
        if (thisExpr != null && thisExpr.type() == ExprType.LITERAL && thisExpr.isString()) {
            DType literalType = switch (e.type()) {
                case DATE_ADD, DATE_SUB, DATE_TRUNC -> DType.DATE;
                case DATETIME_TRUNC -> DType.DATETIME;
                case TIMESTAMP_TRUNC -> DType.TIMESTAMPTZ;
                default -> throw new IllegalStateException(e.type().toString());
            };
            self.setType(e, literalType);
            return;
        }

        self.annotateByArgs(e, false, false, Args.THIS);
    };

    /** {@code _annotate_by_args_with_coerce}. */
    static final Annotator BQ_BY_ARGS_WITH_COERCE = (self, e) -> self.setType(e, self.maybeCoerce(
            e.getThis() != null ? e.getThis().dataType() : null,
            e.getExpression() != null ? e.getExpression().dataType() : null));

    /** {@code _annotate_safe_divide}. */
    static final Annotator BQ_SAFE_DIVIDE = (self, e) -> {
        if (TypeAnnotator.isType(e.getThis(), DataTypes.INTEGER_TYPES)
                && TypeAnnotator.isType(e.getExpression(), DataTypes.INTEGER_TYPES)) {
            self.setType(e, DType.DOUBLE);
            return;
        }
        BQ_BY_ARGS_WITH_COERCE.annotate(self, e);
    };

    /** {@code _annotate_by_args_approx_top}. */
    static final Annotator BQ_APPROX_TOP = (self, e) -> {
        Expression structType = Expression.of(ExprType.DATA_TYPE,
                Args.THIS, DType.STRUCT,
                Args.EXPRESSIONS, TypeAnnotator.listOf(e.getThis().dataType(), DataTypes.of(DType.BIGINT)),
                Args.NESTED, Boolean.TRUE);
        self.setType(e, nested(DType.ARRAY, structType));
    };

    /** {@code _annotate_concat}. */
    static final Annotator BQ_CONCAT = (self, e) -> {
        Expression annotated = self.annotateByArgs(e, false, false, Args.EXPRESSIONS);

        // Args must be BYTES or types that can be cast to STRING, return type is either BYTES or STRING
        // https://cloud.google.com/bigquery/docs/reference/standard-sql/string_functions#concat
        if (!TypeAnnotator.isType(annotated, DType.BINARY, DType.UNKNOWN)) {
            self.setType(annotated, DType.VARCHAR);
        }
    };

    /** {@code _annotate_array}. */
    static final Annotator BQ_ARRAY = (self, e) -> {
        List<Expression> arrayArgs = e.getExpressions();

        // BigQuery behaves as follows:
        //
        // SELECT t, TYPEOF(t) FROM (SELECT 'foo') AS t            -- foo, STRUCT<STRING>
        // SELECT ARRAY(SELECT 'foo'), TYPEOF(ARRAY(SELECT 'foo')) -- foo, ARRAY<STRING>
        // ARRAY(SELECT ... UNION ALL SELECT ...)                  -- ARRAY<type from coerced projections>
        // ARRAY(SELECT AS STRUCT 1 AS a, 'b' AS b)                -- ARRAY<STRUCT<INT64, STRING>>
        if (arrayArgs.size() == 1) {
            Expression unnested = arrayArgs.get(0).unnest();
            Object projectionType = null;

            // Handle ARRAY(SELECT ...) - single SELECT query
            if (unnested.type() == ExprType.SELECT) {
                Object queryType = unnested.metaGet("query_type");

                if (queryType instanceof Expression qt && DataTypes.isType(qt, false, DType.STRUCT)) {
                    List<Expression> queryExprs = qt.getExpressions();

                    List<Expression> colDefs = new java.util.ArrayList<>();
                    for (Expression ce : queryExprs) {
                        if (ce.is(ExprType.COLUMN_DEF)) {
                            Expression kind = ce.expr(Args.KIND);
                            if (!(kind != null && DataTypes.isType(kind, false, DType.UNKNOWN))) {
                                colDefs.add(ce);
                            }
                        }
                    }

                    if (colDefs.size() == queryExprs.size()) {
                        if ("STRUCT".equals(unnested.get(Args.KIND))) {
                            // ARRAY(SELECT AS STRUCT ...) -> ARRAY<STRUCT<col1, col2, ...>>
                            projectionType = qt;
                        } else if (colDefs.size() == 1 && colDefs.get(0).expr(Args.KIND) != null) {
                            // ARRAY(SELECT col FROM ...) -> ARRAY<col_type>
                            projectionType = colDefs.get(0).expr(Args.KIND);
                        }
                    }
                }
            } else if (unnested.is(ExprType.SET_OPERATION)) {
                // Handle ARRAY(SELECT ... UNION ALL SELECT ...) - set operations
                Map<String, Object> colTypes = self.getSetopColumnTypes(unnested);
                // For ARRAY constructor, there should only be one projection
                // https://docs.cloud.google.com/bigquery/docs/reference/standard-sql/array_functions#array
                List<Expression> leftSelects = unnested.getThis().selects();
                if (!colTypes.isEmpty() && !leftSelects.isEmpty()) {
                    String firstColName = leftSelects.get(0).aliasOrName();
                    projectionType = colTypes.get(firstColName);
                }
            }

            // If we successfully determine a projection type and it's not UNKNOWN, wrap it in ARRAY
            if (projectionType != null && !(
                    (projectionType instanceof Expression pt && DataTypes.isType(pt, false, DType.UNKNOWN))
                    || projectionType == DType.UNKNOWN)) {
                Expression elementType = projectionType instanceof Expression pt
                        ? pt.copy()
                        : DataTypes.of((DType) projectionType);
                self.setType(e, nested(DType.ARRAY, elementType));
                return;
            }
        }

        self.annotateByArgs(e, false, true, Args.EXPRESSIONS);
    };

    static final Annotator BQ_GENERATE_TIMESTAMP_ARRAY = (self, e) -> self.setType(e, nested(DType.ARRAY, flat(DType.TIMESTAMPTZ)));
    static final Annotator BQ_JSON_FORMAT = (self, e) -> self.setType(e, e.has(Args.TO_JSON) ? DType.JSON : DType.VARCHAR);
    static final Annotator BQ_ARRAY_VARCHAR = (self, e) -> self.setType(e, nested(DType.ARRAY, flat(DType.VARCHAR)));
    static final Annotator BQ_ARRAY_BIGINT = (self, e) -> self.setType(e, nested(DType.ARRAY, flat(DType.BIGINT)));

    // ------------------------------------------------------------------------------------------
    // Per-dialect tables

    private static final Map<ExprType, Spec> BASE;
    private static final Map<ExprType, Spec> POSTGRES;
    private static final Map<ExprType, Spec> PRESTO;
    private static final Map<ExprType, Spec> BIGQUERY;
    private static final Map<ExprType, Spec> CLICKHOUSE;
    private static final Map<ExprType, Spec> HIVE;

    static {
        EnumMap<ExprType, Spec> m = new EnumMap<>(ExprType.class);
        base(m);
        BASE = Collections.unmodifiableMap(m);
        POSTGRES = Collections.unmodifiableMap(derive(TypeMetadata::postgres));
        PRESTO = Collections.unmodifiableMap(derive(TypeMetadata::presto));
        BIGQUERY = Collections.unmodifiableMap(derive(TypeMetadata::bigquery));
        CLICKHOUSE = Collections.unmodifiableMap(derive(TypeMetadata::clickhouse));
        HIVE = Collections.unmodifiableMap(derive(TypeMetadata::hive));
    }

    private static EnumMap<ExprType, Spec> derive(java.util.function.Consumer<Map<ExprType, Spec>> overrides) {
        EnumMap<ExprType, Spec> m = new EnumMap<>(BASE);
        overrides.accept(m);
        return m;
    }

    /** The dialect's {@code EXPRESSION_METADATA} (Trino and Starburst inherit Presto's). */
    static Map<ExprType, Spec> forDialect(Dialect dialect) {
        if (dialect instanceof Presto) {
            return PRESTO;
        }
        if (dialect instanceof BigQuery) {
            return BIGQUERY;
        }
        if (dialect instanceof Postgres) {
            return POSTGRES;
        }
        if (dialect instanceof ClickHouse) {
            return CLICKHOUSE;
        }
        if (dialect instanceof Hive) {
            return HIVE;
        }
        return BASE;
    }

    // ------------------------------------------------------------------------------------------
    // COERCES_TO

    private static final Map<DType, Set<DType>> BASE_COERCES_TO;
    private static final Map<DType, Set<DType>> BIGQUERY_COERCES_TO;
    private static final Map<DType, Set<DType>> HIVE_COERCES_TO;

    static {
        // Highest-to-lowest type precedence, as specified in Spark's docs (ANSI):
        // https://spark.apache.org/docs/3.2.0/sql-ref-ansi-compliance.html
        DType[][] precedences = {
            {DType.TEXT, DType.NVARCHAR, DType.VARCHAR, DType.NCHAR, DType.CHAR},
            {DType.DECFLOAT, DType.DOUBLE, DType.FLOAT, DType.BIGDECIMAL, DType.DECIMAL, DType.BIGINT, DType.INT,
                DType.SMALLINT, DType.TINYINT},
            {DType.TIMESTAMPLTZ, DType.TIMESTAMPTZ, DType.TIMESTAMP, DType.DATETIME, DType.DATE},
        };
        EnumMap<DType, EnumSet<DType>> base = new EnumMap<>(DType.class);
        for (DType[] precedence : precedences) {
            EnumSet<DType> coercesTo = EnumSet.noneOf(DType.class);
            for (DType dataType : precedence) {
                base.put(dataType, EnumSet.copyOf(coercesTo));
                coercesTo.add(dataType);
            }
        }

        // sqlglot's BigQuery dialect builds its COERCES_TO with a shallow copy and then mutates the
        // shared sets in place (`COERCES_TO[...] |= ...`), so once sqlglot.dialects.bigquery has been
        // imported (as it is when sqlglot's test suite, and thus jglot's fixtures, run) the base
        // TypeAnnotator.COERCES_TO, and every dialect falling back to it, sees these additions too.
        base.get(DType.DECIMAL).add(DType.BIGDECIMAL);
        base.get(DType.BIGINT).add(DType.BIGDECIMAL);
        base.get(DType.VARCHAR).addAll(List.of(DType.DATE, DType.DATETIME, DType.TIME, DType.TIMESTAMP, DType.TIMESTAMPTZ));
        BASE_COERCES_TO = freeze(base);

        // https://cloud.google.com/bigquery/docs/reference/standard-sql/navigation_functions#percentile_cont
        EnumMap<DType, EnumSet<DType>> bigquery = deepCopy(base);
        bigquery.put(DType.BIGDECIMAL, EnumSet.of(DType.DOUBLE));
        BIGQUERY_COERCES_TO = freeze(bigquery);

        // Support only the non-ANSI mode (default for Hive, Spark2, Spark)
        EnumMap<DType, EnumSet<DType>> hive = deepCopy(base);
        EnumSet<DType> targets = EnumSet.copyOf(DataTypes.NUMERIC_TYPES);
        targets.addAll(DataTypes.TEMPORAL_TYPES);
        targets.add(DType.INTERVAL);
        for (DType targetType : targets) {
            hive.computeIfAbsent(targetType, k -> EnumSet.noneOf(DType.class)).addAll(DataTypes.TEXT_TYPES);
        }
        HIVE_COERCES_TO = freeze(hive);
    }

    private static EnumMap<DType, EnumSet<DType>> deepCopy(EnumMap<DType, EnumSet<DType>> m) {
        EnumMap<DType, EnumSet<DType>> out = new EnumMap<>(DType.class);
        m.forEach((k, v) -> out.put(k, EnumSet.copyOf(v)));
        return out;
    }

    private static Map<DType, Set<DType>> freeze(EnumMap<DType, EnumSet<DType>> m) {
        EnumMap<DType, Set<DType>> out = new EnumMap<>(DType.class);
        m.forEach((k, v) -> out.put(k, Collections.unmodifiableSet(v)));
        return Collections.unmodifiableMap(out);
    }

    /** {@code dialect.COERCES_TO or TypeAnnotator.COERCES_TO}. */
    static Map<DType, Set<DType>> coercesTo(Dialect dialect) {
        if (dialect instanceof BigQuery) {
            return BIGQUERY_COERCES_TO;
        }
        if (dialect instanceof Hive) {
            return HIVE_COERCES_TO;
        }
        return BASE_COERCES_TO;
    }

    // ------------------------------------------------------------------------------------------
    // Generated from sqlglot 30.23.0's Dialect.EXPRESSION_METADATA

    private static void base(Map<ExprType, Spec> m) {
        a(m, ExprType.ABS, BY_THIS);
        r(m, ExprType.ACOS, DType.DOUBLE);
        r(m, ExprType.ACOSH, DType.DOUBLE);
        a(m, ExprType.ADD, BINARY);
        a(m, ExprType.ADJACENT, BINARY);
        a(m, ExprType.ALIAS, UNARY);
        r(m, ExprType.ALL, DType.BOOLEAN);
        a(m, ExprType.AND, BINARY);
        a(m, ExprType.ANONYMOUS, ANONYMOUS);
        r(m, ExprType.ANY, DType.BOOLEAN);
        a(m, ExprType.ANY_VALUE, BY_THIS);
        r(m, ExprType.APPROX_DISTINCT, DType.BIGINT);
        r(m, ExprType.APPROX_QUANTILE, DType.DOUBLE);
        a(m, ExprType.ARRAY, ARRAY);
        a(m, ExprType.ARRAY_AGG, ARRAY_BY_THIS);
        a(m, ExprType.ARRAY_CONCAT, BY_THIS_EXPRESSIONS);
        a(m, ExprType.ARRAY_CONCAT_AGG, BY_THIS);
        a(m, ExprType.ARRAY_CONTAINED_BY, BINARY);
        a(m, ExprType.ARRAY_CONTAINS, BINARY);
        a(m, ExprType.ARRAY_CONTAINS_ALL, BINARY);
        a(m, ExprType.ARRAY_FIRST, ARRAY_ELEMENT);
        a(m, ExprType.ARRAY_LAST, ARRAY_ELEMENT);
        a(m, ExprType.ARRAY_OVERLAPS, BINARY);
        a(m, ExprType.ARRAY_POSITION, BINARY);
        a(m, ExprType.ARRAY_REVERSE, BY_THIS);
        r(m, ExprType.ARRAY_SIZE, DType.BIGINT);
        a(m, ExprType.ARRAY_SLICE, BY_THIS);
        r(m, ExprType.ARRAY_TO_STRING, DType.VARCHAR);
        r(m, ExprType.ASCII, DType.INT);
        r(m, ExprType.ASIN, DType.DOUBLE);
        r(m, ExprType.ASINH, DType.DOUBLE);
        r(m, ExprType.ATAN, DType.DOUBLE);
        r(m, ExprType.ATANH, DType.DOUBLE);
        r(m, ExprType.AVG, DType.DOUBLE);
        r(m, ExprType.BETWEEN, DType.BOOLEAN);
        a(m, ExprType.BINARY, BINARY);
        r(m, ExprType.BIT_LENGTH, DType.INT);
        a(m, ExprType.BITWISE_AND, BINARY);
        a(m, ExprType.BITWISE_LEFT_SHIFT, BINARY);
        a(m, ExprType.BITWISE_NOT, UNARY);
        a(m, ExprType.BITWISE_OR, BINARY);
        a(m, ExprType.BITWISE_RIGHT_SHIFT, BINARY);
        a(m, ExprType.BITWISE_XOR, BINARY);
        r(m, ExprType.BOOLEAN, DType.BOOLEAN);
        a(m, ExprType.BRACKET, BRACKET);
        a(m, ExprType.CASE, CASE);
        a(m, ExprType.CAST, CAST);
        r(m, ExprType.CBRT, DType.DOUBLE);
        a(m, ExprType.CEIL, BY_NUMERIC_ARG);
        r(m, ExprType.CHR, DType.VARCHAR);
        a(m, ExprType.COALESCE, BY_THIS_EXPRESSIONS);
        a(m, ExprType.COLLATE, BINARY);
        r(m, ExprType.CONCAT, DType.VARCHAR);
        r(m, ExprType.CONCAT_WS, DType.VARCHAR);
        a(m, ExprType.CONNECTOR, BINARY);
        r(m, ExprType.CONTAINS, DType.BOOLEAN);
        a(m, ExprType.CORR, BINARY);
        r(m, ExprType.COS, DType.DOUBLE);
        r(m, ExprType.COSH, DType.DOUBLE);
        r(m, ExprType.COT, DType.DOUBLE);
        a(m, ExprType.COUNT, BIG_INT);
        r(m, ExprType.COUNT_IF, DType.BIGINT);
        r(m, ExprType.COVAR_POP, DType.DOUBLE);
        r(m, ExprType.COVAR_SAMP, DType.DOUBLE);
        r(m, ExprType.CUME_DIST, DType.DOUBLE);
        r(m, ExprType.CURRENT_CATALOG, DType.VARCHAR);
        r(m, ExprType.CURRENT_DATE, DType.DATE);
        r(m, ExprType.CURRENT_DATETIME, DType.DATETIME);
        r(m, ExprType.CURRENT_ROLE, DType.VARCHAR);
        r(m, ExprType.CURRENT_SCHEMA, DType.VARCHAR);
        r(m, ExprType.CURRENT_TIME, DType.TIME);
        r(m, ExprType.CURRENT_TIMESTAMP, DType.TIMESTAMP);
        r(m, ExprType.CURRENT_TIMESTAMP_LTZ, DType.TIMESTAMPTZ);
        r(m, ExprType.CURRENT_USER, DType.VARCHAR);
        r(m, ExprType.CURRENT_VERSION, DType.VARCHAR);
        r(m, ExprType.D_PIPE, DType.VARCHAR);
        a(m, ExprType.DATA_TYPE, DATA_TYPE);
        r(m, ExprType.DATE, DType.DATE);
        a(m, ExprType.DATE_ADD, TIMEUNIT);
        a(m, ExprType.DATE_DIFF, BIG_INT);
        r(m, ExprType.DATE_FROM_PARTS, DType.DATE);
        r(m, ExprType.DATE_STR_TO_DATE, DType.DATE);
        a(m, ExprType.DATE_SUB, TIMEUNIT);
        r(m, ExprType.DATE_TO_DATE_STR, DType.VARCHAR);
        r(m, ExprType.DATE_TO_DI, DType.INT);
        a(m, ExprType.DATE_TRUNC, TIMEUNIT);
        r(m, ExprType.DATETIME, DType.DATETIME);
        r(m, ExprType.DATETIME_ADD, DType.DATETIME);
        r(m, ExprType.DATETIME_DIFF, DType.INT);
        r(m, ExprType.DATETIME_SUB, DType.DATETIME);
        r(m, ExprType.DAY, DType.TINYINT);
        r(m, ExprType.DAY_OF_MONTH, DType.INT);
        r(m, ExprType.DAY_OF_WEEK, DType.INT);
        r(m, ExprType.DAY_OF_WEEK_ISO, DType.TINYINT);
        r(m, ExprType.DAY_OF_YEAR, DType.INT);
        r(m, ExprType.DAYNAME, DType.VARCHAR);
        r(m, ExprType.DEGREES, DType.DOUBLE);
        r(m, ExprType.DENSE_RANK, DType.BIGINT);
        r(m, ExprType.DI_TO_DATE, DType.DATE);
        a(m, ExprType.DISTANCE, BINARY);
        a(m, ExprType.DISTANCE_ND, BINARY);
        a(m, ExprType.DISTINCT, BY_EXPRESSIONS);
        a(m, ExprType.DIV, DIV);
        a(m, ExprType.DOT, DOT);
        a(m, ExprType.EQ, BINARY);
        r(m, ExprType.ENDS_WITH, DType.BOOLEAN);
        a(m, ExprType.ESCAPE, BINARY);
        r(m, ExprType.EXISTS, DType.BOOLEAN);
        r(m, ExprType.EXP, DType.DOUBLE);
        a(m, ExprType.EXPLODE, EXPLODE);
        a(m, ExprType.EXTENDS_LEFT, BINARY);
        a(m, ExprType.EXTENDS_RIGHT, BINARY);
        a(m, ExprType.EXTRACT, EXTRACT);
        a(m, ExprType.FILTER, BY_THIS);
        a(m, ExprType.FIRST_VALUE, BY_THIS);
        a(m, ExprType.FLOOR, BY_NUMERIC_ARG);
        r(m, ExprType.FROM_BASE32, DType.BINARY);
        r(m, ExprType.FROM_BASE64, DType.BINARY);
        a(m, ExprType.GT, BINARY);
        a(m, ExprType.GTE, BINARY);
        a(m, ExprType.GENERATE_DATE_ARRAY, GENERATE_DATE_ARRAY);
        a(m, ExprType.GENERATE_SERIES, GENERATE_SERIES);
        a(m, ExprType.GENERATE_TIMESTAMP_ARRAY, GENERATE_TIMESTAMP_ARRAY);
        r(m, ExprType.GETBIT, DType.INT);
        a(m, ExprType.GLOB, BINARY);
        a(m, ExprType.GREATEST, BY_THIS_EXPRESSIONS);
        r(m, ExprType.GROUP_CONCAT, DType.VARCHAR);
        a(m, ExprType.HAVING_MAX, BY_THIS);
        a(m, ExprType.HEX_STRING, HEX_STRING);
        r(m, ExprType.HOUR, DType.INT);
        a(m, ExprType.I_LIKE, BINARY);
        a(m, ExprType.IF, IF);
        a(m, ExprType.IGNORE_NULLS, UNARY);
        r(m, ExprType.IN, DType.BOOLEAN);
        r(m, ExprType.INITCAP, DType.VARCHAR);
        r(m, ExprType.INT64, DType.BIGINT);
        a(m, ExprType.INT_DIV, BINARY);
        r(m, ExprType.INTERVAL, DType.INTERVAL);
        a(m, ExprType.IS, BINARY);
        r(m, ExprType.IS_INF, DType.BOOLEAN);
        r(m, ExprType.IS_NAN, DType.BOOLEAN);
        a(m, ExprType.JSON_ARRAY_CONTAINS, BINARY);
        a(m, ExprType.JSONB_CONTAINS, BINARY);
        a(m, ExprType.JSONB_CONTAINS_ALL_TOP_KEYS, BINARY);
        a(m, ExprType.JSONB_CONTAINS_ANY_TOP_KEYS, BINARY);
        a(m, ExprType.JSONB_CONTAINS_TOP_KEY, BINARY);
        a(m, ExprType.JSONB_DELETE_AT_PATH, BINARY);
        a(m, ExprType.JSONB_EXTRACT, BINARY);
        a(m, ExprType.JSONB_EXTRACT_SCALAR, BINARY);
        a(m, ExprType.JSONB_PATH_EXISTS, BINARY);
        a(m, ExprType.JSON_EXTRACT, BINARY);
        a(m, ExprType.JSON_EXTRACT_SCALAR, BINARY);
        r(m, ExprType.JUSTIFY_DAYS, DType.INTERVAL);
        r(m, ExprType.JUSTIFY_HOURS, DType.INTERVAL);
        r(m, ExprType.JUSTIFY_INTERVAL, DType.INTERVAL);
        r(m, ExprType.KURTOSIS, DType.DOUBLE);
        a(m, ExprType.KWARG, BINARY);
        a(m, ExprType.LT, BINARY);
        a(m, ExprType.LTE, BINARY);
        a(m, ExprType.LAG, BY_THIS_DEFAULT);
        r(m, ExprType.LAST_DAY, DType.DATE);
        a(m, ExprType.LAST_VALUE, BY_THIS);
        a(m, ExprType.LEAD, BY_THIS_DEFAULT);
        a(m, ExprType.LEAST, BY_THIS_EXPRESSIONS);
        r(m, ExprType.LENGTH, DType.INT);
        r(m, ExprType.LEVENSHTEIN, DType.INT);
        a(m, ExprType.LIKE, BINARY);
        a(m, ExprType.LIMIT, BY_THIS);
        a(m, ExprType.LITERAL, LITERAL);
        r(m, ExprType.LN, DType.DOUBLE);
        r(m, ExprType.LOCALTIME, DType.TIME);
        r(m, ExprType.LOG, DType.DOUBLE);
        r(m, ExprType.LOGICAL_AND, DType.BOOLEAN);
        r(m, ExprType.LOGICAL_OR, DType.BOOLEAN);
        r(m, ExprType.LOWER, DType.VARCHAR);
        r(m, ExprType.MD5, DType.VARCHAR);
        r(m, ExprType.MAKE_INTERVAL, DType.INTERVAL);
        a(m, ExprType.MAP, MAP);
        a(m, ExprType.MATCH, BINARY);
        a(m, ExprType.MAX, BY_THIS_EXPRESSIONS);
        a(m, ExprType.MIN, BY_THIS_EXPRESSIONS);
        a(m, ExprType.MOD, BINARY);
        r(m, ExprType.MONTH, DType.TINYINT);
        r(m, ExprType.MONTHNAME, DType.VARCHAR);
        a(m, ExprType.MUL, BINARY);
        a(m, ExprType.NEQ, BINARY);
        a(m, ExprType.NEG, UNARY);
        a(m, ExprType.NESTED_JSON_SELECT, BINARY);
        a(m, ExprType.NOT, UNARY);
        a(m, ExprType.NTH_VALUE, BY_THIS);
        r(m, ExprType.NTILE, DType.BIGINT);
        r(m, ExprType.NULL, DType.NULL);
        a(m, ExprType.NULL_SAFE_EQ, BINARY);
        a(m, ExprType.NULL_SAFE_NEQ, BINARY);
        a(m, ExprType.NULLIF, BY_THIS_EXPRESSION);
        a(m, ExprType.OPERATOR, BINARY);
        a(m, ExprType.OR, BINARY);
        a(m, ExprType.ORDER, BY_THIS);
        a(m, ExprType.OVERLAPS, BINARY);
        a(m, ExprType.PAREN, UNARY);
        r(m, ExprType.PARSE_JSON, DType.JSON);
        r(m, ExprType.PERCENT_RANK, DType.DOUBLE);
        r(m, ExprType.PERCENTILE_CONT, DType.DOUBLE);
        r(m, ExprType.PI, DType.DOUBLE);
        a(m, ExprType.PIVOT_ALIAS, UNARY);
        r(m, ExprType.POW, DType.DOUBLE);
        a(m, ExprType.PROPERTY_EQ, BY_EXPRESSION);
        r(m, ExprType.QUANTILE, DType.DOUBLE);
        r(m, ExprType.QUARTER, DType.INT);
        r(m, ExprType.RADIANS, DType.DOUBLE);
        r(m, ExprType.RAND, DType.DOUBLE);
        r(m, ExprType.RANK, DType.BIGINT);
        r(m, ExprType.RAW_STRING, DType.VARCHAR);
        a(m, ExprType.REGEXP_FULL_MATCH, BINARY);
        a(m, ExprType.REGEXP_I_LIKE, BINARY);
        a(m, ExprType.REGEXP_LIKE, BINARY);
        r(m, ExprType.REPEAT, DType.VARCHAR);
        a(m, ExprType.RESPECT_NULLS, UNARY);
        r(m, ExprType.ROUND, DType.DOUBLE);
        r(m, ExprType.ROW_NUMBER, DType.BIGINT);
        r(m, ExprType.SHA, DType.VARCHAR);
        r(m, ExprType.SHA2, DType.VARCHAR);
        r(m, ExprType.SAFE_DIVIDE, DType.DOUBLE);
        r(m, ExprType.SESSION_USER, DType.VARCHAR);
        r(m, ExprType.SIGN, DType.INT);
        a(m, ExprType.SIMILAR_TO, BINARY);
        r(m, ExprType.SIN, DType.DOUBLE);
        r(m, ExprType.SINH, DType.DOUBLE);
        r(m, ExprType.SKEWNESS, DType.DOUBLE);
        a(m, ExprType.SORT_ARRAY, BY_THIS);
        r(m, ExprType.SPACE, DType.VARCHAR);
        r(m, ExprType.SQRT, DType.DOUBLE);
        r(m, ExprType.STARTS_WITH, DType.BOOLEAN);
        r(m, ExprType.STDDEV, DType.DOUBLE);
        r(m, ExprType.STDDEV_POP, DType.DOUBLE);
        r(m, ExprType.STDDEV_SAMP, DType.DOUBLE);
        r(m, ExprType.STR_POSITION, DType.INT);
        r(m, ExprType.STR_TO_DATE, DType.DATE);
        r(m, ExprType.STR_TO_TIME, DType.TIMESTAMP);
        r(m, ExprType.STRING, DType.VARCHAR);
        a(m, ExprType.STRUCT, STRUCT);
        a(m, ExprType.SUB, BINARY);
        a(m, ExprType.SUBQUERY, SUBQUERY);
        r(m, ExprType.SUBSTRING, DType.VARCHAR);
        a(m, ExprType.SUM, PROMOTE_THIS_EXPRESSIONS);
        r(m, ExprType.TAN, DType.DOUBLE);
        r(m, ExprType.TANH, DType.DOUBLE);
        r(m, ExprType.TIME, DType.TIME);
        r(m, ExprType.TIME_ADD, DType.TIME);
        r(m, ExprType.TIME_DIFF, DType.INT);
        r(m, ExprType.TIME_STR_TO_DATE, DType.DATE);
        r(m, ExprType.TIME_STR_TO_TIME, DType.TIMESTAMP);
        r(m, ExprType.TIME_SUB, DType.TIME);
        r(m, ExprType.TIME_TO_STR, DType.VARCHAR);
        r(m, ExprType.TIME_TO_TIME_STR, DType.VARCHAR);
        a(m, ExprType.TIMESTAMP, TIMESTAMP);
        r(m, ExprType.TIMESTAMP_ADD, DType.TIMESTAMP);
        r(m, ExprType.TIMESTAMP_DIFF, DType.INT);
        r(m, ExprType.TIMESTAMP_LTZ_FROM_PARTS, DType.TIMESTAMPLTZ);
        r(m, ExprType.TIMESTAMP_SUB, DType.TIMESTAMP);
        r(m, ExprType.TIMESTAMP_TZ_FROM_PARTS, DType.TIMESTAMPTZ);
        r(m, ExprType.TO_BASE32, DType.VARCHAR);
        r(m, ExprType.TO_BASE64, DType.VARCHAR);
        r(m, ExprType.TO_DOUBLE, DType.DOUBLE);
        a(m, ExprType.TO_MAP, TO_MAP);
        r(m, ExprType.TRANSLATE, DType.VARCHAR);
        r(m, ExprType.TRIM, DType.VARCHAR);
        a(m, ExprType.TRY_CAST, CAST);
        r(m, ExprType.TS_OR_DI_TO_DI, DType.INT);
        r(m, ExprType.TS_OR_DS_TO_DATE, DType.DATE);
        r(m, ExprType.TS_OR_DS_TO_DATE_STR, DType.VARCHAR);
        r(m, ExprType.TYPEOF, DType.VARCHAR);
        a(m, ExprType.UNARY, UNARY);
        r(m, ExprType.UNICODE, DType.INT);
        r(m, ExprType.UNIX_DATE, DType.INT);
        r(m, ExprType.UNIX_MICROS, DType.BIGINT);
        r(m, ExprType.UNIX_MILLIS, DType.BIGINT);
        r(m, ExprType.UNIX_SECONDS, DType.BIGINT);
        r(m, ExprType.UNIX_TO_STR, DType.VARCHAR);
        r(m, ExprType.UNIX_TO_TIME, DType.TIMESTAMP);
        r(m, ExprType.UNIX_TO_TIME_STR, DType.VARCHAR);
        a(m, ExprType.UNNEST, UNNEST);
        r(m, ExprType.UPPER, DType.VARCHAR);
        a(m, ExprType.VAR_MAP, MAP);
        r(m, ExprType.VARIANCE, DType.DOUBLE);
        r(m, ExprType.VARIANCE_POP, DType.DOUBLE);
        r(m, ExprType.WEEK, DType.TINYINT);
        r(m, ExprType.WEEK_OF_YEAR, DType.TINYINT);
        a(m, ExprType.WINDOW, BY_THIS);
        a(m, ExprType.WITHIN_GROUP, WITHIN_GROUP);
        a(m, ExprType.XOR, BINARY);
        r(m, ExprType.YEAR, DType.TINYINT);
        r(m, ExprType.YEAR_OF_WEEK, DType.TINYINT);
        r(m, ExprType.YEAR_OF_WEEK_ISO, DType.TINYINT);
    }

    private static void postgres(Map<ExprType, Spec> m) {
        a(m, ExprType.BITWISE_AND_AGG, BY_THIS);
        a(m, ExprType.BITWISE_OR_AGG, BY_THIS);
        a(m, ExprType.BITWISE_XOR_AGG, BY_THIS);
        a(m, ExprType.CEIL, PG_MATH_FUNCTION);
        r(m, ExprType.DECODE, DType.VARBINARY);
        r(m, ExprType.ENCODE, DType.TEXT);
        a(m, ExprType.FLOOR, PG_MATH_FUNCTION);
        r(m, ExprType.FORMAT, DType.TEXT);
        r(m, ExprType.HEX, DType.TEXT);
        r(m, ExprType.LEFT, DType.TEXT);
        r(m, ExprType.LOCALTIMESTAMP, DType.TIMESTAMP);
        r(m, ExprType.NORMALIZE, DType.TEXT);
        r(m, ExprType.NTILE, DType.INT);
        r(m, ExprType.OVERLAY, DType.TEXT);
        r(m, ExprType.PAD, DType.TEXT);
        r(m, ExprType.REGEXP_REPLACE, DType.TEXT);
        r(m, ExprType.REPLACE, DType.TEXT);
        r(m, ExprType.REVERSE, DType.TEXT);
        r(m, ExprType.RIGHT, DType.TEXT);
        a(m, ExprType.SIGN, PG_MATH_FUNCTION);
        r(m, ExprType.SPLIT_PART, DType.TEXT);
        r(m, ExprType.TIME_FROM_PARTS, DType.TIME);
        r(m, ExprType.TIMESTAMP_FROM_PARTS, DType.TIMESTAMP);
        r(m, ExprType.TO_NUMBER, DType.DECIMAL);
        r(m, ExprType.WIDTH_BUCKET, DType.INT);
    }

    private static void presto(Map<ExprType, Spec> m) {
        r(m, ExprType.BITWISE_AND, DType.BIGINT);
        r(m, ExprType.BITWISE_NOT, DType.BIGINT);
        r(m, ExprType.BITWISE_OR, DType.BIGINT);
        r(m, ExprType.BITWISE_XOR, DType.BIGINT);
        r(m, ExprType.LENGTH, DType.BIGINT);
        r(m, ExprType.LEVENSHTEIN, DType.BIGINT);
        r(m, ExprType.MD5_DIGEST, DType.VARBINARY);
        a(m, ExprType.MOD, BY_THIS_EXPRESSION);
        a(m, ExprType.RAND, PRESTO_RAND);
        a(m, ExprType.ROUND, BY_THIS);
        a(m, ExprType.SIGN, BY_THIS);
        r(m, ExprType.STR_POSITION, DType.BIGINT);
        r(m, ExprType.WIDTH_BUCKET, DType.BIGINT);
    }

    private static void bigquery(Map<ExprType, Spec> m) {
        a(m, ExprType.APPROX_QUANTILES, ARRAY_BY_THIS);
        a(m, ExprType.APPROX_TOP_K, BQ_APPROX_TOP);
        a(m, ExprType.APPROX_TOP_SUM, BQ_APPROX_TOP);
        a(m, ExprType.ARG_MAX, BY_THIS);
        a(m, ExprType.ARG_MIN, BY_THIS);
        a(m, ExprType.ARRAY, BQ_ARRAY);
        r(m, ExprType.ATAN2, DType.DOUBLE);
        a(m, ExprType.AVG, INT_AS_DOUBLE);
        r(m, ExprType.BITWISE_AND_AGG, DType.BIGINT);
        r(m, ExprType.BITWISE_COUNT, DType.BIGINT);
        r(m, ExprType.BITWISE_OR_AGG, DType.BIGINT);
        r(m, ExprType.BITWISE_XOR_AGG, DType.BIGINT);
        r(m, ExprType.BYTE_LENGTH, DType.BIGINT);
        r(m, ExprType.BYTE_STRING, DType.BINARY);
        a(m, ExprType.CEIL, INT_AS_DOUBLE);
        r(m, ExprType.CODE_POINTS_TO_BYTES, DType.BINARY);
        r(m, ExprType.CODE_POINTS_TO_STRING, DType.VARCHAR);
        a(m, ExprType.CONCAT, BQ_CONCAT);
        r(m, ExprType.CORR, DType.DOUBLE);
        r(m, ExprType.COSINE_DISTANCE, DType.DOUBLE);
        r(m, ExprType.COTH, DType.DOUBLE);
        r(m, ExprType.CSC, DType.DOUBLE);
        r(m, ExprType.CSCH, DType.DOUBLE);
        r(m, ExprType.CURRENT_TIMESTAMP, DType.TIMESTAMPTZ);
        a(m, ExprType.DATE_ADD, BQ_DATE_FUNC);
        r(m, ExprType.DATE_FROM_UNIX_DATE, DType.DATE);
        a(m, ExprType.DATE_SUB, BQ_DATE_FUNC);
        a(m, ExprType.DATE_TRUNC, BQ_DATE_FUNC);
        a(m, ExprType.DATETIME_TRUNC, BQ_DATE_FUNC);
        r(m, ExprType.EUCLIDEAN_DISTANCE, DType.DOUBLE);
        a(m, ExprType.EXP, INT_AS_DOUBLE);
        r(m, ExprType.FARM_FINGERPRINT, DType.BIGINT);
        r(m, ExprType.FLOAT64, DType.DOUBLE);
        a(m, ExprType.FLOOR, INT_AS_DOUBLE);
        r(m, ExprType.FORMAT, DType.VARCHAR);
        a(m, ExprType.GENERATE_TIMESTAMP_ARRAY, BQ_GENERATE_TIMESTAMP_ARRAY);
        a(m, ExprType.GROUP_CONCAT, BY_THIS);
        r(m, ExprType.GROUPING, DType.BIGINT);
        r(m, ExprType.HOST, DType.VARCHAR);
        a(m, ExprType.IGNORE_NULLS, BY_THIS);
        r(m, ExprType.JSON_ARRAY, DType.JSON);
        r(m, ExprType.JSON_ARRAY_APPEND, DType.JSON);
        r(m, ExprType.JSON_ARRAY_INSERT, DType.JSON);
        r(m, ExprType.JSON_BOOL, DType.BOOLEAN);
        a(m, ExprType.JSON_EXTRACT, BY_THIS);
        a(m, ExprType.JSON_EXTRACT_ARRAY, ARRAY_BY_THIS);
        r(m, ExprType.JSON_EXTRACT_SCALAR, DType.VARCHAR);
        a(m, ExprType.JSON_FORMAT, BQ_JSON_FORMAT);
        a(m, ExprType.JSON_KEYS_AT_DEPTH, BQ_ARRAY_VARCHAR);
        r(m, ExprType.JSON_OBJECT, DType.JSON);
        r(m, ExprType.JSON_REMOVE, DType.JSON);
        r(m, ExprType.JSON_SET, DType.JSON);
        r(m, ExprType.JSON_STRIP_NULLS, DType.JSON);
        r(m, ExprType.JSON_TYPE, DType.VARCHAR);
        a(m, ExprType.JSON_VALUE_ARRAY, BQ_ARRAY_VARCHAR);
        r(m, ExprType.LAX_BOOL, DType.BOOLEAN);
        r(m, ExprType.LAX_FLOAT64, DType.DOUBLE);
        r(m, ExprType.LAX_INT64, DType.BIGINT);
        r(m, ExprType.LAX_STRING, DType.VARCHAR);
        a(m, ExprType.LEFT, BY_THIS);
        r(m, ExprType.LENGTH, DType.BIGINT);
        a(m, ExprType.LN, INT_AS_DOUBLE);
        a(m, ExprType.LOG, INT_AS_DOUBLE);
        a(m, ExprType.LOWER, BY_THIS);
        r(m, ExprType.LOWER_HEX, DType.VARCHAR);
        r(m, ExprType.MD5_DIGEST, DType.BINARY);
        a(m, ExprType.NET_FUNC, BY_THIS);
        r(m, ExprType.NORMALIZE, DType.VARCHAR);
        a(m, ExprType.PAD, BY_THIS);
        r(m, ExprType.PARSE_BIGNUMERIC, DType.BIGDECIMAL);
        r(m, ExprType.PARSE_DATETIME, DType.DATETIME);
        r(m, ExprType.PARSE_NUMERIC, DType.DECIMAL);
        r(m, ExprType.PARSE_TIME, DType.TIME);
        a(m, ExprType.PERCENTILE_CONT, BQ_BY_ARGS_WITH_COERCE);
        a(m, ExprType.PERCENTILE_DISC, BY_THIS);
        r(m, ExprType.RANGE_BUCKET, DType.BIGINT);
        r(m, ExprType.REG_DOMAIN, DType.VARCHAR);
        a(m, ExprType.REGEXP_EXTRACT, BY_THIS);
        a(m, ExprType.REGEXP_EXTRACT_ALL, ARRAY_BY_THIS);
        r(m, ExprType.REGEXP_INSTR, DType.BIGINT);
        a(m, ExprType.REGEXP_REPLACE, BY_THIS);
        a(m, ExprType.REPEAT, BY_THIS);
        a(m, ExprType.REPLACE, BY_THIS);
        a(m, ExprType.RESPECT_NULLS, BY_THIS);
        a(m, ExprType.REVERSE, BY_THIS);
        a(m, ExprType.RIGHT, BY_THIS);
        a(m, ExprType.ROUND, INT_AS_DOUBLE);
        r(m, ExprType.SHA, DType.BINARY);
        r(m, ExprType.SHA1_DIGEST, DType.BINARY);
        r(m, ExprType.SHA2, DType.BINARY);
        r(m, ExprType.SHA2_DIGEST, DType.BINARY);
        a(m, ExprType.SAFE_ADD, BQ_BY_ARGS_WITH_COERCE);
        r(m, ExprType.SAFE_CONVERT_BYTES_TO_STRING, DType.VARCHAR);
        a(m, ExprType.SAFE_DIVIDE, BQ_SAFE_DIVIDE);
        a(m, ExprType.SAFE_FUNC, BY_THIS);
        a(m, ExprType.SAFE_MULTIPLY, BQ_BY_ARGS_WITH_COERCE);
        a(m, ExprType.SAFE_NEGATE, BY_THIS);
        a(m, ExprType.SAFE_SUBTRACT, BQ_BY_ARGS_WITH_COERCE);
        r(m, ExprType.SEC, DType.DOUBLE);
        r(m, ExprType.SECH, DType.DOUBLE);
        a(m, ExprType.SIGN, BY_THIS);
        r(m, ExprType.SOUNDEX, DType.VARCHAR);
        a(m, ExprType.SPLIT, ARRAY_BY_THIS);
        a(m, ExprType.SQRT, INT_AS_DOUBLE);
        r(m, ExprType.STR_TO_TIME, DType.TIMESTAMPTZ);
        a(m, ExprType.SUBSTRING, BY_THIS);
        r(m, ExprType.TIME_FROM_PARTS, DType.TIME);
        r(m, ExprType.TIME_STR_TO_TIME, DType.TIMESTAMPTZ);
        r(m, ExprType.TIME_TRUNC, DType.TIME);
        r(m, ExprType.TIMESTAMP_ADD, DType.TIMESTAMPTZ);
        r(m, ExprType.TIMESTAMP_FROM_PARTS, DType.DATETIME);
        r(m, ExprType.TIMESTAMP_SUB, DType.TIMESTAMPTZ);
        a(m, ExprType.TIMESTAMP_TRUNC, BQ_DATE_FUNC);
        a(m, ExprType.TO_CODE_POINTS, BQ_ARRAY_BIGINT);
        a(m, ExprType.TRANSLATE, BY_THIS);
        a(m, ExprType.TRIM, BY_THIS);
        r(m, ExprType.TS_OR_DS_TO_TIME, DType.TIME);
        r(m, ExprType.UNHEX, DType.BINARY);
        r(m, ExprType.UNIX_DATE, DType.BIGINT);
        r(m, ExprType.UNIX_TO_TIME, DType.TIMESTAMPTZ);
        a(m, ExprType.UPPER, BY_THIS);
        r(m, ExprType.UUID, DType.VARCHAR);
    }

    private static void clickhouse(Map<ExprType, Spec> m) {
        a(m, ExprType.CORR, CH_FLOAT64);
        r(m, ExprType.COUNT_IF, DType.UBIGINT);
        a(m, ExprType.MD5_DIGEST, CH_FIXED_STRING_16);
    }

    private static void hive(Map<ExprType, Spec> m) {
        r(m, ExprType.ADD_MONTHS, DType.VARCHAR);
        a(m, ExprType.APPROX_QUANTILE, BY_QUANTILE);
        a(m, ExprType.ARRAY_DISTINCT, BY_THIS);
        a(m, ExprType.ARRAY_EXCEPT, BY_THIS);
        a(m, ExprType.ARRAY_INTERSECT, BY_EXPRESSIONS);
        r(m, ExprType.ARRAY_SIZE, DType.INT);
        a(m, ExprType.CEIL, HIVE_FLOOR_CEIL);
        a(m, ExprType.COALESCE, PROMOTE_THIS_EXPRESSIONS);
        r(m, ExprType.CORR, DType.DOUBLE);
        r(m, ExprType.CURRENT_DATABASE, DType.VARCHAR);
        r(m, ExprType.DENSE_RANK, DType.INT);
        r(m, ExprType.ENCODE, DType.BINARY);
        r(m, ExprType.FACTORIAL, DType.BIGINT);
        a(m, ExprType.FIRST, BY_THIS);
        a(m, ExprType.FLOOR, HIVE_FLOOR_CEIL);
        r(m, ExprType.GROUPING, DType.BIGINT);
        r(m, ExprType.HEX, DType.VARCHAR);
        a(m, ExprType.IF, IF_PROMOTE);
        r(m, ExprType.INT_DIV, DType.BIGINT);
        r(m, ExprType.JSON_EXTRACT_SCALAR, DType.VARCHAR);
        r(m, ExprType.JSON_FORMAT, DType.VARCHAR);
        a(m, ExprType.LAST, BY_THIS);
        r(m, ExprType.MINUTE, DType.INT);
        r(m, ExprType.MONTH, DType.INT);
        r(m, ExprType.MONTHS_BETWEEN, DType.DOUBLE);
        a(m, ExprType.NEGATIVE, BY_THIS);
        r(m, ExprType.NEXT_DAY, DType.VARCHAR);
        r(m, ExprType.NTILE, DType.INT);
        r(m, ExprType.PERCENTILE_DISC, DType.DOUBLE);
        a(m, ExprType.QUANTILE, BY_QUANTILE);
        r(m, ExprType.RANK, DType.INT);
        r(m, ExprType.REGEXP_EXTRACT, DType.VARCHAR);
        r(m, ExprType.REGEXP_REPLACE, DType.VARCHAR);
        t(m, ExprType.REGEXP_SPLIT, TypeMetadata::arrayOfText);
        r(m, ExprType.REPLACE, DType.VARCHAR);
        a(m, ExprType.REVERSE, BY_THIS);
        r(m, ExprType.ROW_NUMBER, DType.INT);
        r(m, ExprType.SECOND, DType.INT);
        r(m, ExprType.SIGN, DType.DOUBLE);
        r(m, ExprType.SOUNDEX, DType.VARCHAR);
        t(m, ExprType.STR_TO_MAP, TypeMetadata::mapOfTextText);
        r(m, ExprType.STR_TO_UNIX, DType.BIGINT);
        r(m, ExprType.UNHEX, DType.BINARY);
        a(m, ExprType.WITHIN_GROUP, BY_THIS);
    }
}
