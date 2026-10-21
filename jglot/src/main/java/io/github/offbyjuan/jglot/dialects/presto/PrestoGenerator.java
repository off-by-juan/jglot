package io.github.offbyjuan.jglot.dialects.presto;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.DialectHelpers;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.optimizer.Scopes;
import io.github.offbyjuan.jglot.optimizer.TypeAnnotator;
import io.github.offbyjuan.jglot.transforms.Transforms;
import io.github.offbyjuan.jglot.util.Py;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Presto generator: a port of sqlglot's {@code sqlglot.generators.presto}. */
public class PrestoGenerator extends Generator {
    private static final Object[] TEXT_TYPES = DataTypes.TEXT_TYPES.toArray();

    public PrestoGenerator(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }

    // ------------------------------------------------------------------------------------------
    // Module-level helpers

    protected String sha2DigestSql(Expression expression) {
        String length = expression.text(Args.LENGTH);
        if (length.isEmpty()) {
            length = "256";
        }
        if (!length.equals("256") && !length.equals("512")) {
            unsupported("SHA" + length + " is not supported in Presto");
        }

        Expression this_ = expression.getThis();
        if (this_.isType(TEXT_TYPES)) {
            // the native digest takes VARBINARY, so a text-typed argument needs encoding
            this_ = Expression.of(ExprType.ENCODE, Args.THIS, this_, Args.CHARSET, Exp.string("utf-8"));
        }

        return func("SHA" + length, this_);
    }

    protected String prestoInitcapSql(Expression expression) {
        Expression delimiters = expression.getExpression();
        if (delimiters != null
                && !(delimiters.isString() && java.util.Objects.equals(delimiters.thisValue(), dd.INITCAP_DEFAULT_DELIMITER_CHARS))) {
            unsupported("INITCAP does not support custom delimiters");
        }

        String regex = "(\\w)(\\w*)";
        return "REGEXP_REPLACE(" + sql(expression, Args.THIS) + ", '" + regex + "', x -> UPPER(x[1]) || LOWER(x[2]))";
    }

    protected String noSortArray(Expression expression) {
        String comparator;
        if (Exp.bool(false).equals(expression.get(Args.ASC))) {
            comparator = "(a, b) -> CASE WHEN a < b THEN 1 WHEN a > b THEN -1 ELSE 0 END";
        } else {
            comparator = null;
        }
        return func("ARRAY_SORT", expression.getThis(), comparator);
    }

    protected String prestoSchemaSql(Expression expression) {
        if (Expression.isInstance(expression.parent(), ExprType.PARTITIONED_BY_PROPERTY)) {
            // Any columns in the ARRAY[] string literals should not be quoted. sqlglot replaces
            // each Identifier by its (plain string) name; a Var renders that name verbatim.
            expression.transform(n -> n.is(ExprType.IDENTIFIER) ? Exp.var(n.name()) : n, false);

            List<Expression> literals = new ArrayList<>();
            for (Expression c : expression.getExpressions()) {
                String partitionExpr = c.is(ExprType.FUNC) || c.is(ExprType.PROPERTY) ? sql(c) : sql(c, Args.THIS);
                literals.add(Exp.string(partitionExpr));
            }
            return sql(Expression.of(ExprType.ARRAY, Args.EXPRESSIONS, literals));
        }

        if (expression.parent() != null) {
            for (Expression schema : expression.parent().findAll(ExprType.SCHEMA)) {
                if (schema == expression) {
                    continue;
                }

                List<Expression> columnDefs = schema.findAll(ExprType.COLUMN_DEF);
                if (Expression.isInstance(schema.parent(), ExprType.PROPERTY)) {
                    if (expression.get(Args.EXPRESSIONS) instanceof List<?>) {
                        @SuppressWarnings("unchecked")
                        List<Object> raw = (List<Object>) expression.get(Args.EXPRESSIONS);
                        raw.addAll(columnDefs);
                    }
                }
            }
        }

        return schemaSql(expression);
    }

    protected String quantileSql(Expression expression) {
        unsupported("Presto does not support exact quantiles");
        return func("APPROX_PERCENTILE", expression.getThis(), expression.get(Args.QUANTILE));
    }

    protected String strToTimeSql(Expression expression) {
        return func("DATE_PARSE", expression.getThis(), formatTime(expression));
    }

