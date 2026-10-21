package io.github.offbyjuan.jglot.dialects.hive;

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
import io.github.offbyjuan.jglot.transforms.Transforms;
import io.github.offbyjuan.jglot.util.Py;
import io.github.offbyjuan.jglot.util.TimeFormat;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Hive generator: a port of sqlglot's {@code sqlglot.generators.hive}. */
public class HiveGenerator extends Generator {
    // These constants are duplicated from the Hive dialect class to avoid circular imports.
    protected static final String HIVE_TIME_FORMAT = "'yyyy-MM-dd HH:mm:ss'";
    protected static final String HIVE_DATE_FORMAT = "'yyyy-MM-dd'";
    protected static final String HIVE_DATEINT_FORMAT = "'yyyyMMdd'";

    // The default formats above, as rendered by the lenient rewrite (non-padded month/day/time)
    protected static final List<String> HIVE_NON_PADDED_TIME_FORMATS = List.of("'yyyy-M-d H:m:s'", "'yyyy-M-d'");

    protected static final Pattern CANONICAL_TIME_FORMAT = Pattern.compile("%(?:[mdHIMS]strict|[-:].|.)");

    protected static final Map<String, String> LAX_TO_NON_PADDED_FORMATS = Map.of(
            "%m", "%-m",
            "%d", "%-d",
            "%H", "%-H",
            "%I", "%-I",
            "%M", "%-M",
            "%S", "%-S");

    /** (FuncType, Multiplier) */
    protected record DateDelta(String func, int multiplier) {}

    protected static final Map<String, DateDelta> DATE_DELTA_INTERVAL = Map.of(
            "YEAR", new DateDelta("ADD_MONTHS", 12),
            "MONTH", new DateDelta("ADD_MONTHS", 1),
            "QUARTER", new DateDelta("ADD_MONTHS", 3),
            "WEEK", new DateDelta("DATE_ADD", 7),
            "DAY", new DateDelta("DATE_ADD", 1));

    protected static final Map<String, String> TIME_DIFF_FACTOR = Map.of(
            "MILLISECOND", " * 1000",
            "SECOND", "",
            "MINUTE", " / 60",
            "HOUR", " / 3600");

    protected static final List<String> DIFF_MONTH_SWITCH = List.of("YEAR", "QUARTER", "MONTH");

    public HiveGenerator(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }

    /** Whether {@code e} is one of PARSE_TIME_EXPRESSIONS (parse a string with a format). */
    protected static boolean isParseTimeExpression(Expression e) {
        return e != null && e.is(ExprType.STR_TO_TIME, ExprType.STR_TO_DATE, ExprType.STR_TO_UNIX, ExprType.TS_OR_DS_TO_DATE);
    }

    // ------------------------------------------------------------------------------------------
    // Module-level helpers

