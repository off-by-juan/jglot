package io.github.offbyjuan.jglot.generator;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.optimizer.Scopes;
import io.github.offbyjuan.jglot.optimizer.Simplify;
import io.github.offbyjuan.jglot.optimizer.TypeAnnotator;
import io.github.offbyjuan.jglot.util.Py;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Port of sqlglot's Generator, part E: {@code columnprefix_sql} to the end of the class. */
abstract class GeneratorPartE extends GeneratorMethods {
    GeneratorPartE(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }

    // ------------------------------------------------------------------------------------------
    // Private helpers (module-level sqlglot functions / data not available as shared APIs)

    /** sqlglot.dialects.tsql.TSQL.CONVERT_FORMAT_MAPPING. */
    private static final Map<String, String> TSQL_CONVERT_FORMAT_MAPPING = Map.ofEntries(
            Map.entry("0", "%b %d %Y %-I:%M%p"),
            Map.entry("1", "%m/%d/%y"),
            Map.entry("2", "%y.%m.%d"),
            Map.entry("3", "%d/%m/%y"),
            Map.entry("4", "%d.%m.%y"),
            Map.entry("5", "%d-%m-%y"),
            Map.entry("6", "%d %b %y"),
            Map.entry("7", "%b %d, %y"),
            Map.entry("8", "%H:%M:%S"),
            Map.entry("9", "%b %d %Y %-I:%M:%S:%f%p"),
            Map.entry("10", "mm-dd-yy"),
            Map.entry("11", "yy/mm/dd"),
            Map.entry("12", "yymmdd"),
            Map.entry("13", "%d %b %Y %H:%M:ss:%f"),
            Map.entry("14", "%H:%M:%S:%f"),
            Map.entry("20", "%Y-%m-%d %H:%M:%S"),
            Map.entry("21", "%Y-%m-%d %H:%M:%S.%f"),
            Map.entry("22", "%m/%d/%y %-I:%M:%S %p"),
            Map.entry("23", "%Y-%m-%d"),
            Map.entry("24", "%H:%M:%S"),
            Map.entry("25", "%Y-%m-%d %H:%M:%S.%f"),
            Map.entry("100", "%b %d %Y %-I:%M%p"),
            Map.entry("101", "%m/%d/%Y"),
            Map.entry("102", "%Y.%m.%d"),
            Map.entry("103", "%d/%m/%Y"),
            Map.entry("104", "%d.%m.%Y"),
            Map.entry("105", "%d-%m-%Y"),
            Map.entry("106", "%d %b %Y"),
            Map.entry("107", "%b %d, %Y"),
            Map.entry("108", "%H:%M:%S"),
            Map.entry("109", "%b %d %Y %-I:%M:%S:%f%p"),
            Map.entry("110", "%m-%d-%Y"),
            Map.entry("111", "%Y/%m/%d"),
            Map.entry("112", "%Y%m%d"),
            Map.entry("113", "%d %b %Y %H:%M:%S:%f"),
            Map.entry("114", "%H:%M:%S:%f"),
            Map.entry("120", "%Y-%m-%d %H:%M:%S"),
            Map.entry("121", "%Y-%m-%d %H:%M:%S.%f"),
            Map.entry("126", "%Y-%m-%dT%H:%M:%S.%f"));

    /** sqlglot.dialects.dialect.WEEK_START_DAY_TO_DOW. */
    private static final Map<String, Integer> WEEK_START_DAY_TO_DOW = Map.of(
            "MONDAY", 1,
            "TUESDAY", 2,
            "WEDNESDAY", 3,
            "THURSDAY", 4,
            "FRIDAY", 5,
            "SATURDAY", 6,
            "SUNDAY", 7);

    /** sqlglot.dialects.dialect.week_offset_to_dow. */
    private static int weekOffsetToDow(int offset) {
        return Math.floorMod(offset, 7) + 1;
    }

    /** sqlglot.dialects.dialect.unit_to_str(expression) with default="DAY". */
    private static Expression unitToStr(Expression expression) {
        String default_ = "DAY";
        Expression unit = expression.expr(Args.UNIT);
        if (unit == null) {
            return Exp.string(default_);
        }
        if (unit.is(ExprType.WEEK_START)) {
            return Exp.string("WEEK");
        }
        if (unit.is(ExprType.PLACEHOLDER) || (unit.type() != ExprType.VAR && unit.type() != ExprType.LITERAL)) {
            return unit;
        }
        return Exp.string(unit.name());
    }

    /** {@code expr.is_(other)}: sqlglot's {@code _binop(Is, other)}. */
    private static Expression isOp(Expression self, Expression other) {
        Expression thisExpr = self.copy();
        Expression otherExpr = other.copy();
        if (!thisExpr.is(ExprType.IS) && !otherExpr.is(ExprType.IS)) {
            thisExpr = Exp.wrap(thisExpr, ExprType.BINARY);
            otherExpr = Exp.wrap(otherExpr, ExprType.BINARY);
        }
        return Expression.of(ExprType.IS, Args.THIS, thisExpr, Args.EXPRESSION, otherExpr);
    }