    protected String tsOrDsToDateSql(Expression expression) {
        String timeFormat = formatTime(expression);
        if (timeFormat != null && !timeFormat.isEmpty()
                && !timeFormat.equals(dd.TIME_FORMAT) && !timeFormat.equals(dd.DATE_FORMAT)) {
            return sql(Exp.cast(Exp.maybeParse(strToTimeSql(expression), (Dialect) null), DType.DATE));
        }
        return sql(Exp.cast(Exp.cast(expression.getThis(), DType.TIMESTAMP), DType.DATE));
    }

    protected String tsOrDsAddSql(Expression expression) {
        expression = DialectHelpers.tsOrDsAddCast(expression);
        Expression unit = DialectHelpers.unitToStr(expression);
        return func("DATE_ADD", unit, expression.getExpression(), expression.getThis());
    }

    protected String tsOrDsDiffSql(Expression expression) {
        Expression this_ = Exp.cast(expression.getThis(), DType.TIMESTAMP);
        Expression expr = Exp.cast(expression.getExpression(), DType.TIMESTAMP);
        Expression unit = DialectHelpers.unitToStr(expression);
        return func("DATE_DIFF", unit, expr, this_);
    }

    /**
     * Trino doesn't support FIRST / LAST as functions, but they're valid in the context of
     * MATCH_RECOGNIZE, so we need to preserve them in that case. In all other cases they're
     * converted into an ARBITRARY call.
     */
    protected String firstLastSql(Expression expression) {
        if (Expression.isInstance(expression.findAncestor(ExprType.MATCH_RECOGNIZE, ExprType.SELECT),
                ExprType.MATCH_RECOGNIZE)) {
            return functionFallbackSql(expression);
        }

        return renameFunc("ARBITRARY", expression);
    }

    protected String unixToTimeSql(Expression expression) {
        Object scale = expression.get(Args.SCALE);
        String timestamp = sql(expression, Args.THIS);
        if (scale == null || Exp.number(0).equals(scale)) {
            return renameFunc("FROM_UNIXTIME", expression);
        }

        return "FROM_UNIXTIME(CAST(" + timestamp + " AS DOUBLE) / POW(10, " + sql(scale) + "))";
    }

    protected Expression toInt(Expression expression) {
        if (expression.dataType() == null) {
            TypeAnnotator.annotateTypes(expression, dialect);
        }
        Expression type = expression.dataType();
        if (type != null && !DataTypes.INTEGER_TYPES.contains(type.thisValue())) {
            return Exp.cast(expression, DType.BIGINT);
        }
        return expression;
    }

    protected String dateDeltaSql(String name, boolean negateInterval, Expression expression) {
        Expression interval = toInt(expression.getExpression());
        return func(
                name,
                DialectHelpers.unitToStr(expression),
                negateInterval ? interval.binop(ExprType.MUL, -1) : interval,
                expression.getThis());
    }

    protected String dateDiffSql(Expression expression) {
        // Presto/Trino only expose date_diff(unit, ts1, ts2); it returns ts2 - ts1, so the
        // operands are emitted as (expression, this) to preserve `this - expression` semantics.
        Expression this_ = expression.getThis();
        Expression expr = expression.getExpression();
        Expression unit = DialectHelpers.unitToStr(expression);

        // DATE_DIFF counts complete units between its operands, whereas dialects that set
        // date_part_boundary count unit boundary crossings, so the operands are truncated
        // down to the unit to make the two coincide
        if (unit != null && expression.has(Args.DATE_PART_BOUNDARY)) {
            Expression rawUnit = expression.expr(Args.UNIT);
            Integer dow = DialectHelpers.weekUnitToDow(rawUnit);

            if (dow != null) {
                unit = Exp.string("WEEK");

                // DATE_TRUNC('WEEK', ...) is Monday-based; shifting both operands by the same
                // delta realigns it to the requested week start without changing the diff
                int shiftDays = dow == 7 ? 1 : 1 - dow;
                if (shiftDays != 0) {
                    Expression delta = Expression.of(ExprType.INTERVAL,
                            Args.THIS, Exp.string(String.valueOf(shiftDays)), Args.UNIT, Exp.var("DAY"));
                    this_ = Expression.of(ExprType.ADD, Args.THIS, this_, Args.EXPRESSION, delta);
                    expr = Expression.of(ExprType.ADD, Args.THIS, expr, Args.EXPRESSION, delta.copy());
                }
            }

            if (!Expression.isInstance(rawUnit, ExprType.WEEK_START) || dow != null) {
                this_ = Expression.of(ExprType.DATE_TRUNC, Args.UNIT, unit.copy(), Args.THIS, this_);
                expr = Expression.of(ExprType.DATE_TRUNC, Args.UNIT, unit.copy(), Args.THIS, expr);
            }
        }

        return func("DATE_DIFF", unit, expr, this_);
    }