    /**
     * Changes a lax month/day/hour/minute/second in a canonical format to its non-padded form
     * (e.g. %m -> %-m), which java.time parses with or without a leading zero. This is only safe
     * for delimited specifiers, because adjacent fields parse greedily.
     */
    protected static String lenientParseFormat(String fmt) {
        List<String> parts = new ArrayList<>();
        List<String> formats = new ArrayList<>();
        Matcher m = CANONICAL_TIME_FORMAT.matcher(fmt);
        int last = 0;
        while (m.find()) {
            parts.add(fmt.substring(last, m.start()));
            formats.add(m.group());
            last = m.end();
        }
        parts.add(fmt.substring(last));

        for (int i = 0; i < formats.size(); i++) {
            String f = formats.get(i);
            String replacement = LAX_TO_NON_PADDED_FORMATS.get(f);
            if (replacement != null) {
                String left = parts.get(i);
                String right = parts.get(i + 1);
                boolean leftAdjacent = (left.isEmpty() && i > 0)
                        || (!left.isEmpty() && Character.isDigit(left.charAt(left.length() - 1)));
                boolean rightAdjacent = (right.isEmpty() && i < formats.size() - 1)
                        || (!right.isEmpty() && Character.isDigit(right.charAt(0)));
                if (!leftAdjacent && !rightAdjacent) {
                    formats.set(i, replacement);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            sb.append(parts.get(i));
            if (i < formats.size()) {
                sb.append(formats.get(i));
            }
        }
        return sb.toString();
    }

    /** {@code _add_date_sql}. */
    protected String addDateSql(Expression expression) {
        if (expression.is(ExprType.TS_OR_DS_ADD) && !expression.has(Args.UNIT)) {
            return func("DATE_ADD", expression.getThis(), expression.getExpression());
        }

        String unit = expression.text(Args.UNIT).toUpperCase(Locale.ROOT);
        DateDelta delta = DATE_DELTA_INTERVAL.getOrDefault(unit, new DateDelta("DATE_ADD", 1));
        String fn = delta.func();
        int multiplier = delta.multiplier();

        if (expression.is(ExprType.DATE_SUB)) {
            multiplier *= -1;
        }

        Expression increment = expression.getExpression();
        if (increment != null && increment.is(ExprType.LITERAL)) {
            Object value;
            if (increment.isNumber()) {
                Object py = increment.toPy();
                if (py instanceof BigInteger bi) {
                    value = bi.multiply(BigInteger.valueOf(multiplier));
                } else {
                    // Python float arithmetic
                    value = ((BigDecimal) py).doubleValue() * multiplier;
                }
            } else {
                value = new BigInteger(increment.name().strip()).multiply(BigInteger.valueOf(multiplier));
            }
            increment = Exp.number(value);
        } else if (multiplier != 1) {
            increment = increment.binop(ExprType.MUL, Exp.number(multiplier));
        }

        return func(fn, expression.getThis(), increment);
    }

    /** {@code _date_diff_sql}. */
    protected String dateDiffSql(Expression expression) {
        String unit = expression.text(Args.UNIT).toUpperCase(Locale.ROOT);

        String factor = TIME_DIFF_FACTOR.get(unit);
        if (factor != null) {
            String left = sql(expression, Args.THIS);
            String right = sql(expression, Args.EXPRESSION);
            String secDiff = "UNIX_TIMESTAMP(" + left + ") - UNIX_TIMESTAMP(" + right + ")";
            return !factor.isEmpty() ? "(" + secDiff + ")" + factor : secDiff;
        }

        boolean monthsBetween = DIFF_MONTH_SWITCH.contains(unit);
        String sqlFunc = monthsBetween ? "MONTHS_BETWEEN" : "DATEDIFF";
        DateDelta delta = DATE_DELTA_INTERVAL.get(unit);
        int multiplier = delta == null ? 1 : delta.multiplier();
        String multiplierSql = multiplier > 1 ? " / " + multiplier : "";
        String diffSql = sqlFunc + "(" + formatArgs(expression.getThis(), expression.getExpression()) + ")";

        if (monthsBetween || !multiplierSql.isEmpty()) {
            // MONTHS_BETWEEN returns a float, so we need to truncate the fractional part.
            // For the same reason, we want to truncate if there's a divisor present.
            diffSql = "CAST(" + diffSql + multiplierSql + " AS INT)";
        }

        return diffSql;
    }

    /** {@code _array_sort_sql}. */
    protected String arraySortSql(Expression expression) {
        unsupportedArgs(expression, Args.EXPRESSION, "Hive's SORT_ARRAY does not support a comparator.");
        return func("SORT_ARRAY", expression.getThis());
    }

    /** {@code _str_to_unix_sql}. */
    protected String strToUnixSql(Expression expression) {
        return func("UNIX_TIMESTAMP", expression.getThis(), DialectHelpers.timeFormat(this, expression, "hive"));
    }

    /** {@code _unix_to_time_sql}. */
    protected String unixToTimeSql(Expression expression) {
        String timestamp = sql(expression, Args.THIS);
        Object scale = expression.get(Args.SCALE);
        if (scale == null || Exp.number(0).equals(scale)) {
            return renameFunc("FROM_UNIXTIME", expression);
        }

        // f-string interpolation renders the scale with the default dialect
        return "FROM_UNIXTIME(" + timestamp + " / POW(10, " + ((Expression) scale).sql(Dialects.base()) + "))";
    }

    /** {@code _is_cast_time_format}: checks whether CAST subsumes the expression's parse format. */
    protected boolean isCastTimeFormat(Expression expression, String timeFormat) {
        if (timeFormat.equals(HIVE_TIME_FORMAT) || timeFormat.equals(HIVE_DATE_FORMAT)) {
            return true;
        }

        if (HIVE_NON_PADDED_TIME_FORMATS.contains(timeFormat)) {
            // The base render skips the lenient rewrite: a lax specifier pads back (e.g. %m -> MM),
            // an explicit non-padded specifier (e.g. %-m) doesn't
            String paddedFormat = baseFormatTime(expression);
            return HIVE_TIME_FORMAT.equals(paddedFormat) || HIVE_DATE_FORMAT.equals(paddedFormat);
        }

        return false;
    }

    /** {@code generator.Generator.format_time(self, expression)}, bypassing dialect overrides. */
    protected final String baseFormatTime(Expression expression) {
        return super.formatTime(expression, null, null);
    }

    /** {@code _str_to_date_sql}. */
    protected String strToDateSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String timeFormat = formatTime(expression);
        if (timeFormat != null && !timeFormat.isEmpty() && !isCastTimeFormat(expression, timeFormat)) {
            this_ = "FROM_UNIXTIME(UNIX_TIMESTAMP(" + this_ + ", " + timeFormat + "))";
        }
        return "CAST(" + this_ + " AS DATE)";
    }

    /** {@code _str_to_time_sql}. */
    protected String strToTimeSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String timeFormat = formatTime(expression);
        if (timeFormat != null && !timeFormat.isEmpty() && !isCastTimeFormat(expression, timeFormat)) {
            this_ = "FROM_UNIXTIME(UNIX_TIMESTAMP(" + this_ + ", " + timeFormat + "))";
        }
        return "CAST(" + this_ + " AS TIMESTAMP)";
    }

