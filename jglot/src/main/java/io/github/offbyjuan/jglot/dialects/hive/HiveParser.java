package io.github.offbyjuan.jglot.dialects.hive;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.DialectHelpers;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.util.Py;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Hive parser: a port of sqlglot's {@code sqlglot.parsers.hive}. */
public class HiveParser extends Parser {
    private static final Set<TokenType> PARTITION_TOKENS = EnumSet.of(TokenType.PARTITION_BY, TokenType.DISTRIBUTE_BY);
    private static final Set<String> USING_KINDS = Set.of("JAR", "FILE", "ARCHIVE");

    public HiveParser(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }

    // ------------------------------------------------------------------------------------------
    // Module-level builders

    /** {@code build_with_ignore_nulls(exp_class)(args)}. */
    protected Expression buildWithIgnoreNulls(ExprType expClass, List<Expression> args) {
        Expression this_ = Expression.of(expClass, Args.THIS, Py.seqGet(args, 0));
        if (Exp.bool(true).equals(Py.seqGet(args, 1))) {
            return Expression.of(ExprType.IGNORE_NULLS, Args.THIS, this_);
        }
        return this_;
    }

    /** {@code _build_to_date(args, dialect)}. */
    protected Expression buildToDate(List<Expression> args) {
        Expression expr = DialectHelpers.buildFormattedTime(ExprType.TS_OR_DS_TO_DATE, null, args, dialect);
        expr.set(Args.SAFE, Boolean.TRUE);
        return expr;
    }

    /** {@code _build_named_struct(args)}: maps named_struct('k', v, ...) to exp.Struct. */
    protected Expression buildNamedStruct(List<Expression> args) {
        List<Expression> expressions = new ArrayList<>();
        for (int i = 0; i < args.size() - 1; i += 2) {
            Expression key = args.get(i);
            Expression value = args.get(i + 1);
            String name = key.name();
            expressions.add(Expression.of(ExprType.PROPERTY_EQ, Args.THIS, Exp.toIdentifier(name), Args.EXPRESSION, value));
        }
        return Expression.of(ExprType.STRUCT, Args.EXPRESSIONS, expressions);
    }

    /** {@code _build_date_add(args)} (used for DATE_SUB). */
    protected Expression buildDateAdd(List<Expression> args) {
        Expression expression = Py.seqGet(args, 1);
        if (expression != null) {
            expression = expression.binop(ExprType.MUL, -1);
        }
        return Expression.of(ExprType.TS_OR_DS_ADD,
                Args.THIS, Py.seqGet(args, 0), Args.EXPRESSION, expression, Args.UNIT, Exp.string("DAY"));
    }

    // ------------------------------------------------------------------------------------------
    // FUNCTIONS

    @Override
    protected Expression buildFunction(String name, List<Expression> args) {
        return switch (name) {
            case "COLLECT_LIST" -> Expression.of(ExprType.ARRAY_AGG,
                    Args.THIS, Py.seqGet(args, 0), Args.NULLS_EXCLUDED, Boolean.TRUE);
            case "CONCAT_WS" -> Expression.of(ExprType.CONCAT_WS,
                    Args.EXPRESSIONS, args,
                    Args.SAFE, !dd.STRICT_STRING_CONCAT,
                    Args.COALESCE, dd.CONCAT_WS_COALESCE,
                    Args.FLATTEN, Boolean.TRUE);
            case "DATE_ADD" -> Expression.of(ExprType.TS_OR_DS_ADD,
                    Args.THIS, Py.seqGet(args, 0), Args.EXPRESSION, Py.seqGet(args, 1), Args.UNIT, Exp.string("DAY"));
            case "DATE_FORMAT" -> DialectHelpers.buildFormattedTime(ExprType.TIME_TO_STR, null,
                    Arrays.asList(Expression.of(ExprType.TIME_STR_TO_TIME, Args.THIS, Py.seqGet(args, 0)), Py.seqGet(args, 1)),
                    dialect);
            case "DATE_SUB" -> buildDateAdd(args);
            case "DATEDIFF" -> Expression.of(ExprType.DATE_DIFF,
                    Args.THIS, Expression.of(ExprType.TS_OR_DS_TO_DATE, Args.THIS, Py.seqGet(args, 0)),
                    Args.EXPRESSION, Expression.of(ExprType.TS_OR_DS_TO_DATE, Args.THIS, Py.seqGet(args, 1)));
            case "DAY" -> Expression.of(ExprType.DAY,
                    Args.THIS, Expression.of(ExprType.TS_OR_DS_TO_DATE, Args.THIS, Py.seqGet(args, 0)));
            case "FIRST" -> buildWithIgnoreNulls(ExprType.FIRST, args);
            case "FIRST_VALUE" -> buildWithIgnoreNulls(ExprType.FIRST_VALUE, args);
            case "FROM_UNIXTIME" -> DialectHelpers.buildFormattedTime(ExprType.UNIX_TO_STR, null, Boolean.TRUE, args, dialect);
            case "GET_JSON_OBJECT" -> Expression.of(ExprType.JSON_EXTRACT_SCALAR,
                    Args.THIS, Py.seqGet(args, 0), Args.EXPRESSION, dialect.toJsonPath(Py.seqGet(args, 1)));
            case "LAST" -> buildWithIgnoreNulls(ExprType.LAST, args);
            case "LAST_VALUE" -> buildWithIgnoreNulls(ExprType.LAST_VALUE, args);
            case "MAP" -> buildVarMap(args);
            case "MONTH" -> Expression.of(ExprType.MONTH, Args.THIS, Exp.fromArgList(ExprType.TS_OR_DS_TO_DATE, args));
            case "NAMED_STRUCT" -> buildNamedStruct(args);
            case "REGEXP_EXTRACT" -> DialectHelpers.buildRegexpExtract(ExprType.REGEXP_EXTRACT, args, dialect);
            case "REGEXP_EXTRACT_ALL" -> DialectHelpers.buildRegexpExtract(ExprType.REGEXP_EXTRACT_ALL, args, dialect);
            case "STR_TO_MAP" -> {
                Expression pairDelim = Py.seqGet(args, 1);
                Expression keyValueDelim = Py.seqGet(args, 2);
                yield Expression.of(ExprType.STR_TO_MAP,
                        Args.THIS, Py.seqGet(args, 0),
                        Args.PAIR_DELIM, pairDelim != null ? pairDelim : Exp.string(","),
                        Args.KEY_VALUE_DELIM, keyValueDelim != null ? keyValueDelim : Exp.string(":"));
            }
            case "TO_DATE" -> buildToDate(args);
            case "UNIX_TIMESTAMP" -> DialectHelpers.buildFormattedTime(ExprType.STR_TO_UNIX, null, Boolean.TRUE,
                    args.isEmpty() ? List.of(Expression.of(ExprType.CURRENT_TIMESTAMP)) : args, dialect);
            case "YEAR" -> Expression.of(ExprType.YEAR, Args.THIS, Exp.fromArgList(ExprType.TS_OR_DS_TO_DATE, args));
            default -> super.buildFunction(name, args);
        };
    }