    protected String prestoExplodeToUnnestSql(Expression expression) {
        Expression explode = expression.getThis();
        if (explode != null && explode.is(ExprType.EXPLODE)) {
            Expression explodedType = explode.getThis() == null ? null : explode.getThis().dataType();
            Expression alias = expression.expr(Args.ALIAS);

            // This attempts a best-effort transpilation of LATERAL VIEW EXPLODE on a struct array
            if (alias != null && alias.is(ExprType.TABLE_ALIAS)
                    && explodedType != null && explodedType.is(ExprType.DATA_TYPE)
                    && explodedType.isType(DType.ARRAY)
                    && !explodedType.getExpressions().isEmpty()
                    && explodedType.getExpressions().get(0).isType(DType.STRUCT)) {
                // When unnesting a ROW in Presto, it produces N columns, so we need to fix the alias
                List<Expression> columns = new ArrayList<>();
                for (Expression c : explodedType.getExpressions().get(0).getExpressions()) {
                    columns.add(c.getThis().copy());
                }
                alias.set(Args.COLUMNS, columns);
            }
        } else if (explode != null && explode.is(ExprType.INLINE)) {
            explode.replace(Expression.of(ExprType.EXPLODE, Args.THIS, explode.getThis().copy()));
        }

        return DialectHelpers.explodeToUnnestSql(this, expression);
    }

    /** sqlglot's {@code amend_exploded_column_table}. */
    public static Expression amendExplodedColumnTable(Expression expression) {
        // We check for expression.type because the columns can be amended only if types were inferred
        if (expression.is(ExprType.SELECT) && expression.dataType() != null) {
            for (Expression lateral : expression.exprs(Args.LATERALS)) {
                Expression alias = lateral.expr(Args.ALIAS);
                if (!Expression.isInstance(lateral.getThis(), ExprType.EXPLODE)
                        || alias == null || !alias.is(ExprType.TABLE_ALIAS)
                        || alias.exprs(Args.COLUMNS).size() != 1) {
                    continue;
                }

                Expression newTable = alias.getThis();
                String oldTable = Py.lower(alias.exprs(Args.COLUMNS).get(0).name());

                // When transpiling a LATERAL VIEW EXPLODE Spark query, the exploded fields may be qualified
                // with the struct column, resulting in invalid Presto references that need to be amended
                for (Expression column : Scopes.findAllInScope(expression, ExprType.COLUMN)) {
                    if (Py.lower(column.text(Args.DB)).equals(oldTable)) {
                        column.set(Args.TABLE, column.expr(Args.DB).pop());
                    } else if (Py.lower(column.text(Args.TABLE)).equals(oldTable)) {
                        column.set(Args.TABLE, newTable.copy());
                    } else if (Py.lower(column.name()).equals(oldTable)
                            && Expression.isInstance(column.parent(), ExprType.DOT)) {
                        column.parent().replace(Exp.column(column.parent().getExpression(), newTable));
                    }
                }
            }
        }

        return expression;
    }

    // ------------------------------------------------------------------------------------------
    // TRANSFORMS