    /** {@code _to_date_sql}. */
    protected String toDateSql(Expression expression) {
        String timeFormat = formatTime(expression);
        if (timeFormat != null && !timeFormat.isEmpty() && !isCastTimeFormat(expression, timeFormat)) {
            return func("TO_DATE", expression.getThis(), timeFormat);
        }

        Expression parent = expression.parent();
        if (parent != null && isAny(parent, g.TS_OR_DS_EXPRESSIONS)) {
            return sql(expression, Args.THIS);
        }

        return func("TO_DATE", expression.getThis());
    }

    // ------------------------------------------------------------------------------------------
    // TRANSFORMS

    @Override
    public String transform(Expression e) {
        return switch (e.type()) {
            case PROPERTY -> DialectHelpers.propertySql(this, e);
            case APPROX_DISTINCT -> DialectHelpers.approxCountDistinctSql(this, e);
            case ARG_MAX -> DialectHelpers.argMaxOrMinNoCount(this, e, "MAX_BY");
            case ARG_MIN -> DialectHelpers.argMaxOrMinNoCount(this, e, "MIN_BY");
            case ARRAY -> applyTransforms(e, Transforms::inheritStructFieldNames);
            case ARRAY_TO_STRING -> func("CONCAT_WS", e.getExpression(), e.getThis());
            case ARRAY_SORT -> arraySortSql(e);
            case WITH -> DialectHelpers.noRecursiveCteSql(this, e);
            case DATE_ADD, DATE_SUB, TS_OR_DS_ADD -> addDateSql(e);
            case DATE_DIFF, TS_OR_DS_DIFF -> dateDiffSql(e);
            case DATE_STR_TO_DATE -> DialectHelpers.datestrtodateSql(this, e);
            case DATE_TO_DI -> "CAST(DATE_FORMAT(" + sql(e, Args.THIS) + ", " + HIVE_DATEINT_FORMAT + ") AS INT)";
            case DI_TO_DATE -> "TO_DATE(CAST(" + sql(e, Args.THIS) + " AS STRING), " + HIVE_DATEINT_FORMAT + ")";
            case STORAGE_HANDLER_PROPERTY -> "STORED BY " + sql(e, Args.THIS);
            case GENERATE_SERIES, GENERATE_DATE_ARRAY -> DialectHelpers.sequenceSql(this, e);
            case IF -> DialectHelpers.ifSql(this, e);
            case I_LIKE -> DialectHelpers.noIlikeSql(this, e);
            case INT_DIV -> binary(e, "DIV");
            case JSON_EXTRACT, JSON_EXTRACT_SCALAR -> func("GET_JSON_OBJECT", e.getThis(), e.getExpression());
            case LEFT -> DialectHelpers.leftToSubstringSql(this, e);
            case MAP, VAR_MAP -> DialectHelpers.varMapSql(this, e);
            case MAX -> DialectHelpers.maxOrGreatest(this, e);
            case MD5_DIGEST -> func("UNHEX", func("MD5", e.getThis()));
            case MIN -> DialectHelpers.minOrLeast(this, e);
            case MONTHS_BETWEEN -> func("MONTHS_BETWEEN", e.getThis(), e.getExpression());
            case NOT_NULL_COLUMN_CONSTRAINT -> e.has(Args.ALLOW_NULL) ? "" : "NOT NULL";
            case CREATE -> applyTransforms(e,
                    Transforms::removeUniqueConstraints,
                    Transforms::ctasWithTmpTablesToCreateTmpView,
                    Transforms::moveSchemaColumnsToPartitionedBy);
            case REGEXP_EXTRACT, REGEXP_EXTRACT_ALL -> DialectHelpers.regexpExtractSql(this, e);
            case REGEXP_REPLACE -> DialectHelpers.regexpReplaceSql(this, e);
            case REGEXP_LIKE -> binary(e, "RLIKE");
            case RIGHT -> DialectHelpers.rightToSubstringSql(this, e);
            case SCHEMA_COMMENT_PROPERTY -> nakedProperty(e);
            case SPLIT -> func("SPLIT", e.getThis(), func("CONCAT", "'\\\\Q'", e.getExpression(), "'\\\\E'"));
            case SELECT -> applyTransforms(e,
                    Transforms::eliminateQualify,
                    Transforms::eliminateDistinctOn,
                    x -> Transforms.unnestToExplode(x, false),
                    Transforms::anyToExists);
            case STR_POSITION -> DialectHelpers.strpositionSql(this, e, "LOCATE", true, false, true);
            case STR_TO_DATE -> strToDateSql(e);
            case STR_TO_TIME -> strToTimeSql(e);
            case STR_TO_UNIX -> strToUnixSql(e);
            case STRUCT_EXTRACT -> DialectHelpers.structExtractSql(this, e);
            case TABLE -> applyTransforms(e, Transforms::unnestGenerateSeries);
            case TIME_STR_TO_TIME -> DialectHelpers.timestrtotimeSql(this, e);
            case TIMESTAMP_TRUNC -> func("TRUNC", e.getThis(), DialectHelpers.weekstartUnitToStr(this, e));
            case TS_OR_DI_TO_DI -> "CAST(SUBSTR(REPLACE(CAST(" + sql(e, Args.THIS) + " AS STRING), '-', ''), 1, 8) AS INT)";
            case TS_OR_DS_TO_DATE -> toDateSql(e);
            case TRY_CAST -> DialectHelpers.noTrycastSql(this, e);
            case TRIM -> DialectHelpers.trimSql(this, e);
            case UNIX_TO_STR -> func("FROM_UNIXTIME", e.getThis(), DialectHelpers.timeFormat(this, e, "hive"));
            case UNIX_TO_TIME -> unixToTimeSql(e);
            case PARTITIONED_BY_PROPERTY -> "PARTITIONED BY " + sql(e, Args.THIS);
            case NATIONAL -> nationalSql(e, "");
            case CLUSTERED_COLUMN_CONSTRAINT, NON_CLUSTERED_COLUMN_CONSTRAINT ->
                    "(" + expressions(opts(e).key(Args.THIS).indent(false)) + ")";
            case NOT_FOR_REPLICATION_COLUMN_CONSTRAINT, ON_PROPERTY -> "";
            case PARTITIONED_BY_BUCKET -> func("BUCKET", e.getExpression(), e.getThis());
            case PARTITION_BY_TRUNCATE -> func("TRUNCATE", e.getExpression(), e.getThis());
            case PRIMARY_KEY_COLUMN_CONSTRAINT -> "PRIMARY KEY";
            case LEVENSHTEIN -> {
                unsupportedArgs(e, Args.INS_COST, Args.DEL_COST, Args.SUB_COST, Args.MAX_DIST);
                yield renameFunc("LEVENSHTEIN", e);
            }
            default -> super.transform(e);
        };
    }

