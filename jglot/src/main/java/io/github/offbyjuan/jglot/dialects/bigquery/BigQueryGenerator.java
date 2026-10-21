package io.github.offbyjuan.jglot.dialects.bigquery;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.DialectHelpers;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.optimizer.TypeAnnotator;
import io.github.offbyjuan.jglot.transforms.Transforms;
import io.github.offbyjuan.jglot.util.Py;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** BigQuery generator: a port of sqlglot's {@code sqlglot.generators.bigquery}. */
public class BigQueryGenerator extends Generator {
    private static final Set<String> DQUOTES_ESCAPING_JSON_FUNCTIONS = Set.of("JSON_QUERY", "JSON_VALUE", "JSON_QUERY_ARRAY");

    public BigQueryGenerator(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }

    // ------------------------------------------------------------------------------------------
    // Module-level helpers

    private String derivedTableValuesToUnnest(Expression expression) {
        if (expression.findAncestor(ExprType.FROM, ExprType.JOIN) == null) {
            return valuesSql(expression, true);
        }

        List<Expression> structs = new ArrayList<>();
        Expression alias = expression.expr(Args.ALIAS);
        for (Expression tup : expression.findAll(ExprType.TUPLE)) {
            List<Expression> tupExprs = tup.getExpressions();
            List<Object> fieldAliases = new ArrayList<>();
            List<Expression> aliasColumns = alias != null ? alias.exprs(Args.COLUMNS) : List.of();
            if (alias != null && !aliasColumns.isEmpty()) {
                fieldAliases.addAll(aliasColumns);
            } else {
                for (int i = 0; i < tupExprs.size(); i++) {
                    fieldAliases.add("_c" + i);
                }
            }
            List<Expression> expressions = new ArrayList<>();
            for (int i = 0; i < Math.min(fieldAliases.size(), tupExprs.size()); i++) {
                expressions.add(Expression.of(ExprType.PROPERTY_EQ,
                        Args.THIS, Exp.toIdentifier(fieldAliases.get(i)), Args.EXPRESSION, tupExprs.get(i)));
            }
            structs.add(Expression.of(ExprType.STRUCT, Args.EXPRESSIONS, expressions));
        }

        // Due to `UNNEST_COLUMN_ONLY`, it is expected that the table alias be contained in the columns expression
        Expression aliasNameOnly = alias != null
                ? Expression.of(ExprType.TABLE_ALIAS, Args.COLUMNS, new ArrayList<>(List.of(alias.getThis())))
                : null;
        return unnestSql(Expression.of(ExprType.UNNEST,
                Args.EXPRESSIONS, new ArrayList<>(List.of(Exp.array(structs))), Args.ALIAS, aliasNameOnly));
    }

    private String returnspropertySql(Expression expression) {
        Expression this_ = expression.getThis();
        String thisSql;
        if (this_ != null && this_.is(ExprType.SCHEMA)) {
            thisSql = sql(this_, Args.THIS) + " <" + expressions(this_) + ">";
        } else {
            thisSql = sql(this_);
        }
        return "RETURNS " + thisSql;
    }

    private String bqCreateSql(Expression expression) {
        Expression returns = expression.find(ExprType.RETURNS_PROPERTY);
        if ("FUNCTION".equals(expression.text(Args.KIND)) && returns != null && returns.has(Args.IS_TABLE)) {
            expression.set(Args.KIND, "TABLE FUNCTION");

            Expression expr = expression.getExpression();
            if (expr != null && expr.is(ExprType.SUBQUERY, ExprType.LITERAL)) {
                expression.set(Args.EXPRESSION, expr.getThis());
            }
        }

        return createSql(expression);
    }

    // https://issuetracker.google.com/issues/162294746
    // workaround for bigquery bug when grouping by an expression and then ordering
    // WITH x AS (SELECT 1 y)
    // SELECT y + 1 z
    // FROM x
    // GROUP BY x + 1
    // ORDER by z
    static Expression aliasOrderedGroup(Expression expression) {
        if (expression.is(ExprType.SELECT)) {
            Expression group = expression.expr(Args.GROUP);
            Expression order = expression.expr(Args.ORDER);

            if (group != null && order != null) {
                Map<Expression, Expression> aliases = new HashMap<>();
                for (Expression select : expression.selects()) {
                    if (select.is(ExprType.ALIAS)) {
                        aliases.put(select.getThis(), select.expr(Args.ALIAS));
                    }
                }

                for (Expression grouped : new ArrayList<>(group.getExpressions())) {
                    if (grouped.isInt()) {
                        continue;
                    }
                    Expression alias = aliases.get(grouped);
                    if (alias != null) {
                        grouped.replace(Exp.column(alias));
                    }
                }
            }
        }

        return expression;
    }