    @Override
    public String transform(Expression e) {
        return switch (e.type()) {
            case APPROX_QUANTILE -> func("APPROX_PERCENTILE",
                    e.getThis(), e.get(Args.WEIGHT), e.get(Args.QUANTILE), e.get(Args.ACCURACY));
            case ARRAY -> applyTransformsThen(e,
                    x -> "ARRAY[" + expressions(opts(x).flat()) + "]",
                    Transforms::inheritStructFieldNames);
            case ARRAY_SLICE -> DialectHelpers.arraySliceToSliceSql(this, e);
            case BITWISE_AND -> func("BITWISE_AND", e.getThis(), e.getExpression());
            case BITWISE_NOT -> func("BITWISE_NOT", e.getThis());
            case BITWISE_OR -> func("BITWISE_OR", e.getThis(), e.getExpression());
            case BITWISE_XOR -> func("BITWISE_XOR", e.getThis(), e.getExpression());
            case CAST -> applyTransforms(e, Transforms::epochCastToTs);
            case CURRENT_TIME -> "CURRENT_TIME";
            case CURRENT_TIMESTAMP -> "CURRENT_TIMESTAMP";
            case CURRENT_USER -> "CURRENT_USER";
            case DATE_ADD -> dateDeltaSql("DATE_ADD", false, e);
            case DATE_DIFF, DATETIME_DIFF, TIMESTAMP_DIFF -> dateDiffSql(e);
            case DATE_STR_TO_DATE -> DialectHelpers.datestrtodateSql(this, e);
            case DATE_TO_DI -> "CAST(DATE_FORMAT(" + sql(e, Args.THIS) + ", " + dd.DATEINT_FORMAT + ") AS INT)";
            case DATE_SUB -> dateDeltaSql("DATE_ADD", true, e);
            case DAY_OF_WEEK -> "((" + func("DAY_OF_WEEK", e.getThis()) + " % 7) + 1)";
            case DECODE -> DialectHelpers.encodeDecodeSql(this, e, "FROM_UTF8");
            case DI_TO_DATE -> "CAST(DATE_PARSE(CAST(" + sql(e, Args.THIS) + " AS VARCHAR), " + dd.DATEINT_FORMAT
                    + ") AS DATE)";
            case ENCODE -> DialectHelpers.encodeDecodeSql(this, e, "TO_UTF8");
            case FILE_FORMAT_PROPERTY -> "format=" + sql(Exp.string(e.name()));
            case FIRST, LAST -> firstLastSql(e);
            case FROM_TIME_ZONE -> "WITH_TIMEZONE(" + sql(e, Args.THIS) + ", " + sql(e, Args.ZONE) + ") AT TIME ZONE 'UTC'";
            case GENERATE_SERIES, GENERATE_DATE_ARRAY -> DialectHelpers.sequenceSql(this, e);
            case IF -> DialectHelpers.ifSql(this, e);
            case I_LIKE -> DialectHelpers.noIlikeSql(this, e);
            case INITCAP -> prestoInitcapSql(e);
            case LAST_DAY -> func("LAST_DAY_OF_MONTH", e.getThis());
            case LATERAL -> prestoExplodeToUnnestSql(e);
            case LEFT -> DialectHelpers.leftToSubstringSql(this, e);
            case LEVENSHTEIN -> {
                unsupportedArgs(e, Args.INS_COST, Args.DEL_COST, Args.SUB_COST, Args.MAX_DIST);
                yield renameFunc("LEVENSHTEIN_DISTANCE", e);
            }
            case PIVOT -> DialectHelpers.noPivotSql(this, e);
            case QUANTILE -> quantileSql(e);
            case REGEXP_EXTRACT, REGEXP_EXTRACT_ALL -> DialectHelpers.regexpExtractSql(this, e);
            case RIGHT -> DialectHelpers.rightToSubstringSql(this, e);
            case SCHEMA -> prestoSchemaSql(e);
            case SCHEMA_COMMENT_PROPERTY -> nakedProperty(e);
            case SELECT -> applyTransforms(e,
                    Transforms::eliminateWindowClause,
                    Transforms::eliminateQualify,
                    Transforms::eliminateDistinctOn,
                    Transforms.explodeProjectionToUnnest(1, true),
                    Transforms::eliminateSemiAndAntiJoins,
                    PrestoGenerator::amendExplodedColumnTable);
            case SORT_ARRAY -> noSortArray(e);
            case SQL_SECURITY_PROPERTY -> "SECURITY " + sql(e.thisValue());
            case STR_POSITION -> DialectHelpers.strpositionSql(this, e, "STRPOS", false, true, true);
            case STR_TO_DATE -> "CAST(" + strToTimeSql(e) + " AS DATE)";
            case STR_TO_TIME -> strToTimeSql(e);
            case STRUCT_EXTRACT -> DialectHelpers.structExtractSql(this, e);
            case TABLE -> applyTransforms(e, Transforms::unnestGenerateSeries);
            case TIMESTAMP -> DialectHelpers.noTimestampSql(this, e);
            case TIMESTAMP_ADD -> dateDeltaSql("DATE_ADD", false, e);
            case TIMESTAMP_TRUNC -> DialectHelpers.timestamptruncSql(this, e);
            case TIME_STR_TO_DATE, TIME_STR_TO_TIME -> DialectHelpers.timestrtotimeSql(this, e);
            case TIME_STR_TO_UNIX -> func("TO_UNIXTIME", func("DATE_PARSE", e.getThis(), dd.TIME_FORMAT));
            case TIME_TO_STR, TO_CHAR -> func("DATE_FORMAT", e.getThis(), formatTime(e));
            case TRY_CAST -> applyTransforms(e, Transforms::epochCastToTs);
            case TS_OR_DI_TO_DI -> "CAST(SUBSTR(REPLACE(CAST(" + sql(e, Args.THIS) + " AS VARCHAR), '-', ''), 1, 8) AS INT)";
            case TS_OR_DS_ADD -> tsOrDsAddSql(e);
            case TS_OR_DS_DIFF -> tsOrDsDiffSql(e);
            case TS_OR_DS_TO_DATE -> tsOrDsToDateSql(e);
            case UNIX_TO_STR -> "DATE_FORMAT(FROM_UNIXTIME(" + sql(e, Args.THIS) + "), " + formatTime(e) + ")";
            case UNIX_TO_TIME -> unixToTimeSql(e);
            case UNIX_TO_TIME_STR -> "CAST(FROM_UNIXTIME(" + sql(e, Args.THIS) + ") AS VARCHAR)";
            case WITH -> applyTransforms(e, Transforms::addRecursiveCteColumnNames);
            case WITHIN_GROUP -> applyTransforms(e, Transforms::removeWithinGroupForPercentiles);
            case XOR -> DialectHelpers.boolXorSql(this, e);
            case SHA2_DIGEST -> sha2DigestSql(e);
            default -> super.transform(e);
        };
    }

