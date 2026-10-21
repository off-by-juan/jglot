package io.github.offbyjuan.jglot.dialects.postgres;

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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Postgres generator: a port of sqlglot's {@code sqlglot.generators.postgres}. */
public class PostgresGenerator extends Generator {
    private static final Map<String, String> DATE_DIFF_FACTOR = Map.of(
            "MICROSECOND", " * 1000000",
            "MILLISECOND", " * 1000",
            "SECOND", "",
            "MINUTE", " / 60",
            "HOUR", " / 3600",
            "DAY", " / 86400");

    public PostgresGenerator(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }

    // ------------------------------------------------------------------------------------------
    // Module-level helpers

    private String dateAddSql(Expression expression, String kind) {
        if (expression.is(ExprType.TS_OR_DS_ADD)) {
            expression = DialectHelpers.tsOrDsAddCast(expression);
        }

        String this_ = sql(expression, Args.THIS);
        Object unit = expression.get(Args.UNIT);

        Expression e = simplifyUnlessLiteral(expression.getExpression());
        if (e.is(ExprType.INTERVAL)) {
            return this_ + " " + kind + " " + sql(e);
        } else if (e.is(ExprType.LITERAL)) {
            e.set(Args.IS_STRING, Boolean.TRUE);
        } else if (e.isNumber()) {
            e = Exp.string(Py.str(e.toPy()));
        } else {
            Expression one = Exp.number(1);
            Expression intervalTimesValue = Expression.of(ExprType.INTERVAL, Args.THIS, one, Args.UNIT, unit)
                    .binop(ExprType.MUL, e);
            return this_ + " " + kind + " " + sql(intervalTimesValue);
        }

        return this_ + " " + kind + " " + sql(Expression.of(ExprType.INTERVAL, Args.THIS, e, Args.UNIT, unit));
    }

    private String dayMonthYearSql(Expression expression) {
        Expression this_ = expression.getThis();
        Expression value = this_.is(ExprType.TS_OR_DS_TO_DATE) ? this_.getThis() : this_;

        if (value.isType(DataTypes.INTEGER_TYPES.toArray())) {
            Expression defaultDate = this_.expr(Args.DEFAULT_DATE);
            if (defaultDate != null) {
                this_ = Exp.cast(defaultDate, DType.DATE).binop(ExprType.ADD, value);
            }
        }

        return sql(Expression.of(ExprType.EXTRACT,
                Args.THIS, Exp.var(expression.type().sqlName()), Args.EXPRESSION, this_));
    }

    private String dateDiffSql(Expression expression) {
        String unit = Py.upper(expression.text(Args.UNIT));
        if (unit.isEmpty()) {
            unit = "DAY";
        }

        // Dialects like MySQL count crossed day boundaries, which maps to DATE subtraction
        if (unit.equals("DAY") && expression.has(Args.DATE_PART_BOUNDARY)) {
            Expression this_ = Exp.cast(expression.getThis(), DType.DATE);
            Expression expr = Exp.cast(expression.getExpression(), DType.DATE);
            return sql(Exp.paren(this_.binop(ExprType.SUB, expr)));
        }

        String factor = DATE_DIFF_FACTOR.get(unit);

        String end = "CAST(" + sql(expression, Args.THIS) + " AS TIMESTAMP)";
        String start = "CAST(" + sql(expression, Args.EXPRESSION) + " AS TIMESTAMP)";

        if (factor != null) {
            return "CAST(EXTRACT(epoch FROM " + end + " - " + start + ")" + factor + " AS BIGINT)";
        }

        String age = "AGE(" + end + ", " + start + ")";

        switch (unit) {
            case "WEEK" -> unit = "EXTRACT(days FROM (" + end + " - " + start + ")) / 7";
            case "MONTH" -> unit = "EXTRACT(year FROM " + age + ") * 12 + EXTRACT(month FROM " + age + ")";
            case "QUARTER" -> unit = "EXTRACT(year FROM " + age + ") * 4 + EXTRACT(month FROM " + age + ") / 3";
            case "YEAR" -> unit = "EXTRACT(year FROM " + age + ")";
            default -> unit = age;
        }

        return "CAST(" + unit + " AS BIGINT)";
    }

    private String substringSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String start = sql(expression, Args.START);
        String length = sql(expression, Args.LENGTH);

        String fromPart = !start.isEmpty() ? " FROM " + start : "";
        String forPart = !length.isEmpty() ? " FOR " + length : "";