    // ------------------------------------------------------------------------------------------
    // *_sql methods the base generator lacks

    @Override
    public String sqlFor(Expression e) {
        return switch (e.type()) {
            case ROW_FORMAT_SERDE_PROPERTY -> rowformatserdepropertySql(e);
            case TRUNC -> truncSql(e);
            case SERDE_PROPERTIES -> serdepropertiesSql(e);
            case TIME_TO_STR -> timetostrSql(e);
            case FILE_FORMAT_PROPERTY -> fileformatpropertySql(e);
            default -> super.sqlFor(e);
        };
    }

    // ------------------------------------------------------------------------------------------
    // Overridden methods

    @Override
    public String formatTime(Expression expression, Map<String, Object> inverseTimeMapping, Object inverseTimeTrie) {
        // Inferred property because this method is reused by other dialects under Hive
        boolean isDialectStrict = "%mstrict".equals(dd.TIME_MAPPING.get("MM"));

        if (isDialectStrict && inverseTimeMapping == null && isParseTimeExpression(expression)) {
            // Render a lenient %m/%d non-padded (M/d) so single-digit sources stay parseable
            return TimeFormat.formatTime(lenientParseFormat(sql(expression, Args.FORMAT)), dd.INVERSE_TIME_MAPPING,
                    dialect.inverseTimeTrie());
        }

        return super.formatTime(expression, inverseTimeMapping, inverseTimeTrie);
    }