    /** BigQuery doesn't allow column names when defining a CTE, so we try to push them down. */
    static Expression pushdownCteColumnNames(Expression expression) {
        if (expression.is(ExprType.CTE) && !expression.aliasColumnNames().isEmpty()) {
            Expression cteQuery = expression.getThis();

            if (cteQuery.isStar()) {
                LOGGER.warning("Can't push down CTE column names for star queries. Run the query through"
                        + " the optimizer or use 'qualify' to expand the star projections first.");
                return expression;
            }

            List<String> columnNames = expression.aliasColumnNames();
            expression.expr(Args.ALIAS).set(Args.COLUMNS, null);

            List<Expression> selects = new ArrayList<>(cteQuery.selects());
            for (int i = 0; i < Math.min(columnNames.size(), selects.size()); i++) {
                String name = columnNames.get(i);
                Expression select = selects.get(i);
                Expression toReplace = select;

                if (select.is(ExprType.ALIAS)) {
                    select = select.getThis();
                }

                // Inner aliases are shadowed by the CTE column names
                toReplace.replace(Exp.alias(select, name));
            }
        }

        return expression;
    }

    /**
     * Rewrites exploding GENERATE_SERIES projections into table references, since BigQuery can't
     * explode in the projection and must unnest it in the FROM clause instead. The resulting table
     * reference is unnested downstream by {@code transforms.unnest_generate_series}.
     */
    static Expression unnestExplodeGenerateSeries(Expression expression) {
        if (expression.is(ExprType.SELECT)) {
            for (Expression projection : new ArrayList<>(expression.selects())) {
                Expression series = projection.unalias();
                if (series != null && series.is(ExprType.EXPLODING_GENERATE_SERIES)) {
                    String columnName = projection.outputName();
                    if (columnName == null || columnName.isEmpty()) {
                        columnName = "_gen_series_value";
                    }

                    projection.replace(Exp.column(columnName));
                    Expression table = Expression.of(ExprType.TABLE,
                            Args.THIS, series,
                            Args.ALIAS, Expression.of(ExprType.TABLE_ALIAS, Args.THIS, Exp.toIdentifier(columnName)));

                    if (expression.has(Args.FROM_)) {
                        expression.join(table, null, null, "CROSS", null, false);
                    } else {
                        expression.set(Args.FROM_, Expression.of(ExprType.FROM, Args.THIS, table));
                    }
                }
            }
        }

        return expression;
    }

    private String arrayContainsSql(Expression expression) {
        Expression unnest = Exp.tableAlias(
                Expression.of(ExprType.UNNEST, Args.EXPRESSIONS, new ArrayList<>(List.of(expression.getThis()))),
                "_unnest", List.of("_col"));
        Expression query = Exp.select("1")
                .from(unnest, true)
                .where(true, true, Exp.column("_col").eq(expression.getExpression()));
        return sql(Expression.of(ExprType.EXISTS, Args.THIS, query));
    }

    private String tsOrDsAddSql(Expression expression) {
        return DialectHelpers.dateAddIntervalSql(this, DialectHelpers.tsOrDsAddCast(expression), "DATE", "ADD");
    }

    private String tsOrDsDiffSql(Expression expression) {
        expression.getThis().replace(Exp.cast(expression.getThis(), DType.TIMESTAMP));
        expression.getExpression().replace(Exp.cast(expression.getExpression(), DType.TIMESTAMP));
        Expression unit = DialectHelpers.unitToVar(expression);
        return func("DATE_DIFF", expression.getThis(), expression.getExpression(), unit);
    }