    /** Python's {@code f"{value}"} for a raw arg value. */
    private static String pyStr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (value instanceof Expression e) {
            // str(expression) renders it with the default dialect
            return e.sql(io.github.offbyjuan.jglot.dialects.Dialects.get(""));
        }
        return value.toString();
    }

    private Pattern safeJsonPathKeyRe;

    private Pattern safeJsonPathKeyRe() {
        if (safeJsonPathKeyRe == null) {
            safeJsonPathKeyRe = Pattern.compile(g.SAFE_JSON_PATH_KEY_RE, Pattern.UNICODE_CHARACTER_CLASS);
        }
        return safeJsonPathKeyRe;
    }

    // ------------------------------------------------------------------------------------------

    @Override
    public String columnprefixSql(Expression expression) {
        return sql(expression, Args.THIS) + "(" + sql(expression, Args.EXPRESSION) + ")";
    }

    @Override
    public String opclassSql(Expression expression) {
        return sql(expression, Args.THIS) + " " + sql(expression, Args.EXPRESSION);
    }

    @Override
    public String mlSql(Expression expression, String name) {
        String model = sql(expression, Args.THIS);
        model = "MODEL " + model;
        Expression expr = expression.getExpression();
        String exprSql;
        if (expr != null) {
            exprSql = sql(expression, Args.EXPRESSION);
            exprSql = expr.is(ExprType.TABLE) ? "TABLE " + exprSql : exprSql;
        } else {
            exprSql = null;
        }

        String parameters = sql(expression, Args.PARAMS_STRUCT);
        if (parameters.isEmpty()) {
            parameters = null;
        }

        return func(name, model, exprSql, parameters);
    }

    @Override
    public String predictSql(Expression expression) {
        return mlSql(expression, "PREDICT");
    }

    @Override
    public String generateembeddingSql(Expression expression) {
        String name = expression.has(Args.IS_TEXT) ? "GENERATE_TEXT_EMBEDDING" : "GENERATE_EMBEDDING";
        return mlSql(expression, name);
    }

    @Override
    public String generatetextSql(Expression expression) {
        return mlSql(expression, "GENERATE_TEXT");
    }

    @Override
    public String generatetableSql(Expression expression) {
        return mlSql(expression, "GENERATE_TABLE");
    }

    @Override
    public String generateboolSql(Expression expression) {
        return mlSql(expression, "GENERATE_BOOL");
    }

    @Override
    public String generateintSql(Expression expression) {
        return mlSql(expression, "GENERATE_INT");
    }

    @Override
    public String generatedoubleSql(Expression expression) {
        return mlSql(expression, "GENERATE_DOUBLE");
    }

    @Override
    public String mltranslateSql(Expression expression) {
        return mlSql(expression, "TRANSLATE");
    }

    @Override
    public String mlforecastSql(Expression expression) {
        return mlSql(expression, "FORECAST");
    }

    @Override
    public String aiforecastSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        if (expression.getThis() != null && expression.getThis().is(ExprType.TABLE)) {
            thisSql = "TABLE " + thisSql;
        }

        return func(
                "FORECAST",
                thisSql,
                expression.get(Args.DATA_COL),
                expression.get(Args.TIMESTAMP_COL),
                expression.get(Args.MODEL),
                expression.get(Args.ID_COLS),
                expression.get(Args.HORIZON),
                expression.get(Args.FORECAST_END_TIMESTAMP),
                expression.get(Args.CONFIDENCE_LEVEL),
                expression.get(Args.OUTPUT_HISTORICAL_TIME_SERIES),
                expression.get(Args.CONTEXT_WINDOW));
    }

    @Override
    public String featuresattimeSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        if (expression.getThis() != null && expression.getThis().is(ExprType.TABLE)) {
            thisSql = "TABLE " + thisSql;
        }

        return func(
                "FEATURES_AT_TIME",
                thisSql,
                expression.get(Args.TIME),
                expression.get(Args.NUM_ROWS),
                expression.get(Args.IGNORE_FEATURE_NULLS));
    }

    @Override
    public String vectorsearchSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        if (expression.getThis() != null && expression.getThis().is(ExprType.TABLE)) {
            thisSql = "TABLE " + thisSql;
        }

        String queryTable = sql(expression, Args.QUERY_TABLE);
        if (Expression.isInstance(expression.get(Args.QUERY_TABLE), ExprType.TABLE)) {
            queryTable = "TABLE " + queryTable;
        }

        return func(
                "VECTOR_SEARCH",
                thisSql,
                expression.get(Args.COLUMN_TO_SEARCH),
                queryTable,
                expression.get(Args.QUERY_COLUMN_TO_SEARCH),
                expression.get(Args.TOP_K),
                expression.get(Args.DISTANCE_TYPE),
                expression.get(Args.OPTIONS));
    }

    @Override
    public String forinSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expressionSql = sql(expression, Args.EXPRESSION);
        return "FOR " + thisSql + " DO " + expressionSql;
    }

    @Override
    public String refreshSql(Expression expression) {
        unsupportedArgs(expression, Args.PARTITION_START, Args.PARTITION_END, Args.FORCE, Args.MODE);
        String thisSql = sql(expression, Args.THIS);
        String kind = expression.getThis() != null && expression.getThis().is(ExprType.LITERAL)
                ? ""
                : expression.text(Args.KIND) + " ";
        return "REFRESH " + kind + thisSql;
    }

    @Override
    public String toarraySql(Expression expression) {
        Expression arg = expression.getThis();
        if (arg.dataType() == null) {
            arg = TypeAnnotator.annotateTypes(arg, dialect);
        }

        if (arg.isType(DType.ARRAY)) {
            return sql(arg);
        }

        Expression condForNull = isOp(arg, Exp.nul());
        return sql(Exp.func("IF", null, condForNull, Exp.nul(), Exp.array(List.of(arg))));
    }

    @Override
    public String tsordstotimeSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        String timeFormat = formatTime(expression);

        if (timeFormat != null && !timeFormat.isEmpty()) {
            return sql(
                    Exp.cast(
                            Expression.of(ExprType.STR_TO_TIME, Args.THIS, thisExpr, Args.FORMAT, expression.get(Args.FORMAT)),
                            DType.TIME));
        }

        if (thisExpr.is(ExprType.TS_OR_DS_TO_TIME) || thisExpr.isType(DType.TIME)) {
            return sql(thisExpr);
        }

        return sql(Exp.cast(thisExpr, DType.TIME));
    }

    @Override
    public String tsordstotimestampSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        if (thisExpr.is(ExprType.TS_OR_DS_TO_TIMESTAMP) || thisExpr.isType(DType.TIMESTAMP)) {
            return sql(thisExpr);
        }

        return sql(Exp.cast(thisExpr, DType.TIMESTAMP, true, dialect));
    }

    @Override
    public String tsordstodatetimeSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        if (thisExpr.is(ExprType.TS_OR_DS_TO_DATETIME) || thisExpr.isType(DType.DATETIME)) {
            return sql(thisExpr);
        }

        return sql(Exp.cast(thisExpr, DType.DATETIME, true, dialect));
    }

    @Override
    public String tsordstodateSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        String timeFormat = formatTime(expression);
        Object safe = expression.get(Args.SAFE);
        if (timeFormat != null && !timeFormat.isEmpty()
                && !timeFormat.equals(dd.TIME_FORMAT) && !timeFormat.equals(dd.DATE_FORMAT)) {
            return sql(
                    Exp.cast(
                            Expression.of(ExprType.STR_TO_TIME, Args.THIS, thisExpr, Args.FORMAT, expression.get(Args.FORMAT),
                                    Args.SAFE, safe),
                            DType.DATE));
        }

        if (thisExpr.is(ExprType.TS_OR_DS_TO_DATE) || thisExpr.isType(DType.DATE)) {
            return sql(thisExpr);
        }

        if (Expression.truthy(safe)) {
            return sql(Expression.of(ExprType.TRY_CAST, Args.THIS, thisExpr, Args.TO, DataTypes.of(DType.DATE)));
        }

        return sql(Exp.cast(thisExpr, DType.DATE));
    }

    @Override
    public String unixdateSql(Expression expression) {
        return sql(
                Exp.func(
                        "DATEDIFF",
                        null,
                        expression.getThis(),
                        Exp.cast(Exp.string("1970-01-01"), DType.DATE),
                        "day"));
    }

    @Override
    public String lastdaySql(Expression expression) {
        if (g.LAST_DAY_SUPPORTS_DATE_PART) {
            return functionFallbackSql(expression);
        }

        Expression unit = expression.expr(Args.UNIT);
        if (unit != null && !Py.upper(unit.name()).equals("MONTH")) {
            unsupported("Date parts are not supported in LAST_DAY.");
        }

        return func("LAST_DAY", expression.getThis());
    }

    @Override
    public String dateaddSql(Expression expression) {
        return func(
                "DATE_ADD",
                expression.getThis(),
                expression.getExpression(),
                unitToStr(expression));
    }

    @Override
    public String arrayinsertSql(Expression expression, int indexOffset) {
        Expression thisExpr = expression.getThis();
        Expression position = expression.expr(Args.POSITION);
        Object offsetArg = expression.get(Args.OFFSET);
        int offset = indexOffset - (Expression.truthy(offsetArg) ? ((Number) offsetArg).intValue() : 0);

        if (offset != 0) {
            if (position.isInt()) {
                java.math.BigInteger value = new java.math.BigInteger(position.toPy().toString());
                if (value.signum() >= 0) {
                    position = Exp.number(value.add(java.math.BigInteger.valueOf(offset)));
                } else if (offset < 0 && value.equals(java.math.BigInteger.valueOf(-1))) {
                    // 1-based -1 appends, which a 0-based position can only express as the size
                    position = Expression.of(ExprType.ARRAY_SIZE, Args.THIS, thisExpr.copy());
                } else {
                    // Negative positions count from the end, so they shift in the opposite
                    // direction, e.g. 0-based -1 (before the last element) is 1-based -2
                    position = Exp.number(value.subtract(java.math.BigInteger.valueOf(offset)));
                }
            } else {
                unsupported("ARRAY_INSERT position can only be converted if it's a literal");
            }
        }

        return func("ARRAY_INSERT", thisExpr, position, expression.getExpression());
    }

    @Override
    public String arrayanySql(Expression expression) {
        if (g.CAN_IMPLEMENT_ARRAY_ANY) {
            Expression filtered = Expression.of(ExprType.ARRAY_FILTER, Args.THIS, expression.getThis(),
                    Args.EXPRESSION, expression.getExpression());
            Expression filteredNotEmpty = Expression.of(ExprType.ARRAY_SIZE, Args.THIS, filtered).neq(0);
            Expression originalIsEmpty = Expression.of(ExprType.ARRAY_SIZE, Args.THIS, expression.getThis()).eq(0);
            return sql(Exp.paren(originalIsEmpty.or(true, filteredNotEmpty)));
        }

        // SQLGlot's executor supports ARRAY_ANY, so we don't wanna warn for the SQLGlot dialect
        if (dialect.getClass() != Dialect.class) {
            unsupported("ARRAY_ANY is unsupported");
        }

        return functionFallbackSql(expression);
    }

    @Override
    public String structSql(Expression expression) {
        List<Expression> newExpressions = new ArrayList<>();
        for (Expression e : expression.getExpressions()) {
            if (e.is(ExprType.PROPERTY_EQ)) {
                newExpressions.add(Exp.alias(e.getExpression(), e.getThis().isString() ? e.name() : e.getThis()));
            } else {
                newExpressions.add(e);
            }
        }
        expression.set(Args.EXPRESSIONS, newExpressions);

        return functionFallbackSql(expression);
    }

    @Override
    public String partitionrangeSql(Expression expression) {
        String low = sql(expression, Args.THIS);
        String high = sql(expression, Args.EXPRESSION);

        return low + " TO " + high;
    }

    @Override
    public String truncatetableSql(Expression expression) {
        String target = expression.has(Args.IS_DATABASE) ? "DATABASE" : "TABLE";
        String tables = " " + expressions(expression);

        String exists = expression.has(Args.EXISTS) ? " IF EXISTS" : "";

        String onCluster = sql(expression, Args.CLUSTER);
        onCluster = !onCluster.isEmpty() ? " " + onCluster : "";

        String identity = sql(expression, Args.IDENTITY);
        identity = !identity.isEmpty() ? " " + identity + " IDENTITY" : "";

        String option = sql(expression, Args.OPTION);
        option = !option.isEmpty() ? " " + option : "";

        String partition = sql(expression, Args.PARTITION);
        partition = !partition.isEmpty() ? " " + partition : "";

        return "TRUNCATE " + target + exists + tables + onCluster + identity + option + partition;
    }

    // This transpiles T-SQL's CONVERT function
    // https://learn.microsoft.com/en-us/sql/t-sql/functions/cast-and-convert-transact-sql?view=sql-server-ver16
    @Override
    public String convertSql(Expression expression) {
        Expression to = expression.getThis();
        Expression value = expression.getExpression();
        Object style = expression.get(Args.STYLE);
        Object safe = expression.get(Args.SAFE);
        Object strict = expression.get(Args.STRICT);

        if (to == null || value == null) {
            return "";
        }

        // Retrieve length of datatype and override to default if not specified
        if (Py.seqGet(to.getExpressions(), 0) == null && g.PARAMETERIZABLE_TEXT_TYPES.contains(to.thisValue())) {
            to = Expression.of(ExprType.DATA_TYPE, Args.THIS, to.thisValue(),
                    Args.EXPRESSIONS, new ArrayList<>(List.of(Exp.number(30))), Args.NESTED, Boolean.FALSE);
        }

        Expression transformed = null;
        ExprType cast = Expression.truthy(strict) ? ExprType.CAST : ExprType.TRY_CAST;

        // Check whether a conversion with format (T-SQL calls this 'style') is applicable
        if (style instanceof Expression styleExpr && styleExpr.is(ExprType.LITERAL) && styleExpr.isInt()) {
            String styleValue = styleExpr.name();
            String convertedStyle = TSQL_CONVERT_FORMAT_MAPPING.get(styleValue);
            if (convertedStyle == null || convertedStyle.isEmpty()) {
                unsupported("Unsupported T-SQL 'style' value: " + styleValue);
            }

            Expression fmt = Exp.string(convertedStyle);

            Object toThis = to.thisValue();
            if (toThis == DType.DATE) {
                transformed = Expression.of(ExprType.STR_TO_DATE, Args.THIS, value, Args.FORMAT, fmt);
            } else if (toThis == DType.DATETIME || toThis == DType.DATETIME2) {
                transformed = Expression.of(ExprType.STR_TO_TIME, Args.THIS, value, Args.FORMAT, fmt);
            } else if (g.PARAMETERIZABLE_TEXT_TYPES.contains(toThis)) {
                transformed = Expression.of(cast,
                        Args.THIS, Expression.of(ExprType.TIME_TO_STR, Args.THIS, value, Args.FORMAT, fmt),
                        Args.TO, to,
                        Args.SAFE, safe);
            } else if (toThis == DType.TEXT) {
                transformed = Expression.of(ExprType.TIME_TO_STR, Args.THIS, value, Args.FORMAT, fmt);
            }
        }

        if (transformed == null) {
            transformed = Expression.of(cast, Args.THIS, value, Args.TO, to, Args.SAFE, safe);
        }

        return sql(transformed);
    }

    @Override
    public String jsonpathkeySql(Expression expression) {
        Object thisValue = expression.thisValue();
        if (Expression.isInstance(thisValue, ExprType.JSON_PATH_WILDCARD)) {
            String part = jsonPathPart(thisValue);
            return !part.isEmpty() ? "." + part : "";
        }

        Object quoted = expression.get(Args.QUOTED);
        if (!(Expression.truthy(quoted) && g.JSON_PATH_KEY_QUOTED_FORCES_BRACKETS)
                && safeJsonPathKeyRe().matcher((String) thisValue).lookingAt()) {
            return "." + thisValue;
        }

        String part = jsonPathPart(thisValue);

        return quoteJsonPathKeyUsingBrackets && g.JSON_PATH_BRACKETED_KEY_SUPPORTED
                ? "[" + part + "]"
                : "." + part;
    }

    @Override
    public String jsonpathsubscriptSql(Expression expression) {
        String thisSql = jsonPathPart(expression.thisValue());
        return !thisSql.isEmpty() ? "[" + thisSql + "]" : "";
    }

    @Override
    public Expression simplifyUnlessLiteral(Expression expression) {
        if (!expression.is(ExprType.LITERAL)) {
            expression = Simplify.simplify(expression, dialect);
        }

        return expression;
    }

    @Override
    public String embedIgnoreNulls(Expression expression, String text) {
        Expression thisExpr = expression.getThis();
        if (thisExpr != null && isAny(thisExpr, g.RESPECT_IGNORE_NULLS_UNSUPPORTED_EXPRESSIONS)) {
            unsupported("RESPECT/IGNORE NULLS is not supported for " + thisExpr.key() + " in "
                    + dialect.getClass().getSimpleName());
            return sql(thisExpr);
        }

        if (g.IGNORE_NULLS_IN_FUNC && !Expression.truthy(expression.metaGet("inline"))) {
            if (g.IGNORE_NULLS_BEFORE_ORDER) {
                // The first modifier here will be the one closest to the AggFunc's arg
                List<Expression> mods = new ArrayList<>(
                        Scopes.findAllInScope(expression, ExprType.HAVING_MAX, ExprType.ORDER, ExprType.LIMIT));
                mods.sort((a, b) -> Integer.compare(modRank(a), modRank(b)));

                if (!mods.isEmpty()) {
                    Expression mod = mods.get(0);
                    Expression wrapped = Expression.of(expression.type(), Args.THIS, mod.getThis().copy());
                    wrapped.meta().put("inline", Boolean.TRUE);
                    mod.getThis().replace(wrapped);
                    return sql(expression.getThis());
                }
            }

            Expression aggFunc = expression.find(ExprType.AGG_FUNC);

            if (aggFunc != null) {
                String aggFuncSql = Py.slice(sql(aggFunc, false), null, -1) + " " + text + ")";
                return maybeComment(aggFuncSql, aggFunc.comments());
            }
        }

        return sql(expression, Args.THIS) + " " + text;
    }

    private static int modRank(Expression x) {
        return x.is(ExprType.HAVING_MAX) ? 0 : (x.is(ExprType.ORDER) ? 1 : 2);
    }

    @Override
    public String copyparameterSql(Expression expression) {
        String option = sql(expression, Args.THIS);

        if (!expression.getExpressions().isEmpty()) {
            String upper = Py.upper(option);

            // Snowflake FILE_FORMAT options are separated by whitespace
            String sep = upper.equals("FILE_FORMAT") ? " " : ", ";

            // Databricks copy/format options do not set their list of values with EQ
            String op = upper.equals("COPY_OPTIONS") || upper.equals("FORMAT_OPTIONS") ? " " : " = ";
            String values = expressions(opts(expression).flat().sep(sep));
            return option + op + "(" + values + ")";
        }

        String value = sql(expression, Args.EXPRESSION);

        if (value.isEmpty()) {
            return option;
        }

        String op = g.COPY_PARAMS_EQ_REQUIRED ? " = " : " ";

        return option + op + value;
    }

    @Override
    public String credentialsSql(Expression expression) {
        Object credExpr = expression.get(Args.CREDENTIALS);
        String credentials;
        if (Expression.isInstance(credExpr, ExprType.LITERAL)) {
            // Redshift case: CREDENTIALS <string>
            credentials = sql(expression, Args.CREDENTIALS);
            credentials = !credentials.isEmpty() ? "CREDENTIALS " + credentials : "";
        } else {
            // Snowflake case: CREDENTIALS = (...)
            credentials = expressions(opts(expression).key(Args.CREDENTIALS).flat().sep(" "));
            credentials = credExpr != null ? "CREDENTIALS = (" + credentials + ")" : "";
        }

        String storage = sql(expression, Args.STORAGE);
        storage = !storage.isEmpty() ? "STORAGE_INTEGRATION = " + storage : "";

        String encryption = expressions(opts(expression).key(Args.ENCRYPTION).flat().sep(" "));
        encryption = !encryption.isEmpty() ? " ENCRYPTION = (" + encryption + ")" : "";

        String iamRole = sql(expression, Args.IAM_ROLE);
        iamRole = !iamRole.isEmpty() ? "IAM_ROLE " + iamRole : "";

        String region = sql(expression, Args.REGION);
        region = !region.isEmpty() ? " REGION " + region : "";

        return credentials + storage + encryption + iamRole + region;
    }

    @Override
    public String copySql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        thisSql = g.COPY_HAS_INTO_KEYWORD ? " INTO " + thisSql : " " + thisSql;

        String credentials = sql(expression, Args.CREDENTIALS);
        credentials = !credentials.isEmpty() ? seg(credentials) : "";
        String files = expressions(opts(expression).key(Args.FILES).flat());
        String kind = !files.isEmpty() ? seg(expression.has(Args.KIND) ? "FROM" : "TO") : "";

        String sep = dd.COPY_PARAMS_ARE_CSV ? ", " : " ";
        String params = expressions(
                opts(expression)
                        .key(Args.PARAMS)
                        .sep(sep)
                        .newLine()
                        .skipLast()
                        .skipFirst()
                        .indent(g.COPY_PARAMS_ARE_WRAPPED));

        if (!params.isEmpty()) {
            if (g.COPY_PARAMS_ARE_WRAPPED) {
                params = " WITH (" + params + ")";
            } else if (!pretty && (!files.isEmpty() || !credentials.isEmpty())) {
                params = " " + params;
            }
        }

        return "COPY" + thisSql + kind + " " + files + credentials + params;
    }

    @Override
    public String semicolonSql(Expression expression) {
        return "";
    }

    @Override
    public String datadeletionpropertySql(Expression expression) {
        String onSql = expression.has(Args.ON) ? "ON" : "OFF";
        String filterCol = sql(expression, Args.FILTER_COLUMN);
        filterCol = !filterCol.isEmpty() ? "FILTER_COLUMN=" + filterCol : null;
        String retentionPeriod = sql(expression, Args.RETENTION_PERIOD);
        retentionPeriod = !retentionPeriod.isEmpty() ? "RETENTION_PERIOD=" + retentionPeriod : null;

        if (filterCol != null || retentionPeriod != null) {
            onSql = func("ON", filterCol, retentionPeriod);
        }

        return "DATA_DELETION=" + onSql;
    }

    @Override
    public String maskingpolicycolumnconstraintSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String exprs = expressions(opts(expression).flat());
        exprs = !exprs.isEmpty() ? " USING (" + exprs + ")" : "";
        return "MASKING POLICY " + thisSql + exprs;
    }

    @Override
    public String gapfillSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        thisSql = "TABLE " + thisSql;
        List<Object> args = new ArrayList<>();
        args.add(thisSql);
        for (Map.Entry<Arg, Object> entry : expression.argEntries()) {
            if (entry.getKey() != Args.THIS) {
                args.add(entry.getValue());
            }
        }
        return func("GAP_FILL", args.toArray());
    }

    @Override
    public String scopeResolution(String rhs, String scopeName) {
        return func("SCOPE_RESOLUTION", scopeName == null || scopeName.isEmpty() ? null : scopeName, rhs);
    }

    @Override
    public String scoperesolutionSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        Expression expr = expression.getExpression();

        String exprSql;
        if (expr != null && expr.is(ExprType.FUNC)) {
            // T-SQL's CLR functions are case sensitive
            exprSql = sql(expr, Args.THIS) + "(" + formatArgs(expr.getExpressions().toArray()) + ")";
        } else {
            exprSql = sql(expression, Args.EXPRESSION);
        }

        return scopeResolution(exprSql, thisSql);
    }

    @Override
    public String parsejsonSql(Expression expression) {
        if (g.PARSE_JSON_NAME == null) {
            return sql(expression.getThis());
        }

        return func(g.PARSE_JSON_NAME, expression.getThis(), expression.getExpression());
    }

    @Override
    public String randSql(Expression expression) {
        String lower = sql(expression, Args.LOWER);
        String upper = sql(expression, Args.UPPER);

        if (!lower.isEmpty() && !upper.isEmpty()) {
            return "(" + upper + " - " + lower + ") * " + func("RAND", expression.getThis()) + " + " + lower;
        }
        return func("RAND", expression.getThis());
    }

    @Override
    public String changesSql(Expression expression) {
        String information = sql(expression, Args.INFORMATION);
        information = "INFORMATION => " + information;
        String atBefore = sql(expression, Args.AT_BEFORE);
        atBefore = !atBefore.isEmpty() ? seg("") + atBefore : "";
        String end = sql(expression, Args.END);
        end = !end.isEmpty() ? seg("") + end : "";

        return "CHANGES (" + information + ")" + atBefore + end;
    }

    @Override
    public String padSql(Expression expression) {
        String prefix = expression.has(Args.IS_LEFT) ? "L" : "R";

        String fillPattern = sql(expression, Args.FILL_PATTERN);
        if (fillPattern.isEmpty()) {
            fillPattern = null;
        }
        if (fillPattern == null && g.PAD_FILL_PATTERN_IS_REQUIRED) {
            fillPattern = "' '";
        }

        return func(prefix + "PAD", expression.getThis(), expression.getExpression(), fillPattern);
    }

    @Override
    public String summarizeSql(Expression expression) {
        String table = expression.has(Args.TABLE) ? " TABLE" : "";
        return "SUMMARIZE" + table + " " + sql(expression.getThis());
    }

    @Override
    public String explodinggenerateseriesSql(Expression expression) {
        Map<Arg, Object> args = new LinkedHashMap<>();
        for (Map.Entry<Arg, Object> entry : expression.argEntries()) {
            args.put(entry.getKey(), entry.getValue());
        }
        Expression generateSeries = Expression.of(ExprType.GENERATE_SERIES, args);

        Expression parent = expression.parent();
        if (parent != null && parent.is(ExprType.ALIAS, ExprType.TABLE_ALIAS)) {
            parent = parent.parent();
        }

        if (g.SUPPORTS_EXPLODING_PROJECTIONS && !(parent != null && parent.is(ExprType.TABLE, ExprType.UNNEST))) {
            return sql(Expression.of(ExprType.UNNEST, Args.EXPRESSIONS, new ArrayList<>(List.of(generateSeries))));
        }

        if (parent != null && parent.is(ExprType.SELECT)) {
            unsupported("GenerateSeries projection unnesting is not supported.");
        }

        return sql(generateSeries);
    }

    @Override
    public String converttimezoneSql(Expression expression) {
        if (g.SUPPORTS_CONVERT_TIMEZONE) {
            return functionFallbackSql(expression);
        }

        Expression sourceTz = expression.expr(Args.SOURCE_TZ);
        Expression targetTz = expression.expr(Args.TARGET_TZ);
        Expression timestamp = expression.expr(Args.TIMESTAMP);

        if (sourceTz != null && timestamp != null) {
            timestamp = Expression.of(ExprType.AT_TIME_ZONE,
                    Args.THIS, Exp.cast(timestamp, DType.TIMESTAMPNTZ), Args.ZONE, sourceTz);
        }

        Expression expr = Expression.of(ExprType.AT_TIME_ZONE, Args.THIS, timestamp, Args.ZONE, targetTz);

        return sql(expr);
    }

    @Override
    public String jsonSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        thisSql = !thisSql.isEmpty() ? " " + thisSql : "";

        Object with = expression.get(Args.WITH_);

        String withSql;
        if (with == null) {
            withSql = "";
        } else if (!Expression.truthy(with)) {
            withSql = " WITHOUT";
        } else {
            withSql = " WITH";
        }

        String uniqueSql = expression.has(Args.UNIQUE) ? " UNIQUE KEYS" : "";

        return "JSON" + thisSql + withSql + uniqueSql;
    }

    @Override
    public String jsonvalueSql(Expression expression) {
        String path = sql(expression, Args.PATH);
        String returning = sql(expression, Args.RETURNING);
        returning = !returning.isEmpty() ? " RETURNING " + returning : "";

        String onCondition = sql(expression, Args.ON_CONDITION);
        onCondition = !onCondition.isEmpty() ? " " + onCondition : "";

        return func("JSON_VALUE", expression.getThis(), path + returning + onCondition);
    }

    @Override
    public String skipjsoncolumnSql(Expression expression) {
        String regexp = expression.has(Args.REGEXP) ? " REGEXP" : "";
        return "SKIP" + regexp + " " + sql(expression.getExpression());
    }

    @Override
    public String conditionalinsertSql(Expression expression) {
        String else_ = expression.has(Args.ELSE_) ? "ELSE " : "";
        String condition = sql(expression, Args.EXPRESSION);
        condition = !condition.isEmpty() ? "WHEN " + condition + " THEN " : else_;
        String insert = Py.slice(sql(expression, Args.THIS), "INSERT".length()).strip();
        return condition + insert;
    }

    @Override
    public String multitableinsertsSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        String source = sql(expression, Args.SOURCE);

        String sqlText;
        if (!kind.isEmpty()) {
            String exprs = seg(expressions(opts(expression).sep(" ")));
            sqlText = "INSERT " + kind + exprs + seg(source);
        } else {
            // If kind isn't present, we have Hive's FROM-first form:
            // FROM x INSERT ... SELECT ... [INSERT ... SELECT ...]
            List<String> parts = new ArrayList<>();
            for (Expression e : expression.getExpressions()) {
                parts.add(sql(e));
            }
            String inserts = String.join(sep(), parts);
            sqlText = "FROM " + source + seg(inserts);
        }

        return prependCtes(expression, sqlText);
    }

    @Override
    public String onconditionSql(Expression expression) {
        // Static options like "NULL ON ERROR" are stored as strings, in contrast to "DEFAULT <expr> ON ERROR"
        Object emptyArg = expression.get(Args.EMPTY);
        String empty = emptyArg instanceof Expression
                ? "DEFAULT " + pyStr(emptyArg) + " ON EMPTY"
                : sql(expression, Args.EMPTY);

        Object errorArg = expression.get(Args.ERROR);
        String error = errorArg instanceof Expression
                ? "DEFAULT " + pyStr(errorArg) + " ON ERROR"
                : sql(expression, Args.ERROR);

        if (!error.isEmpty() && !empty.isEmpty()) {
            error = dd.ON_CONDITION_EMPTY_BEFORE_ERROR ? empty + " " + error : error + " " + empty;
            empty = "";
        }

        String nul = sql(expression, Args.NULL);

        return empty + error + nul;
    }

    @Override
    public String jsonextractquoteSql(Expression expression) {
        String scalar = expression.has(Args.SCALAR) ? " ON SCALAR STRING" : "";
        return sql(expression, Args.OPTION) + " QUOTES" + scalar;
    }

    @Override
    public String jsonexistsSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String path = sql(expression, Args.PATH);

        String passing = expressions(expression, Args.PASSING);
        passing = !passing.isEmpty() ? " PASSING " + passing : "";

        String onCondition = sql(expression, Args.ON_CONDITION);
        onCondition = !onCondition.isEmpty() ? " " + onCondition : "";

        path = path + passing + onCondition;

        return func("JSON_EXISTS", thisSql, path);
    }

    /**
     * Add NULL filter to ARRAY_AGG if dialect requires it.
     *
     * @param arrayAggSql The generated ARRAY_AGG SQL string
     * @param arrayAggExpr The ArrayAgg expression node
     * @param columnExpr The column/expression to filter (before ORDER BY wrapping)
     * @return SQL string with FILTER clause added if needed
     */
    @Override
    public String addArrayaggNullFilter(String arrayAggSql, Expression arrayAggExpr, Expression columnExpr) {
        // Add a NULL FILTER on the column to mimic the results going from a dialect that excludes nulls
        // on ARRAY_AGG (e.g Spark) to one that doesn't (e.g. DuckDB)
        if (!(Expression.truthy(dd.ARRAY_AGG_INCLUDES_NULLS) && arrayAggExpr.has(Args.NULLS_EXCLUDED))) {
            return arrayAggSql;
        }

        Expression parent = arrayAggExpr.parent();
        if (parent != null && parent.is(ExprType.FILTER)) {
            Expression parentCond = parent.getExpression().getThis();
            parentCond.replace(parentCond.and(true, isOp(columnExpr, Exp.nul()).not(true)));
        } else if (columnExpr.find(ExprType.COLUMN) != null) {
            // Do not add the filter if the input is not a column (e.g. literal, struct etc)
            // DISTINCT is already present in the agg function, do not propagate it to FILTER as well
            String thisSql = columnExpr.is(ExprType.DISTINCT) ? expressions(columnExpr) : sql(columnExpr);
            arrayAggSql = arrayAggSql + " FILTER(WHERE " + thisSql + " IS NOT NULL)";
        }

        return arrayAggSql;
    }

    @Override
    public String arrayaggSql(Expression expression) {
        String arrayAgg = functionFallbackSql(expression);
        Expression columnExpr = expression.getThis();
        if (columnExpr != null && columnExpr.is(ExprType.ORDER)) {
            columnExpr = columnExpr.getThis();
        }

        return addArrayaggNullFilter(arrayAgg, expression, columnExpr);
    }

    @Override
    public String sliceSql(Expression expression) {
        String step = sql(expression, Args.STEP);
        String end = sql(expression.getExpression());
        String begin = sql(expression.getThis());

        String sqlText = !step.isEmpty() ? end + ":" + step : end;
        return !sqlText.isEmpty() ? begin + ":" + sqlText : begin + ":";
    }

    @Override
    public String applySql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expr = sql(expression, Args.EXPRESSION);

        return thisSql + " APPLY(" + expr + ")";
    }

    @Override
    public String grantOrRevokeSql(Expression expression, String keyword, String preposition, String grantOptionPrefix,
            String grantOptionSuffix) {
        String privilegesSql = expressions(opts(expression).key(Args.PRIVILEGES).flat());

        String kind = sql(expression, Args.KIND);
        kind = !kind.isEmpty() ? " " + kind : "";

        String securable = sql(expression, Args.SECURABLE);
        securable = !securable.isEmpty() ? " " + securable : "";

        String principals = expressions(opts(expression).key(Args.PRINCIPALS).flat());

        if (!expression.has(Args.GRANT_OPTION)) {
            grantOptionPrefix = grantOptionSuffix = "";
        }

        // cascade for revoke only
        String cascade = sql(expression, Args.CASCADE);
        cascade = !cascade.isEmpty() ? " " + cascade : "";

        return keyword + " " + grantOptionPrefix + privilegesSql + " ON" + kind + securable + " " + preposition + " "
                + principals + grantOptionSuffix + cascade;
    }

    @Override
    public String grantSql(Expression expression) {
        return grantOrRevokeSql(expression, "GRANT", "TO", "", " WITH GRANT OPTION");
    }

    @Override
    public String revokeSql(Expression expression) {
        return grantOrRevokeSql(expression, "REVOKE", "FROM", "GRANT OPTION FOR ", "");
    }

    @Override
    public String grantprivilegeSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String columns = expressions(opts(expression).flat());
        columns = !columns.isEmpty() ? "(" + columns + ")" : "";

        return thisSql + columns;
    }

    @Override
    public String grantprincipalSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);

        String kind = sql(expression, Args.KIND);
        kind = !kind.isEmpty() ? kind + " " : "";

        return kind + thisSql;
    }

    @Override
    public String columnsSql(Expression expression) {
        String fn = functionFallbackSql(expression);
        if (expression.has(Args.UNPACK)) {
            fn = "*" + fn;
        }

        return fn;
    }

    @Override
    public String overlaySql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expr = sql(expression, Args.EXPRESSION);
        String fromSql = sql(expression, Args.FROM_);
        String forSql = sql(expression, Args.FOR_);
        forSql = !forSql.isEmpty() ? " FOR " + forSql : "";

        return "OVERLAY(" + thisSql + " PLACING " + expr + " FROM " + fromSql + forSql + ")";
    }

    @Override
    public String todoubleSql(Expression expression) {
        unsupportedArgs(expression, Args.FORMAT);
        ExprType cast = expression.has(Args.SAFE) ? ExprType.TRY_CAST : ExprType.CAST;
        return sql(Expression.of(cast, Args.THIS, expression.getThis(), Args.TO, DataTypes.of(DType.DOUBLE)));
    }

    @Override
    public String stringSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        Expression zone = expression.expr(Args.ZONE);

        if (zone != null) {
            // This is a BigQuery specific argument for STRING(<timestamp_expr>, <time_zone>)
            // BigQuery stores timestamps internally as UTC, so ConvertTimezone is used with UTC
            // set for source_tz to transpile the time conversion before the STRING cast
            thisExpr = Expression.of(ExprType.CONVERT_TIMEZONE,
                    Args.SOURCE_TZ, Exp.string("UTC"), Args.TARGET_TZ, zone, Args.TIMESTAMP, thisExpr);
        }

        return sql(Exp.cast(thisExpr, DType.VARCHAR));
    }

    @Override
    public String medianSql(Expression expression) {
        if (!g.SUPPORTS_MEDIAN) {
            return sql(Expression.of(ExprType.PERCENTILE_CONT,
                    Args.THIS, expression.getThis(), Args.EXPRESSION, Exp.number(0.5)));
        }

        return functionFallbackSql(expression);
    }

    @Override
    public String overflowtruncatebehaviorSql(Expression expression) {
        String filler = sql(expression, Args.THIS);
        filler = !filler.isEmpty() ? " " + filler : "";
        String withCount = expression.has(Args.WITH_COUNT) ? "WITH COUNT" : "WITHOUT COUNT";
        return "TRUNCATE" + filler + " " + withCount;
    }

    @Override
    public String unixsecondsSql(Expression expression) {
        if (g.SUPPORTS_UNIX_SECONDS) {
            return functionFallbackSql(expression);
        }

        Expression startTs = Exp.cast(Exp.string("1970-01-01 00:00:00+00"), DType.TIMESTAMPTZ);

        return sql(Expression.of(ExprType.TIMESTAMP_DIFF,
                Args.THIS, expression.getThis(), Args.EXPRESSION, startTs, Args.UNIT, Exp.var("SECONDS")));
    }

    @Override
    public String arraysizeSql(Expression expression) {
        Expression dim = expression.getExpression();

        // For dialects that don't support the dimension arg, we can safely transpile it's default value (1st dimension)
        if (dim != null && g.ARRAY_SIZE_DIM_REQUIRED == null) {
            if (!(dim.isInt() && dim.name().equals("1"))) {
                unsupported("Cannot transpile dimension argument for ARRAY_LENGTH");
            }
            dim = null;
        }

        // If dimension is required but not specified, default initialize it
        if (Boolean.TRUE.equals(g.ARRAY_SIZE_DIM_REQUIRED) && dim == null) {
            dim = Exp.number(1);
        }

        return func(g.ARRAY_SIZE_NAME, expression.getThis(), dim);
    }

    @Override
    public String attachSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String existsSql = expression.has(Args.EXISTS) ? " IF NOT EXISTS" : "";
        String exprs = expressions(expression);
        exprs = !exprs.isEmpty() ? " (" + exprs + ")" : "";

        return "ATTACH" + existsSql + " " + thisSql + exprs;
    }

    @Override
    public String detachSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        kind = !kind.isEmpty() ? " " + kind : "";
        // the DATABASE keyword is required if IF EXISTS is set for DuckDB
        // ref: https://duckdb.org/docs/stable/sql/statements/attach.html#detach-syntax
        String exists = expression.has(Args.EXISTS) ? " IF EXISTS" : "";
        if (!exists.isEmpty()) {
            kind = !kind.isEmpty() ? kind : " DATABASE";
        }

        String thisSql = sql(expression, Args.THIS);
        thisSql = !thisSql.isEmpty() ? " " + thisSql : "";
        String cluster = sql(expression, Args.CLUSTER);
        cluster = !cluster.isEmpty() ? " " + cluster : "";
        String permanent = expression.has(Args.PERMANENT) ? " PERMANENTLY" : "";
        String sync = expression.has(Args.SYNC) ? " SYNC" : "";
        return "DETACH" + kind + exists + thisSql + cluster + permanent + sync;
    }

    @Override
    public String attachoptionSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String value = sql(expression, Args.EXPRESSION);
        value = !value.isEmpty() ? " " + value : "";
        return thisSql + value;
    }

    @Override
    public String watermarkcolumnconstraintSql(Expression expression) {
        return "WATERMARK FOR " + sql(expression, Args.THIS) + " AS " + sql(expression, Args.EXPRESSION);
    }

    @Override
    public String encodepropertySql(Expression expression) {
        String encode = expression.has(Args.KEY) ? "KEY ENCODE" : "ENCODE";
        encode = encode + " " + sql(expression, Args.THIS);

        Expression properties = expression.expr(Args.PROPERTIES);
        if (properties != null) {
            encode = encode + " " + properties(properties);
        }

        return encode;
    }

    @Override
    public String includepropertySql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String include = "INCLUDE " + thisSql;

        String columnDef = sql(expression, Args.COLUMN_DEF);
        if (!columnDef.isEmpty()) {
            include = include + " " + columnDef;
        }

        String alias = sql(expression, Args.ALIAS);
        if (!alias.isEmpty()) {
            include = include + " AS " + alias;
        }

        return include;
    }

    @Override
    public String xmlelementSql(Expression expression) {
        String prefix = expression.has(Args.EVALNAME) ? "EVALNAME" : "NAME";
        String name = prefix + " " + sql(expression, Args.THIS);
        List<Object> args = new ArrayList<>();
        args.add(name);
        args.addAll(expression.getExpressions());
        return func("XMLELEMENT", args.toArray());
    }

    @Override
    public String xmlkeyvalueoptionSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expr = sql(expression, Args.EXPRESSION);
        expr = !expr.isEmpty() ? "(" + expr + ")" : "";
        return thisSql + expr;
    }

    @Override
    public String partitionbyrangepropertySql(Expression expression) {
        String partitions = expressions(expression, Args.PARTITION_EXPRESSIONS);
        String create = expressions(expression, Args.CREATE_EXPRESSIONS);
        return "PARTITION BY RANGE " + wrap(partitions) + " " + wrap(create);
    }

    @Override
    public String partitionbyrangepropertydynamicSql(Expression expression) {
        String start = sql(expression, Args.START);
        String end = sql(expression, Args.END);

        Expression every = expression.expr(Args.EVERY);
        if (every != null && every.is(ExprType.INTERVAL) && every.getThis().isString()) {
            every.getThis().replace(Exp.number(every.name()));
        }

        return "START " + wrap(start) + " END " + wrap(end) + " EVERY " + wrap(sql(every));
    }

    @Override
    public String unpivotcolumnsSql(Expression expression) {
        String name = sql(expression, Args.THIS);
        String values = expressions(opts(expression).flat());

        return "NAME " + name + " VALUE " + values;
    }

    @Override
    public String analyzesampleSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        String sample = sql(expression, Args.SAMPLE);
        return "SAMPLE " + sample + " " + kind;
    }

    @Override
    public String analyzestatisticsSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        String option = sql(expression, Args.OPTION);
        option = !option.isEmpty() ? " " + option : "";
        String thisSql = sql(expression, Args.THIS);
        thisSql = !thisSql.isEmpty() ? " " + thisSql : "";
        String columns = expressions(expression);
        columns = !columns.isEmpty() ? " " + columns : "";
        return kind + option + " STATISTICS" + thisSql + columns;
    }

    @Override
    public String analyzehistogramSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String columns = expressions(expression);
        String innerExpression = sql(expression, Args.EXPRESSION);
        innerExpression = !innerExpression.isEmpty() ? " " + innerExpression : "";
        String updateOptions = sql(expression, Args.UPDATE_OPTIONS);
        updateOptions = !updateOptions.isEmpty() ? " " + updateOptions + " UPDATE" : "";
        return thisSql + " HISTOGRAM ON " + columns + innerExpression + updateOptions;
    }

    @Override
    public String analyzedeleteSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        kind = !kind.isEmpty() ? " " + kind : "";
        return "DELETE" + kind + " STATISTICS";
    }

    @Override
    public String analyzelistchainedrowsSql(Expression expression) {
        String innerExpression = sql(expression, Args.EXPRESSION);
        return "LIST CHAINED ROWS" + innerExpression;
    }

    @Override
    public String analyzevalidateSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        String thisSql = sql(expression, Args.THIS);
        thisSql = !thisSql.isEmpty() ? " " + thisSql : "";
        String innerExpression = sql(expression, Args.EXPRESSION);
        return "VALIDATE " + kind + thisSql + innerExpression;
    }

    @Override
    public String analyzeSql(Expression expression) {
        String options = expressions(opts(expression).key(Args.OPTIONS).sep(" "));
        options = !options.isEmpty() ? " " + options : "";
        String kind = sql(expression, Args.KIND);
        kind = !kind.isEmpty() ? " " + kind : "";
        String tables = expressions(opts(expression).key(Args.TABLES).flat());
        tables = !tables.isEmpty() ? " " + tables : "";
        String mode = sql(expression, Args.MODE);
        mode = !mode.isEmpty() ? " " + mode : "";
        String properties = sql(expression, Args.PROPERTIES);
        properties = !properties.isEmpty() ? " " + properties : "";
        String partition = sql(expression, Args.PARTITION);
        partition = !partition.isEmpty() ? " " + partition : "";
        String innerExpression = sql(expression, Args.EXPRESSION);
        innerExpression = !innerExpression.isEmpty() ? " " + innerExpression : "";
        return "ANALYZE" + options + kind + tables + partition + mode + innerExpression + properties;
    }

    @Override
    public String xmltableSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String namespaces = expressions(opts(expression).key(Args.NAMESPACES));
        namespaces = !namespaces.isEmpty() ? "XMLNAMESPACES(" + namespaces + "), " : "";
        String passing = expressions(opts(expression).key(Args.PASSING));
        passing = !passing.isEmpty() ? sep() + "PASSING" + seg(passing) : "";
        String columns = expressions(opts(expression).key(Args.COLUMNS));
        columns = !columns.isEmpty() ? sep() + "COLUMNS" + seg(columns) : "";
        String byRef = expression.has(Args.BY_REF) ? sep() + "RETURNING SEQUENCE BY REF" : "";
        return "XMLTABLE(" + sep("") + indent(namespaces + thisSql + passing + byRef + columns) + seg(")", "");
    }

    @Override
    public String xmlnamespaceSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        return expression.getThis() != null && expression.getThis().is(ExprType.ALIAS) ? thisSql : "DEFAULT " + thisSql;
    }

    @Override
    public String exportSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String connection = sql(expression, Args.CONNECTION);
        connection = !connection.isEmpty() ? "WITH CONNECTION " + connection + " " : "";
        String options = sql(expression, Args.OPTIONS);
        return "EXPORT DATA " + connection + options + " AS " + thisSql;
    }

    @Override
    public String declareSql(Expression expression) {
        String replace = expression.has(Args.REPLACE) ? "OR REPLACE " : "";
        return "DECLARE " + replace + expressions(opts(expression).flat());
    }

    @Override
    public String declareitemSql(Expression expression) {
        String variables = expressions(expression, Args.THIS);
        String default_ = sql(expression, Args.DEFAULT);
        default_ = !default_.isEmpty() ? " " + g.DECLARE_DEFAULT_ASSIGNMENT + " " + default_ : "";

        String kind = sql(expression, Args.KIND);
        if (Expression.isInstance(expression.get(Args.KIND), ExprType.SCHEMA)) {
            kind = "TABLE " + kind;
        }

        kind = !kind.isEmpty() ? " " + kind : "";

        return variables + kind + default_;
    }

    @Override
    public String recursivewithsearchSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        String thisSql = sql(expression, Args.THIS);
        String set = sql(expression, Args.EXPRESSION);
        String to = sql(expression, Args.TO);
        to = !to.isEmpty() ? " TO " + to : "";
        String default_ = sql(expression, Args.DEFAULT);
        default_ = !default_.isEmpty() ? " DEFAULT " + default_ : "";
        String using = sql(expression, Args.USING);
        using = !using.isEmpty() ? " USING " + using : "";

        String kindSql = kind.equals("CYCLE") ? kind : "SEARCH " + kind + " FIRST BY";

        return kindSql + " " + thisSql + " SET " + set + to + default_ + using;
    }

    @Override
    public String parameterizedaggSql(Expression expression) {
        String params = expressions(opts(expression).key(Args.PARAMS).flat());
        return func(expression.name(), expression.getExpressions().toArray()) + "(" + params + ")";
    }

    @Override
    public String anonymousaggfuncSql(Expression expression) {
        return func(expression.name(), expression.getExpressions().toArray());
    }

    @Override
    public String combinedaggfuncSql(Expression expression) {
        return anonymousaggfuncSql(expression);
    }

    @Override
    public String combinedparameterizedaggSql(Expression expression) {
        return parameterizedaggSql(expression);
    }

    @Override
    public String showSql(Expression expression) {
        unsupported("Unsupported SHOW statement");
        return "";
    }

    @Override
    public String installSql(Expression expression) {
        unsupported("Unsupported INSTALL statement");
        return "";
    }

    @Override
    public String liststageSql(Expression expression) {
        String pattern = sql(expression, Args.PATTERN);
        pattern = !pattern.isEmpty() ? " PATTERN = " + pattern : "";
        return "LIST " + sql(expression, Args.THIS) + pattern;
    }

    @Override
    public String getPutSql(Expression expression) {
        // Snowflake GET/PUT statements:
        //   PUT <file> <internalStage> <properties>
        //   GET <internalStage> <file> <properties>
        Expression props = expression.expr(Args.PROPERTIES);
        String propsSql = props != null ? properties(props, " ", " ", "", false) : "";
        String thisSql = sql(expression, Args.THIS);
        String target = sql(expression, Args.TARGET);

        if (expression.is(ExprType.PUT)) {
            return "PUT " + thisSql + " " + target + propsSql;
        } else {
            return "GET " + target + " " + thisSql + propsSql;
        }
    }

    @Override
    public String translatecharactersSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expr = sql(expression, Args.EXPRESSION);
        String withError = expression.has(Args.WITH_ERROR) ? " WITH ERROR" : "";
        return "TRANSLATE(" + thisSql + " USING " + expr + withError + ")";
    }

    @Override
    public String decodecaseSql(Expression expression) {
        if (g.SUPPORTS_DECODE_CASE) {
            return func("DECODE", expression.getExpressions().toArray());
        }

        List<Expression> all = expression.getExpressions();
        Expression decodeExpr = all.get(0);
        List<Expression> exprs = all.subList(1, all.size());

        List<Expression> ifs = new ArrayList<>();
        for (int i = 0; i + 1 < exprs.size(); i += 2) {
            Expression search = exprs.get(i);
            Expression result = exprs.get(i + 1);
            if (search.is(ExprType.LITERAL)) {
                ifs.add(Expression.of(ExprType.IF, Args.THIS, decodeExpr.eq(search), Args.TRUE, result));
            } else if (search.is(ExprType.NULL)) {
                ifs.add(Expression.of(ExprType.IF, Args.THIS, isOp(decodeExpr, Expression.of(ExprType.NULL)), Args.TRUE, result));
            } else {
                if (search.is(ExprType.BINARY)) {
                    search = Exp.paren(search);
                }

                Expression cond = Exp.or(
                        decodeExpr.eq(search),
                        Exp.and(isOp(decodeExpr, Expression.of(ExprType.NULL)), isOp(search, Expression.of(ExprType.NULL))));
                ifs.add(Expression.of(ExprType.IF, Args.THIS, cond, Args.TRUE, result));
            }
        }

        Expression caseExpr = Expression.of(ExprType.CASE,
                Args.IFS, ifs,
                Args.DEFAULT, exprs.size() % 2 == 1 ? exprs.get(exprs.size() - 1) : null);
        return sql(caseExpr);
    }

    @Override
    public String semanticviewSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        thisSql = seg(thisSql, "");
        String dimensions = expressions(opts(expression).key(Args.DIMENSIONS).dynamic().skipFirst().skipLast());
        dimensions = !dimensions.isEmpty() ? seg("DIMENSIONS " + dimensions) : "";
        String metrics = expressions(opts(expression).key(Args.METRICS).dynamic().skipFirst().skipLast());
        metrics = !metrics.isEmpty() ? seg("METRICS " + metrics) : "";
        String facts = expressions(opts(expression).key(Args.FACTS).dynamic().skipFirst().skipLast());
        facts = !facts.isEmpty() ? seg("FACTS " + facts) : "";
        String where = sql(expression, Args.WHERE);
        where = !where.isEmpty() ? seg("WHERE " + where) : "";
        String body = indent(thisSql + metrics + dimensions + facts + where, 0, null, true, false);
        return "SEMANTIC_VIEW(" + body + seg(")", "");
    }

    @Override
    public String getextractSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        Expression expr = expression.getExpression();

        if (thisExpr.dataType() == null || expression.dataType() == null) {
            thisExpr = TypeAnnotator.annotateTypes(thisExpr, dialect);
        }

        if (thisExpr.isType(DType.ARRAY, DType.MAP)) {
            return sql(Expression.of(ExprType.BRACKET, Args.THIS, thisExpr, Args.EXPRESSIONS, new ArrayList<>(List.of(expr))));
        }

        return sql(Expression.of(ExprType.JSON_EXTRACT, Args.THIS, thisExpr, Args.EXPRESSION, dialect.toJsonPath(expr)));
    }

    @Override
    public String datefromunixdateSql(Expression expression) {
        return sql(
                Expression.of(ExprType.DATE_ADD,
                        Args.THIS, Exp.cast(Exp.string("1970-01-01"), DType.DATE),
                        Args.EXPRESSION, expression.getThis(),
                        Args.UNIT, Exp.var("DAY")));
    }

    @Override
    public String spaceSql(Expression expression) {
        return sql(Expression.of(ExprType.REPEAT, Args.THIS, Exp.string(" "), Args.TIMES, expression.getThis()));
    }

    @Override
    public String buildpropertySql(Expression expression) {
        return "BUILD " + sql(expression, Args.THIS);
    }

    @Override
    public String refreshtriggerpropertySql(Expression expression) {
        String method = sql(expression, Args.METHOD);
        Object kind = expression.get(Args.KIND);
        if (!Expression.truthy(kind)) {
            return "REFRESH " + method;
        }

        String every = sql(expression, Args.EVERY);
        String unit = sql(expression, Args.UNIT);
        every = !every.isEmpty() ? " EVERY " + every + " " + unit : "";
        String starts = sql(expression, Args.STARTS);
        starts = !starts.isEmpty() ? " STARTS " + starts : "";

        return "REFRESH " + method + " ON " + pyStr(kind) + every + starts;
    }

    @Override
    public String modelattributeSql(Expression expression) {
        unsupported("The model!attribute syntax is not supported");
        return "";
    }

    @Override
    public String directorystageSql(Expression expression) {
        return func("DIRECTORY", expression.getThis());
    }

    @Override
    public String uuidSql(Expression expression) {
        boolean isString = expression.has(Args.IS_STRING);
        String uuidFuncSql = func("UUID");

        if (isString && !dd.UUID_IS_STRING_TYPE) {
            return sql(Exp.cast(Exp.maybeParse(uuidFuncSql, dialect), DType.VARCHAR, true, dialect));
        }

        return uuidFuncSql;
    }

    @Override
    public String initcapSql(Expression expression) {
        Expression delimiters = expression.getExpression();

        if (delimiters != null) {
            // do not generate delimiters arg if we are round-tripping from default delimiters
            if (delimiters.isString()
                    && Objects.equals(delimiters.thisValue(), dd.INITCAP_DEFAULT_DELIMITER_CHARS)) {
                delimiters = null;
            } else if (!dd.INITCAP_SUPPORTS_CUSTOM_DELIMITERS) {
                unsupported("INITCAP does not support custom delimiters");
                delimiters = null;
            }
        }

        return func("INITCAP", expression.getThis(), delimiters);
    }

    @Override
    public String localtimeSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        return thisExpr != null ? func("LOCALTIME", thisExpr) : "LOCALTIME";
    }

    @Override
    public String localtimestampSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        return thisExpr != null ? func("LOCALTIMESTAMP", thisExpr) : "LOCALTIMESTAMP";
    }

    @Override
    public String weekstartName(Expression expression) {
        // WEEK(<day>) is BigQuery-only syntax, so it degrades to the plain WEEK unit
        String thisName = Py.upper(expression.getThis().name());

        Integer dowFromWeekStartDay = WEEK_START_DAY_TO_DOW.get(thisName);
        int dowFromWeekOffset = weekOffsetToDow(dd.WEEK_OFFSET);

        if (dowFromWeekStartDay == null || dowFromWeekStartDay != dowFromWeekOffset) {
            unsupported("WEEK(" + thisName + ") is not supported; falling back to the default week start day");
        }

        return "WEEK";
    }

    @Override
    public String weekstartSql(Expression expression) {
        String name = weekstartName(expression);

        // DateTrunc stores string literal units, whereas TimeUnit expressions store keywords
        if (expression.parent() != null && expression.parent().is(ExprType.DATE_TRUNC)) {
            return sql(Exp.string(name));
        }

        return name;
    }

    @Override
    public String chrSql(Expression expression, String name) {
        String thisSql = expressions(expression);
        String charset = sql(expression, Args.CHARSET);
        String using = !charset.isEmpty() ? " USING " + charset : "";
        return func(name, thisSql + using);
    }

    @Override
    public String weightstringSql(Expression expression) {
        String to = sql(expression, Args.TO);
        to = !to.isEmpty() ? " AS " + to : "";
        return func("WEIGHT_STRING", sql(expression, Args.THIS) + to);
    }

    @Override
    public String blockSql(Expression expression) {
        String exprs = expressions(opts(expression).sep("; ").flat());
        String begin = expression.has(Args.BEGIN) ? "BEGIN " : "";
        return !exprs.isEmpty() ? begin + exprs : "";
    }

    @Override
    public String functionspecificationSql(Expression expression) {
        unsupported("Unsupported Inline UDFs syntax");
        return "";
    }

    @Override
    public String storedprocedureSql(Expression expression) {
        unsupported("Unsupported Stored Procedure syntax");
        return "";
    }

    @Override
    public String ifblockSql(Expression expression) {
        unsupported("Unsupported If block syntax");
        return "";
    }

    @Override
    public String casestatementSql(Expression expression) {
        unsupported("Unsupported Case statement syntax");
        return "";
    }

    @Override
    public String whileblockSql(Expression expression) {
        unsupported("Unsupported While block syntax");
        return "";
    }

    @Override
    public String loopblockSql(Expression expression) {
        unsupported("Unsupported Loop block syntax");
        return "";
    }

    @Override
    public String repeatblockSql(Expression expression) {
        unsupported("Unsupported Repeat block syntax");
        return "";
    }

    @Override
    public String leaveSql(Expression expression) {
        unsupported("Unsupported Leave syntax");
        return "";
    }

    @Override
    public String iterateSql(Expression expression) {
        unsupported("Unsupported Iterate syntax");
        return "";
    }

    @Override
    public String executeSql(Expression expression) {
        unsupported("Unsupported Execute syntax");
        return "";
    }

    @Override
    public String executesqlSql(Expression expression) {
        unsupported("Unsupported Execute syntax");
        return "";
    }

    @Override
    public String altermodifysqlsecuritySql(Expression expression) {
        String props = expressions(opts(expression).sep(" "));
        return "MODIFY " + props;
    }

    @Override
    public String usingpropertySql(Expression expression) {
        Object kind = expression.get(Args.KIND);
        return "USING " + pyStr(kind) + " " + sql(expression, Args.THIS);
    }

    @Override
    public String renameindexSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String to = sql(expression, Args.TO);
        return "RENAME INDEX " + thisSql + " TO " + to;
    }
}