    @Override
    public String ignorenullsSql(Expression expression) {
        Expression this_ = expression.getThis();
        if (this_ != null && isAny(this_, g.IGNORE_NULLS_FUNCS)) {
            return func(this_.type().sqlName(), this_.getThis(), Exp.bool(true));
        }

        return super.ignorenullsSql(expression);
    }

    @Override
    public String unnestSql(Expression expression) {
        return renameFunc("EXPLODE", expression);
    }

    @Override
    public String jsonpathkeySql(Expression expression) {
        if (Expression.isInstance(expression.get(Args.THIS), ExprType.JSON_PATH_WILDCARD)) {
            unsupported("Unsupported wildcard in JSONPathKey expression");
            return "";
        }

        return super.jsonpathkeySql(expression);
    }

    @Override
    public String parameterSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String expressionSql = sql(expression, Args.EXPRESSION);

        Expression parent = expression.parent();
        this_ = !expressionSql.isEmpty() ? this_ + ":" + expressionSql : this_;

        if (parent != null && parent.is(ExprType.EQ) && Expression.isInstance(parent.parent(), ExprType.SET_ITEM)) {
            // We need to produce SET key = value instead of SET ${key} = value
            return this_;
        }

        return "${" + this_ + "}";
    }

    @Override
    public String schemaSql(Expression expression) {
        for (Expression ordered : expression.findAll(ExprType.ORDERED)) {
            if (Boolean.FALSE.equals(ordered.get(Args.DESC))) {
                ordered.set(Args.DESC, null);
            }
        }

        return super.schemaSql(expression);
    }

    @Override
    public String constraintSql(Expression expression) {
        for (Expression prop : new ArrayList<>(expression.findAll(ExprType.PROPERTIES))) {
            prop.pop();
        }

        String this_ = sql(expression, Args.THIS);
        String expressions = expressions(opts(expression).sep(" ").flat());
        return "CONSTRAINT " + this_ + " " + expressions;
    }

    public String rowformatserdepropertySql(Expression expression) {
        String serdeProps = sql(expression, Args.SERDE_PROPERTIES);
        serdeProps = !serdeProps.isEmpty() ? " " + serdeProps : "";
        return "ROW FORMAT SERDE " + sql(expression, Args.THIS) + serdeProps;
    }

    @Override
    public String arrayaggSql(Expression expression) {
        Expression this_ = expression.getThis();
        return func("COLLECT_LIST", this_ != null && this_.is(ExprType.ORDER) ? this_.getThis() : this_);
    }

    // Hive/Spark lack native numeric TRUNC. CAST to BIGINT truncates toward zero (not rounds).
    public String truncSql(Expression expression) {
        unsupportedArgs(expression, Args.DECIMALS);
        return sql(Exp.cast(expression.getThis(), DType.BIGINT));
    }

    @Override
    public String datatypeSql(Expression expression) {
        Object thisValue = expression.thisValue();
        List<Expression> exprs = expression.getExpressions();
        if (g.PARAMETERIZABLE_TEXT_TYPES.contains(thisValue)
                && (exprs.isEmpty() || exprs.get(0).name().equals("MAX"))) {
            expression.set(Args.THIS, DType.TEXT);
            expression.set(Args.EXPRESSIONS, null);
        } else if (expression.isType(DType.TEXT) && !exprs.isEmpty()) {
            expression.set(Args.THIS, DType.VARCHAR);
        } else if (DataTypes.TEMPORAL_TYPES.contains(thisValue)) {
            expression.set(Args.EXPRESSIONS, null);
        } else if (expression.isType(DType.FLOAT)) {
            Expression sizeExpression = expression.find(ExprType.DATA_TYPE_PARAM);
            if (sizeExpression != null) {
                int size = Integer.parseInt(sizeExpression.name().strip());
                expression.set(Args.THIS, size <= 32 ? DType.FLOAT : DType.DOUBLE);
                expression.set(Args.EXPRESSIONS, null);
            }
        }
        return super.datatypeSql(expression);
    }

    @Override
    public String versionSql(Expression expression) {
        String sql = super.versionSql(expression);
        return sql.replaceFirst(Pattern.quote("FOR "), "");
    }

    @Override
    public String structSql(Expression expression) {
        List<Object> values = new ArrayList<>();

        for (Expression e : expression.getExpressions()) {
            if (e.is(ExprType.PROPERTY_EQ)) {
                unsupported("Hive does not support named structs.");
                values.add(e.getExpression());
            } else {
                values.add(e);
            }
        }

        return func("STRUCT", values.toArray());
    }

    @Override
    public String columndefSql(Expression expression, String sep) {
        Expression parent = expression.parent();
        return super.columndefSql(expression,
                parent != null && parent.is(ExprType.DATA_TYPE) && parent.isType(DType.STRUCT) ? ": " : sep);
    }

    @Override
    public String altercolumnSql(Expression expression) {
        if (expression.has(Args.EXISTS)) {
            unsupported("ALTER COLUMN IF EXISTS is not supported by this dialect");
        }

        String this_ = sql(expression, Args.THIS);
        String newName = sql(expression, Args.RENAME_TO);
        if (newName.isEmpty()) {
            newName = this_;
        }
        String dtype = sql(expression, Args.DTYPE);
        String comment = !sql(expression, Args.COMMENT).isEmpty() ? " COMMENT " + sql(expression, Args.COMMENT) : "";
        String default_ = sql(expression, Args.DEFAULT);
        Object visible = expression.get(Args.VISIBLE);
        Object allowNull = expression.get(Args.ALLOW_NULL);
        Object drop = expression.get(Args.DROP);

        if (!default_.isEmpty() || Expression.truthy(drop) || Expression.truthy(visible) || allowNull != null) {
            unsupported("Unsupported CHANGE COLUMN syntax");
        }

        if (dtype.isEmpty()) {
            unsupported("CHANGE COLUMN without a type is not supported");
        }

        return "CHANGE COLUMN " + this_ + " " + newName + " " + dtype + comment;
    }

    @Override
    public String renamecolumnSql(Expression expression) {
        unsupported("Cannot rename columns without data type defined in Hive");
        return "";
    }

    @Override
    public String altersetSql(Expression expression) {
        String exprs = expressions(opts(expression).flat());
        exprs = !exprs.isEmpty() ? " " + exprs : "";
        String location = sql(expression, Args.LOCATION);
        location = !location.isEmpty() ? " LOCATION " + location : "";
        String fileFormat = expressions(opts(expression).key(Args.FILE_FORMAT).flat().sep(" "));
        fileFormat = !fileFormat.isEmpty() ? " FILEFORMAT " + fileFormat : "";
        String serde = sql(expression, Args.SERDE);
        serde = !serde.isEmpty() ? " SERDE " + serde : "";
        String tags = expressions(opts(expression).key(Args.TAG).flat().sep(""));
        tags = !tags.isEmpty() ? " TAGS " + tags : "";

        return "SET" + serde + exprs + location + fileFormat + tags;
    }

    public String serdepropertiesSql(Expression expression) {
        String prefix = expression.has(Args.WITH_) ? "WITH " : "";
        String exprs = expressions(opts(expression).flat());

        return prefix + "SERDEPROPERTIES (" + exprs + ")";
    }

    @Override
    public String existsSql(Expression expression) {
        if (expression.getExpression() != null) {
            return functionFallbackSql(expression);
        }

        return super.existsSql(expression);
    }

    public String timetostrSql(Expression expression) {
        Expression this_ = expression.getThis();
        if (this_ != null && this_.is(ExprType.TIME_STR_TO_TIME)) {
            this_ = this_.getThis();
        }

        return func("DATE_FORMAT", this_, formatTime(expression));
    }

    @Override
    public String usingpropertySql(Expression expression) {
        Object kind = expression.get(Args.KIND);
        return "USING " + Py.str(kind) + " " + sql(expression, Args.THIS);
    }

    public String fileformatpropertySql(Expression expression) {
        String this_;
        if (Expression.isInstance(expression.get(Args.THIS), ExprType.INPUT_OUTPUT_FORMAT)) {
            this_ = sql(expression, Args.THIS);
        } else {
            this_ = expression.name().toUpperCase(Locale.ROOT);
        }

        return "STORED AS " + this_;
    }
}