    private String unixToTimeSql(Expression expression) {
        Expression scale = expression.expr(Args.SCALE);
        Expression timestamp = expression.getThis();

        if (scale == null || scale.equals(Exp.number(0))) {
            return func("TIMESTAMP_SECONDS", timestamp);
        }
        if (scale.equals(Exp.number(3))) {
            return func("TIMESTAMP_MILLIS", timestamp);
        }
        if (scale.equals(Exp.number(6))) {
            return func("TIMESTAMP_MICROS", timestamp);
        }

        Expression unixSeconds = Exp.cast(
                Expression.of(ExprType.DIV, Args.THIS, timestamp, Args.EXPRESSION, Exp.func("POW", null, 10, scale)),
                DType.BIGINT);
        return func("TIMESTAMP_SECONDS", unixSeconds);
    }

    @SuppressWarnings("unchecked")
    private String strToDatetimeSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String dtype = expression.is(ExprType.STR_TO_DATE) ? "DATE" : "TIMESTAMP";

        if (expression.has(Args.SAFE)) {
            String fmt = formatTime(expression, (Map<String, Object>) (Map<String, ?>) dd.INVERSE_FORMAT_MAPPING,
                    dialect.inverseFormatTrie());
            return "SAFE_CAST(" + this_ + " AS " + dtype + " FORMAT " + fmt + ")";
        }