    // ------------------------------------------------------------------------------------------
    // *_sql methods the base Generator lacks

    @Override
    public String sqlFor(Expression e) {
        return switch (e.type()) {
            case JSON_FORMAT -> jsonformatSql(e);
            case MD5 -> md5Sql(e);
            case SHA2 -> sha2Sql(e);
            case STR_TO_UNIX -> strtounixSql(e);
            case JSON_EXTRACT -> jsonextractSql(e);
            case GROUP_CONCAT -> groupconcatSql(e);
            default -> super.sqlFor(e);
        };
    }

    // ------------------------------------------------------------------------------------------
    // *_sql methods

    @Override
    public String datatypeSql(Expression expression) {
        if (!g.SUPPORTS_TEMPORAL_PRECISION && DataTypes.TEMPORAL_TYPES.contains(expression.thisValue())) {
            List<Expression> exprs = expression.getExpressions();
            if (!exprs.isEmpty() && !exprs.get(0).name().equals("3")) {
                unsupported("Presto only supports millisecond precision for temporal types");
            }
            expression.set(Args.EXPRESSIONS, null);
        }
        return super.datatypeSql(expression);
    }

    @Override
    public String extractSql(Expression expression) {
        String datePart = expression.name();

        if (!datePart.startsWith("EPOCH")) {
            return super.extractSql(expression);
        }

        Long scale;
        if (datePart.equals("EPOCH_MILLISECOND")) {
            scale = 1_000L;
        } else if (datePart.equals("EPOCH_MICROSECOND")) {
            scale = 1_000_000L;
        } else if (datePart.equals("EPOCH_NANOSECOND")) {
            scale = 1_000_000_000L;
        } else {
            scale = null;
        }

        Expression value = expression.getExpression();

        Expression ts = Exp.cast(value, DType.TIMESTAMP);
        Expression toUnix = Expression.of(ExprType.TIME_TO_UNIX, Args.THIS, ts);

        if (scale != null) {
            toUnix = Expression.of(ExprType.MUL, Args.THIS, toUnix, Args.EXPRESSION, Exp.number(scale));
        }

        return sql(toUnix);
    }

