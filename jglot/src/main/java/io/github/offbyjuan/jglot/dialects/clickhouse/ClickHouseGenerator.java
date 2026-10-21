package io.github.offbyjuan.jglot.dialects.clickhouse;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.DialectHelpers;
import io.github.offbyjuan.jglot.dialects.data.PropertiesLocation;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.util.Py;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ClickHouse generator: a port of sqlglot's {@code sqlglot.generators.clickhouse}. */
public class ClickHouseGenerator extends Generator {
    public ClickHouseGenerator(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }

    // ------------------------------------------------------------------------------------------
    // Module-level helpers

    private String unixToTimeSql(Expression expression) {
        Expression scale = expression.expr(Args.SCALE);
        Expression timestamp = expression.getThis();

        if (scale == null || scale.equals(Exp.number(0))) {
            return func("fromUnixTimestamp", Exp.cast(timestamp, DType.BIGINT));
        }
        if (scale.equals(Exp.number(3))) {
            return func("fromUnixTimestamp64Milli", Exp.cast(timestamp, DType.BIGINT));
        }
        if (scale.equals(Exp.number(6))) {
            return func("fromUnixTimestamp64Micro", Exp.cast(timestamp, DType.BIGINT));
        }
        if (scale.equals(Exp.number(9))) {
            return func("fromUnixTimestamp64Nano", Exp.cast(timestamp, DType.BIGINT));
        }

        return func("fromUnixTimestamp",
                Exp.cast(Expression.of(ExprType.DIV, Args.THIS, timestamp,
                        Args.EXPRESSION, Exp.func("POW", dialect, 10, scale)), DType.BIGINT));
    }

    private static String lowerFunc(String sql) {
        int index = sql.indexOf('(');
        return Py.lower(sql.substring(0, index)) + sql.substring(index);
    }

    private String quantileSql(Expression expression) {
        Expression quantile = expression.expr(Args.QUANTILE);
        String args = "(" + sql(expression, Args.THIS) + ")";

        String fn;
        if (quantile != null && quantile.is(ExprType.ARRAY)) {
            fn = func("quantiles", quantile.getExpressions().toArray());
        } else {
            fn = func("quantile", quantile);
        }

        return fn + args;
    }

    private String datetimeDeltaSql(String name, Expression expression) {
        if (!expression.has(Args.UNIT)) {
            return renameFunc(name, expression);
        }

        return func(name,
                DialectHelpers.unitToVar(expression),
                expression.getExpression(),
                expression.getThis(),
                expression.get(Args.ZONE));
    }