        String fmt = formatTime(expression);
        return func("PARSE_" + dtype, fmt, this_, expression.get(Args.ZONE));
    }

    private String levenshteinSql(Expression expression) {
        unsupportedArgs(expression, Args.INS_COST, Args.DEL_COST, Args.SUB_COST);
        Expression maxDist = expression.expr(Args.MAX_DIST);
        if (maxDist != null) {
            maxDist = Expression.of(ExprType.KWARG, Args.THIS, Exp.var("max_distance"), Args.EXPRESSION, maxDist);
        }

        return func("EDIT_DISTANCE", expression.getThis(), expression.getExpression(), maxDist);
    }

    private String jsonExtractSql(Expression expression) {
        String upper = Py.upper(functionName(expression));

        boolean dquoteEscaping = DQUOTES_ESCAPING_JSON_FUNCTIONS.contains(upper);

        if (dquoteEscaping) {
            quoteJsonPathKeyUsingBrackets = false;
        }

        String sqlText = renameFunc(upper, expression);

        if (dquoteEscaping) {
            quoteJsonPathKeyUsingBrackets = true;
        }

        return sqlText;
    }

    // ------------------------------------------------------------------------------------------
    // TRANSFORMS

    @Override
    public String transform(Expression e) {
        return switch (e.type()) {
            case ARG_MAX -> DialectHelpers.argMaxOrMinNoCount(this, e, "MAX_BY");
            case ARG_MIN -> DialectHelpers.argMaxOrMinNoCount(this, e, "MIN_BY");
            case ARRAY -> DialectHelpers.inlineArrayUnlessQuery(this, e);
            case ARRAY_CONTAINS -> arrayContainsSql(e);
            case ARRAY_FILTER, ARRAY_REMOVE -> DialectHelpers.filterArrayUsingUnnest(this, e);
            case CAST -> applyTransforms(e, Transforms::removePrecisionParameterizedTypes);
            case COLLATE_PROPERTY -> e.has(Args.DEFAULT)
                    ? "DEFAULT COLLATE " + sql(e, Args.THIS)
                    : "COLLATE " + sql(e, Args.THIS);
            case COMMIT -> "COMMIT TRANSACTION";
            case CREATE -> bqCreateSql(e);
            case CTE -> applyTransforms(e, BigQueryGenerator::pushdownCteColumnNames);
            case DATE_ADD -> DialectHelpers.dateAddIntervalSql(this, e, "DATE", "ADD");
            case DATE_DIFF -> func("DATE_DIFF", e.getThis(), e.getExpression(), DialectHelpers.unitToVar(e));
            case DATE_STR_TO_DATE -> DialectHelpers.datestrtodateSql(this, e);
            case DATE_SUB -> DialectHelpers.dateAddIntervalSql(this, e, "DATE", "SUB");
            case DATETIME_ADD -> DialectHelpers.dateAddIntervalSql(this, e, "DATETIME", "ADD");
            case DATETIME_SUB -> DialectHelpers.dateAddIntervalSql(this, e, "DATETIME", "SUB");
            case FILTER -> DialectHelpers.noFilterSql(this, e);
            case FROM_TIME_ZONE -> func("DATETIME", func("TIMESTAMP", e.getThis(), e.get(Args.ZONE)), "'UTC'");
            case GENERATE_SERIES -> DialectHelpers.generateSeriesSql(this, e, "GENERATE_ARRAY");
            case GROUP_CONCAT -> DialectHelpers.groupconcatSql(this, e, "STRING_AGG", null, false, false);
            case HEX -> func("UPPER", func("TO_HEX", sql(e, Args.THIS)));
            case HEX_STRING -> hexstringSql(e, "FROM_HEX");
            case IF -> DialectHelpers.ifSql(this, e, "IF", "NULL");
            case I_LIKE -> DialectHelpers.noIlikeSql(this, e);
            case JSON_EXTRACT, JSON_EXTRACT_ARRAY, JSON_EXTRACT_SCALAR -> jsonExtractSql(e);
            case JSON_FORMAT -> func(e.has(Args.TO_JSON) ? "TO_JSON" : "TO_JSON_STRING", e.getThis(), e.get(Args.OPTIONS));
            case LEVENSHTEIN -> levenshteinSql(e);
            case MAX -> DialectHelpers.maxOrGreatest(this, e);
            case MD5 -> func("TO_HEX", func("MD5", e.getThis()));
            case MIN -> DialectHelpers.minOrLeast(this, e);
            case NORMALIZE -> func(e.has(Args.IS_CASEFOLD) ? "NORMALIZE_AND_CASEFOLD" : "NORMALIZE",
                    e.getThis(), e.get(Args.FORM));
            case PARTITIONED_BY_PROPERTY -> "PARTITION BY " + sql(e, Args.THIS);
            case REGEXP_EXTRACT -> func("REGEXP_EXTRACT",
                    e.getThis(), e.getExpression(), e.get(Args.POSITION), e.get(Args.OCCURRENCE));
            case REGEXP_EXTRACT_ALL -> func("REGEXP_EXTRACT_ALL", e.getThis(), e.getExpression());
            case REGEXP_REPLACE -> DialectHelpers.regexpReplaceSql(this, e);
            case RETURNS_PROPERTY -> returnspropertySql(e);
            case ROLLBACK -> "ROLLBACK TRANSACTION";
            case PARSE_TIME -> func("PARSE_TIME", formatTime(e), e.getThis());
            case PARSE_DATETIME -> func("PARSE_DATETIME", formatTime(e), e.getThis());
            case SELECT -> applyTransforms(e,
                    BigQueryGenerator::unnestExplodeGenerateSeries,
                    Transforms.explodeProjectionToUnnest(),
                    Transforms::unqualifyUnnest,
                    Transforms::eliminateDistinctOn,
                    BigQueryGenerator::aliasOrderedGroup,
                    Transforms::eliminateSemiAndAntiJoins);
            case SHA2 -> DialectHelpers.sha256Sql(this, e);
            case SHA2_DIGEST -> DialectHelpers.sha2DigestSql(this, e);
            case STABILITY_PROPERTY -> e.name().equals("IMMUTABLE") ? "DETERMINISTIC" : "NOT DETERMINISTIC";
            case STR_POSITION -> DialectHelpers.strpositionSql(this, e, "INSTR", true, true, true);
            case STR_TO_DATE, STR_TO_TIME -> strToDatetimeSql(e);
            case SESSION_USER -> "SESSION_USER()";
            case TABLE -> applyTransforms(e, Transforms::unnestGenerateSeries);
            case TIME_ADD -> DialectHelpers.dateAddIntervalSql(this, e, "TIME", "ADD");
            case TIME_SUB -> DialectHelpers.dateAddIntervalSql(this, e, "TIME", "SUB");
            case TIMESTAMP_ADD -> DialectHelpers.dateAddIntervalSql(this, e, "TIMESTAMP", "ADD");
            case TIMESTAMP_SUB -> DialectHelpers.dateAddIntervalSql(this, e, "TIMESTAMP", "SUB");
            case TIME_STR_TO_TIME -> DialectHelpers.timestrtotimeSql(this, e);
            case TRANSACTION -> "BEGIN TRANSACTION";
            case TS_OR_DS_ADD -> tsOrDsAddSql(e);
            case TS_OR_DS_DIFF -> tsOrDsDiffSql(e);
            case UNIX_TO_TIME -> unixToTimeSql(e);
            case UUID -> "GENERATE_UUID()";
            case VALUES -> derivedTableValuesToUnnest(e);
            default -> super.transform(e);
        };
    }

    // ------------------------------------------------------------------------------------------
    // *_sql methods the base generator lacks

    @Override
    public String sqlFor(Expression e) {
        return switch (e.type()) {
            case DATE_TRUNC -> datetruncSql(e);
            case TIME_TO_STR -> timetostrSql(e);
            case CONTAINS -> containsSql(e);
            default -> super.sqlFor(e);
        };
    }

    public String datetruncSql(Expression expression) {
        Expression unit = expression.expr(Args.UNIT);
        Object unitSql = unit != null && unit.isString() ? unit.name() : sql(unit);
        return func("DATE_TRUNC", expression.getThis(), unitSql, expression.get(Args.ZONE));
    }

    public String timetostrSql(Expression expression) {
        Expression this_ = expression.getThis();
        String funcName;
        if (this_ != null && this_.is(ExprType.TS_OR_DS_TO_DATETIME)) {
            funcName = "FORMAT_DATETIME";
        } else if (this_ != null && this_.is(ExprType.TS_OR_DS_TO_TIMESTAMP)) {
            funcName = "FORMAT_TIMESTAMP";
        } else if (this_ != null && this_.is(ExprType.TS_OR_DS_TO_TIME)) {
            funcName = "FORMAT_TIME";
        } else {
            funcName = "FORMAT_DATE";
        }

        Expression timeExpr = this_ != null && g.TS_OR_DS_TYPES.contains(this_.type()) ? this_ : expression;
        return func(funcName, formatTime(expression), timeExpr.getThis(), expression.get(Args.ZONE));
    }

    public String containsSql(Expression expression) {
        Expression this_ = expression.getThis();
        Expression expr = expression.getExpression();

        if (this_ != null && this_.is(ExprType.LOWER) && expr != null && expr.is(ExprType.LOWER)) {
            this_ = this_.getThis();
            expr = expr.getThis();
        }

        return func("CONTAINS_SUBSTR", this_, expr, expression.get(Args.JSON_SCOPE));
    }

    // ------------------------------------------------------------------------------------------
    // Overridden *_sql methods

    @Override
    public String weekstartSql(Expression expression) {
        if (Py.upper(expression.getThis().name()).equals("SUNDAY")) {
            // BigQuery specific optimization since WEEK(SUNDAY) == WEEK
            return "WEEK";
        }

        return func("WEEK", expression.getThis());
    }

    @Override
    public String modSql(Expression expression) {
        Expression this_ = expression.getThis();
        Expression expr = expression.getExpression();
        return func("MOD",
                this_ != null && this_.is(ExprType.PAREN) ? this_.unnest() : this_,
                expr != null && expr.is(ExprType.PAREN) ? expr.unnest() : expr);
    }

    private static String joinNames(List<Expression> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(parts.get(i).name());
        }
        return sb.toString();
    }

    @Override
    public String columnParts(Expression expression) {
        if (Expression.truthy(expression.metaGet("quoted_column"))) {
            // If a column reference is of the form `dataset.table`.name, we need
            // to preserve the quoted table path, otherwise the reference breaks
            List<Expression> parts = expression.parts();
            String tableParts = joinNames(parts.subList(0, Math.max(0, parts.size() - 1)));
            String tablePath = sql(Expression.of(ExprType.IDENTIFIER, Args.THIS, tableParts, Args.QUOTED, Boolean.TRUE));
            return tablePath + "." + sql(expression, Args.THIS);
        }

        return super.columnParts(expression);
    }

    @Override
    public String tableParts(Expression expression) {
        // Depending on the context, `x.y` may not resolve to the same data source as `x`.`y`, so
        // we need to make sure the correct quoting is used in each case.
        //
        // For example, if there is a CTE x that clashes with a schema name, then the former will
        // return the table y in that schema, whereas the latter will return the CTE's y column:
        //
        // - WITH x AS (SELECT [1, 2] AS y) SELECT * FROM x, `x.y`   -> cross join
        // - WITH x AS (SELECT [1, 2] AS y) SELECT * FROM x, `x`.`y` -> implicit unnest
        if (Expression.truthy(expression.metaGet("quoted_table"))) {
            String tableParts = joinNames(expression.parts());
            return sql(Expression.of(ExprType.IDENTIFIER, Args.THIS, tableParts, Args.QUOTED, Boolean.TRUE));
        }

        return super.tableParts(expression);
    }

    @Override
    public String eqSql(Expression expression) {
        // Operands of = cannot be NULL in BigQuery
        if (Expression.isInstance(expression.get(Args.THIS), ExprType.NULL)
                || Expression.isInstance(expression.get(Args.EXPRESSION), ExprType.NULL)) {
            if (!Expression.isInstance(expression.parent(), ExprType.UPDATE)) {
                return "NULL";
            }
        }

        return binary(expression, "=");
    }

    @Override
    public String attimezoneSql(Expression expression) {
        Expression parent = expression.parent();

        // BigQuery allows CAST(.. AS {STRING|TIMESTAMP} [FORMAT <fmt> [AT TIME ZONE <tz>]]).
        // Only the TIMESTAMP one should use the below conversion, when AT TIME ZONE is included.
        if (parent == null || !parent.is(ExprType.CAST) || !parent.expr(Args.TO).isType("text")) {
            return func("TIMESTAMP", func("DATETIME", expression.getThis(), expression.get(Args.ZONE)));
        }

        return super.attimezoneSql(expression);
    }

    @Override
    public String trycastSql(Expression expression) {
        return castSql(expression, "SAFE_");
    }

    @Override
    public String bracketSql(Expression expression) {
        Expression this_ = expression.getThis();
        List<Expression> expressions = expression.getExpressions();

        if (expressions.size() == 1 && this_ != null && this_.isType(DType.STRUCT)) {
            Expression arg = expressions.get(0);
            if (arg.dataType() == null) {
                arg = TypeAnnotator.annotateTypes(arg, dialect);
            }

            Expression argType = arg.dataType();
            if (argType != null && argType.thisValue() instanceof DType dt && DataTypes.TEXT_TYPES.contains(dt)) {
                // BQ doesn't support bracket syntax with string values for structs
                return sql(this_) + "." + arg.name();
            }
        }

        String expressionsSql = expressions(opts(expression).flat());
        Object offset = expression.get(Args.OFFSET);

        if (isIntValue(offset, 0)) {
            expressionsSql = "OFFSET(" + expressionsSql + ")";
        } else if (isIntValue(offset, 1)) {
            expressionsSql = "ORDINAL(" + expressionsSql + ")";
        } else if (offset != null) {
            unsupported("Unsupported array offset: " + offset);
        }

        if (expression.has(Args.SAFE)) {
            expressionsSql = "SAFE_" + expressionsSql;
        }

        return sql(this_) + "[" + expressionsSql + "]";
    }

    private static boolean isIntValue(Object value, int n) {
        if (value instanceof Boolean b) {
            return (b ? 1 : 0) == n;
        }
        return value instanceof Number num && num.doubleValue() == n;
    }

    @Override
    public String inUnnestOp(Expression unnest) {
        return sql(unnest);
    }

    @Override
    public String versionSql(Expression expression) {
        if (expression.name().equals("TIMESTAMP")) {
            expression.set(Args.THIS, "SYSTEM_TIME");
        }
        return super.versionSql(expression);
    }

    @Override
    public String parsejsonSql(Expression expression) {
        if (expression.has(Args.IS_LITERAL)) {
            return "JSON " + sql(expression, Args.THIS);
        }
        return super.parsejsonSql(expression);
    }

    @Override
    public String castSql(Expression expression, String safePrefix) {
        Expression this_ = expression.getThis();

        // This ensures that inline type-annotated ARRAY literals like ARRAY<INT64>[1, 2, 3]
        // are roundtripped unaffected. The inner check excludes ARRAY(SELECT ...) expressions,
        // because they aren't literals and so the above syntax is invalid BigQuery.
        if (this_ != null && this_.is(ExprType.ARRAY)) {
            Expression elem = Py.seqGet(this_.getExpressions(), 0);
            if (!(elem != null && elem.find(ExprType.QUERY) != null)) {
                return sql(expression, Args.TO) + sql(this_);
            }
        }

        return super.castSql(expression, safePrefix);
    }

    @Override
    public String clusterpropertySql(Expression expression) {
        if (expression.has(Args.THIS)) {
            unsupported("Unsupported CLUSTER BY " + sql(expression, Args.THIS));
            return "";
        }
        return opExpressions("CLUSTER BY", expression, false);
    }
}