    public String jsonformatSql(Expression expression) {
        Expression this_ = expression.getThis();
        boolean isJson = expression.has(Args.IS_JSON);

        if (this_ != null && !(isJson || this_.dataType() != null)) {
            this_ = TypeAnnotator.annotateTypes(this_, dialect);
        }

        if (!(isJson || this_.isType(DType.JSON))) {
            this_.replace(Exp.cast(this_, DType.JSON));
        }

        return functionFallbackSql(expression);
    }

    public String md5Sql(Expression expression) {
        Expression this_ = expression.getThis();

        if (this_.dataType() == null) {
            this_ = TypeAnnotator.annotateTypes(this_, dialect);
        }

        if (this_.isType(TEXT_TYPES)) {
            this_ = Expression.of(ExprType.ENCODE, Args.THIS, this_, Args.CHARSET, Exp.string("utf-8"));
        }

        return func("LOWER", func("TO_HEX", func("MD5", sql(this_))));
    }

    public String sha2Sql(Expression expression) {
        String length = expression.text(Args.LENGTH);
        if (length.isEmpty()) {
            length = "256";
        }
        if (!length.equals("256") && !length.equals("512")) {
            unsupported("SHA" + length + " is not supported in Presto");
        }

        Expression this_ = expression.getThis();

        if (this_.isType(TEXT_TYPES)) {
            this_ = Expression.of(ExprType.ENCODE, Args.THIS, this_, Args.CHARSET, Exp.string("utf-8"));
        }

        return func("LOWER", func("TO_HEX", func("SHA" + length, sql(this_))));
    }

    @SuppressWarnings("unchecked")
    public String strtounixSql(Expression expression) {
        // Since `TO_UNIXTIME` requires a `TIMESTAMP`, we need to parse the argument into one.
        // To do this, we first try to `DATE_PARSE` it, but since this can fail when there's a
        // timezone involved, we wrap it in a `TRY` call and use `PARSE_DATETIME` as a fallback,
        // which seems to be using the same time mapping as Hive, as per:
        // https://joda-time.sourceforge.net/apidocs/org/joda/time/format/DateTimeFormat.html
        Expression this_ = expression.getThis();
        Expression valueAsText = Exp.cast(this_, DType.TEXT);
        Expression valueAsTimestamp = this_.isString() ? Exp.cast(this_, DType.TIMESTAMP) : this_;

        String parseWithoutTz = func("DATE_PARSE", valueAsText, formatTime(expression));

        String formattedValue = func("DATE_FORMAT", valueAsTimestamp, formatTime(expression));
        Dialect hive = Dialects.get("hive");
        String parseWithTz = func(
                "PARSE_DATETIME",
                formattedValue,
                formatTime(expression, (Map<String, Object>) (Map<String, ?>) hive.data().INVERSE_TIME_MAPPING,
                        hive.inverseTimeTrie()));
        String coalesced = func("COALESCE", func("TRY", parseWithoutTz), parseWithTz);
        return func("TO_UNIXTIME", coalesced);
    }

    @Override
    public String bracketSql(Expression expression) {
        if (expression.has(Args.SAFE)) {
            return DialectHelpers.bracketToElementAtSql(this, expression);
        }
        return super.bracketSql(expression);
    }

    @Override
    public String structSql(Expression expression) {
        if (expression.dataType() == null) {
            TypeAnnotator.annotateTypes(expression, dialect);
        }

        List<String> values = new ArrayList<>();
        List<String> schema = new ArrayList<>();
        boolean unknownType = false;

        for (Expression e : expression.getExpressions()) {
            if (e.is(ExprType.PROPERTY_EQ)) {
                if (e.dataType() != null && e.dataType().isType(DType.UNKNOWN)) {
                    unknownType = true;
                } else {
                    schema.add(sql(e, Args.THIS) + " " + sql(e.dataType()));
                }
                values.add(sql(e, Args.EXPRESSION));
            } else {
                values.add(sql(e));
            }
        }

        int size = expression.getExpressions().size();

        if (size == 0 || schema.size() != size) {
            if (unknownType) {
                unsupported("Cannot convert untyped key-value definitions (try annotate_types).");
            }
            return func("ROW", values.toArray());
        }
        return "CAST(ROW(" + String.join(", ", values) + ") AS ROW(" + String.join(", ", schema) + "))";
    }