        return "SUBSTRING(" + this_ + fromPart + forPart + ")";
    }

    private static Expression autoIncrementToSerial(Expression expression) {
        Expression auto = expression.find(ExprType.AUTO_INCREMENT_COLUMN_CONSTRAINT);

        if (auto != null) {
            List<Object> constraints = new ArrayList<>(expression.exprs(Args.CONSTRAINTS));
            constraints.remove(auto.parent());
            expression.set(Args.CONSTRAINTS, constraints);
            Expression kind = expression.expr(Args.KIND);

            Object kindThis = kind.thisValue();
            if (kindThis == DType.INT) {
                kind.replace(DataTypes.of(DType.SERIAL));
            } else if (kindThis == DType.SMALLINT) {
                kind.replace(DataTypes.of(DType.SMALLSERIAL));
            } else if (kindThis == DType.BIGINT) {
                kind.replace(DataTypes.of(DType.BIGSERIAL));
            }
        }

        return expression;
    }

    private static Expression serialToGenerated(Expression expression) {
        if (!expression.is(ExprType.COLUMN_DEF)) {
            return expression;
        }
        Expression kind = expression.expr(Args.KIND);
        if (kind == null) {
            return expression;
        }

        Object kindThis = kind.thisValue();
        Expression dataType;
        if (kindThis == DType.SERIAL) {
            dataType = DataTypes.of(DType.INT);
        } else if (kindThis == DType.SMALLSERIAL) {
            dataType = DataTypes.of(DType.SMALLINT);
        } else if (kindThis == DType.BIGSERIAL) {
            dataType = DataTypes.of(DType.BIGINT);
        } else {
            dataType = null;
        }

        if (dataType != null) {
            kind.replace(dataType);
            List<Object> constraints = new ArrayList<>(expression.exprs(Args.CONSTRAINTS));
            Expression generated = Expression.of(ExprType.COLUMN_CONSTRAINT,
                    Args.KIND, Expression.of(ExprType.GENERATED_AS_IDENTITY_COLUMN_CONSTRAINT, Args.THIS, Boolean.FALSE));
            Expression notnull = Expression.of(ExprType.COLUMN_CONSTRAINT,
                    Args.KIND, Expression.of(ExprType.NOT_NULL_COLUMN_CONSTRAINT));

            if (!constraints.contains(notnull)) {
                constraints.add(0, notnull);
            }
            if (!constraints.contains(generated)) {
                constraints.add(0, generated);
            }
            expression.set(Args.CONSTRAINTS, constraints);
        }

        return expression;
    }

    private String jsonExtractSql(Expression expression, String name, String op) {
        Expression path = expression.getExpression();
        // Single non-literal segment: render as infix, not JSON_EXTRACT_PATH[_TEXT] (jsonb-unsafe).
        if ((path == null || !path.is(ExprType.JSON_PATH, ExprType.VARIADIC))
                && Py.ensureList(expression.get(Args.EXPRESSIONS)).isEmpty()) {
            return DialectHelpers.arrowJsonExtractSql(this, expression, op);
        }

        // JSON_EXTRACT_PATH requires a key, so use an empty variadic array for the root path
        List<Expression> pathExpressions = path.getExpressions();
        if (pathExpressions.size() == 1 && pathExpressions.get(0).is(ExprType.JSON_PATH_ROOT)) {
            expression.set(Args.EXPRESSION, Expression.of(ExprType.VARIADIC, Args.THIS, Exp.string("{}")));
        }

        if (expression.has(Args.ONLY_JSON_TYPES)) {
            return DialectHelpers.jsonExtractSegments(this, expression, name, false, op);
        }
        return DialectHelpers.jsonExtractSegments(this, expression, name);
    }

    private String unixToTimeSql(Expression expression) {
        Object scale = expression.get(Args.SCALE);
        Expression timestamp = expression.getThis();

        if (scale == null || Objects.equals(scale, Exp.number(0))) {
            return func("TO_TIMESTAMP", timestamp, formatTime(expression));
        }

        return func("TO_TIMESTAMP",
                Expression.of(ExprType.DIV, Args.THIS, timestamp, Args.EXPRESSION, Exp.func("POW", null, 10, scale)),
                formatTime(expression));
    }

    private String levenshteinSql(Expression expression) {
        String name = expression.has(Args.MAX_DIST) ? "LEVENSHTEIN_LESS_EQUAL" : "LEVENSHTEIN";

        return renameFunc(name, expression);
    }

    private String versionedAnyvalueSql(Expression expression) {
        // https://www.postgresql.org/docs/16/functions-aggregate.html
        // https://www.postgresql.org/about/featurematrix/
        if (dialect.version()[0] < 16) {
            return DialectHelpers.anyValueToMaxSql(this, expression);
        }

        return renameFunc("ANY_VALUE", expression);
    }

    private String roundSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String decimals = sql(expression, Args.DECIMALS);

        if (decimals.isEmpty()) {
            return func("ROUND", this_);
        }

        if (expression.dataType() == null) {
            expression = TypeAnnotator.annotateTypes(expression, dialect);
        }

        // ROUND(double precision, integer) is not permitted in Postgres
        // so it's necessary to cast to decimal before rounding.
        if (expression.getThis().isType(DType.DOUBLE)) {
            Expression decimalType = Expression.of(ExprType.DATA_TYPE,
                    Args.THIS, DType.DECIMAL, Args.EXPRESSIONS, expression.getExpressions());
            this_ = sql(Expression.of(ExprType.CAST, Args.THIS, this_, Args.TO, decimalType));
        }

        return func("ROUND", this_, decimals);
    }

    // ------------------------------------------------------------------------------------------
    // TRANSFORMS

    @Override
    public String transform(Expression e) {
        return switch (e.type()) {
            case ANY_VALUE -> versionedAnyvalueSql(e);
            case ARRAY_CONCAT -> DialectHelpers.arrayConcatSql(this, e, "ARRAY_CAT");
            case ARRAY_FILTER -> DialectHelpers.filterArrayUsingUnnest(this, e);
            case ARRAY_APPEND -> DialectHelpers.arrayAppendSql(this, e, "ARRAY_APPEND");
            case ARRAY_PREPEND -> DialectHelpers.arrayAppendSql(this, e, "ARRAY_PREPEND", true);
            case BITWISE_XOR -> binary(e, "#");
            case COLUMN_DEF -> applyTransforms(e, PostgresGenerator::autoIncrementToSerial,
                    PostgresGenerator::serialToGenerated);
            case CURRENT_DATE -> DialectHelpers.noParenCurrentDateSql(this, e);
            case CURRENT_TIMESTAMP -> "CURRENT_TIMESTAMP";
            case CURRENT_USER -> "CURRENT_USER";
            case DATE_ADD -> dateAddSql(e, "+");
            case DATE_DIFF -> dateDiffSql(e);
            case DATE_STR_TO_DATE -> DialectHelpers.datestrtodateSql(this, e);
            case DATE_SUB -> dateAddSql(e, "-");
            case DAY -> dayMonthYearSql(e);
            case GENERATE_SERIES -> DialectHelpers.generateSeriesSql(this, e, "GENERATE_SERIES");
            case GETBIT -> DialectHelpers.getbitSql(this, e);
            case GROUP_CONCAT -> DialectHelpers.groupconcatSql(this, e, "STRING_AGG", ",", false, false);
            case JSON_ARRAY_AGG -> funcWith("JSON_AGG", "(", sql(e, Args.ORDER) + ")", true, sql(e, Args.THIS));
            case JSON_EXTRACT -> jsonExtractSql(e, "JSON_EXTRACT_PATH", "->");
            case JSON_EXTRACT_SCALAR -> jsonExtractSql(e, "JSON_EXTRACT_PATH_TEXT", "->>");
            case JSONB_EXTRACT -> DialectHelpers.arrowJsonExtractSql(this, e, "#>");
            case JSONB_EXTRACT_SCALAR -> DialectHelpers.arrowJsonExtractSql(this, e, "#>>");
            case PARSE_JSON -> sql(Exp.cast(e.getThis(), DType.JSON));
            case JSON_PATH_KEY -> DialectHelpers.jsonPathKeyOnlyName(this, e);
            case JSON_PATH_ROOT -> "";
            case JSON_PATH_SUBSCRIPT -> jsonPathPart(e.thisValue());
            case LAST_DAY -> DialectHelpers.noLastDaySql(this, e);
            case MAX -> DialectHelpers.maxOrGreatest(this, e);
            case MAP_FROM_ENTRIES -> DialectHelpers.noMapFromEntriesSql(this, e);
            case MIN -> DialectHelpers.minOrLeast(this, e);
            case MERGE -> DialectHelpers.mergeWithoutTargetSql(this, e);
            case MONTH -> dayMonthYearSql(e);
            case PARTITIONED_BY_PROPERTY -> "PARTITION BY " + sql(e, Args.THIS);
            case PERCENTILE_CONT, PERCENTILE_DISC -> applyTransforms(e, Transforms::addWithinGroupForPercentiles);
            case PIVOT -> DialectHelpers.noPivotSql(this, e);
            case REGEXP_LIKE -> binary(e, "~");
            case REGEXP_I_LIKE -> binary(e, "~*");
            case REGEXP_REPLACE -> func("REGEXP_REPLACE",
                    e.getThis(),
                    e.getExpression(),
                    e.get(Args.REPLACEMENT),
                    e.get(Args.POSITION),
                    e.get(Args.OCCURRENCE),
                    DialectHelpers.regexpReplaceGlobalModifier(e));
            case ROUND -> roundSql(e);
            case SELECT -> applyTransforms(e, Transforms::eliminateSemiAndAntiJoins, Transforms::eliminateQualify);
            case SHA2 -> DialectHelpers.sha256Sql(this, e);
            case SHA2_DIGEST -> DialectHelpers.sha2DigestSql(this, e);
            case STR_POSITION -> DialectHelpers.strpositionSql(this, e, "POSITION", false, false, true);
            case STR_TO_DATE -> func("TO_DATE", e.getThis(), formatTime(e));
            case STR_TO_TIME -> func("TO_TIMESTAMP", e.getThis(), formatTime(e));
            case STRUCT_EXTRACT -> DialectHelpers.structExtractSql(this, e);
            case SUBSTRING -> substringSql(e);
            case TIMESTAMP_TRUNC -> DialectHelpers.timestamptruncSql(this, e, true);
            case TIME_STR_TO_TIME -> DialectHelpers.timestrtotimeSql(this, e);
            case TIME_TO_STR -> func("TO_CHAR", e.getThis(), formatTime(e));
            case TO_CHAR -> e.has(Args.FORMAT) ? functionFallbackSql(e) : tocharSql(e);
            case TRIM -> DialectHelpers.trimSql(this, e);
            case TRY_CAST -> DialectHelpers.noTrycastSql(this, e);
            case TS_OR_DS_ADD -> dateAddSql(e, "+");
            case TS_OR_DS_DIFF -> dateDiffSql(e);
            case UUID -> "GEN_RANDOM_UUID()";
            case TIME_TO_UNIX -> func("DATE_PART", Exp.string("epoch"), e.getThis());
            case XOR -> DialectHelpers.boolXorSql(this, e);
            case YEAR -> dayMonthYearSql(e);
            case UNIX_TO_TIME -> unixToTimeSql(e);
            case LEVENSHTEIN -> levenshteinSql(e);
            case COUNT_IF -> DialectHelpers.countIfToSum(this, e);
            default -> super.transform(e);
        };
    }

    // ------------------------------------------------------------------------------------------
    // <key>_sql methods the base Generator lacks

    @Override
    public String sqlFor(Expression e) {
        return switch (e.type()) {
            case SCHEMA_COMMENT_PROPERTY -> schemacommentpropertySql(e);
            case COMMENT_COLUMN_CONSTRAINT -> commentcolumnconstraintSql(e);
            case ARRAY -> arraySql(e);
            case IS_ASCII -> isasciiSql(e);
            case CURRENT_SCHEMA -> currentschemaSql(e);
            case ARRAY_CONTAINS -> arraycontainsSql(e);
            default -> super.sqlFor(e);
        };
    }

    // ------------------------------------------------------------------------------------------
    // <key>_sql methods

    @Override
    public String lateralSql(Expression expression) {
        String sql = super.lateralSql(expression);

        if (expression.get(Args.CROSS_APPLY) != null) {
            sql = sql + " ON TRUE";
        }

        return sql;
    }

    @Override
    public String bytestringSql(Expression expression) {
        if (!expression.has(Args.IS_BYTES)) {
            return super.bytestringSql(expression);
        }

        byte[] bytes = Py.str(expression.thisValue()).getBytes(StandardCharsets.ISO_8859_1);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }

        return func("DECODE", Exp.string(hex.toString()), Exp.string("hex"));
    }

    public String schemacommentpropertySql(Expression expression) {
        unsupported("Table comments are not supported in the CREATE statement");
        return "";
    }

    public String commentcolumnconstraintSql(Expression expression) {
        unsupported("Column comments are not supported in the CREATE statement");
        return "";
    }

    @Override
    public String columndefSql(Expression expression, String sep) {
        // PostgreSQL places parameter modes BEFORE parameter name
        Expression paramConstraint = expression.find(ExprType.IN_OUT_COLUMN_CONSTRAINT);

        if (paramConstraint != null) {
            String modeSql = sql(paramConstraint);
            paramConstraint.pop(); // Remove to prevent double-rendering
            String baseSql = super.columndefSql(expression, sep);
            return modeSql + " " + baseSql;
        }

        return super.columndefSql(expression, sep);
    }

    @Override
    public String unnestSql(Expression expression) {
        List<Expression> exprs = expression.getExpressions();
        if (exprs.size() == 1) {
            Expression arg = exprs.get(0);
            if (arg.is(ExprType.GENERATE_DATE_ARRAY)) {
                Map<io.github.offbyjuan.jglot.expressions.Arg, Object> args = new java.util.LinkedHashMap<>();
                for (Map.Entry<io.github.offbyjuan.jglot.expressions.Arg, Object> entry : arg.argEntries()) {
                    args.put(entry.getKey(), entry.getValue());
                }
                Expression generateSeries = Expression.of(ExprType.GENERATE_SERIES, args);
                Expression parent = expression.parent();
                if (parent != null && parent.is(ExprType.FROM, ExprType.JOIN)) {
                    Object alias = expression.get(Args.ALIAS);
                    generateSeries = Exp.select("value::date")
                            .from(Exp.tableAlias(Expression.of(ExprType.TABLE, Args.THIS, generateSeries),
                                    "_t", List.of("value")), true)
                            .subquery(Expression.truthy(alias) ? alias : "_unnested_generate_series", true);
                }
                return sql(generateSeries);
            }

            Expression this_ = TypeAnnotator.annotateTypes(arg, dialect);
            if (this_.isType("array<json>")) {
                while (this_.is(ExprType.CAST)) {
                    this_ = this_.getThis();
                }

                String argAsJson = sql(Exp.cast(this_, DType.JSON));
                String alias = sql(expression, Args.ALIAS);
                alias = !alias.isEmpty() ? " AS " + alias : "";

                if (expression.has(Args.OFFSET)) {
                    unsupported("Unsupported JSON_ARRAY_ELEMENTS with offset");
                }

                return "JSON_ARRAY_ELEMENTS(" + argAsJson + ")" + alias;
            }
        }

        return super.unnestSql(expression);
    }

    /** Forms like ARRAY[1, 2, 3][3] aren't allowed; we need to wrap the ARRAY. */
    @Override
    public String bracketSql(Expression expression) {
        Expression this_ = expression.getThis();
        if (this_ != null && this_.is(ExprType.ARRAY)) {
            expression.set(Args.THIS, Exp.paren(this_));
        }

        return super.bracketSql(expression);
    }

    @Override
    public String matchagainstSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        List<String> expressions = new ArrayList<>();
        for (Expression e : expression.getExpressions()) {
            expressions.add(sql(e) + " @@ " + this_);
        }
        String sql = String.join(" OR ", expressions);
        return expressions.size() > 1 ? "(" + sql + ")" : sql;
    }

    @Override
    public String altersetSql(Expression expression) {
        String exprs = expressions(opts(expression).flat());
        exprs = !exprs.isEmpty() ? "(" + exprs + ")" : "";

        String accessMethod = sql(expression, Args.ACCESS_METHOD);
        accessMethod = !accessMethod.isEmpty() ? "ACCESS METHOD " + accessMethod : "";
        String tablespace = sql(expression, Args.TABLESPACE);
        tablespace = !tablespace.isEmpty() ? "TABLESPACE " + tablespace : "";
        String option = sql(expression, Args.OPTION);

        return "SET " + exprs + accessMethod + tablespace + option;
    }

    @Override
    public String datatypeSql(Expression expression) {
        if (expression.isType(DType.ARRAY)) {
            if (!expression.getExpressions().isEmpty()) {
                String values = expressions(opts(expression).key(Args.VALUES).flat());
                return expressions(opts(expression).flat()) + "[" + values + "]";
            }
            return "ARRAY";
        }

        if (expression.isType(DType.ENUM)) {
            return "ENUM (" + expressions(opts(expression).flat()) + ")";
        }

        if (expression.isType(DType.DOUBLE, DType.FLOAT) && !expression.getExpressions().isEmpty()) {
            // Postgres doesn't support precision for REAL and DOUBLE PRECISION types
            return "FLOAT(" + expressions(opts(expression).flat()) + ")";
        }

        return super.datatypeSql(expression);
    }

    @Override
    public String castSql(Expression expression, String safePrefix) {
        Expression this_ = expression.getThis();

        // Postgres casts DIV() to decimal for transpilation but when roundtripping it's superfluous
        if (this_ != null && this_.is(ExprType.INT_DIV)
                && (g.TRANSFORMS_RENAME.containsKey(ExprType.INT_DIV) || g.TRANSFORMS_CUSTOM.contains(ExprType.INT_DIV))
                && Objects.equals(expression.get(Args.TO), DataTypes.of(DType.DECIMAL))) {
            return sql(this_);
        }

        return super.castSql(expression, safePrefix);
    }

    public String arraySql(Expression expression) {
        List<Expression> exprs = expression.getExpressions();
        String funcName = normalizeFunc("ARRAY");

        Expression first = Py.seqGet(exprs, 0);
        if (first != null && first.is(ExprType.QUERY)) {
            return funcName + "(" + sql(exprs.get(0)) + ")";
        }

        return funcName + DialectHelpers.inlineArraySql(this, expression);
    }

    @Override
    public String computedcolumnconstraintSql(Expression expression) {
        return "GENERATED ALWAYS AS (" + sql(expression, Args.THIS) + ") STORED";
    }

    public String isasciiSql(Expression expression) {
        return "(" + sql(expression.getThis()) + " ~ '^[[:ascii:]]*$')";
    }

    @Override
    public String ignorenullsSql(Expression expression) {
        // https://www.postgresql.org/docs/current/functions-window.html
        unsupported("PostgreSQL does not support IGNORE NULLS.");
        return sql(expression.getThis());
    }

    @Override
    public String respectnullsSql(Expression expression) {
        // https://www.postgresql.org/docs/current/functions-window.html
        unsupported("PostgreSQL does not support RESPECT NULLS.");
        return sql(expression.getThis());
    }

    public String currentschemaSql(Expression expression) {
        unsupportedArgs(expression, Args.THIS);
        return "CURRENT_SCHEMA";
    }

    @Override
    public String intervalSql(Expression expression) {
        String unit = Py.lower(expression.text(Args.UNIT));

        Expression this_ = expression.getThis();
        if (unit.startsWith("quarter") && this_ != null && this_.is(ExprType.LITERAL)) {
            this_.replace(Exp.string(String.valueOf(pyInt(this_.toPy()) * 3)));
            expression.expr(Args.UNIT).replace(Exp.var("MONTH"));
        }

        return super.intervalSql(expression);
    }

    private static long pyInt(Object value) {
        if (value instanceof Number n) {
            return n instanceof BigDecimal bd ? bd.longValue() : n.longValue();
        }
        return Long.parseLong(Py.str(value).strip());
    }

    @Override
    public String placeholderSql(Expression expression) {
        if (expression.has(Args.JDBC)) {
            return "?";
        }

        String this_ = expression.thisValue() != null ? "(" + expression.name() + ")" : "";
        return g.NAMED_PLACEHOLDER_TOKEN + this_ + "s";
    }

    public String arraycontainsSql(Expression expression) {
        // Convert DuckDB's LIST_CONTAINS(array, value) to PostgreSQL
        // DuckDB behavior:
        //   - LIST_CONTAINS([1,2,3], 2) -> true
        //   - LIST_CONTAINS([1,2,3], 4) -> false
        //   - LIST_CONTAINS([1,2,NULL], 4) -> false (not NULL)
        //   - LIST_CONTAINS([1,2,3], NULL) -> NULL
        //
        // PostgreSQL equivalent: CASE WHEN value IS NULL THEN NULL
        //                            ELSE COALESCE(value = ANY(array), FALSE) END
        Expression value = expression.getExpression();
        Expression array = expression.getThis();

        Expression coalesceExpr = Expression.of(ExprType.COALESCE,
                Args.THIS, value.eq(Expression.of(ExprType.ANY, Args.THIS, Exp.paren(array))),
                Args.EXPRESSIONS, List.of(Exp.bool(false)));

        Expression caseExpr = Expression.of(ExprType.CASE)
                .when(Expression.of(ExprType.IS, Args.THIS, value, Args.EXPRESSION, Exp.nul()), Exp.nul(), false)
                .elseCase(coalesceExpr, false);

        return sql(caseExpr);
    }
}