    private static final Pattern ISO_RE = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2})(?::?(\\d{2})(?::?(\\d{2})(?:[.,](\\d+))?)?)?)?"
                    + "\\s*(Z|[+-]\\d{2}(?::?\\d{2}(?::?\\d{2}(?:\\.\\d+)?)?)?)?");

    /** {@code datetime.datetime.fromisoformat(s).replace(tzinfo=None).isoformat(sep=" ")}. */
    private static String isoWithoutTz(String s) {
        Matcher m = ISO_RE.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid isoformat string: '" + s + "'");
        }
        String hh = m.group(4) != null ? m.group(4) : "00";
        String mm = m.group(5) != null ? m.group(5) : "00";
        String ss = m.group(6) != null ? m.group(6) : "00";
        String frac = m.group(7);
        StringBuilder sb = new StringBuilder();
        sb.append(m.group(1)).append('-').append(m.group(2)).append('-').append(m.group(3)).append(' ')
                .append(hh).append(':').append(mm).append(':').append(ss);
        if (frac != null) {
            String f = frac.length() >= 6 ? frac.substring(0, 6) : (frac + "000000").substring(0, 6);
            if (Integer.parseInt(f) != 0) {
                sb.append('.').append(f);
            }
        }
        return sb.toString();
    }

    private String timestrtotimeSql(Expression expression) {
        Expression ts = expression.getThis();

        Expression tz = expression.expr(Args.ZONE);
        if (tz != null && ts != null && ts.is(ExprType.LITERAL)) {
            // Clickhouse will not accept timestamps that include a UTC offset, so we must remove them.
            // The first step to removing is parsing the string with `datetime.datetime.fromisoformat`.
            //
            // In python <3.11, `fromisoformat()` can only parse timestamps of millisecond (3 digit)
            // or microsecond (6 digit) precision. It will error if passed any other number of fractional
            // digits, so we extract the fractional seconds and pad to 6 digits before parsing.
            String tsString = ts.name().strip();

            // separate [date and time] from [fractional seconds and UTC offset]
            String[] tsParts = tsString.split("\\.", -1);
            if (tsParts.length == 2) {
                // separate fractional seconds and UTC offset
                String offsetSep = tsParts[1].contains("+") ? "+" : "-";
                String[] tsFracParts = tsParts[1].split(Pattern.quote(offsetSep), -1);
                int numFracParts = tsFracParts.length;

                // pad to 6 digits if fractional seconds present
                StringBuilder frac = new StringBuilder(tsFracParts[0]);
                while (frac.length() < 6) {
                    frac.append('0');
                }
                tsString = tsParts[0] + "." + frac
                        + (numFracParts > 1 ? offsetSep : "")
                        + (numFracParts > 1 ? tsFracParts[1] : "");
            }

            // return literal with no timezone, eg turn '2020-01-01 12:13:14-08:00' into '2020-01-01 12:13:14'
            // this is because Clickhouse encodes the timezone as a data type parameter and throws an error if
            // it's part of the timestamp string
            ts = Exp.string(isoWithoutTz(tsString));
        }

        // Non-nullable DateTime64 with microsecond precision
        List<Expression> expressions = new ArrayList<>();
        expressions.add(Expression.of(ExprType.DATA_TYPE_PARAM, Args.THIS, Exp.number(6)));
        if (tz != null) {
            expressions.add(Expression.of(ExprType.DATA_TYPE_PARAM, Args.THIS, tz));
        }
        Expression datatype = Expression.of(ExprType.DATA_TYPE,
                Args.THIS, DType.DATETIME64, Args.EXPRESSIONS, expressions, Args.NULLABLE, Boolean.FALSE);

        return sql(Exp.cast(ts, datatype, true, dialect));
    }

    private String mapSql(Expression expression) {
        Expression parent = expression.parent();
        if (!(parent != null && parent.argKey() == Args.SETTINGS)) {
            return lowerFunc(DialectHelpers.varMapSql(this, expression));
        }

        Object keys = expression.get(Args.KEYS);
        Object values = expression.get(Args.VALUES);

        if (!Expression.isInstance(keys, ExprType.ARRAY) || !Expression.isInstance(values, ExprType.ARRAY)) {
            unsupported("Cannot convert array columns into map.");
            return "";
        }

        List<Expression> keyList = ((Expression) keys).getExpressions();
        List<Expression> valueList = ((Expression) values).getExpressions();
        List<String> args = new ArrayList<>();
        for (int i = 0; i < Math.min(keyList.size(), valueList.size()); i++) {
            args.add(sql(keyList.get(i)) + ": " + sql(valueList.get(i)));
        }

        return "{" + String.join(", ", args) + "}";
    }

    private String jsonCastSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        Expression to = expression.expr(Args.TO);
        String toSql = sql(to);

        if (to != null && !to.getExpressions().isEmpty()) {
            toSql = sql(Exp.toIdentifier(toSql));
        }

        return this_ + ".:" + toSql;
    }

    // ------------------------------------------------------------------------------------------
    // TRANSFORMS

    @Override
    public String transform(Expression e) {
        return switch (e.type()) {
            case ARRAY_FILTER -> func("arrayFilter", e.getExpression(), e.getThis());
            case TRANSFORM -> func("arrayMap", e.getExpression(), e.getThis());
            case ARRAY_REMOVE -> DialectHelpers.removeFromArrayUsingFilter(this, e);
            case ARG_MAX -> DialectHelpers.argMaxOrMinNoCount(this, e, "argMax");
            case ARG_MIN -> DialectHelpers.argMaxOrMinNoCount(this, e, "argMin");
            case ARRAY -> DialectHelpers.inlineArraySql(this, e);
            case COMPRESS_COLUMN_CONSTRAINT -> "CODEC(" + expressions(opts(e).key(Args.THIS).flat()) + ")";
            case COMPUTED_COLUMN_CONSTRAINT -> (e.has(Args.PERSISTED) ? "MATERIALIZED" : "ALIAS") + " "
                    + sql(e, Args.THIS);
            case CURRENT_DATE -> func("CURRENT_DATE");
            case DATE_ADD -> datetimeDeltaSql("DATE_ADD", e);
            case DATE_DIFF -> datetimeDeltaSql("DATE_DIFF", e);
            case DATE_SUB -> datetimeDeltaSql("DATE_SUB", e);
            case FINAL -> sql(e, Args.THIS) + " FINAL";
            case JAROWINKLER_SIMILARITY -> DialectHelpers.jarowinklerSimilarity(this, e, "jaroWinklerSimilarity");
            case JSON_CAST -> jsonCastSql(e);
            case JSON_EXTRACT -> DialectHelpers.jsonExtractSegments(this, e, "JSONExtractRaw", false, null);
            case JSON_EXTRACT_SCALAR -> DialectHelpers.jsonExtractSegments(this, e, "JSONExtractString", false, null);
            case JSON_PATH_KEY -> DialectHelpers.jsonPathKeyOnlyName(this, e);
            case JSON_PATH_ROOT -> "";
            case LENGTH -> DialectHelpers.lengthOrCharLengthSql(this, e);
            case MAP, VAR_MAP -> mapSql(e);
            case PARTITIONED_BY_PROPERTY -> "PARTITION BY " + sql(e, Args.THIS);
            case PIVOT -> DialectHelpers.noPivotSql(this, e);
            case QUANTILE -> quantileSql(e);
            case REGEXP_LIKE -> func("match", e.getThis(), e.getExpression());
            case STR_POSITION -> DialectHelpers.strpositionSql(this, e, "POSITION", true, false, false);
            case TIME_TO_STR -> {
                Expression thisExpr = e.getThis();
                yield func("formatDateTime",
                        thisExpr != null && thisExpr.is(ExprType.TS_OR_DS_TO_TIMESTAMP) ? thisExpr.getThis() : thisExpr,
                        formatTime(e),
                        e.get(Args.ZONE));
            }
            case TIME_STR_TO_TIME -> timestrtotimeSql(e);
            case TIMESTAMP_ADD -> datetimeDeltaSql("TIMESTAMP_ADD", e);
            case TIMESTAMP_SUB -> datetimeDeltaSql("TIMESTAMP_SUB", e);
            case XOR -> func("xor", e.getThis(), e.getExpression());
            case MD5 -> func("LOWER", func("HEX", func("MD5", e.getThis())));
            case SHA2 -> DialectHelpers.sha256Sql(this, e);
            case SHA2_DIGEST -> DialectHelpers.sha2DigestSql(this, e);
            case SPLIT -> func("splitByString", e.get(Args.EXPRESSION), e.getThis(), e.get(Args.LIMIT));
            case REGEXP_SPLIT -> func("splitByRegexp", e.get(Args.EXPRESSION), e.getThis(), e.get(Args.LIMIT));
            case UNIX_TO_TIME -> unixToTimeSql(e);
            case TRIM -> DialectHelpers.trimSql(this, e, "BOTH");
            case SCHEMA_COMMENT_PROPERTY -> nakedProperty(e);
            case LEVENSHTEIN -> {
                unsupportedArgs(e, Args.INS_COST, Args.DEL_COST, Args.SUB_COST, Args.MAX_DIST);
                yield renameFunc("editDistance", e);
            }
            case PARSE_DATETIME -> func("parseDateTime", e.getThis(), e.get(Args.FORMAT), e.get(Args.ZONE));
            default -> super.transform(e);
        };
    }

    // ------------------------------------------------------------------------------------------
    // <key>_sql methods the base Generator lacks

    @Override
    public String sqlFor(Expression e) {
        return switch (e.type()) {
            case CONTAINS -> containsSql(e);
            case GROUP_CONCAT -> groupconcatSql(e);
            case REGEXP_COUNT -> regexpcountSql(e);
            case REGEXP_I_LIKE -> regexpilikeSql(e);
            case AUTO_REFRESH_PROPERTY -> autorefreshpropertySql(e);
            case PARTITION_ID -> partitionidSql(e);
            case REPLACE_PARTITION -> replacepartitionSql(e);
            case PROJECTION_DEF -> projectiondefSql(e);
            case NESTED_JSON_SELECT -> nestedjsonselectSql(e);
            case TIMESTAMP_TRUNC -> timestamptruncSql(e);
            case DATE_TRUNC -> datetruncSql(e);
            default -> super.sqlFor(e);
        };
    }

    public String containsSql(Expression expression) {
        unsupportedArgs(expression, Args.JSON_SCOPE);
        // ClickHouse has no CONTAINS; the comparison needs parentheses inside another operator
        Expression result = Expression.of(ExprType.GT,
                Args.THIS, Expression.of(ExprType.STR_POSITION,
                        Args.THIS, expression.getThis(), Args.SUBSTR, expression.getExpression()),
                Args.EXPRESSION, Exp.number(0));
        Expression parent = expression.parent();
        if (parent != null && parent.is(ExprType.BINARY) && !parent.is(ExprType.CONNECTOR)) {
            return sql(Exp.paren(result));
        }
        return sql(result);
    }

    public String groupconcatSql(Expression expression) {
        Expression this_ = expression.getThis();
        Expression separator = expression.expr(Args.SEPARATOR);

        if (this_ != null && this_.is(ExprType.LIMIT) && this_.getThis() != null) {
            Expression limit = this_;
            this_ = limit.getThis().pop();
            List<Expression> exprs = new ArrayList<>();
            exprs.add(separator);
            exprs.add(limit.getExpression());
            return sql(Expression.of(ExprType.PARAMETERIZED_AGG,
                    Args.THIS, "groupConcat",
                    Args.PARAMS, new ArrayList<>(List.of(this_)),
                    Args.EXPRESSIONS, exprs));
        }

        if (separator != null) {
            List<Expression> exprs = new ArrayList<>();
            exprs.add(separator);
            List<Expression> params = new ArrayList<>();
            params.add(this_);
            return sql(Expression.of(ExprType.PARAMETERIZED_AGG,
                    Args.THIS, "groupConcat",
                    Args.PARAMS, params,
                    Args.EXPRESSIONS, exprs));
        }

        return func("groupConcat", this_);
    }

    @Override
    public String selectSql(Expression expression) {
        Expression limit = expression.expr(Args.LIMIT);
        if (limit != null && limit.is(ExprType.FETCH) && !expression.has(Args.ORDER)) {
            Expression count = limit.expr(Args.COUNT);
            expression.set(Args.LIMIT, Expression.of(ExprType.LIMIT,
                    Args.EXPRESSION, count != null ? count : Exp.number(1)));
        }
        return super.selectSql(expression);
    }

    @Override
    public String offsetSql(Expression expression) {
        String offset = super.offsetSql(expression);

        // OFFSET ... FETCH syntax requires a "ROW" or "ROWS" keyword
        // https://clickhouse.com/docs/sql-reference/statements/select/offset
        Expression parent = expression.parent();
        if (parent != null && parent.is(ExprType.SELECT) && Expression.isInstance(parent.get(Args.LIMIT), ExprType.FETCH)) {
            offset = offset + " ROWS";
        }

        return offset;
    }

    @Override
    public String strtodateSql(Expression expression) {
        String strtodateSql = functionFallbackSql(expression);

        Expression parent = expression.parent();
        if (!(parent != null && parent.is(ExprType.CAST))) {
            // StrToDate returns DATEs in other dialects (eg. postgres), so
            // this branch aims to improve the transpilation to clickhouse
            return castSql(Exp.cast(expression, "DATE"));
        }

        return strtodateSql;
    }

    @Override
    public String castSql(Expression expression, String safePrefix) {
        Expression this_ = expression.getThis();
        Expression to = expression.expr(Args.TO);

        if (this_ != null && this_.is(ExprType.STR_TO_DATE) && to != null && to.equals(DataTypes.of(DType.DATETIME))) {
            return sql(this_);
        }

        this_ = this_ == null ? null : this_.unnest();
        if (this_ != null && this_.is(ExprType.JSON_EXTRACT)
                && this_.has(Args.REQUIRES_JSON)
                && to != null && to.isType(DataTypes.TEXT_TYPES.toArray())) {
            return func("JSONExtract", this_, Exp.string("Nullable(String)"));
        }

        return super.castSql(expression, safePrefix);
    }

    @Override
    public String trycastSql(Expression expression) {
        Expression dtype = expression.expr(Args.TO);
        if (!DataTypes.isType(dtype, true, g.NON_NULLABLE_TYPES.toArray())) {
            // Casting x into Nullable(T) appears to behave similarly to TRY_CAST(x AS T)
            dtype.set(Args.NULLABLE, Boolean.TRUE);
        }

        return super.castSql(expression, null);
    }

    @Override
    public String jsonpathsubscriptSql(Expression expression) {
        String this_ = jsonPathPart(expression.thisValue());
        return Py.isInt(this_) ? String.valueOf(new java.math.BigInteger(this_.strip()).add(java.math.BigInteger.ONE)) : this_;
    }

    @Override
    public String likepropertySql(Expression expression) {
        return "AS " + sql(expression, Args.THIS);
    }

    private interface SqlFn {
        String apply(Expression e);
    }

    private String anyToHas(Expression expression, SqlFn default_, String prefix) {
        Expression left = expression.getThis();
        Expression right = expression.getExpression();
        Expression arr;
        Expression this_;
        if (left != null && left.is(ExprType.ANY)) {
            arr = left;
            this_ = right;
        } else if (right != null && right.is(ExprType.ANY)) {
            arr = right;
            this_ = left;
        } else {
            return default_.apply(expression);
        }

        return prefix + func("has", arr.getThis().unnest(), this_);
    }

    @Override
    public String eqSql(Expression expression) {
        return anyToHas(expression, super::eqSql, "");
    }

    @Override
    public String neqSql(Expression expression) {
        return anyToHas(expression, super::neqSql, "NOT ");
    }

    public String regexpcountSql(Expression expression) {
        Expression this_ = expression.getThis();
        Object pattern = expression.getExpression();
        Expression position = expression.expr(Args.POSITION);
        Expression parameters = expression.expr(Args.PARAMETERS);

        Object thisArg = this_;
        if (position != null) {
            // SUBSTRING counts bytes in ClickHouse, the position counts characters
            thisArg = func("substringUTF8", this_, position);
        }

        if (parameters != null) {
            String flags = parameters.isString() ? parameters.name() : "";
            boolean badFlags = false;
            for (int i = 0; i < flags.length(); i++) {
                if ("cims".indexOf(flags.charAt(i)) < 0) {
                    badFlags = true;
                    break;
                }
            }
            if (!parameters.isString() || badFlags) {
                unsupported("Only literal 'c', 'i', 'm' and 's' regexp flags are supported");
            }
            // "c" (case-sensitive) is the default, and the last of "c" and "i" wins
            if (flags.lastIndexOf('c') > flags.lastIndexOf('i')) {
                flags = flags.replace("i", "");
            }
            StringBuilder kept = new StringBuilder();
            for (int i = 0; i < flags.length(); i++) {
                char c = flags.charAt(i);
                if ("ims".indexOf(c) >= 0) {
                    kept.append(c);
                }
            }
            flags = kept.toString();
            if (!flags.isEmpty()) {
                // countMatches has no flags argument, so they are embedded in the pattern
                pattern = func("CONCAT", Exp.string("(?" + flags + ")"), pattern);
            }
        }

        return func("countMatches", thisArg, pattern);
    }

    public String regexpilikeSql(Expression expression) {
        // Manually add a flag to make the search case-insensitive
        String regex = func("CONCAT", "'(?i)'", expression.getExpression());
        return func("match", expression.getThis(), regex);
    }

    @Override
    public String datatypeSql(Expression expression) {
        // String is the standard ClickHouse type, every other variant is just an alias.
        // Additionally, any supplied length parameter will be ignored.
        //
        // https://clickhouse.com/docs/en/sql-reference/data-types/string
        String dtype;
        Object thisValue = expression.thisValue();
        if (thisValue instanceof DType t && g.STRING_TYPE_MAPPING.containsKey(t)) {
            dtype = "String";
        } else {
            dtype = super.datatypeSql(expression);
        }

        // This section changes the type to `Nullable(...)` if the following conditions hold:
        // - It's marked as nullable - this ensures we won't wrap ClickHouse types with `Nullable`
        //   and change their semantics
        // - It's not the key type of a `Map`. This is because ClickHouse enforces the following
        //   constraint: "Type of Map key must be a type, that can be represented by integer or
        //   String or FixedString (possibly LowCardinality) or UUID or IPv6"
        // - It's not a composite type, e.g. `Nullable(Array(...))` is not a valid type
        Expression parent = expression.parent();
        Object nullable = expression.get(Args.NULLABLE);
        if (Boolean.TRUE.equals(nullable) || (nullable == null
                && !(parent != null && parent.is(ExprType.DATA_TYPE)
                        && DataTypes.isType(parent, true, DType.MAP)
                        && (expression.index() == -1 || expression.index() == 0))
                && !DataTypes.isType(expression, true, g.NON_NULLABLE_TYPES.toArray()))) {
            dtype = "Nullable(" + dtype + ")";
        }

        return dtype;
    }

    @Override
    public String cteSql(Expression expression) {
        if (expression.has(Args.SCALAR)) {
            String this_ = sql(expression, Args.THIS);
            String alias = sql(expression, Args.ALIAS);
            return this_ + " AS " + alias;
        }

        return super.cteSql(expression);
    }

    @Override
    public Collection<String> afterLimitModifiers(Expression expression) {
        List<String> out = new ArrayList<>(super.afterLimitModifiers(expression));
        out.add(expression.has(Args.SETTINGS)
                ? seg("SETTINGS ") + expressions(opts(expression).key(Args.SETTINGS).flat())
                : "");
        out.add(expression.has(Args.FORMAT)
                ? seg("FORMAT ") + sql(expression, Args.FORMAT)
                : "");
        return out;
    }

    @Override
    public String placeholderSql(Expression expression) {
        return "{" + expression.name() + ": " + sql(expression, Args.KIND) + "}";
    }

    @Override
    public String onclusterSql(Expression expression) {
        return "ON CLUSTER " + sql(expression, Args.THIS);
    }

    private String refreshIntervalSql(Expression expression) {
        if (expression.is(ExprType.ADD)) {
            return refreshIntervalSql(expression.getThis()) + " " + refreshIntervalSql(expression.getExpression());
        }
        return sql(expression.assertIs(ExprType.INTERVAL));
    }

    public String autorefreshpropertySql(Expression expression) {
        String cadence = sql(expression, Args.CADENCE);
        Expression interval = expression.getThis();
        String schedule = !cadence.isEmpty() && interval != null
                ? " " + cadence + " " + refreshIntervalSql(interval)
                : "";
        Expression offsetExpr = expression.expr(Args.OFFSET);
        String offset = offsetExpr != null ? " OFFSET " + refreshIntervalSql(offsetExpr) : "";
        Expression randomizeExpr = expression.expr(Args.RANDOMIZE);
        String randomize = randomizeExpr != null ? " RANDOMIZE FOR " + refreshIntervalSql(randomizeExpr) : "";
        String dependencies = expressions(opts(expression).flat());
        dependencies = !dependencies.isEmpty() ? " DEPENDS ON " + dependencies : "";
        String settings = sql(expression, Args.SETTINGS);
        settings = !settings.isEmpty() ? " " + settings : "";
        String append = expression.has(Args.APPEND) ? " APPEND" : "";

        return "REFRESH" + schedule + offset + randomize + dependencies + settings + append;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String createableSql(Expression expression, Object locations) {
        List<Expression> postName = null;
        if (locations instanceof Map<?, ?> m) {
            postName = (List<Expression>) m.get(PropertiesLocation.POST_NAME);
        }
        String kind = expression.text(Args.KIND);
        if (g.ON_CLUSTER_TARGETS.contains(kind) && postName != null && !postName.isEmpty()) {
            Expression thisExpr = expression.getThis();
            String thisName = sql(thisExpr != null && thisExpr.is(ExprType.SCHEMA) ? thisExpr : expression, Args.THIS);
            List<String> props = new ArrayList<>();
            for (Expression prop : postName) {
                props.add(sql(prop));
            }
            String thisProperties = String.join(" ", props);
            String thisSchema = schemaColumnsSql(expression.getThis());
            thisSchema = !thisSchema.isEmpty() ? sep() + thisSchema : "";

            return thisName + sep() + thisProperties + thisSchema;
        }

        return super.createableSql(expression, locations);
    }

    @Override
    public String createSql(Expression expression) {
        // The comment property comes last in CTAS statements, i.e. after the query
        Expression query = expression.getExpression();
        Expression commentProp;
        if (query != null && query.is(ExprType.QUERY)) {
            commentProp = expression.find(ExprType.SCHEMA_COMMENT_PROPERTY);
            if (commentProp != null) {
                commentProp.pop();
                query.replace(Exp.paren(query.copy()));
            }
        } else {
            commentProp = null;
        }

        String createSql = super.createSql(expression);

        String commentSql = sql(commentProp);
        commentSql = !commentSql.isEmpty() ? " " + commentSql : "";

        return createSql + commentSql;
    }

    @Override
    public String prewhereSql(Expression expression) {
        String this_ = indent(sql(expression, Args.THIS));
        return seg("PREWHERE") + sep() + this_;
    }

    @Override
    public String indexcolumnconstraintSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        this_ = !this_.isEmpty() ? " " + this_ : "";
        String expr = sql(expression, Args.EXPRESSION);
        expr = !expr.isEmpty() ? " " + expr : "";
        String indexType = sql(expression, Args.INDEX_TYPE);
        indexType = !indexType.isEmpty() ? " TYPE " + indexType : "";
        String granularity = sql(expression, Args.GRANULARITY);
        granularity = !granularity.isEmpty() ? " GRANULARITY " + granularity : "";

        return "INDEX" + this_ + expr + indexType + granularity;
    }

    @Override
    public String partitionSql(Expression expression) {
        return "PARTITION " + expressions(opts(expression).flat());
    }

    public String partitionidSql(Expression expression) {
        return "ID " + sql(expression.getThis());
    }

    public String replacepartitionSql(Expression expression) {
        return "REPLACE " + sql(expression.getExpression()) + " FROM " + sql(expression, Args.SOURCE);
    }

    public String projectiondefSql(Expression expression) {
        return "PROJECTION " + sql(expression.getThis()) + " " + wrap(expression.getExpression());
    }

    public String nestedjsonselectSql(Expression expression) {
        return sql(expression, Args.THIS) + ".^" + sql(expression, Args.EXPRESSION);
    }

    @Override
    public String isSql(Expression expression) {
        String isSql = super.isSql(expression);

        Expression parent = expression.parent();
        if (parent != null && parent.is(ExprType.NOT)) {
            // value IS NOT NULL -> NOT (value IS NULL)
            isSql = wrap(isSql);
        }

        return isSql;
    }

    @Override
    public String inSql(Expression expression) {
        String inSql = super.inSql(expression);

        Expression parent = expression.parent();
        if (parent != null && parent.is(ExprType.NOT) && expression.has(Args.IS_GLOBAL)) {
            inSql = inSql.replaceFirst(Pattern.quote("GLOBAL IN"), "GLOBAL NOT IN");
        }

        return inSql;
    }

    @Override
    public String notSql(Expression expression) {
        Expression this_ = expression.getThis();
        if (this_ != null && this_.is(ExprType.IN)) {
            if (this_.has(Args.IS_GLOBAL)) {
                // let `GLOBAL IN` child interpose `NOT`
                return sql(expression, Args.THIS);
            }

            expression.set(Args.THIS, Exp.paren(this_));
        }

        return super.notSql(expression);
    }

    @Override
    public String valuesSql(Expression expression, boolean valuesAsTable) {
        // If the VALUES clause contains tuples of expressions, we need to treat it
        // as a table since Clickhouse will automatically alias it as such.
        Expression alias = expression.expr(Args.ALIAS);

        if (alias != null && alias.has(Args.COLUMNS) && !expression.getExpressions().isEmpty()) {
            List<Expression> values = expression.getExpressions().get(0).getExpressions();
            valuesAsTable = false;
            for (Expression value : values) {
                if (value.is(ExprType.TUPLE)) {
                    valuesAsTable = true;
                    break;
                }
            }
        } else {
            valuesAsTable = true;
        }

        return super.valuesSql(expression, valuesAsTable);
    }

    public String timestamptruncSql(Expression expression) {
        Expression unit = DialectHelpers.weekstartUnitToStr(this, expression);
        // https://clickhouse.com/docs/whats-new/changelog/2023#improvement
        int[] version = dialect.version();
        boolean before2312 = version[0] < 23 || (version[0] == 23 && version[1] < 12);
        if (before2312 && unit != null && unit.isString()) {
            unit = Exp.string(Py.lower(unit.name()));
        }
        return func("dateTrunc", unit, expression.getThis(), expression.get(Args.ZONE));
    }

    public String datetruncSql(Expression expression) {
        return timestamptruncSql(expression);
    }
}