    // ------------------------------------------------------------------------------------------
    // Callable tables

    @Override
    protected Expression functionParser(String key) {
        return switch (key) {
            case "PERCENTILE" -> parseDistinctArgFunction(ExprType.QUANTILE);
            case "PERCENTILE_APPROX" -> parseDistinctArgFunction(ExprType.APPROX_QUANTILE);
            default -> super.functionParser(key);
        };
    }

    @Override
    protected Expression noParenFunctionParser(String key) {
        if (key.equals("TRANSFORM")) {
            return parseTransform();
        }
        return super.noParenFunctionParser(key);
    }

    @Override
    protected Object propertyParser(String key, Map<String, Object> kwargs) {
        return switch (key) {
            case "SERDEPROPERTIES" -> Expression.of(ExprType.SERDE_PROPERTIES,
                    Args.EXPRESSIONS, parseWrapped(() -> parseCsvOf(this::parseProperty, TokenType.COMMA)));
            case "USING" -> parseUsingProperty();
            default -> super.propertyParser(key, kwargs);
        };
    }

    @Override
    protected Object alterParser(String key) {
        if (key.equals("CHANGE")) {
            return parseAlterTableChange();
        }
        return super.alterParser(key);
    }

    // ------------------------------------------------------------------------------------------
    // Overridden methods

    @Override
    protected Expression buildCast(boolean strict, Map<Arg, Object> kwargs) {
        if (prev.text().toUpperCase(Locale.ROOT).equals("BD")) {
            kwargs = kwargs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(kwargs);
            Expression this_ = (Expression) kwargs.get(Args.THIS);
            BigDecimal dec = new BigDecimal(this_.name());
            String digits = dec.unscaledValue().abs().toString();
            int precision = digits.length();
            int scale = dec.scale();

            if (d.NORMALIZE_DECIMAL_LITERALS) {
                if (dec.unscaledValue().signum() == 0) {
                    precision = 1;
                    scale = 0;
                } else {
                    while (scale > 0 && digits.charAt(precision - 1) == '0') {
                        precision -= 1;
                        scale -= 1;
                    }
                }
            }

            if (scale < 0 && !d.DECIMAL_LITERALS_ALLOW_NEGATIVE_SCALE) {
                precision -= scale;
                scale = 0;
            }

            // Unsuffixed exponent literals can be DOUBLE, so cast from text to avoid rounding.
            kwargs.put(Args.THIS, scale < 0 ? this_ : Exp.string(this_.name()));
            List<Expression> params = new ArrayList<>();
            for (int value : new int[] {Math.max(precision, scale), scale}) {
                params.add(Expression.of(ExprType.DATA_TYPE_PARAM, Args.THIS, Exp.number(value)));
            }
            kwargs.put(Args.TO, Expression.of(ExprType.DATA_TYPE, Args.THIS, DType.DECIMAL, Args.EXPRESSIONS, params));
        }
        return super.buildCast(strict, kwargs);
    }