    @Override
    public String intervalSql(Expression expression) {
        if (expression.getThis() != null && Py.upper(expression.text(Args.UNIT)).startsWith("WEEK")) {
            return "(" + expression.getThis().name() + " * INTERVAL '7' DAY)";
        }
        return super.intervalSql(expression);
    }

    @Override
    public String transactionSql(Expression expression) {
        Object modes = expression.get(Args.MODES);
        String modesSql = "";
        if (Expression.truthy(modes)) {
            List<String> parts = new ArrayList<>();
            for (Object m : Py.ensureList(modes)) {
                parts.add(String.valueOf(m));
            }
            modesSql = " " + String.join(", ", parts);
        }
        return "START TRANSACTION" + modesSql;
    }

    @Override
    public Collection<String> offsetLimitModifiers(Expression expression, boolean fetch, Expression limit) {
        List<String> out = new ArrayList<>();
        out.add(sql(expression, Args.OFFSET));
        out.add(sql(limit));
        return out;
    }

    /**
     * Presto doesn't support CREATE VIEW with expressions (ex: {@code CREATE VIEW x (cola)} then
     * {@code (cola)} is the expression), so we need to remove them.
     */
    @Override
    public String createSql(Expression expression) {
        Object kind = expression.get(Args.KIND);
        Expression schema = expression.getThis();
        if ("VIEW".equals(kind) && !schema.getExpressions().isEmpty()) {
            expression.getThis().set(Args.EXPRESSIONS, null);
        }
        return super.createSql(expression);
    }

    /**
     * Presto only supports DELETE FROM for a single table without an alias, so we need to remove
     * the unnecessary parts. If the original DELETE statement contains more than one table to be
     * deleted, we can't safely map it 1-1 to a Presto statement.
     */
    @Override
    public String deleteSql(Expression expression) {
        List<Expression> tables = expression.exprs(Args.TABLES);
        if (tables.isEmpty()) {
            tables = java.util.Arrays.asList(expression.getThis());
        }
        if (tables.size() > 1) {
            return super.deleteSql(expression);
        }

        Expression table = tables.get(0);
        expression.set(Args.THIS, table);
        expression.set(Args.TABLES, null);

        if (table != null && table.is(ExprType.TABLE)) {
            Expression tableAlias = table.expr(Args.ALIAS);
            if (tableAlias != null) {
                tableAlias.pop();
                expression = expression.transform(Transforms::unqualifyColumns);
            }
        }

        return super.deleteSql(expression);
    }

    /** Whether the dialect setting {@code variant_extract_is_json_extract} is enabled (default true). */
    protected boolean variantExtractIsJsonExtract() {
        return true;
    }

    public String jsonextractSql(Expression expression) {
        boolean isJsonExtract = variantExtractIsJsonExtract();

        // Generate JSON_EXTRACT unless the user has configured that a Snowflake / Databricks
        // VARIANT extract (e.g. col:x.y) should map to dot notation (i.e ROW access) in Presto/Trino
        if (!expression.has(Args.VARIANT_EXTRACT) || isJsonExtract) {
            List<Object> args = new ArrayList<>();
            args.add(expression.getThis());
            args.add(expression.getExpression());
            args.addAll(expression.getExpressions());
            return func("JSON_EXTRACT", args.toArray());
        }

        String this_ = sql(expression, Args.THIS);

        // Convert the JSONPath extraction `JSON_EXTRACT(col, '$.x.y) to a ROW access col.x.y
        StringBuilder segments = new StringBuilder();
        List<Expression> pathKeys = expression.getExpression().getExpressions();
        for (Expression pathKey : pathKeys.subList(Math.min(1, pathKeys.size()), pathKeys.size())) {
            if (!pathKey.is(ExprType.JSON_PATH_KEY)) {
                // Cannot transpile subscripts, wildcards etc to dot notation
                unsupported("Cannot transpile JSONPath segment '" + pathKey + "' to ROW access");
                continue;
            }
            String key = Py.str(pathKey.thisValue());
            if (!Exp.SAFE_IDENTIFIER_RE.matcher(key).lookingAt()) {
                key = "\"" + key + "\"";
            }
            segments.append(".").append(key);
        }

        return this_ + segments;
    }

    public String groupconcatSql(Expression expression) {
        return func(
                "ARRAY_JOIN",
                func("ARRAY_AGG", expression.getThis()),
                expression.get(Args.SEPARATOR));
    }
}