    /** {@code _parse_transform}. */
    protected Expression parseTransform() {
        if (!match(TokenType.L_PAREN, false)) {
            retreat(tokenIndex - 1);
            return null;
        }

        List<Expression> args = parseWrappedCsv(this::parseLambda);
        Expression rowFormatBefore = parseRowFormat(true);

        Expression recordWriter = null;
        if (matchTextSeq("RECORDWRITER")) {
            recordWriter = parseString();
        }

        if (!match(TokenType.USING)) {
            return Exp.fromArgList(ExprType.TRANSFORM, args);
        }

        Expression commandScript = parseString();

        match(TokenType.ALIAS);
        Expression schema = parseSchema();

        Expression rowFormatAfter = parseRowFormat(true);
        Expression recordReader = null;
        if (matchTextSeq("RECORDREADER")) {
            recordReader = parseString();
        }

        return expression(Expression.of(ExprType.QUERY_TRANSFORM,
                Args.EXPRESSIONS, args,
                Args.COMMAND_SCRIPT, commandScript,
                Args.SCHEMA, schema,
                Args.ROW_FORMAT_BEFORE, rowFormatBefore,
                Args.RECORD_WRITER, recordWriter,
                Args.ROW_FORMAT_AFTER, rowFormatAfter,
                Args.RECORD_READER, recordReader));
    }

    /**
     * Spark (and most likely Hive) treats casts to CHAR(length) and VARCHAR(length) as casts to
     * STRING in all contexts except for schema definitions, so the length is dropped to transpile
     * correctly.
     */
    @Override
    protected Expression parseTypes(boolean checkFunc, boolean schema, boolean allowIdentifiers, boolean withCollation) {
        Expression this_ = super.parseTypes(checkFunc, schema, allowIdentifiers, withCollation);

        if (this_ != null && !schema) {
            return this_.transform(node -> {
                if (node.is(ExprType.DATA_TYPE) && node.isType(DType.CHAR, DType.VARCHAR)) {
                    node.set(Args.THIS, DType.TEXT);
                    node.set(Args.EXPRESSIONS, null);
                }
                return node;
            }, false);
        }

        return this_;
    }

    /** {@code _parse_alter_table_change}. */
    protected Expression parseAlterTableChange() {
        match(TokenType.COLUMN);
        Expression this_ = parseField(true);

        if (d.CHANGE_COLUMN_ALTER_SYNTAX && matchTextSeq("TYPE")) {
            return expression(Expression.of(ExprType.ALTER_COLUMN, Args.THIS, this_, Args.DTYPE, parseTypes(false, true)));
        }

        Expression columnNew = parseField(true);
        Expression dtype = parseTypes(false, true);

        Object comment = match(TokenType.COMMENT) ? parseString() : Boolean.FALSE;

        if (this_ == null || columnNew == null || dtype == null) {
            raiseError("Expected 'CHANGE COLUMN' to be followed by 'column_name' 'column_name' 'data_type'");
        }

        return expression(Expression.of(ExprType.ALTER_COLUMN,
                Args.THIS, this_, Args.RENAME_TO, columnNew, Args.DTYPE, dtype, Args.COMMENT, comment));
    }

    /** {@code _parse_using_property}. */
    protected Expression parseUsingProperty() {
        if (matchTexts(USING_KINDS)) {
            String kind = prev.text().toUpperCase(Locale.ROOT);
            return Expression.of(ExprType.USING_PROPERTY, Args.THIS, parseString(), Args.KIND, kind);
        }

        return parsePropertyAssignment(ExprType.FILE_FORMAT_PROPERTY);
    }

    @Override
    protected Object[] parsePartitionAndOrder() {
        List<Expression> partition = matchSet(PARTITION_TOKENS) ? parseCsv(this::parseAssignment) : new ArrayList<>();
        Expression order = super.parseOrder(null, match(TokenType.SORT_BY));
        return new Object[] {partition, order};
    }

    @Override
    protected Expression parseParameter() {
        match(TokenType.L_BRACE);
        Expression this_ = parseIdentifier();
        if (this_ == null) {
            this_ = parsePrimaryOrVar();
        }
        Object expression;
        if (match(TokenType.COLON)) {
            Expression e = parseIdentifier();
            expression = e != null ? e : parsePrimaryOrVar();
        } else {
            expression = Boolean.FALSE;
        }
        match(TokenType.R_BRACE);
        return expression(Expression.of(ExprType.PARAMETER, Args.THIS, this_, Args.EXPRESSION, expression));
    }

    @Override
    protected Expression toPropEq(Expression expression, int index) {
        if (expression.isStar()) {
            return expression;
        }

        Expression key;
        if (expression.is(ExprType.COLUMN)) {
            key = expression.getThis();
        } else {
            key = Exp.toIdentifier("col" + (index + 1));
        }

        return expression(Expression.of(ExprType.PROPERTY_EQ, Args.THIS, key, Args.EXPRESSION, expression));
    }
}
