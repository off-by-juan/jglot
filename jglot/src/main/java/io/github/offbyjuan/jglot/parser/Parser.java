package io.github.offbyjuan.jglot.parser;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.errors.ParseError;
import io.github.offbyjuan.jglot.errors.TokenError;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.optimizer.NormalizeIdentifiers;
import io.github.offbyjuan.jglot.optimizer.Scopes;
import io.github.offbyjuan.jglot.tokens.Token;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.util.Py;
import io.github.offbyjuan.jglot.util.TimeFormat;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SQL parser: a port of sqlglot's {@code Parser}. Dialects subclass it to override individual
 * parse methods and dispatch hooks.
 *
 * <p>Create parsers through {@link Dialect#newParser}; an instance parses one input and is not
 * thread-safe, but creating one is cheap since all tables are shared, immutable dialect data.
 */
public class Parser extends ParserMethods {
    public Parser(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }

    // ---------------------------------------------------------------------------------------
    // From ParserPartA

    // Used to detect alphabetical characters and +/- in timestamp literals
    protected static final Pattern TIME_ZONE_RE = Pattern.compile(":.*?[a-zA-Z\\+\\-]");

    // ------------------------------------------------------------------------------------------
    // Module-level builders

    protected Expression buildVarMap(List<Expression> args) {
        if (args.size() == 1 && args.get(0).isStar()) {
            return Expression.of(ExprType.STAR_MAP, Args.THIS, args.get(0));
        }

        List<Expression> keys = new ArrayList<>();
        List<Expression> values = new ArrayList<>();
        for (int i = 0; i < args.size(); i += 2) {
            keys.add(args.get(i));
            values.add(args.get(i + 1));
        }

        return Expression.of(ExprType.VAR_MAP, Args.KEYS, Exp.array(keys), Args.VALUES, Exp.array(values));
    }

    protected Expression buildLike(List<Expression> args) {
        Expression like = Expression.of(ExprType.LIKE, Args.THIS, Py.seqGet(args, 1), Args.EXPRESSION, Py.seqGet(args, 0));
        return args.size() > 2 ? Expression.of(ExprType.ESCAPE, Args.THIS, like, Args.EXPRESSION, Py.seqGet(args, 2)) : like;
    }

    /** {@code binary_range_parser(expr_type, reverse_args)(self, this)}. */
    protected Expression binaryRangeParser(ExprType exprType, boolean reverseArgs, Expression this_) {
        Expression expression = parseBitwise();
        if (reverseArgs) {
            Expression tmp = this_;
            this_ = expression;
            expression = tmp;
        }
        return parseEscape(expression(Expression.of(exprType, Args.THIS, this_, Args.EXPRESSION, expression)));
    }

    protected Expression binaryRangeParser(ExprType exprType, Expression this_) {
        return binaryRangeParser(exprType, false, this_);
    }

    protected Expression buildLogarithm(List<Expression> args) {
        // Default argument order is base, expression
        Expression this_ = Py.seqGet(args, 0);
        Expression expression = Py.seqGet(args, 1);

        if (expression != null) {
            if (!Boolean.TRUE.equals(dd.LOG_BASE_FIRST)) {
                Expression tmp = this_;
                this_ = expression;
                expression = tmp;
            }
            return Expression.of(ExprType.LOG, Args.THIS, this_, Args.EXPRESSION, expression);
        }

        return Expression.of(d.LOG_DEFAULTS_TO_LN ? ExprType.LN : ExprType.LOG, Args.THIS, this_);
    }

    protected Expression buildHex(List<Expression> args) {
        Expression arg = Py.seqGet(args, 0);
        return dd.HEX_LOWERCASE
                ? Expression.of(ExprType.LOWER_HEX, Args.THIS, arg)
                : Expression.of(ExprType.HEX, Args.THIS, arg);
    }

    protected Expression buildLower(List<Expression> args) {
        // LOWER(HEX(..)) can be simplified to LowerHex to simplify its transpilation
        Expression arg = Py.seqGet(args, 0);
        return arg != null && arg.is(ExprType.HEX)
                ? Expression.of(ExprType.LOWER_HEX, Args.THIS, arg.getThis())
                : Expression.of(ExprType.LOWER, Args.THIS, arg);
    }

    protected Expression buildUpper(List<Expression> args) {
        // UPPER(HEX(..)) can be simplified to Hex to simplify its transpilation
        Expression arg = Py.seqGet(args, 0);
        return arg != null && arg.is(ExprType.HEX)
                ? Expression.of(ExprType.HEX, Args.THIS, arg.getThis())
                : Expression.of(ExprType.UPPER, Args.THIS, arg);
    }

    /** {@code build_extract_json_with_path(expr_type)(args, dialect)}. */
    protected Expression buildExtractJsonWithPath(ExprType exprType, List<Expression> args) {
        Expression expression = Expression.of(exprType, Args.THIS, Py.seqGet(args, 0), Args.EXPRESSION,
                dialect.toJsonPath(Py.seqGet(args, 1)));
        if (args.size() > 2 && exprType == ExprType.JSON_EXTRACT) {
            expression.set(Args.EXPRESSIONS, Py.slice(args, 2));
        }
        if (exprType == ExprType.JSON_EXTRACT_SCALAR) {
            expression.set(Args.SCALAR_ONLY, dd.JSON_EXTRACT_SCALAR_SCALAR_ONLY);
        }

        return expression;
    }

    protected Expression buildMod(List<Expression> args) {
        Expression this_ = Py.seqGet(args, 0);
        Expression expression = Py.seqGet(args, 1);

        // Wrap the operands if they are binary nodes, e.g. MOD(a + 1, 7) -> (a + 1) % 7
        this_ = this_ != null && this_.is(ExprType.BINARY) ? Expression.of(ExprType.PAREN, Args.THIS, this_) : this_;
        expression = expression != null && expression.is(ExprType.BINARY)
                ? Expression.of(ExprType.PAREN, Args.THIS, expression)
                : expression;

        return Expression.of(ExprType.MOD, Args.THIS, this_, Args.EXPRESSION, expression);
    }

    protected Expression buildPad(List<Expression> args, boolean isLeft) {
        return Expression.of(ExprType.PAD,
                Args.THIS, Py.seqGet(args, 0),
                Args.EXPRESSION, Py.seqGet(args, 1),
                Args.FILL_PATTERN, Py.seqGet(args, 2),
                Args.IS_LEFT, isLeft);
    }

    protected Expression buildPad(List<Expression> args) {
        return buildPad(args, true);
    }

    /** {@code build_array_constructor(exp_class, args, bracket_kind, self.dialect)}. */
    protected Expression buildArrayConstructor(ExprType expClass, List<Expression> args, TokenType bracketKind) {
        Expression arrayExp = Expression.of(expClass, Args.EXPRESSIONS, args);

        if (expClass == ExprType.ARRAY) {
            arrayExp.set(Args.VALUE_CONSTRUCTOR, args.size() == 1 && args.get(0) != null && args.get(0).is(ExprType.QUERY));
            if (dd.HAS_DISTINCT_ARRAY_CONSTRUCTORS) {
                arrayExp.set(Args.BRACKET_NOTATION, bracketKind == TokenType.L_BRACKET);
            }
        }

        return arrayExp;
    }

    protected Expression buildConvertTimezone(List<Expression> args, String defaultSourceTz) {
        if (args.size() == 2) {
            Expression sourceTz = defaultSourceTz != null && !defaultSourceTz.isEmpty() ? Exp.string(defaultSourceTz) : null;
            return Expression.of(ExprType.CONVERT_TIMEZONE,
                    Args.SOURCE_TZ, sourceTz, Args.TARGET_TZ, Py.seqGet(args, 0), Args.TIMESTAMP, Py.seqGet(args, 1));
        }

        return Exp.fromArgList(ExprType.CONVERT_TIMEZONE, args);
    }

    protected Expression buildConvertTimezone(List<Expression> args) {
        return buildConvertTimezone(args, null);
    }

    protected Expression buildTrim(List<Expression> args, boolean isLeft, boolean reverseArgs) {
        Expression this_ = Py.seqGet(args, 0);
        Expression expression = Py.seqGet(args, 1);

        if (expression != null && reverseArgs) {
            Expression tmp = this_;
            this_ = expression;
            expression = tmp;
        }

        return Expression.of(ExprType.TRIM, Args.THIS, this_, Args.EXPRESSION, expression,
                Args.POSITION, isLeft ? "LEADING" : "TRAILING");
    }

    protected Expression buildTrim(List<Expression> args, boolean isLeft) {
        return buildTrim(args, isLeft, false);
    }

    protected Expression buildTrim(List<Expression> args) {
        return buildTrim(args, true, false);
    }

    protected Expression buildCoalesce(List<Expression> args, Boolean isNvl, Boolean isNull) {
        return Expression.of(ExprType.COALESCE, Args.THIS, Py.seqGet(args, 0), Args.EXPRESSIONS, Py.slice(args, 1),
                Args.IS_NVL, isNvl, Args.IS_NULL, isNull);
    }

    protected Expression buildCoalesce(List<Expression> args) {
        return buildCoalesce(args, null, null);
    }

    protected Expression buildLocateStrposition(List<Expression> args) {
        return Expression.of(ExprType.STR_POSITION,
                Args.THIS, Py.seqGet(args, 1),
                Args.SUBSTR, Py.seqGet(args, 0),
                Args.POSITION, Py.seqGet(args, 2));
    }

    /**
     * Builds ArrayAppend with NULL propagation semantics based on the dialect configuration. Some
     * dialects (Databricks, Spark, Snowflake) return NULL when the input array is NULL; others
     * (DuckDB, PostgreSQL) create a new single-element array instead.
     */
    protected Expression buildArrayAppend(List<Expression> args) {
        return Expression.of(ExprType.ARRAY_APPEND,
                Args.THIS, Py.seqGet(args, 0),
                Args.EXPRESSION, Py.seqGet(args, 1),
                Args.NULL_PROPAGATION, dd.ARRAY_FUNCS_PROPAGATES_NULLS);
    }

    /** Builds ArrayPrepend with NULL propagation semantics based on the dialect configuration. */
    protected Expression buildArrayPrepend(List<Expression> args) {
        return Expression.of(ExprType.ARRAY_PREPEND,
                Args.THIS, Py.seqGet(args, 0),
                Args.EXPRESSION, Py.seqGet(args, 1),
                Args.NULL_PROPAGATION, dd.ARRAY_FUNCS_PROPAGATES_NULLS);
    }

    /**
     * Builds ArrayConcat with NULL propagation semantics based on the dialect configuration. Some
     * dialects (Redshift, Snowflake) return NULL when any input array is NULL; others (DuckDB,
     * PostgreSQL) skip NULL arrays and continue concatenation.
     */
    protected Expression buildArrayConcat(List<Expression> args) {
        return Expression.of(ExprType.ARRAY_CONCAT,
                Args.THIS, Py.seqGet(args, 0),
                Args.EXPRESSIONS, Py.slice(args, 1),
                Args.NULL_PROPAGATION, dd.ARRAY_FUNCS_PROPAGATES_NULLS);
    }

    /**
     * Builds ArrayRemove with NULL propagation semantics based on the dialect configuration. Some
     * dialects (Snowflake) return NULL when the removal value is NULL; others (DuckDB) may return an
     * empty array due to NULL comparison semantics.
     */
    protected Expression buildArrayRemove(List<Expression> args) {
        return Expression.of(ExprType.ARRAY_REMOVE,
                Args.THIS, Py.seqGet(args, 0),
                Args.EXPRESSION, Py.seqGet(args, 1),
                Args.NULL_PROPAGATION, dd.ARRAY_FUNCS_PROPAGATES_NULLS);
    }

    /** {@code _unpivot_target}. */
    protected Expression unpivotTarget(Expression expr) {
        // UNPIVOT's pre-FOR values and FOR field are new output names, not column references.
        if (expr != null && expr.is(ExprType.COLUMN) && expr.text(Args.TABLE).isEmpty()) {
            return expr.getThis();
        }
        if (expr != null && expr.is(ExprType.TUPLE)) {
            List<Expression> targets = new ArrayList<>();
            for (Expression e : expr.getExpressions()) {
                targets.add(unpivotTarget(e));
            }
            expr.set(Args.EXPRESSIONS, targets);
        }
        return expr;
    }

    // Builders for the JSON `->` / `->>` / `#>` / `#>>` / `?` operators, shared between
    // COLUMN_OPERATORS (accessor-tier dialects) and JSON_OPERATORS (Postgres/DuckDB's
    // binary-operator tier).

    /** Module-level {@code build_json_extract(self, this, path)} (not {@code Parser._build_json_extract}). */
    protected Expression buildJsonExtract(Expression this_, Expression path) {
        return expression(Expression.of(ExprType.JSON_EXTRACT,
                Args.THIS, this_,
                Args.EXPRESSION, dialect.toJsonPath(path),
                Args.ONLY_JSON_TYPES, d.JSON_ARROWS_REQUIRE_JSON_TYPE));
    }

    protected Expression buildJsonExtractScalar(Expression this_, Expression path) {
        return expression(Expression.of(ExprType.JSON_EXTRACT_SCALAR,
                Args.THIS, this_,
                Args.EXPRESSION, dialect.toJsonPath(path),
                Args.ONLY_JSON_TYPES, d.JSON_ARROWS_REQUIRE_JSON_TYPE,
                Args.SCALAR_ONLY, dd.JSON_EXTRACT_SCALAR_SCALAR_ONLY));
    }

    protected Expression buildJsonbExtract(Expression this_, Expression path) {
        return expression(Expression.of(ExprType.JSONB_EXTRACT, Args.THIS, this_, Args.EXPRESSION, path));
    }

    protected Expression buildJsonbExtractScalar(Expression this_, Expression path) {
        return expression(Expression.of(ExprType.JSONB_EXTRACT_SCALAR, Args.THIS, this_, Args.EXPRESSION, path));
    }

    protected Expression buildJsonbContainsTopKey(Expression this_, Expression key) {
        return expression(Expression.of(ExprType.JSONB_CONTAINS_TOP_KEY, Args.THIS, this_, Args.EXPRESSION, key));
    }

    // ------------------------------------------------------------------------------------------
    // FUNCTIONS (custom entries)

    @Override
    protected Expression buildFunction(String name, List<Expression> args) {
        return switch (name) {
            case "COALESCE", "IFNULL", "NVL" -> buildCoalesce(args);
            case "ARRAY" -> Expression.of(ExprType.ARRAY, Args.EXPRESSIONS, args);
            case "ARRAYAGG", "ARRAY_AGG" -> Expression.of(ExprType.ARRAY_AGG,
                    Args.THIS, Py.seqGet(args, 0),
                    Args.NULLS_EXCLUDED, dd.ARRAY_AGG_INCLUDES_NULLS == null ? Boolean.TRUE : null);
            case "ARRAY_APPEND" -> buildArrayAppend(args);
            case "ARRAY_CAT", "ARRAY_CONCAT" -> buildArrayConcat(args);
            case "ARRAY_INTERSECT", "ARRAY_INTERSECTION" ->
                    Expression.of(ExprType.ARRAY_INTERSECT, Args.EXPRESSIONS, args);
            case "ARRAY_PREPEND" -> buildArrayPrepend(args);
            case "ARRAY_REMOVE" -> buildArrayRemove(args);
            case "COUNT" -> Expression.of(ExprType.COUNT,
                    Args.THIS, Py.seqGet(args, 0), Args.EXPRESSIONS, Py.slice(args, 1), Args.BIG_INT, Boolean.TRUE);
            case "CONCAT" -> Expression.of(ExprType.CONCAT,
                    Args.EXPRESSIONS, args,
                    Args.SAFE, !dd.STRICT_STRING_CONCAT,
                    Args.COALESCE, dd.CONCAT_COALESCE);
            case "CONCAT_WS" -> Expression.of(ExprType.CONCAT_WS,
                    Args.EXPRESSIONS, args,
                    Args.SAFE, !dd.STRICT_STRING_CONCAT,
                    Args.COALESCE, dd.CONCAT_WS_COALESCE);
            case "CONVERT_TIMEZONE" -> buildConvertTimezone(args);
            case "DATE_TO_DATE_STR", "TIME_TO_TIME_STR" -> Expression.of(ExprType.CAST,
                    Args.THIS, Py.seqGet(args, 0),
                    Args.TO, DataTypes.of(DType.TEXT));
            case "GENERATE_DATE_ARRAY" -> {
                Expression step = Py.seqGet(args, 2);
                if (step == null) {
                    step = Expression.of(ExprType.INTERVAL, Args.THIS, Exp.string("1"), Args.UNIT, Exp.var("DAY"));
                }
                yield Expression.of(ExprType.GENERATE_DATE_ARRAY,
                        Args.START, Py.seqGet(args, 0),
                        Args.END, Py.seqGet(args, 1),
                        Args.STEP, step);
            }
            case "GENERATE_UUID", "UUID" ->
                    Expression.of(ExprType.UUID, Args.IS_STRING, dd.UUID_IS_STRING_TYPE ? Boolean.TRUE : null);
            case "GLOB" -> Expression.of(ExprType.GLOB, Args.THIS, Py.seqGet(args, 1), Args.EXPRESSION, Py.seqGet(args, 0));
            case "GREATEST" -> Expression.of(ExprType.GREATEST,
                    Args.THIS, Py.seqGet(args, 0),
                    Args.EXPRESSIONS, Py.slice(args, 1),
                    Args.IGNORE_NULLS, dd.LEAST_GREATEST_IGNORES_NULLS);
            case "LEAST" -> Expression.of(ExprType.LEAST,
                    Args.THIS, Py.seqGet(args, 0),
                    Args.EXPRESSIONS, Py.slice(args, 1),
                    Args.IGNORE_NULLS, dd.LEAST_GREATEST_IGNORES_NULLS);
            case "HEX", "TO_HEX" -> buildHex(args);
            case "JSON_EXTRACT" -> buildExtractJsonWithPath(ExprType.JSON_EXTRACT, args);
            case "JSON_EXTRACT_SCALAR", "JSON_EXTRACT_PATH_TEXT" ->
                    buildExtractJsonWithPath(ExprType.JSON_EXTRACT_SCALAR, args);
            case "JSON_KEYS" -> Expression.of(ExprType.JSON_KEYS,
                    Args.THIS, Py.seqGet(args, 0),
                    Args.EXPRESSION, dialect.toJsonPath(Py.seqGet(args, 1)));
            case "LIKE" -> buildLike(args);
            case "LOG" -> buildLogarithm(args);
            case "LOG2" -> Expression.of(ExprType.LOG, Args.THIS, Exp.number(2), Args.EXPRESSION, Py.seqGet(args, 0));
            case "LOG10" -> Expression.of(ExprType.LOG, Args.THIS, Exp.number(10), Args.EXPRESSION, Py.seqGet(args, 0));
            case "LOWER" -> buildLower(args);
            case "LPAD", "LEFTPAD" -> buildPad(args);
            case "LTRIM" -> buildTrim(args);
            case "MOD" -> buildMod(args);
            case "RIGHTPAD", "RPAD" -> buildPad(args, false);
            case "RTRIM" -> buildTrim(args, false);
            case "SCOPE_RESOLUTION" -> args.size() != 2
                    ? Expression.of(ExprType.SCOPE_RESOLUTION, Args.EXPRESSION, Py.seqGet(args, 0))
                    : Expression.of(ExprType.SCOPE_RESOLUTION, Args.THIS, Py.seqGet(args, 0), Args.EXPRESSION, Py.seqGet(args, 1));
            case "CHARINDEX", "LOCATE" -> buildLocateStrposition(args);
            case "TS_OR_DS_TO_DATE_STR" -> Expression.of(ExprType.SUBSTRING,
                    Args.THIS, Expression.of(ExprType.CAST, Args.THIS, Py.seqGet(args, 0), Args.TO, DataTypes.of(DType.TEXT)),
                    Args.START, Exp.number(1),
                    Args.LENGTH, Exp.number(10));
            case "UNNEST" -> Expression.of(ExprType.UNNEST, Args.EXPRESSIONS, Py.ensureList(Py.seqGet(args, 0)));
            case "UPPER" -> buildUpper(args);
            case "UUID_STRING" -> Expression.of(ExprType.UUID,
                    Args.THIS, Py.seqGet(args, 0),
                    Args.NAME, Py.seqGet(args, 1),
                    Args.IS_STRING, dd.UUID_IS_STRING_TYPE ? Boolean.TRUE : null);
            case "VAR_MAP" -> buildVarMap(args);
            default -> super.buildFunction(name, args);
        };
    }

    // ------------------------------------------------------------------------------------------
    // LAMBDAS

    @Override
    protected Expression lambdaParser(TokenType t, List<Expression> expressions) {
        return switch (t) {
            case ARROW -> expression(Expression.of(ExprType.LAMBDA,
                    Args.THIS, replaceLambda(parseDisjunction(), expressions),
                    Args.EXPRESSIONS, expressions));
            case FARROW -> {
                Expression this_ = Exp.var(expressions.get(0).name());
                Expression value = parseDisjunction();
                if (value == null) {
                    value = parseSelect();
                }
                yield expression(Expression.of(ExprType.KWARG, Args.THIS, this_, Args.EXPRESSION, value));
            }
            default -> super.lambdaParser(t, expressions);
        };
    }

    // ------------------------------------------------------------------------------------------
    // COLUMN_OPERATORS (DOT maps to None and is never dispatched)

    @Override
    protected Expression columnOperator(TokenType t, Expression this_, Expression other) {
        return switch (t) {
            case DOTCOLON -> expression(Expression.of(ExprType.JSON_CAST, Args.THIS, this_, Args.TO, other));
            case DCOLON -> {
                Map<Arg, Object> kwargs = new LinkedHashMap<>();
                kwargs.put(Args.THIS, this_);
                kwargs.put(Args.TO, other);
                yield buildCast(d.STRICT_CAST, kwargs);
            }
            case ARROW -> buildJsonExtract(this_, other);
            case DARROW -> buildJsonExtractScalar(this_, other);
            case HASH_ARROW -> buildJsonbExtract(this_, other);
            case DHASH_ARROW -> buildJsonbExtractScalar(this_, other);
            case PLACEHOLDER -> buildJsonbContainsTopKey(this_, other);
            default -> super.columnOperator(t, this_, other);
        };
    }

    // ------------------------------------------------------------------------------------------
    // EXPRESSION_PARSERS

    @Override
    protected Expression expressionParser(ExprType type) {
        return switch (type) {
            case CLUSTER -> parseSort(ExprType.CLUSTER, TokenType.CLUSTER_BY);
            case COLUMN -> parseColumn();
            case COLUMN_DEF -> parseColumnDef(parseColumn());
            case CONDITION -> parseDisjunction();
            case DATA_TYPE -> parseTypes(false, true, false, false);
            case EXPRESSION -> parseExpression();
            case FROM -> parseFrom(true);
            case GRANT_PRINCIPAL -> parseGrantPrincipal();
            case GRANT_PRIVILEGE -> parseGrantPrivilege();
            case GROUP -> parseGroup();
            case HAVING -> parseHaving();
            case HINT -> parseHintBody();
            case IDENTIFIER -> parseIdVar();
            case JOIN -> parseJoin();
            case LAMBDA -> parseLambda();
            case LATERAL -> parseLateral();
            case LIMIT -> parseLimit();
            case OFFSET -> parseOffset();
            case ORDER -> parseOrder();
            case ORDERED -> parseOrdered();
            case PROPERTIES -> parseProperties();
            case PARTITIONED_BY_PROPERTY -> parsePartitionedBy();
            case QUALIFY -> parseQualify();
            case RETURNING -> parseReturning();
            case SELECT -> parseSelect();
            case SORT -> parseSort(ExprType.SORT, TokenType.SORT_BY);
            case TABLE -> parseTableParts();
            case TABLE_ALIAS -> parseTableAlias();
            case TUPLE -> parseValue(false);
            case WHENS -> parseWhenMatched();
            case WHERE -> parseWhere();
            case WINDOW -> parseNamedWindow();
            case WITH -> parseWith();
            default -> super.expressionParser(type);
        };
    }

    // ------------------------------------------------------------------------------------------
    // STATEMENT_PARSERS

    @Override
    protected Expression statementParser(TokenType t) {
        return switch (t) {
            case ALTER -> parseAlter();
            case ANALYZE -> parseAnalyze();
            case BEGIN -> parseTransaction();
            case CACHE -> parseCache();
            case COMMENT -> parseComment();
            case COMMIT -> parseCommitOrRollback();
            case COPY -> parseCopy();
            case CREATE -> parseCreate();
            case DECLARE -> parseDeclare();
            case DELETE -> parseDelete();
            case DESC -> parseDescribe();
            case DESCRIBE -> parseDescribe();
            case DROP -> parseDrop();
            case GRANT -> parseGrant();
            case REVOKE -> parseRevoke();
            case INSERT -> parseInsert();
            case KILL -> parseKill();
            case LOAD -> parseLoad();
            case MERGE -> parseMerge();
            case PIVOT -> parseSimplifiedPivot();
            case PRAGMA -> expression(Expression.of(ExprType.PRAGMA, Args.THIS, parseExpression()));
            case REFRESH -> parseRefresh();
            case ROLLBACK -> parseCommitOrRollback();
            case SET -> parseSet();
            case TRUNCATE -> parseTruncateTable();
            case UNCACHE -> parseUncache();
            case UNPIVOT -> parseSimplifiedPivot(Boolean.TRUE);
            case UPDATE -> parseUpdate();
            case USE -> parseUse();
            case SEMICOLON -> Expression.of(ExprType.SEMICOLON);
            default -> super.statementParser(t);
        };
    }

    // ------------------------------------------------------------------------------------------
    // UNARY_PARSERS

    @Override
    protected Expression unaryParser(TokenType t) {
        return switch (t) {
            case PLUS -> parseUnary(); // Unary + is handled as a no-op
            case NOT -> expression(Expression.of(ExprType.NOT, Args.THIS, parseEquality()));
            case TILDE -> expression(Expression.of(ExprType.BITWISE_NOT, Args.THIS, parseUnary()));
            case DASH -> expression(Expression.of(ExprType.NEG, Args.THIS, parseUnary()));
            case PIPE_SLASH -> expression(Expression.of(ExprType.SQRT, Args.THIS, parseUnary()));
            case DPIPE_SLASH -> expression(Expression.of(ExprType.CBRT, Args.THIS, parseUnary()));
            default -> super.unaryParser(t);
        };
    }

    // ------------------------------------------------------------------------------------------
    // STRING_PARSERS / NUMERIC_PARSERS / PRIMARY_PARSERS

    @Override
    protected Expression stringParser(TokenType t, Token token) {
        return switch (t) {
            case HEREDOC_STRING, RAW_STRING ->
                    expression(Expression.of(ExprType.RAW_STRING, Args.THIS, token.text()), token);
            case NATIONAL_STRING, NATIONAL_RAW_STRING ->
                    expression(Expression.of(ExprType.NATIONAL, Args.THIS, token.text()), token);
            case STRING -> expression(Expression.of(ExprType.LITERAL, Args.THIS, token.text(), Args.IS_STRING, Boolean.TRUE), token);
            case UNICODE_STRING -> expression(Expression.of(ExprType.UNICODE_STRING,
                    Args.THIS, token.text(),
                    Args.ESCAPE, matchTextSeq("UESCAPE") ? parseString() : Boolean.FALSE), token);
            default -> super.stringParser(t, token);
        };
    }

    @Override
    protected Expression numericParser(TokenType t, Token token) {
        return switch (t) {
            case BIT_STRING -> expression(Expression.of(ExprType.BIT_STRING, Args.THIS, token.text()), token);
            case BYTE_STRING -> expression(Expression.of(ExprType.BYTE_STRING,
                    Args.THIS, token.text(),
                    Args.IS_BYTES, dd.BYTE_STRING_IS_BYTES_TYPE ? Boolean.TRUE : null), token);
            case HEX_STRING -> expression(Expression.of(ExprType.HEX_STRING,
                    Args.THIS, token.text(),
                    Args.IS_INTEGER, dd.HEX_STRING_IS_INTEGER_TYPE ? Boolean.TRUE : null), token);
            case NUMBER -> expression(Expression.of(ExprType.LITERAL, Args.THIS, token.text(), Args.IS_STRING, Boolean.FALSE), token);
            default -> super.numericParser(t, token);
        };
    }

    @Override
    protected Expression primaryParser(TokenType t, Token token) {
        return switch (t) {
            // **STRING_PARSERS
            case HEREDOC_STRING, NATIONAL_STRING, NATIONAL_RAW_STRING, RAW_STRING, STRING, UNICODE_STRING ->
                    stringParser(t, token);
            // **NUMERIC_PARSERS
            case BIT_STRING, BYTE_STRING, HEX_STRING, NUMBER -> numericParser(t, token);
            case INTRODUCER -> parseIntroducer(token);
            case NULL -> expression(Expression.of(ExprType.NULL));
            case TRUE -> expression(Expression.of(ExprType.BOOLEAN, Args.THIS, Boolean.TRUE));
            case FALSE -> expression(Expression.of(ExprType.BOOLEAN, Args.THIS, Boolean.FALSE));
            case SESSION_PARAMETER -> parseSessionParameter();
            case STAR -> parseStarOps();
            default -> super.primaryParser(t, token);
        };
    }

    // ------------------------------------------------------------------------------------------
    // PLACEHOLDER_PARSERS

    @Override
    protected Expression placeholderParser(TokenType t) {
        return switch (t) {
            case PLACEHOLDER -> expression(Expression.of(ExprType.PLACEHOLDER));
            case PARAMETER -> parseParameter();
            case COLON -> matchSet(d.COLON_PLACEHOLDER_TOKENS)
                    ? expression(Expression.of(ExprType.PLACEHOLDER, Args.THIS, prev.text()))
                    : null;
            default -> super.placeholderParser(t);
        };
    }

    // ------------------------------------------------------------------------------------------
    // RANGE_PARSERS

    @Override
    protected Expression rangeParser(TokenType t, Expression this_) {
        return switch (t) {
            case AT_GT -> binaryRangeParser(ExprType.ARRAY_CONTAINS_ALL, this_);
            case BETWEEN -> parseBetween(this_);
            case GLOB -> binaryRangeParser(ExprType.GLOB, this_);
            case ILIKE -> binaryRangeParser(ExprType.I_LIKE, this_);
            case IN -> parseIn(this_);
            case IRLIKE -> binaryRangeParser(ExprType.REGEXP_I_LIKE, this_);
            case IS -> parseIs(this_);
            case LIKE -> binaryRangeParser(ExprType.LIKE, this_);
            case LT_AT -> binaryRangeParser(ExprType.ARRAY_CONTAINED_BY, this_);
            case OVERLAPS -> binaryRangeParser(ExprType.OVERLAPS, this_);
            case RLIKE -> binaryRangeParser(ExprType.REGEXP_LIKE, this_);
            case SIMILAR_TO -> binaryRangeParser(ExprType.SIMILAR_TO, this_);
            case FOR -> parseComprehension(this_);
            case QMARK_AMP -> binaryRangeParser(ExprType.JSONB_CONTAINS_ALL_TOP_KEYS, this_);
            case QMARK_PIPE -> binaryRangeParser(ExprType.JSONB_CONTAINS_ANY_TOP_KEYS, this_);
            case HASH_DASH -> binaryRangeParser(ExprType.JSONB_DELETE_AT_PATH, this_);
            case AT_QMARK -> binaryRangeParser(ExprType.JSONB_PATH_EXISTS, this_);
            case ADJACENT -> binaryRangeParser(ExprType.ADJACENT, this_);
            case OPERATOR -> parseOperator(this_);
            case AMP_LT -> binaryRangeParser(ExprType.EXTENDS_LEFT, this_);
            case AMP_GT -> binaryRangeParser(ExprType.EXTENDS_RIGHT, this_);
            case CARET_AT -> binaryRangeParser(ExprType.STARTS_WITH, this_);
            default -> super.rangeParser(t, this_);
        };
    }

    // ------------------------------------------------------------------------------------------
    // PIPE_SYNTAX_TRANSFORM_PARSERS

    @Override
    protected Expression pipeSyntaxTransformParser(String key, Expression query) {
        return switch (key) {
            case "AGGREGATE" -> parsePipeSyntaxAggregate(query);
            case "AS" -> {
                List<Expression> star = new ArrayList<>();
                star.add(Exp.star());
                yield buildPipeCte(query, star, parseTableAlias());
            }
            case "DISTINCT" -> {
                advance();
                yield query.distinct(true, false);
            }
            case "EXTEND" -> parsePipeSyntaxExtend(query);
            case "LIMIT" -> parsePipeSyntaxLimit(query);
            case "ORDER BY" -> query.orderBy(false, false, parseOrder());
            case "PIVOT" -> parsePipeSyntaxPivot(query);
            case "SELECT" -> parsePipeSyntaxSelect(query);
            case "TABLESAMPLE" -> parsePipeSyntaxTablesample(query);
            case "UNPIVOT" -> parsePipeSyntaxPivot(query);
            case "WHERE" -> query.where(true, false, parseWhere());
            default -> super.pipeSyntaxTransformParser(key, query);
        };
    }

    // ------------------------------------------------------------------------------------------
    // PROPERTY_PARSERS

    /**
     * Emulates Python's TypeError when a property parser is called with keyword arguments it does
     * not accept ({@code _parse_property_before} turns it into "Cannot parse property").
     */
    private static void checkKwargs(String key, Map<String, Object> kwargs, String... allowed) {
        if (kwargs == null || kwargs.isEmpty()) {
            return;
        }
        outer:
        for (String k : kwargs.keySet()) {
            for (String a : allowed) {
                if (a.equals(k)) {
                    continue outer;
                }
            }
            throw new IllegalArgumentException(
                    "TypeError: PROPERTY_PARSERS[" + key + "] got an unexpected keyword argument '" + k + "'");
        }
    }

    private static boolean kwFlag(Map<String, Object> kwargs, String name) {
        return kwargs != null && Expression.truthy(kwargs.get(name));
    }

    private static Boolean kwBool(Map<String, Object> kwargs, String name) {
        if (kwargs == null) {
            return null;
        }
        Object v = kwargs.get(name);
        if (v == null) {
            return null;
        }
        return v instanceof Boolean b ? b : Expression.truthy(v);
    }

    /** {@code **kwargs} forwarded into an expression constructor. */
    private static Map<Arg, Object> kwArgs(Map<String, Object> kwargs) {
        if (kwargs == null || kwargs.isEmpty()) {
            return null;
        }
        Map<Arg, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : kwargs.entrySet()) {
            Arg arg = Arg.forName(e.getKey());
            if (arg == null) {
                throw new IllegalArgumentException("Unknown arg: " + e.getKey());
            }
            out.put(arg, e.getValue());
        }
        return out;
    }

    @Override
    protected Object propertyParser(String key, Map<String, Object> kwargs) {
        switch (key) {
            case "CHARSET":
                checkKwargs(key, kwargs, "default");
                return parseCharacterSet(kwFlag(kwargs, "default"));
            case "COLLATE":
                return parsePropertyAssignment(ExprType.COLLATE_PROPERTY, kwArgs(kwargs));
            case "DATABLOCKSIZE":
                checkKwargs(key, kwargs, "default", "minimum", "maximum");
                return parseDatablocksize(kwBool(kwargs, "default"), kwBool(kwargs, "minimum"), kwBool(kwargs, "maximum"));
            case "FALLBACK":
                checkKwargs(key, kwargs, "no");
                return parseFallback(kwFlag(kwargs, "no"));
            case "JOURNAL":
                return parseJournal(kwArgs(kwargs));
            case "LOG":
                checkKwargs(key, kwargs, "no");
                return parseLog(kwFlag(kwargs, "no"));
            case "MERGEBLOCKRATIO":
                checkKwargs(key, kwargs, "no", "default");
                return parseMergeblockratio(kwFlag(kwargs, "no"), kwFlag(kwargs, "default"));
            default:
                break;
        }

        if (d.PROPERTY_PARSERS.contains(key)) {
            // The remaining entries take no keyword arguments.
            checkKwargs(key, kwargs);
        }

        return switch (key) {
            case "ALLOWED_VALUES" -> expression(Expression.of(ExprType.ALLOWED_VALUES_PROPERTY,
                    Args.EXPRESSIONS, parseCsv(this::parsePrimary)));
            case "ALGORITHM" -> parsePropertyAssignment(ExprType.ALGORITHM_PROPERTY);
            case "AUTO" -> parseAutoProperty();
            case "AUTO_INCREMENT" -> parsePropertyAssignment(ExprType.AUTO_INCREMENT_PROPERTY);
            case "BACKUP" -> expression(Expression.of(ExprType.BACKUP_PROPERTY, Args.THIS, parseVar(true)));
            case "BLOCKCOMPRESSION" -> parseBlockcompression();
            case "CALLED" -> parseCalledOnNullInputProperty();
            case "CHECKSUM" -> parseChecksum();
            case "CLUSTER BY" -> parseClusterProperty();
            case "CLUSTERED" -> parseClusteredBy();
            case "COMMENT" -> parsePropertyAssignment(ExprType.SCHEMA_COMMENT_PROPERTY);
            case "CONTAINS" -> parseContainsProperty();
            case "COPY" -> parseCopyProperty();
            case "DATA_DELETION" -> parseDataDeletionProperty();
            case "DEFINER" -> parseDefiner();
            case "DETERMINISTIC" -> expression(Expression.of(ExprType.STABILITY_PROPERTY, Args.THIS, Exp.string("IMMUTABLE")));
            case "DISTRIBUTED" -> parseDistributedProperty();
            case "DUPLICATE" -> parseCompositeKeyProperty(ExprType.DUPLICATE_KEY_PROPERTY);
            case "DYNAMIC" -> expression(Expression.of(ExprType.DYNAMIC_PROPERTY));
            case "DISTKEY" -> parseDistkey();
            case "DISTSTYLE" -> parsePropertyAssignment(ExprType.DIST_STYLE_PROPERTY);
            case "EMPTY" -> expression(Expression.of(ExprType.EMPTY_PROPERTY));
            case "ENGINE" -> parsePropertyAssignment(ExprType.ENGINE_PROPERTY);
            case "ENVIRONMENT" -> expression(Expression.of(ExprType.ENVIROMENT_PROPERTY,
                    Args.EXPRESSIONS, parseWrappedCsv(this::parseAssignment)));
            case "HANDLER" -> parsePropertyAssignment(ExprType.HANDLER_PROPERTY);
            case "EXECUTE" -> parsePropertyAssignment(ExprType.EXECUTE_AS_PROPERTY);
            case "EXTERNAL" -> expression(Expression.of(ExprType.EXTERNAL_PROPERTY));
            case "FORMAT" -> parsePropertyAssignment(ExprType.FILE_FORMAT_PROPERTY);
            case "FREESPACE" -> parseFreespace();
            case "GLOBAL" -> expression(Expression.of(ExprType.GLOBAL_PROPERTY));
            case "HEAP" -> expression(Expression.of(ExprType.HEAP_PROPERTY));
            case "ICEBERG" -> expression(Expression.of(ExprType.ICEBERG_PROPERTY));
            case "IMMUTABLE" -> expression(Expression.of(ExprType.STABILITY_PROPERTY, Args.THIS, Exp.string("IMMUTABLE")));
            case "INHERITS" -> expression(Expression.of(ExprType.INHERITS_PROPERTY,
                    Args.EXPRESSIONS, parseWrappedCsv(this::parseTable)));
            case "INPUT" -> expression(Expression.of(ExprType.INPUT_MODEL_PROPERTY, Args.THIS, parseSchema()));
            case "LANGUAGE" -> parsePropertyAssignment(ExprType.LANGUAGE_PROPERTY);
            case "LAYOUT" -> parseDictProperty("LAYOUT");
            case "LIFETIME" -> parseDictRange("LIFETIME");
            case "LIKE" -> parseCreateLike();
            case "LOCATION" -> parsePropertyAssignment(ExprType.LOCATION_PROPERTY);
            case "LOCK", "LOCKING" -> parseLocking();
            case "MATERIALIZED" -> expression(Expression.of(ExprType.MATERIALIZED_PROPERTY));
            case "MODIFIES" -> parseModifiesProperty();
            case "MULTISET" -> expression(Expression.of(ExprType.SET_PROPERTY, Args.MULTI, Boolean.TRUE));
            case "NO" -> parseNoProperty();
            case "ON" -> parseOnProperty();
            case "ORDER BY" -> parseOrder(null, true);
            case "OUTPUT" -> expression(Expression.of(ExprType.OUTPUT_MODEL_PROPERTY, Args.THIS, parseSchema()));
            case "PARTITION" -> parsePartitionedOf();
            case "PARTITION BY", "PARTITIONED BY", "PARTITIONED_BY" -> parsePartitionedBy();
            case "PRIMARY KEY" -> parsePrimaryKey(false, true);
            case "RANGE" -> parseDictRange("RANGE");
            case "READS" -> parseReadsProperty();
            case "REMOTE" -> parseRemoteWithConnection();
            case "RETURNS" -> parseReturns();
            case "STRICT" -> expression(Expression.of(ExprType.STRICT_PROPERTY));
            case "STREAMING" -> expression(Expression.of(ExprType.STREAMING_TABLE_PROPERTY));
            case "ROW" -> parseRow();
            case "ROW_FORMAT" -> parsePropertyAssignment(ExprType.ROW_FORMAT_PROPERTY);
            case "SAMPLE" -> expression(Expression.of(ExprType.SAMPLE_PROPERTY,
                    Args.THIS, matchTextSeq("BY") ? parseBitwise() : Boolean.FALSE));
            case "SECURE" -> expression(Expression.of(ExprType.SECURE_PROPERTY));
            case "SECURITY", "SQL SECURITY" -> parseSqlSecurity();
            case "SET" -> expression(Expression.of(ExprType.SET_PROPERTY, Args.MULTI, Boolean.FALSE));
            case "SETTINGS" -> parseSettingsProperty();
            case "SHARING" -> parsePropertyAssignment(ExprType.SHARING_PROPERTY);
            case "SORTKEY" -> parseSortkey();
            case "SOURCE" -> parseDictProperty("SOURCE");
            case "STABLE" -> expression(Expression.of(ExprType.STABILITY_PROPERTY, Args.THIS, Exp.string("STABLE")));
            case "STORED" -> parseStored();
            case "SYSTEM_VERSIONING" -> parseSystemVersioningProperty();
            case "TBLPROPERTIES" -> parseWrappedProperties();
            case "TEMP", "TEMPORARY" -> expression(Expression.of(ExprType.TEMPORARY_PROPERTY));
            case "TO" -> parseToTable();
            case "TRANSIENT" -> expression(Expression.of(ExprType.TRANSIENT_PROPERTY));
            case "TRANSFORM" -> expression(Expression.of(ExprType.TRANSFORM_MODEL_PROPERTY,
                    Args.EXPRESSIONS, parseWrappedCsv(this::parseExpression)));
            case "TTL" -> parseTtl();
            case "USING" -> parsePropertyAssignment(ExprType.FILE_FORMAT_PROPERTY);
            case "UNLOGGED" -> expression(Expression.of(ExprType.UNLOGGED_PROPERTY));
            case "VOLATILE" -> parseVolatileProperty();
            case "WITH" -> parseWithProperty();
            default -> super.propertyParser(key, kwargs);
        };
    }

    // ------------------------------------------------------------------------------------------
    // CONSTRAINT_PARSERS

    @Override
    protected Expression constraintParser(String key) {
        return switch (key) {
            case "AUTOINCREMENT", "AUTO_INCREMENT" -> parseAutoIncrement();
            case "CASESPECIFIC" -> expression(Expression.of(ExprType.CASE_SPECIFIC_COLUMN_CONSTRAINT, Args.NOT_, Boolean.FALSE));
            case "CHECK" -> parseCheckConstraint();
            case "COLLATE" -> {
                Expression this_ = parseIdentifier();
                if (this_ == null) {
                    this_ = parseColumn();
                }
                yield expression(Expression.of(ExprType.COLLATE_COLUMN_CONSTRAINT, Args.THIS, this_));
            }
            case "COMMENT" -> expression(Expression.of(ExprType.COMMENT_COLUMN_CONSTRAINT, Args.THIS, parseString()));
            case "COMPRESS" -> parseCompress();
            case "CLUSTERED" -> expression(Expression.of(ExprType.CLUSTERED_COLUMN_CONSTRAINT,
                    Args.THIS, parseWrappedCsv(this::parseOrdered)));
            case "NONCLUSTERED" -> expression(Expression.of(ExprType.NON_CLUSTERED_COLUMN_CONSTRAINT,
                    Args.THIS, parseWrappedCsv(this::parseOrdered)));
            case "DEFAULT" -> expression(Expression.of(ExprType.DEFAULT_COLUMN_CONSTRAINT, Args.THIS, parseBitwise()));
            case "ENCODE" -> expression(Expression.of(ExprType.ENCODE_COLUMN_CONSTRAINT, Args.THIS, parseVar()));
            case "EPHEMERAL" -> expression(Expression.of(ExprType.EPHEMERAL_COLUMN_CONSTRAINT, Args.THIS, parseBitwise()));
            case "EXCLUDE" -> expression(Expression.of(ExprType.EXCLUDE_COLUMN_CONSTRAINT, Args.THIS, parseIndexParams()));
            case "FOREIGN KEY" -> parseForeignKey();
            case "FORMAT" -> expression(Expression.of(ExprType.DATE_FORMAT_COLUMN_CONSTRAINT, Args.THIS, parseVarOrString()));
            case "GENERATED" -> parseGeneratedAsIdentity();
            case "IDENTITY" -> parseAutoIncrement();
            case "INLINE" -> parseInline();
            case "LIKE" -> parseCreateLike();
            case "NOT" -> parseNotConstraint();
            case "NULL" -> expression(Expression.of(ExprType.NOT_NULL_COLUMN_CONSTRAINT, Args.ALLOW_NULL, Boolean.TRUE));
            case "ON" -> match(TokenType.UPDATE)
                    ? expression(Expression.of(ExprType.ON_UPDATE_COLUMN_CONSTRAINT, Args.THIS, parseFunction()))
                    : expression(Expression.of(ExprType.ON_PROPERTY, Args.THIS, parseIdVar()));
            case "PATH" -> expression(Expression.of(ExprType.PATH_COLUMN_CONSTRAINT, Args.THIS, parseString()));
            case "PERIOD" -> parsePeriodForSystemTime();
            case "PRIMARY KEY" -> parsePrimaryKey();
            case "REFERENCES" -> parseReferences(false);
            case "TITLE" -> expression(Expression.of(ExprType.TITLE_COLUMN_CONSTRAINT, Args.THIS, parseVarOrString()));
            case "TTL" -> {
                List<Expression> exprs = new ArrayList<>();
                exprs.add(parseBitwise());
                yield expression(Expression.of(ExprType.MERGE_TREE_TTL, Args.EXPRESSIONS, exprs));
            }
            case "UNIQUE" -> parseUnique();
            case "UPPERCASE" -> expression(Expression.of(ExprType.UPPERCASE_COLUMN_CONSTRAINT));
            case "WITH" -> expression(Expression.of(ExprType.PROPERTIES, Args.EXPRESSIONS, parseWrappedProperties()));
            case "BUCKET", "TRUNCATE" -> parsePartitionedByBucketOrTruncate();
            default -> super.constraintParser(key);
        };
    }

    @Override
    protected Expression parsePartitionedByBucketOrTruncate() {
        if (!match(TokenType.L_PAREN, false)) {
            // Partitioning by bucket or truncate follows the syntax:
            // PARTITION BY (BUCKET(..) | TRUNCATE(..))
            // If we don't have parenthesis after each keyword, we should instead parse this as an identifier
            retreat(tokenIndex - 1);
            return null;
        }

        ExprType klass = Py.upper(prev.text()).equals("BUCKET")
                ? ExprType.PARTITIONED_BY_BUCKET
                : ExprType.PARTITION_BY_TRUNCATE;

        List<Expression> args = parseWrappedCsv(() -> {
            Expression e = parsePrimary();
            return e != null ? e : parseColumn();
        });
        Expression this_ = Py.seqGet(args, 0);
        Expression expression = Py.seqGet(args, 1);

        if (this_ != null && this_.is(ExprType.LITERAL)) {
            // Check for Iceberg partition transforms (bucket / truncate) and ensure their arguments are in the right order
            //  - For Hive, it's `bucket(<num buckets>, <col name>)` or `truncate(<num_chars>, <col_name>)`
            //  - For Trino, it's reversed - `bucket(<col name>, <num buckets>)` or `truncate(<col_name>, <num_chars>)`
            // Both variants are canonicalized in the latter i.e `bucket(<col name>, <num buckets>)`
            Expression tmp = this_;
            this_ = expression;
            expression = tmp;
        }

        return expression(Expression.of(klass, Args.THIS, this_, Args.EXPRESSION, expression));
    }

    // ------------------------------------------------------------------------------------------
    // ALTER_PARSERS / ALTER_ALTER_PARSERS

    @Override
    protected Object alterParser(String key) {
        return switch (key) {
            case "ADD" -> parseAlterTableAdd();
            case "AS" -> parseSelect();
            case "ALTER" -> parseAlterTableAlter();
            case "CLUSTER BY" -> parseClusterProperty();
            case "DELETE" -> expression(Expression.of(ExprType.DELETE, Args.WHERE, parseWhere()));
            case "DROP" -> parseAlterTableDrop();
            case "RENAME" -> parseAlterTableRename();
            case "SET" -> parseAlterTableSet();
            case "SWAP" -> expression(Expression.of(ExprType.SWAP_TABLE,
                    Args.THIS, match(TokenType.WITH) ? parseTable(true) : Boolean.FALSE));
            default -> super.alterParser(key);
        };
    }

    @Override
    protected Expression alterAlterParser(String key) {
        return switch (key) {
            case "DISTKEY", "DISTSTYLE" -> parseAlterDiststyle();
            case "SORTKEY" -> parseAlterSortkey();
            case "COMPOUND" -> parseAlterSortkey(Boolean.TRUE);
            default -> super.alterAlterParser(key);
        };
    }

    // ------------------------------------------------------------------------------------------
    // NO_PAREN_FUNCTION_PARSERS / FUNCTION_PARSERS

    @Override
    protected Expression noParenFunctionParser(String key) {
        return switch (key) {
            case "ANY" -> expression(Expression.of(ExprType.ANY, Args.THIS, parseBitwise()));
            case "CASE" -> parseCase();
            case "CONNECT_BY_ROOT" -> expression(Expression.of(ExprType.CONNECT_BY_ROOT, Args.THIS, parseColumn()));
            case "IF" -> parseIf();
            case "PRIOR" -> priorParserActive ? parsePriorNoParenFunction() : super.noParenFunctionParser(key);
            default -> super.noParenFunctionParser(key);
        };
    }

    @Override
    protected Expression functionParser(String key) {
        return switch (key) {
            // exp.ArgMax.sql_names() / exp.ArgMin.sql_names()
            case "ARG_MAX", "ARGMAX", "MAX_BY" -> parseDistinctArgFunction(ExprType.ARG_MAX);
            case "ARG_MIN", "ARGMIN", "MIN_BY" -> parseDistinctArgFunction(ExprType.ARG_MIN);
            case "CAST" -> parseCast(d.STRICT_CAST);
            case "CEIL" -> parseCeilFloor(ExprType.CEIL);
            case "CONVERT" -> parseConvert(d.STRICT_CAST);
            case "CHAR", "CHR" -> parseChar();
            case "DECODE" -> parseDecode();
            case "EXTRACT" -> parseExtract();
            case "FLOOR" -> parseCeilFloor(ExprType.FLOOR);
            case "GAP_FILL" -> parseGapFill();
            case "INITCAP" -> parseInitcap();
            case "JSON_OBJECT" -> (Expression) parseJsonObject();
            case "JSON_OBJECTAGG" -> (Expression) parseJsonObject(Boolean.TRUE);
            case "JSON_TABLE" -> parseJsonTable();
            case "MATCH" -> parseMatchAgainst();
            case "NORMALIZE" -> parseNormalize();
            case "OPENJSON" -> parseOpenJson();
            case "OVERLAY" -> parseOverlay();
            case "POSITION" -> parsePosition();
            case "SAFE_CAST" -> parseCast(false, Boolean.TRUE);
            case "STRING_AGG" -> parseStringAgg();
            case "SUBSTRING" -> parseSubstring();
            case "TRIM" -> parseTrim();
            case "TRY_CAST" -> parseCast(false, Boolean.TRUE);
            case "TRY_CONVERT" -> parseConvert(false, Boolean.TRUE);
            case "WEIGHT_STRING" -> parseWeightString();
            case "XMLELEMENT" -> parseXmlElement();
            case "XMLTABLE" -> parseXmlTable();
            default -> super.functionParser(key);
        };
    }

    // ------------------------------------------------------------------------------------------
    // QUERY_MODIFIER_PARSERS

    @Override
    protected ModifierResult queryModifierParser(TokenType t) {
        return switch (t) {
            case MATCH_RECOGNIZE -> new ModifierResult(Args.MATCH, parseMatchRecognize());
            case PREWHERE -> new ModifierResult(Args.PREWHERE, parsePrewhere());
            case WHERE -> new ModifierResult(Args.WHERE, parseWhere());
            case GROUP_BY -> new ModifierResult(Args.GROUP, parseGroup());
            case HAVING -> new ModifierResult(Args.HAVING, parseHaving());
            case QUALIFY -> new ModifierResult(Args.QUALIFY, parseQualify());
            case WINDOW -> new ModifierResult(Args.WINDOWS, parseWindowClause());
            case ORDER_BY -> new ModifierResult(Args.ORDER, parseOrder());
            case LIMIT -> new ModifierResult(Args.LIMIT, parseLimit());
            case FETCH -> new ModifierResult(Args.LIMIT, parseLimit());
            case OFFSET -> new ModifierResult(Args.OFFSET, parseOffset());
            case FOR -> new ModifierResult(Args.LOCKS, parseLocks());
            case LOCK -> new ModifierResult(Args.LOCKS, parseLocks());
            case TABLE_SAMPLE -> new ModifierResult(Args.SAMPLE, parseTableSample(true));
            case USING -> new ModifierResult(Args.SAMPLE, parseTableSample(true));
            case CLUSTER_BY -> new ModifierResult(Args.CLUSTER, parseCluster());
            case DISTRIBUTE_BY -> new ModifierResult(Args.DISTRIBUTE, parseSort(ExprType.DISTRIBUTE, TokenType.DISTRIBUTE_BY));
            case SORT_BY -> new ModifierResult(Args.SORT, parseSort(ExprType.SORT, TokenType.SORT_BY));
            case CONNECT_BY -> new ModifierResult(Args.CONNECT, parseConnect(true));
            default -> super.queryModifierParser(t);
        };
    }

    // ------------------------------------------------------------------------------------------
    // SET_PARSERS (SHOW_PARSERS and TYPE_CONVERTERS are empty in the base Parser)

    @Override
    protected Expression setParser(String key) {
        return switch (key) {
            case "GLOBAL" -> parseSetItemAssignment("GLOBAL");
            case "LOCAL" -> parseSetItemAssignment("LOCAL");
            case "SESSION" -> parseSetItemAssignment("SESSION");
            case "TRANSACTION" -> parseSetTransaction();
            default -> super.setParser(key);
        };
    }

    // ------------------------------------------------------------------------------------------
    // TYPE_LITERAL_PARSERS

    @Override
    protected Expression typeLiteralParser(DType t, Expression this_, Expression dataType) {
        if (t == DType.JSON) {
            return expression(Expression.of(ExprType.PARSE_JSON, Args.THIS, this_, Args.IS_LITERAL, Boolean.TRUE));
        }
        return super.typeLiteralParser(t, this_, dataType);
    }

    // ------------------------------------------------------------------------------------------
    // ANALYZE_EXPRESSION_PARSERS

    @Override
    protected Expression analyzeExpressionParser(String key) {
        return switch (key) {
            case "ALL" -> parseAnalyzeColumns();
            case "COMPUTE" -> parseAnalyzeStatistics();
            case "DELETE" -> parseAnalyzeDelete();
            case "DROP" -> parseAnalyzeHistogram();
            case "ESTIMATE" -> parseAnalyzeStatistics();
            case "LIST" -> parseAnalyzeList();
            case "PREDICATE" -> parseAnalyzeColumns();
            case "UPDATE" -> parseAnalyzeHistogram();
            case "VALIDATE" -> parseAnalyzeValidate();
            default -> super.analyzeExpressionParser(key);
        };
    }

    // ---------------------------------------------------------------------------------------
    // From ParserPartB

    private static final Set<TokenType> R_PAREN_OR_COMMA = EnumSet.of(TokenType.R_PAREN, TokenType.COMMA);
    private static final Set<TokenType> FUNCTION_OR_PROCEDURE =
            EnumSet.of(TokenType.FUNCTION, TokenType.PROCEDURE);
    private static final Set<TokenType> FIRST_OR_ALL = EnumSet.of(TokenType.FIRST, TokenType.ALL);
    private static final Set<String> CASCADE_RESTRICT = Set.of("CASCADE", "RESTRICT");
    private static final Set<String> IMMEDIATE_DEFERRED = Set.of("IMMEDIATE", "DEFERRED");
    private static final Set<String> ROW_STATEMENT = Set.of("ROW", "STATEMENT");
    private static final Set<String> MIN_MINIMUM = Set.of("MIN", "MINIMUM");
    private static final Set<String> MAX_MAXIMUM = Set.of("MAX", "MAXIMUM");
    private static final Set<String> BLOCKSIZE_UNITS = Set.of("BYTES", "KBYTES", "KILOBYTES");
    private static final Set<String> EXCL_EXCLUSIVE = Set.of("EXCL", "EXCLUSIVE");
    private static final Set<String> INCLUDING_EXCLUDING = Set.of("INCLUDING", "EXCLUDING");
    private static final Set<String> CONNECTION_QUERY = Set.of("CONNECTION", "QUERY");

    // ------------------------------------------------------------------------------------------
    // Private helpers (Python builtins / exp builders with semantics not covered by shared APIs)

    /** {@code exp.var(name)}: raises on an empty name and takes an expression's name. */
    private static Expression pyVar(Object name) {
        if (!Expression.truthy(name)) {
            throw new IllegalArgumentException("Cannot convert empty name into var.");
        }
        if (name instanceof Expression e) {
            name = e.name();
        }
        return Exp.var((String) name);
    }


    /** Python's {@code list(iterable)} for the result of a generator-returning parse method. */
    private static List<Object> pyList(Object v) {
        List<Object> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        if (v instanceof Iterable<?> it) {
            for (Object o : it) {
                out.add(o);
            }
        } else if (v instanceof Iterator<?> it) {
            while (it.hasNext()) {
                out.add(it.next());
            }
        } else {
            throw new IllegalArgumentException("not iterable: " + v);
        }
        return out;
    }

    /** {@code extend_props} closure of {@code _parse_create}. */
    @SuppressWarnings("unchecked")
    private static void extendProps(Expression[] properties, Expression tempProps) {
        if (properties[0] != null && tempProps != null) {
            // properties.expressions.extend(temp_props.expressions): a raw list extend (no re-parenting)
            Object raw = properties[0].get(Args.EXPRESSIONS);
            List<Expression> extra = tempProps.getExpressions();
            if (raw instanceof List<?> l) {
                ((List<Object>) l).addAll(extra);
            } else {
                List<Object> combined = new ArrayList<>(Py.ensureList(raw));
                combined.addAll(extra);
                properties[0].set(Args.EXPRESSIONS, combined);
            }
        } else if (tempProps != null) {
            properties[0] = tempProps;
        }
    }

    // ------------------------------------------------------------------------------------------

    @Override
    protected Expression parseCommand() {
        warnUnsupported();
        List<String> comments = prevComments;
        return expression(
                Expression.of(ExprType.COMMAND, Args.THIS, Py.upper(prev.text()), Args.EXPRESSION, parseString()),
                comments);
    }

    @Override
    protected Expression parseComment(boolean allowExists) {
        Token start = prev;
        Boolean exists = allowExists ? parseExists() : null;

        match(TokenType.ON);

        boolean materialized = matchTextSeq("MATERIALIZED");
        Token kind = matchSet(d.CREATABLES) ? prev : null;
        if (kind == null) {
            return parseAsCommand(start);
        }

        Expression this_;
        if (kind.type() == TokenType.FUNCTION || kind.type() == TokenType.PROCEDURE) {
            this_ = parseUserDefinedFunction(kind.type());
        } else if (kind.type() == TokenType.TABLE) {
            this_ = parseTable(false, false, d.COMMENT_TABLE_ALIAS_TOKENS);
        } else if (kind.type() == TokenType.COLUMN) {
            this_ = parseColumn();
        } else {
            this_ = parseTableParts(true);
        }

        match(TokenType.IS);

        return expression(Expression.of(ExprType.COMMENT,
                Args.THIS, this_,
                Args.KIND, kind.text(),
                Args.EXPRESSION, parseString(),
                Args.EXISTS, exists,
                Args.MATERIALIZED, materialized));
    }

    @Override
    protected Expression parseToTable() {
        Expression table = parseTableParts(true);
        return expression(Expression.of(ExprType.TO_TABLE_PROPERTY, Args.THIS, table));
    }

    private Expression parseTtlAction() {
        Expression this_ = parseBitwise();

        if (matchTextSeq("DELETE")) {
            return expression(Expression.of(ExprType.MERGE_TREE_TTL_ACTION, Args.THIS, this_, Args.DELETE, true));
        }
        if (matchTextSeq("RECOMPRESS")) {
            return expression(Expression.of(ExprType.MERGE_TREE_TTL_ACTION,
                    Args.THIS, this_, Args.RECOMPRESS, parseBitwise()));
        }
        if (matchTextSeq("TO", "DISK")) {
            return expression(Expression.of(ExprType.MERGE_TREE_TTL_ACTION,
                    Args.THIS, this_, Args.TO_DISK, parseString()));
        }
        if (matchTextSeq("TO", "VOLUME")) {
            return expression(Expression.of(ExprType.MERGE_TREE_TTL_ACTION,
                    Args.THIS, this_, Args.TO_VOLUME, parseString()));
        }

        return this_;
    }

    // https://clickhouse.com/docs/en/engines/table-engines/mergetree-family/mergetree#mergetree-table-ttl
    @Override
    protected Expression parseTtl() {
        List<Expression> expressions = parseCsv(this::parseTtlAction);
        Expression where = parseWhere();
        Expression group = parseGroup();

        List<Expression> aggregates = null;
        if (group != null && match(TokenType.SET)) {
            aggregates = parseCsv(() -> parseSetItem());
        }

        return expression(Expression.of(ExprType.MERGE_TREE_TTL,
                Args.EXPRESSIONS, expressions, Args.WHERE, where, Args.GROUP, group, Args.AGGREGATES, aggregates));
    }

    @Override
    protected Expression parseCondition() {
        return parseWrapped(() -> parseExpression(), true);
    }

    @Override
    protected Expression parseBlock() {
        return expression(Expression.of(ExprType.BLOCK,
                Args.EXPRESSIONS, parseBatchStatements(p -> p.parseStatement(), true)));
    }

    @Override
    protected Expression parseWhileblock() {
        return expression(Expression.of(ExprType.WHILE_BLOCK, Args.THIS, parseCondition(), Args.BODY, parseBlock()));
    }

    @Override
    protected Expression parseStatement() {
        if (!present(curr)) {
            return null;
        }

        if (matchSet(d.STATEMENT_PARSERS)) {
            List<String> comments = prevComments;
            Expression stmt = statementParser(prev.type());
            stmt.addComments(comments, true);
            return stmt;
        }

        if (matchSet(dialect.tokenizerConfig().commands())) {
            return parseCommand();
        }

        if (matchTextSeq("WHILE")) {
            return parseWhileblock();
        }

        Expression expression = parseExpression();
        expression = expression != null ? parseSetOperations(expression) : parseSelect();

        if (expression != null && expression.is(ExprType.SUBQUERY) && match(TokenType.PIPE_GT, false)) {
            expression = parsePipeSyntaxQuery(expression);
        }

        return (Expression) parseQueryModifiers(expression);
    }

    @Override
    protected Expression parseDrop(boolean exists, String kind) {
        Token start = prev;

        boolean temporary;
        boolean materialized;
        boolean iceberg;
        if (kind == null || kind.isEmpty()) {
            temporary = match(TokenType.TEMPORARY);
            materialized = matchTextSeq("MATERIALIZED");
            iceberg = matchTextSeq("ICEBERG");
            kind = matchSet(d.CREATABLES) ? Py.upper(prev.text()) : null;
        } else {
            temporary = false;
            materialized = false;
            iceberg = false;
        }

        if (kind == null || kind.isEmpty() || (iceberg && !kind.equals("TABLE"))) {
            return parseAsCommand(start);
        }

        boolean concurrently = kind.equals("INDEX")
                && d.ID_VAR_TOKENS.contains(next.type())
                && matchTextSeq("CONCURRENTLY");

        Boolean ifExists = exists ? Boolean.TRUE : parseExists();

        Object tables;
        if (kind.equals("COLUMN")) {
            // A dropped column named after a keyword, like `any` or `charset`, is only a name
            Expression name = hasNoParenFunctionParser(Py.upper(curr.text())) ? null : parseColumn();
            if (name == null) {
                name = parseIdVar();
            }
            tables = name != null && name.is(ExprType.IDENTIFIER)
                    ? expression(Expression.of(ExprType.COLUMN, Args.THIS, name))
                    : name;
        } else if (kind.equals("TABLE") || kind.equals("VIEW")) {
            tables = parseCsv(() -> parseTableParts(true));
        } else {
            tables = parseTableParts(true, kind.equals("SCHEMA"));
        }

        Expression cluster = null;
        if (match(TokenType.ON)) {
            if (kind.equals("INDEX")) {
                cluster = expression(Expression.of(ExprType.ON_PROPERTY, Args.THIS, parseTableParts()));
            } else {
                cluster = parseOnProperty();
            }
        }

        List<Expression> expressions;
        if (match(TokenType.L_PAREN, false)) {
            expressions = parseWrappedCsv(() -> parseTypes());
        } else {
            expressions = null;
        }

        Object cascadeOrRestrict = matchTexts(CASCADE_RESTRICT) ? Py.upper(prev.text()) : Boolean.FALSE;

        String mappedKind = dd.CREATABLE_KIND_MAPPING.get(kind);
        return expression(Expression.of(ExprType.DROP,
                Args.EXISTS, ifExists,
                Args.TABLES, Py.ensureList(tables),
                Args.EXPRESSIONS, expressions,
                Args.KIND, mappedKind != null && !mappedKind.isEmpty() ? mappedKind : kind,
                Args.TEMPORARY, temporary,
                Args.MATERIALIZED, materialized,
                Args.CASCADE, "CASCADE".equals(cascadeOrRestrict),
                Args.RESTRICT, "RESTRICT".equals(cascadeOrRestrict),
                Args.CONSTRAINTS, matchTextSeq("CONSTRAINTS"),
                Args.PURGE, matchTextSeq("PURGE"),
                Args.CLUSTER, cluster,
                Args.CONCURRENTLY, concurrently,
                Args.SYNC, matchTextSeq("SYNC"),
                Args.ICEBERG, iceberg,
                Args.FORCE, matchTextSeq("FORCE")));
    }

    @Override
    protected Boolean parseExists(boolean not) {
        if (not) {
            return matchTextSeq("IF", "NOT", "EXISTS");
        }
        return matchTextSeq("IF", "EXISTS");
    }

    @Override
    protected Expression parseCreate() {
        // Note: this can't be None because we've matched a statement parser
        Token start = prev;

        boolean replace = start.type() == TokenType.REPLACE
                || matchPair(TokenType.OR, TokenType.REPLACE)
                || matchPair(TokenType.OR, TokenType.ALTER);
        boolean refresh = matchPair(TokenType.OR, TokenType.REFRESH);

        boolean unique = match(TokenType.UNIQUE);

        Boolean clustered;
        if (matchTextSeq("CLUSTERED", "COLUMNSTORE")) {
            clustered = true;
        } else if (matchTextSeq("NONCLUSTERED", "COLUMNSTORE") || matchTextSeq("COLUMNSTORE")) {
            clustered = false;
        } else {
            clustered = null;
        }

        if (matchPair(TokenType.TABLE, TokenType.FUNCTION, false)) {
            advance();
        }

        Expression[] properties = {null};
        Token createToken = matchSet(d.CREATABLES) ? prev : null;

        if (createToken == null) {
            // exp.Properties.Location.POST_CREATE
            properties[0] = parseProperties();
            createToken = matchSet(d.CREATABLES) ? prev : null;

            if (properties[0] == null || createToken == null) {
                return parseAsCommand(start);
            }
        }

        TokenType createTokenType = createToken.type();

        boolean concurrently = matchTextSeq("CONCURRENTLY");
        Boolean exists = parseExists(true);
        Expression this_ = null;
        Expression expression = null;
        List<Expression> indexes = null;
        Boolean noSchemaBinding = null;
        Boolean begin = null;
        Expression clone = null;

        if (createTokenType == TokenType.FUNCTION || createTokenType == TokenType.PROCEDURE) {
            this_ = parseUserDefinedFunction(createTokenType);

            // exp.Properties.Location.POST_SCHEMA ("schema" here is the UDF's type signature)
            extendProps(properties, parseProperties());

            expression = match(TokenType.ALIAS) ? parseHeredoc() : null;

            boolean isTable;
            boolean overloadMode;
            if (expression == null
                    && createTokenType == TokenType.FUNCTION
                    && this_ != null && this_.is(ExprType.USER_DEFINED_FUNCTION)
                    && this_.has(Args.WRAPPED)) {
                int preTableIndex = tokenIndex;
                isTable = match(TokenType.TABLE);

                expression = parseExpression();
                overloadMode = expression != null
                        && curr.type() == TokenType.COMMA
                        && next.type() == TokenType.L_PAREN;
                if (!overloadMode) {
                    retreat(preTableIndex);
                    isTable = false;
                    expression = null;
                }
            } else {
                isTable = false;
                overloadMode = false;
            }

            extendProps(properties, parseFunctionProperties());

            if (expression == null) {
                if (match(TokenType.COMMAND)) {
                    expression = parseAsCommand(prev);
                } else {
                    begin = match(TokenType.BEGIN);
                    boolean return_ = matchTextSeq("RETURN");

                    if (match(TokenType.STRING, false)) {
                        // Takes care of BigQuery's JavaScript UDF definitions that end in an OPTIONS property
                        // # https://cloud.google.com/bigquery/docs/reference/standard-sql/data-definition-language#create_function_statement
                        expression = parseString();
                        extendProps(properties, parseProperties());
                    } else {
                        expression = createTokenType == TokenType.FUNCTION
                                ? parseUserDefinedFunctionExpression()
                                : parseBlock();
                    }

                    if (return_) {
                        expression = expression(Expression.of(ExprType.RETURN, Args.THIS, expression));
                    }
                }
            }

            if (overloadMode && expression != null) {
                expression = parseMacroOverloads(this_, expression, isTable);
            }
        } else if (createTokenType == TokenType.INDEX) {
            // Postgres allows anonymous indexes, eg. CREATE INDEX IF NOT EXISTS ON t(c)
            Expression index;
            boolean anonymous;
            if (!match(TokenType.ON)) {
                index = parseIdVar();
                anonymous = false;
            } else {
                index = null;
                anonymous = true;
            }

            this_ = parseIndex(index, anonymous);
        } else if ((createTokenType == TokenType.CONSTRAINT && match(TokenType.TRIGGER))
                || createTokenType == TokenType.TRIGGER) {
            boolean isConstraint = createTokenType == TokenType.CONSTRAINT;
            if (isConstraint) {
                createToken = prev;
            }

            Expression triggerName = parseIdVar();
            if (triggerName == null) {
                return parseAsCommand(start);
            }

            Expression timingVar = parseVarFromOptions(d.TRIGGER_TIMING, false);
            Object timing = timingVar != null ? timingVar.thisValue() : null;
            if (!Expression.truthy(timing)) {
                return parseAsCommand(start);
            }

            List<Expression> events = parseTriggerEvents();
            if (!match(TokenType.ON)) {
                raiseError("Expected ON in trigger definition");
            }

            Expression table = parseTableParts();
            Expression referencedTable = match(TokenType.FROM) ? parseTableParts() : null;
            Object[] deferrableInitially = parseTriggerDeferrable();
            Object deferrable = deferrableInitially[0];
            Object initially = deferrableInitially[1];
            Expression referencing = parseTriggerReferencing();
            String forEach = parseTriggerForEach();
            Object when = matchTextSeq("WHEN") ? parseWrapped(() -> parseDisjunction(), true) : Boolean.FALSE;
            Expression execute = parseTriggerExecute();

            if (execute == null) {
                return parseAsCommand(start);
            }

            Expression triggerProps = expression(Expression.of(ExprType.TRIGGER_PROPERTIES,
                    Args.TABLE, table,
                    Args.TIMING, timing,
                    Args.EVENTS, events,
                    Args.EXECUTE, execute,
                    Args.CONSTRAINT, isConstraint,
                    Args.REFERENCED_TABLE, referencedTable,
                    Args.DEFERRABLE, deferrable,
                    Args.INITIALLY, initially,
                    Args.REFERENCING, referencing,
                    Args.FOR_EACH, forEach,
                    Args.WHEN, when));

            this_ = triggerName;
            List<Expression> triggerList = new ArrayList<>();
            if (triggerProps != null) {
                triggerList.add(triggerProps);
            }
            extendProps(properties, Expression.of(ExprType.PROPERTIES, Args.EXPRESSIONS, triggerList));
        } else if (createTokenType == TokenType.TYPE) {
            this_ = parseTableParts(true);
            if (this_ == null || !match(TokenType.ALIAS)) {
                return parseAsCommand(start);
            }

            if (match(TokenType.ENUM)) {
                expression = Expression.of(ExprType.DATA_TYPE,
                        Args.THIS, DType.ENUM,
                        Args.EXPRESSIONS, parseWrappedCsv(() -> parseString()));
            } else if (match(TokenType.L_PAREN, false)) {
                expression = parseSchema();
            } else {
                return parseAsCommand(start);
            }
        } else if (d.DB_CREATABLES.contains(createTokenType)) {
            Expression tableParts = parseTableParts(true, createTokenType == TokenType.SCHEMA);

            // exp.Properties.Location.POST_NAME
            match(TokenType.COMMA);
            extendProps(properties, parseProperties(true));

            this_ = parseSchema(tableParts);

            // exp.Properties.Location.POST_SCHEMA and POST_WITH
            extendProps(properties, parseProperties());

            boolean hasAlias = match(TokenType.ALIAS);
            if (!matchSet(d.DDL_SELECT_TOKENS, false)) {
                // exp.Properties.Location.POST_ALIAS
                extendProps(properties, parseProperties());
            }

            if (createTokenType == TokenType.SEQUENCE) {
                expression = parseTypes();
                Expression props = parseProperties();
                if (props != null) {
                    Expression sequenceProps = Expression.of(ExprType.SEQUENCE_PROPERTIES);
                    List<Object> options = new ArrayList<>();
                    // Iterates the live list like Python's `for prop in props`, so popping an
                    // element skips the one after it, as in sqlglot.
                    List<Object> live = props.list(Args.EXPRESSIONS);
                    for (int i = 0; i < live.size(); i++) {
                        Object prop = live.get(i);
                        if (prop instanceof Expression p && p.is(ExprType.SEQUENCE_PROPERTIES)) {
                            for (Map.Entry<Arg, Object> entry : p.argEntries()) {
                                if (entry.getKey() == Args.OPTIONS) {
                                    options.addAll(Py.ensureList(entry.getValue()));
                                } else {
                                    sequenceProps.set(entry.getKey(), entry.getValue());
                                }
                            }
                            p.pop();
                        }
                    }

                    if (!options.isEmpty()) {
                        sequenceProps.set(Args.OPTIONS, options);
                    }

                    props.append(Args.EXPRESSIONS, sequenceProps);
                    extendProps(properties, props);
                }
            } else {
                expression = parseDdlSelect();

                // Some dialects also support using a table as an alias instead of a SELECT.
                // Here we fallback to this as an alternative.
                if (expression == null && hasAlias) {
                    expression = tryParse(() -> parseTableParts());
                }
            }

            if (createTokenType == TokenType.TABLE) {
                // exp.Properties.Location.POST_EXPRESSION
                extendProps(properties, parseProperties());

                indexes = new ArrayList<>();
                while (true) {
                    Expression index = parseIndex();

                    // exp.Properties.Location.POST_INDEX
                    extendProps(properties, parseProperties());
                    if (index == null) {
                        break;
                    } else {
                        match(TokenType.COMMA);
                        indexes.add(index);
                    }
                }
            } else if (createTokenType == TokenType.VIEW) {
                if (matchTextSeq("WITH", "NO", "SCHEMA", "BINDING")) {
                    noSchemaBinding = true;
                }
            } else if (createTokenType == TokenType.SINK || createTokenType == TokenType.SOURCE) {
                extendProps(properties, parseProperties());
            }

            boolean shallow = matchTextSeq("SHALLOW");

            if (matchTexts(d.CLONE_KEYWORDS)) {
                boolean copy = Py.lower(prev.text()).equals("copy");
                clone = expression(Expression.of(ExprType.CLONE,
                        Args.THIS, parseTable(true), Args.SHALLOW, shallow, Args.COPY, copy));
            }
        }

        if (present(curr) && !matchSet(R_PAREN_OR_COMMA, false)) {
            return parseAsCommand(start);
        }

        String createKindText = Py.upper(createToken.text());
        String mappedKind = dd.CREATABLE_KIND_MAPPING.get(createKindText);
        return expression(Expression.of(ExprType.CREATE,
                Args.THIS, this_,
                Args.KIND, mappedKind != null && !mappedKind.isEmpty() ? mappedKind : createKindText,
                Args.REPLACE, replace,
                Args.REFRESH, refresh,
                Args.UNIQUE, unique,
                Args.EXPRESSION, expression,
                Args.EXISTS, exists,
                Args.PROPERTIES, properties[0],
                Args.INDEXES, indexes,
                Args.NO_SCHEMA_BINDING, noSchemaBinding,
                Args.BEGIN, begin,
                Args.CLONE, clone,
                Args.CONCURRENTLY, concurrently,
                Args.CLUSTERED, clustered));
    }

    @Override
    protected Expression parseSequenceProperties() {
        Expression seq = Expression.of(ExprType.SEQUENCE_PROPERTIES);

        List<Expression> options = new ArrayList<>();
        int index = tokenIndex;

        while (present(curr)) {
            match(TokenType.COMMA);
            if (matchTextSeq("INCREMENT")) {
                matchTextSeq("BY");
                matchTextSeq("=");
                seq.set(Args.INCREMENT, parseTerm());
            } else if (matchTextSeq("MINVALUE")) {
                seq.set(Args.MINVALUE, parseTerm());
            } else if (matchTextSeq("MAXVALUE")) {
                seq.set(Args.MAXVALUE, parseTerm());
            } else if (matchTextSeq("START")) {
                matchTextSeq("WITH");
                matchTextSeq("=");
                seq.set(Args.START, parseTerm());
            } else if (matchTextSeq("CACHE")) {
                // T-SQL allows empty CACHE which is initialized dynamically
                Expression number = parseNumber();
                seq.set(Args.CACHE, number != null ? number : (Object) Boolean.TRUE);
            } else if (matchTextSeq("OWNED", "BY")) {
                // "OWNED BY NONE" is the default
                seq.set(Args.OWNED, matchTextSeq("NONE") ? null : parseColumn());
            } else {
                Expression opt = parseVarFromOptions(d.CREATE_SEQUENCE, false);
                if (opt != null) {
                    options.add(opt);
                } else {
                    break;
                }
            }
        }

        seq.set(Args.OPTIONS, !options.isEmpty() ? options : null);
        return tokenIndex == index ? null : seq;
    }

    @Override
    protected List<Expression> parseTriggerEvents() {
        List<Expression> events = new ArrayList<>();

        while (true) {
            Object eventType = matchSet(d.TRIGGER_EVENTS) ? Py.upper(prev.text()) : Boolean.FALSE;

            if (!Expression.truthy(eventType)) {
                raiseError("Expected trigger event (INSERT, UPDATE, DELETE, TRUNCATE)");
            }

            List<Expression> columns = "UPDATE".equals(eventType) && matchTextSeq("OF")
                    ? parseCsv(() -> parseColumn())
                    : null;

            events.add(expression(Expression.of(ExprType.TRIGGER_EVENT, Args.THIS, eventType, Args.COLUMNS, columns)));

            if (!match(TokenType.OR)) {
                break;
            }
        }

        return events;
    }

    @Override
    protected Object[] parseTriggerDeferrable() {
        Expression deferrableVar = parseVarFromOptions(d.TRIGGER_DEFERRABLE, false);
        Object deferrable = deferrableVar != null ? deferrableVar.thisValue() : null;

        String initially = null;
        if (Expression.truthy(deferrable) && matchTextSeq("INITIALLY")) {
            initially = matchTexts(IMMEDIATE_DEFERRED) ? Py.upper(prev.text()) : null;
        }

        return new Object[] {deferrable, initially};
    }

    @Override
    protected Expression parseTriggerReferencingClause(String keyword) {
        if (!matchTextSeq(keyword)) {
            return null;
        }
        if (!matchTextSeq("TABLE")) {
            raiseError("Expected TABLE after " + keyword + " in REFERENCING clause");
        }
        matchTextSeq("AS");
        return parseIdVar();
    }

    @Override
    protected Expression parseTriggerReferencing() {
        if (!matchTextSeq("REFERENCING")) {
            return null;
        }

        Expression oldAlias = null;
        Expression newAlias = null;

        while (true) {
            Expression alias;
            if ((alias = parseTriggerReferencingClause("OLD")) != null) {
                if (oldAlias != null) {
                    raiseError("Duplicate OLD clause in REFERENCING");
                }
                oldAlias = alias;
            } else if ((alias = parseTriggerReferencingClause("NEW")) != null) {
                if (newAlias != null) {
                    raiseError("Duplicate NEW clause in REFERENCING");
                }
                newAlias = alias;
            } else {
                break;
            }
        }

        if (oldAlias == null && newAlias == null) {
            raiseError("REFERENCING clause requires at least OLD TABLE or NEW TABLE");
        }

        return expression(Expression.of(ExprType.TRIGGER_REFERENCING, Args.OLD, oldAlias, Args.NEW, newAlias));
    }

    @Override
    protected String parseTriggerForEach() {
        if (!matchTextSeq("FOR", "EACH")) {
            return null;
        }

        return matchTexts(ROW_STATEMENT) ? Py.upper(prev.text()) : null;
    }

    @Override
    protected Expression parseTriggerExecute() {
        if (!match(TokenType.EXECUTE)) {
            return null;
        }

        if (!matchSet(FUNCTION_OR_PROCEDURE)) {
            raiseError("Expected FUNCTION or PROCEDURE after EXECUTE");
        }

        Expression funcCall = parseColumn();
        return expression(Expression.of(ExprType.TRIGGER_EXECUTE, Args.THIS, funcCall));
    }

    @Override
    protected Object parsePropertyBefore() {
        // only used for teradata currently
        match(TokenType.COMMA);

        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("no", matchTextSeq("NO"));
        kwargs.put("dual", matchTextSeq("DUAL"));
        kwargs.put("before", matchTextSeq("BEFORE"));
        kwargs.put("default", matchTextSeq("DEFAULT"));
        Object local;
        if (matchTextSeq("LOCAL")) {
            local = "LOCAL";
        } else if (matchTextSeq("NOT", "LOCAL")) {
            local = "NOT LOCAL";
        } else {
            local = Boolean.FALSE;
        }
        kwargs.put("local", local);
        kwargs.put("after", matchTextSeq("AFTER"));
        kwargs.put("minimum", matchTexts(MIN_MINIMUM));
        kwargs.put("maximum", matchTexts(MAX_MAXIMUM));

        if (matchTexts(d.PROPERTY_PARSERS)) {
            String key = Py.upper(prev.text());
            Map<String, Object> passed = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : kwargs.entrySet()) {
                if (Expression.truthy(e.getValue())) {
                    passed.put(e.getKey(), e.getValue());
                }
            }
            // sqlglot catches the TypeError raised when a property parser doesn't accept the
            // given keyword arguments; jglot's property parsers signal that with
            // IllegalArgumentException.
            try {
                return propertyParser(key, passed);
            } catch (IllegalArgumentException e) {
                raiseError("Cannot parse property '" + prev.text() + "'");
            }
        }

        if (matchPair(TokenType.CHAR, TokenType.SET)) {
            return parseCharacterSet(Expression.truthy(kwargs.get("default")));
        }

        return null;
    }

    @Override
    protected List<Object> parseWrappedProperties() {
        return parseWrapped(() -> parseCsvOf(() -> parseProperty(), TokenType.COMMA));
    }

    @Override
    protected Object parseProperty() {
        if (matchTexts(d.PROPERTY_PARSERS)) {
            return propertyParser(Py.upper(prev.text()), Map.of());
        }

        if (matchPair(TokenType.CHAR, TokenType.SET)) {
            return parseCharacterSet();
        }

        if (match(TokenType.DEFAULT)) {
            if (matchTexts(d.PROPERTY_PARSERS)) {
                return propertyParser(Py.upper(prev.text()), Map.of("default", Boolean.TRUE));
            }

            if (matchPair(TokenType.CHAR, TokenType.SET)) {
                return parseCharacterSet(true);
            }
        }

        if (matchTextSeq("COMPOUND", "SORTKEY")) {
            return parseSortkey(true);
        }

        if (matchTextSeq("PARAMETER", "STYLE", "PANDAS")) {
            return expression(Expression.of(ExprType.PARAMETER_STYLE_PROPERTY, Args.THIS, "PANDAS"));
        }

        if (matchTextSeq("NOT", "DETERMINISTIC")) {
            return expression(Expression.of(ExprType.STABILITY_PROPERTY, Args.THIS, Exp.string("VOLATILE")));
        }

        int index = tokenIndex;

        Expression seqProps = parseSequenceProperties();
        if (seqProps != null) {
            return seqProps;
        }

        retreat(index);
        return parseKeyValueProperty();
    }

    @Override
    protected Expression parseKeyValueProperty(ParseFn parseValue) {
        int index = tokenIndex;
        Expression key = parseColumn();

        if (!match(TokenType.EQ)) {
            retreat(index);
            return null;
        }

        // Transform the key to exp.Dot if it's dotted identifiers wrapped in exp.Column or to exp.Var otherwise
        if (key != null && key.is(ExprType.COLUMN)) {
            key = key.parts().size() > 1 ? key.toDot(true) : pyVar(key.name());
        }

        Expression value;
        if (parseValue != null) {
            value = parseValue.parse();
        } else {
            value = parseBitwise();
            if (value == null) {
                value = parseVar(true);
            }
        }

        // Transform the value to exp.Var if it was parsed as exp.Column(exp.Identifier())
        if (value != null && value.is(ExprType.COLUMN)) {
            value = pyVar(value.name());
        }

        return expression(Expression.of(ExprType.PROPERTY, Args.THIS, key, Args.VALUE, value));
    }

    @Override
    protected Expression parseStored() {
        if (matchTextSeq("BY")) {
            return expression(Expression.of(ExprType.STORAGE_HANDLER_PROPERTY, Args.THIS, parseVarOrString()));
        }

        match(TokenType.ALIAS);
        Expression inputFormat = matchTextSeq("INPUTFORMAT") ? parseString() : null;
        Expression outputFormat = matchTextSeq("OUTPUTFORMAT") ? parseString() : null;

        Expression this_;
        if (inputFormat != null || outputFormat != null) {
            this_ = expression(Expression.of(ExprType.INPUT_OUTPUT_FORMAT,
                    Args.INPUT_FORMAT, inputFormat, Args.OUTPUT_FORMAT, outputFormat));
        } else {
            this_ = parseVarOrString();
            if (this_ == null) {
                this_ = parseNumber();
            }
            if (this_ == null) {
                this_ = parseIdVar();
            }
        }

        return expression(Expression.of(ExprType.FILE_FORMAT_PROPERTY, Args.THIS, this_, Args.HIVE_FORMAT, true));
    }

    @Override
    protected Expression parseUnquotedField() {
        Expression field = parseField();
        if (field != null && field.is(ExprType.IDENTIFIER) && !field.has(Args.QUOTED)) {
            field = pyVar(field);
        }

        return field;
    }

    @Override
    protected Expression parsePropertyAssignment(ExprType expClass, Map<Arg, Object> kwargs) {
        match(TokenType.EQ);
        match(TokenType.ALIAS);

        Map<Arg, Object> args = new LinkedHashMap<>();
        args.put(Args.THIS, parseUnquotedField());
        if (kwargs != null) {
            args.putAll(kwargs);
        }
        return expression(Expression.of(expClass, args));
    }

    @Override
    protected Expression parseProperties(Boolean before) {
        List<Expression> properties = new ArrayList<>();
        while (true) {
            Object prop;
            if (Boolean.TRUE.equals(before)) {
                prop = parsePropertyBefore();
            } else {
                prop = parseProperty();
            }
            if (!Expression.truthy(prop)) {
                break;
            }
            for (Object p : Py.ensureList(prop)) {
                properties.add((Expression) p);
            }

            match(TokenType.COMMA, true, properties.get(properties.size() - 1));
        }

        if (!properties.isEmpty()) {
            return expression(Expression.of(ExprType.PROPERTIES, Args.EXPRESSIONS, properties));
        }

        return null;
    }

    @Override
    protected Expression parseFallback(boolean no) {
        return expression(Expression.of(ExprType.FALLBACK_PROPERTY,
                Args.NO, no, Args.PROTECTION, matchTextSeq("PROTECTION")));
    }

    @Override
    protected Expression parseSqlSecurity() {
        return expression(Expression.of(ExprType.SQL_SECURITY_PROPERTY,
                Args.THIS, matchTexts(d.SECURITY_PROPERTY_KEYWORDS) ? Py.upper(prev.text()) : Boolean.FALSE));
    }

    @Override
    protected Expression parseSettingsProperty() {
        return expression(Expression.of(ExprType.SETTINGS_PROPERTY,
                Args.EXPRESSIONS, parseCsv(() -> parseAssignment())));
    }

    @Override
    protected Expression parseCalledOnNullInputProperty() {
        if (!matchTextSeq("ON", "NULL", "INPUT")) {
            retreat(tokenIndex - 1);
            return null;
        }

        return expression(Expression.of(ExprType.CALLED_ON_NULL_INPUT_PROPERTY));
    }

    @Override
    protected Expression parseVolatileProperty() {
        Token preVolatileToken;
        if (tokenIndex >= 2) {
            preVolatileToken = tokenList.get(tokenIndex - 2);
        } else {
            preVolatileToken = null;
        }

        if (present(preVolatileToken) && d.PRE_VOLATILE_TOKENS.contains(preVolatileToken.type())) {
            return Expression.of(ExprType.VOLATILE_PROPERTY);
        }

        return expression(Expression.of(ExprType.STABILITY_PROPERTY, Args.THIS, Exp.string("VOLATILE")));
    }

    @Override
    protected Expression parseRetentionPeriod() {
        // Parse TSQL's HISTORY_RETENTION_PERIOD: {INFINITE | <number> DAY | DAYS | MONTH ...}
        Expression number = parseNumber();
        String numberStr = number != null ? Py.str(number) + " " : "";
        Expression unit = parseVar(true);
        return pyVar(numberStr + Py.str(unit));
    }

    @Override
    protected Expression parseSystemVersioningProperty(boolean with) {
        match(TokenType.EQ);
        Expression prop = expression(Expression.of(ExprType.WITH_SYSTEM_VERSIONING_PROPERTY,
                Args.ON, true, Args.WITH_, with));

        if (matchTextSeq("OFF")) {
            prop.set(Args.ON, false);
            return prop;
        }

        match(TokenType.ON);
        if (match(TokenType.L_PAREN)) {
            while (present(curr) && !match(TokenType.R_PAREN)) {
                if (matchTextSeq("HISTORY_TABLE", "=")) {
                    prop.set(Args.THIS, parseTableParts());
                } else if (matchTextSeq("DATA_CONSISTENCY_CHECK", "=")) {
                    prop.set(Args.DATA_CONSISTENCY, advanceAny() != null ? Py.upper(prev.text()) : null);
                } else if (matchTextSeq("HISTORY_RETENTION_PERIOD", "=")) {
                    prop.set(Args.RETENTION_PERIOD, parseRetentionPeriod());
                }

                match(TokenType.COMMA);
            }
        }

        return prop;
    }

    @Override
    protected Expression parseDataDeletionProperty() {
        match(TokenType.EQ);
        boolean on = matchTextSeq("ON") || !matchTextSeq("OFF");
        Expression prop = expression(Expression.of(ExprType.DATA_DELETION_PROPERTY, Args.ON, on));

        if (match(TokenType.L_PAREN)) {
            while (present(curr) && !match(TokenType.R_PAREN)) {
                if (matchTextSeq("FILTER_COLUMN", "=")) {
                    prop.set(Args.FILTER_COLUMN, parseColumn());
                } else if (matchTextSeq("RETENTION_PERIOD", "=")) {
                    prop.set(Args.RETENTION_PERIOD, parseRetentionPeriod());
                }

                match(TokenType.COMMA);
            }
        }

        return prop;
    }

    @Override
    protected Expression parseDistributedProperty() {
        String kind = "HASH";
        List<Expression> expressions = null;
        if (matchTextSeq("BY", "HASH")) {
            expressions = parseWrappedCsv(() -> parseIdVar());
        } else if (matchTextSeq("BY", "RANDOM")) {
            kind = "RANDOM";
        }

        // If the BUCKETS keyword is not present, the number of buckets is AUTO
        Expression buckets = null;
        if (matchTextSeq("BUCKETS") && !matchTextSeq("AUTO")) {
            buckets = parseNumber();
        }

        return expression(Expression.of(ExprType.DISTRIBUTED_BY_PROPERTY,
                Args.EXPRESSIONS, expressions, Args.KIND, kind, Args.BUCKETS, buckets, Args.ORDER, parseOrder()));
    }

    @Override
    protected Expression parseCompositeKeyProperty(ExprType exprType) {
        matchTextSeq("KEY");
        List<Expression> expressions = parseWrappedIdVars();
        return expression(Expression.of(exprType, Args.EXPRESSIONS, expressions));
    }

    @Override
    protected Object parseWithProperty() {
        if (matchTextSeq("(", "SYSTEM_VERSIONING")) {
            Expression prop = parseSystemVersioningProperty(true);
            matchRParen();
            return prop;
        }

        if (match(TokenType.L_PAREN, false)) {
            List<Expression> result = new ArrayList<>();
            for (Object i : parseWrappedProperties()) {
                if (i instanceof List<?> l) {
                    for (Object o : l) {
                        result.add((Expression) o);
                    }
                } else {
                    result.add((Expression) i);
                }
            }
            return result;
        }

        if (matchTextSeq("JOURNAL")) {
            return parseWithjournaltable();
        }

        if (matchTexts(d.VIEW_ATTRIBUTES)) {
            return expression(Expression.of(ExprType.VIEW_ATTRIBUTE_PROPERTY, Args.THIS, Py.upper(prev.text())));
        }

        if (matchTextSeq("DATA")) {
            return parseWithdata(false);
        } else if (matchTextSeq("NO", "DATA")) {
            return parseWithdata(true);
        }

        if (match(TokenType.SERDE_PROPERTIES, false)) {
            return parseSerdeProperties(true);
        }

        if (match(TokenType.SCHEMA)) {
            return expression(Expression.of(ExprType.WITH_SCHEMA_BINDING_PROPERTY,
                    Args.THIS, parseVarFromOptions(d.SCHEMA_BINDING_OPTIONS)));
        }

        if (matchTexts(d.PROCEDURE_OPTIONS.keySet(), false)) {
            return expression(Expression.of(ExprType.WITH_PROCEDURE_OPTIONS,
                    Args.EXPRESSIONS, parseCsv(() -> parseProcedureOption())));
        }

        if (!present(next)) {
            return null;
        }

        return parseWithisolatedloading();
    }

    @Override
    protected Expression parseProcedureOption() {
        if (matchTextSeq("EXECUTE", "AS")) {
            Expression this_ = parseVarFromOptions(d.EXECUTE_AS_OPTIONS, false);
            if (this_ == null) {
                this_ = parseString();
            }
            return expression(Expression.of(ExprType.EXECUTE_AS_PROPERTY, Args.THIS, this_));
        }

        return parseVarFromOptions(d.PROCEDURE_OPTIONS);
    }

    // https://dev.mysql.com/doc/refman/8.0/en/create-view.html
    @Override
    protected Expression parseDefiner() {
        match(TokenType.EQ);

        Expression user = parseIdVar();
        match(TokenType.PARAMETER);
        Object host = parseIdVar();
        if (host == null) {
            host = match(TokenType.MOD) ? prev.text() : Boolean.FALSE;
        }

        if (user == null || !Expression.truthy(host)) {
            return null;
        }

        return Expression.of(ExprType.DEFINER_PROPERTY, Args.THIS, Py.str(user) + "@" + Py.str(host));
    }

    @Override
    protected Expression parseWithjournaltable() {
        match(TokenType.TABLE);
        match(TokenType.EQ);
        return expression(Expression.of(ExprType.WITH_JOURNAL_TABLE_PROPERTY, Args.THIS, parseTableParts()));
    }

    @Override
    protected Expression parseLog(boolean no) {
        return expression(Expression.of(ExprType.LOG_PROPERTY, Args.NO, no));
    }

    @Override
    protected Expression parseJournal(Map<Arg, Object> kwargs) {
        return expression(Expression.of(ExprType.JOURNAL_PROPERTY, kwargs == null ? Map.<Arg, Object>of() : kwargs));
    }

    @Override
    protected Expression parseChecksum() {
        match(TokenType.EQ);

        Boolean on = null;
        if (match(TokenType.ON)) {
            on = true;
        } else if (matchTextSeq("OFF")) {
            on = false;
        }

        return expression(Expression.of(ExprType.CHECKSUM_PROPERTY,
                Args.ON, on, Args.DEFAULT, match(TokenType.DEFAULT)));
    }

    @Override
    protected Expression parseCluster() {
        match(TokenType.CLUSTER_BY);
        return expression(Expression.of(ExprType.CLUSTER, Args.EXPRESSIONS, parseCsv(() -> parseColumn())));
    }

    @Override
    protected Expression parseClusterProperty() {
        return expression(Expression.of(ExprType.CLUSTER_PROPERTY,
                Args.EXPRESSIONS, parseWrappedCsv(() -> parseColumn())));
    }

    @Override
    protected Expression parseClusteredBy() {
        matchTextSeq("BY");

        matchLParen();
        List<Expression> expressions = parseCsv(() -> parseColumn());
        matchRParen();

        List<Expression> sortedBy;
        if (matchTextSeq("SORTED", "BY")) {
            matchLParen();
            sortedBy = parseCsv(() -> parseOrdered());
            matchRParen();
        } else {
            sortedBy = null;
        }

        match(TokenType.INTO);
        Expression buckets = parseNumber();
        matchTextSeq("BUCKETS");

        return expression(Expression.of(ExprType.CLUSTERED_BY_PROPERTY,
                Args.EXPRESSIONS, expressions, Args.SORTED_BY, sortedBy, Args.BUCKETS, buckets));
    }

    @Override
    protected Expression parseCopyProperty() {
        if (!matchTextSeq("GRANTS")) {
            retreat(tokenIndex - 1);
            return null;
        }

        return expression(Expression.of(ExprType.COPY_GRANTS_PROPERTY));
    }

    @Override
    protected Expression parseFreespace() {
        match(TokenType.EQ);
        return expression(Expression.of(ExprType.FREESPACE_PROPERTY,
                Args.THIS, parseNumber(), Args.PERCENT, match(TokenType.PERCENT)));
    }

    @Override
    protected Expression parseMergeblockratio(boolean no, boolean default_) {
        if (match(TokenType.EQ)) {
            return expression(Expression.of(ExprType.MERGE_BLOCK_RATIO_PROPERTY,
                    Args.THIS, parseNumber(), Args.PERCENT, match(TokenType.PERCENT)));
        }

        return expression(Expression.of(ExprType.MERGE_BLOCK_RATIO_PROPERTY, Args.NO, no, Args.DEFAULT, default_));
    }

    @Override
    protected Expression parseDatablocksize(Boolean default_, Boolean minimum, Boolean maximum) {
        match(TokenType.EQ);
        Expression size = parseNumber();

        String units = null;
        if (matchTexts(BLOCKSIZE_UNITS)) {
            units = prev.text();
        }

        return expression(Expression.of(ExprType.DATA_BLOCKSIZE_PROPERTY,
                Args.SIZE, size, Args.UNITS, units, Args.DEFAULT, default_,
                Args.MINIMUM, minimum, Args.MAXIMUM, maximum));
    }

    @Override
    protected Expression parseBlockcompression() {
        match(TokenType.EQ);
        boolean always = matchTextSeq("ALWAYS");
        boolean manual = matchTextSeq("MANUAL");
        boolean never = matchTextSeq("NEVER");
        boolean default_ = matchTextSeq("DEFAULT");

        Expression autotemp = null;
        if (matchTextSeq("AUTOTEMP")) {
            autotemp = parseSchema();
        }

        return expression(Expression.of(ExprType.BLOCK_COMPRESSION_PROPERTY,
                Args.ALWAYS, always, Args.MANUAL, manual, Args.NEVER, never,
                Args.DEFAULT, default_, Args.AUTOTEMP, autotemp));
    }

    @Override
    protected Expression parseWithisolatedloading() {
        int index = tokenIndex;
        boolean no = matchTextSeq("NO");
        boolean concurrent = matchTextSeq("CONCURRENT");

        if (!matchTextSeq("ISOLATED", "LOADING")) {
            retreat(index);
            return null;
        }

        Expression target = parseVarFromOptions(d.ISOLATED_LOADING_OPTIONS, false);
        return expression(Expression.of(ExprType.ISOLATED_LOADING_PROPERTY,
                Args.NO, no, Args.CONCURRENT, concurrent, Args.TARGET, target));
    }

    @Override
    protected Expression parseLocking() {
        String kind;
        if (match(TokenType.TABLE)) {
            kind = "TABLE";
        } else if (match(TokenType.VIEW)) {
            kind = "VIEW";
        } else if (match(TokenType.ROW)) {
            kind = "ROW";
        } else if (matchTextSeq("DATABASE")) {
            kind = "DATABASE";
        } else {
            kind = null;
        }

        Expression this_;
        if (kind != null && (kind.equals("DATABASE") || kind.equals("TABLE") || kind.equals("VIEW"))) {
            this_ = parseTableParts();
        } else {
            this_ = null;
        }

        String forOrIn;
        if (match(TokenType.FOR)) {
            forOrIn = "FOR";
        } else if (match(TokenType.IN)) {
            forOrIn = "IN";
        } else {
            forOrIn = null;
        }

        String lockType;
        if (matchTextSeq("ACCESS")) {
            lockType = "ACCESS";
        } else if (matchTexts(EXCL_EXCLUSIVE)) {
            lockType = "EXCLUSIVE";
        } else if (matchTextSeq("SHARE")) {
            lockType = "SHARE";
        } else if (matchTextSeq("READ")) {
            lockType = "READ";
        } else if (matchTextSeq("WRITE")) {
            lockType = "WRITE";
        } else if (matchTextSeq("CHECKSUM")) {
            lockType = "CHECKSUM";
        } else {
            lockType = null;
        }

        boolean override = matchTextSeq("OVERRIDE");

        return expression(Expression.of(ExprType.LOCKING_PROPERTY,
                Args.THIS, this_, Args.KIND, kind, Args.FOR_OR_IN, forOrIn,
                Args.LOCK_TYPE, lockType, Args.OVERRIDE, override));
    }

    @Override
    protected List<Expression> parsePartitionBy() {
        if (match(TokenType.PARTITION_BY)) {
            return parseCsv(() -> parseDisjunction());
        }
        return new ArrayList<>();
    }

    private Expression parsePartitionBoundExpr() {
        if (matchTextSeq("MINVALUE")) {
            return Exp.var("MINVALUE");
        }
        if (matchTextSeq("MAXVALUE")) {
            return Exp.var("MAXVALUE");
        }
        return parseBitwise();
    }

    @Override
    protected Expression parsePartitionBoundSpec() {
        Object this_ = null;
        Expression expression = null;
        List<Expression> fromExpressions = null;
        List<Expression> toExpressions = null;

        if (match(TokenType.IN)) {
            this_ = parseWrappedCsv(() -> parseBitwise());
        } else if (match(TokenType.FROM)) {
            fromExpressions = parseWrappedCsv(this::parsePartitionBoundExpr);
            matchTextSeq("TO");
            toExpressions = parseWrappedCsv(this::parsePartitionBoundExpr);
        } else if (matchTextSeq("WITH", "(", "MODULUS")) {
            this_ = parseNumber();
            matchTextSeq(",", "REMAINDER");
            expression = parseNumber();
            matchRParen();
        } else {
            raiseError("Failed to parse partition bound spec.");
        }

        return expression(Expression.of(ExprType.PARTITION_BOUND_SPEC,
                Args.THIS, this_,
                Args.EXPRESSION, expression,
                Args.FROM_EXPRESSIONS, fromExpressions,
                Args.TO_EXPRESSIONS, toExpressions));
    }

    // https://www.postgresql.org/docs/current/sql-createtable.html
    @Override
    protected Expression parsePartitionedOf() {
        if (!matchTextSeq("OF")) {
            retreat(tokenIndex - 1);
            return null;
        }

        Expression this_ = parseTable(true);

        Expression expression = null;
        if (match(TokenType.DEFAULT)) {
            expression = Exp.var("DEFAULT");
        } else if (matchTextSeq("FOR", "VALUES")) {
            expression = parsePartitionBoundSpec();
        } else {
            raiseError("Expecting either DEFAULT or FOR VALUES clause.");
        }

        return expression(Expression.of(ExprType.PARTITIONED_OF_PROPERTY, Args.THIS, this_, Args.EXPRESSION, expression));
    }

    @Override
    protected Expression parsePartitionedBy() {
        match(TokenType.EQ);
        Expression this_ = parseSchema();
        if (this_ == null) {
            this_ = parseBracket(parseField());
        }
        return expression(Expression.of(ExprType.PARTITIONED_BY_PROPERTY, Args.THIS, this_));
    }

    @Override
    protected Expression parseWithdata(boolean no) {
        Boolean statistics;
        if (matchTextSeq("AND", "STATISTICS")) {
            statistics = true;
        } else if (matchTextSeq("AND", "NO", "STATISTICS")) {
            statistics = false;
        } else {
            statistics = null;
        }

        return expression(Expression.of(ExprType.WITH_DATA_PROPERTY, Args.NO, no, Args.STATISTICS, statistics));
    }

    @Override
    protected Expression parseContainsProperty() {
        if (matchTextSeq("SQL")) {
            return expression(Expression.of(ExprType.SQL_READ_WRITE_PROPERTY, Args.THIS, "CONTAINS SQL"));
        }
        return null;
    }

    @Override
    protected Expression parseModifiesProperty() {
        if (matchTextSeq("SQL", "DATA")) {
            return expression(Expression.of(ExprType.SQL_READ_WRITE_PROPERTY, Args.THIS, "MODIFIES SQL DATA"));
        }
        return null;
    }

    @Override
    protected Expression parseNoProperty() {
        if (matchTextSeq("PRIMARY", "INDEX")) {
            return Expression.of(ExprType.NO_PRIMARY_INDEX_PROPERTY);
        }
        if (matchTextSeq("SQL")) {
            return expression(Expression.of(ExprType.SQL_READ_WRITE_PROPERTY, Args.THIS, "NO SQL"));
        }
        return null;
    }

    @Override
    protected Expression parseOnProperty() {
        if (matchTextSeq("COMMIT", "PRESERVE", "ROWS")) {
            return Expression.of(ExprType.ON_COMMIT_PROPERTY);
        }
        if (matchTextSeq("COMMIT", "DELETE", "ROWS")) {
            return Expression.of(ExprType.ON_COMMIT_PROPERTY, Args.DELETE, true);
        }
        return expression(Expression.of(ExprType.ON_PROPERTY, Args.THIS, parseSchema(parseIdVar())));
    }

    @Override
    protected Expression parseReadsProperty() {
        if (matchTextSeq("SQL", "DATA")) {
            return expression(Expression.of(ExprType.SQL_READ_WRITE_PROPERTY, Args.THIS, "READS SQL DATA"));
        }
        return null;
    }

    @Override
    protected Expression parseDistkey() {
        return expression(Expression.of(ExprType.DIST_KEY_PROPERTY, Args.THIS, parseWrapped(() -> parseIdVar())));
    }

    @Override
    protected Expression parseCreateLike() {
        Expression table = parseTable(true);

        List<Expression> options = new ArrayList<>();
        while (matchTexts(INCLUDING_EXCLUDING)) {
            String this_ = Py.upper(prev.text());

            Expression idVar = parseIdVar();
            if (idVar == null) {
                return null;
            }

            options.add(expression(Expression.of(ExprType.PROPERTY,
                    Args.THIS, this_, Args.VALUE, pyVar(Py.upper((String) idVar.thisValue())))));
        }

        return expression(Expression.of(ExprType.LIKE_PROPERTY, Args.THIS, table, Args.EXPRESSIONS, options));
    }

    @Override
    protected Expression parseSortkey(boolean compound) {
        return expression(Expression.of(ExprType.SORT_KEY_PROPERTY,
                Args.THIS, parseWrappedIdVars(), Args.COMPOUND, compound));
    }

    @Override
    protected Expression parseCharacterSet(boolean default_) {
        match(TokenType.EQ);
        return expression(Expression.of(ExprType.CHARACTER_SET_PROPERTY,
                Args.THIS, parseVarOrString(), Args.DEFAULT, default_));
    }

    @Override
    protected Expression parseRemoteWithConnection() {
        matchTextSeq("WITH", "CONNECTION");
        return expression(Expression.of(ExprType.REMOTE_WITH_CONNECTION_MODEL_PROPERTY,
                Args.THIS, parseTableParts()));
    }

    @Override
    protected Expression parseReturns() {
        Expression value;
        Boolean null_ = null;
        boolean isTable = match(TokenType.TABLE);

        if (isTable) {
            if (match(TokenType.LT)) {
                value = expression(Expression.of(ExprType.SCHEMA,
                        Args.THIS, "TABLE", Args.EXPRESSIONS, parseCsv(() -> parseStructTypes())));
                if (!match(TokenType.GT)) {
                    raiseError("Expecting >");
                }
            } else {
                value = parseSchema(Exp.var("TABLE"));
            }
        } else if (matchTextSeq("NULL", "ON", "NULL", "INPUT")) {
            null_ = true;
            value = null;
        } else {
            value = parseTypes();
        }

        return expression(Expression.of(ExprType.RETURNS_PROPERTY,
                Args.THIS, value, Args.IS_TABLE, isTable, Args.NULL, null_));
    }

    @Override
    protected Expression parseDescribe() {
        String kind = matchSet(d.CREATABLES) ? prev.text() : null;
        String style = matchTexts(d.DESCRIBE_STYLES) ? Py.upper(prev.text()) : null;
        if (match(TokenType.DOT)) {
            style = null;
            retreat(tokenIndex - 2);
        }

        Object format = match(TokenType.FORMAT, false) ? parseProperty() : null;

        Expression this_;
        if (matchSet(d.STATEMENT_PARSERS, false)) {
            this_ = parseStatement();
        } else {
            this_ = parseTable(true);
        }

        Expression properties = parseProperties();
        List<Expression> expressions = properties != null ? properties.getExpressions() : null;
        Expression partition = parsePartition();
        return expression(Expression.of(ExprType.DESCRIBE,
                Args.THIS, this_,
                Args.STYLE, style,
                Args.KIND, kind,
                Args.EXPRESSIONS, expressions,
                Args.PARTITION, partition,
                Args.FORMAT, format,
                Args.AS_JSON, matchTextSeq("AS", "JSON")));
    }

    private Expression parseConditionalInsert() {
        Expression expression;
        if (match(TokenType.WHEN)) {
            expression = parseDisjunction();
            match(TokenType.THEN);
        } else {
            expression = null;
        }

        boolean else_ = match(TokenType.ELSE);

        if (!match(TokenType.INTO)) {
            return null;
        }

        return expression(Expression.of(ExprType.CONDITIONAL_INSERT,
                Args.THIS, expression(Expression.of(ExprType.INSERT,
                        Args.THIS, parseTable(true),
                        Args.EXPRESSION, parseDerivedTableValues())),
                Args.EXPRESSION, expression,
                Args.ELSE_, else_));
    }

    @Override
    protected Expression parseMultitableInserts(Collection<String> comments, String kind) {
        kind = kind != null && !kind.isEmpty() ? kind : Py.upper(prev.text());
        List<Expression> expressions = new ArrayList<>();

        Expression expression = parseConditionalInsert();
        while (expression != null) {
            expressions.add(expression);
            expression = parseConditionalInsert();
        }

        return expression(
                Expression.of(ExprType.MULTITABLE_INSERTS,
                        Args.KIND, kind, Args.EXPRESSIONS, expressions, Args.SOURCE, parseTable()),
                comments == null ? null : new ArrayList<>(comments));
    }

    @Override
    protected Expression parseInsert() {
        List<String> comments = new ArrayList<>();
        Expression hint = parseHint();
        boolean overwrite = match(TokenType.OVERWRITE);
        boolean ignore = match(TokenType.IGNORE);
        boolean local = matchTextSeq("LOCAL");
        Object alternative = null;
        Boolean isFunction = null;

        Expression this_;
        if (matchTextSeq("DIRECTORY")) {
            this_ = expression(Expression.of(ExprType.DIRECTORY,
                    Args.THIS, parseVarOrString(),
                    Args.LOCAL, local,
                    Args.ROW_FORMAT, parseRowFormat(true)));
        } else {
            if (matchSet(FIRST_OR_ALL)) {
                comments.addAll(Py.ensureList(prevComments));
                return parseMultitableInserts(comments);
            }

            // Oracle's conditional form defaults to ALL when the keyword is omitted
            if (match(TokenType.WHEN, false)) {
                return parseMultitableInserts(comments, "ALL");
            }

            if (match(TokenType.OR)) {
                alternative = matchTexts(d.INSERT_ALTERNATIVES) ? prev.text() : Boolean.FALSE;
            }

            match(TokenType.INTO);
            comments.addAll(Py.ensureList(prevComments));
            match(TokenType.TABLE);
            isFunction = match(TokenType.FUNCTION);

            this_ = isFunction ? parseFunction() : parseInsertTable();
        }

        // MySQL's INSERT ... SET is normalized into the INSERT ... (cols) VALUES (vals) variant
        Expression setValues = null;
        if (match(TokenType.SET)) {
            List<Expression> columns = new ArrayList<>();
            List<Expression> values = new ArrayList<>();

            parseCsv(() -> {
                Expression target = parseColumn();
                if (target != null && target.is(ExprType.COLUMN) && match(TokenType.EQ)) {
                    Expression value;
                    if (dd.SUPPORTS_VALUES_DEFAULT && match(TokenType.DEFAULT)) {
                        value = pyVar(Py.upper(prev.text()));
                    } else {
                        value = parseDisjunction();
                    }

                    if (value != null) {
                        columns.add(target.getThis());
                        values.add(value);
                        return value;
                    }
                }

                raiseError("Expected column assignment in INSERT ... SET");
                return null;
            });

            this_ = expression(Expression.of(ExprType.SCHEMA, Args.THIS, this_, Args.EXPRESSIONS, columns));
            setValues = expression(Expression.of(ExprType.VALUES,
                    Args.EXPRESSIONS, List.of(Expression.of(ExprType.TUPLE, Args.EXPRESSIONS, values)),
                    Args.ALIAS, parseTableAlias()));
        }

        Expression returning = parseReturning(); // TSQL allows RETURNING before source

        Object stored = matchTextSeq("STORED") ? parseStored() : Boolean.FALSE;
        boolean byName = matchTextSeq("BY", "NAME");
        Boolean exists = parseExists();
        Expression replaceWhere = null;
        List<Expression> replaceUsing = null;

        if (match(TokenType.REPLACE)) {
            if (match(TokenType.WHERE)) {
                replaceWhere = parseDisjunction();
            } else if (match(TokenType.USING)) {
                replaceUsing = parseUsingIdentifiers();
            }
        }

        Expression finalSetValues = setValues;
        return expression(
                Expression.of(ExprType.INSERT,
                        Args.HINT, hint,
                        Args.IS_FUNCTION, isFunction,
                        Args.THIS, this_,
                        Args.STORED, stored,
                        Args.BY_NAME, byName,
                        Args.EXISTS, exists,
                        Args.WHERE, replaceWhere,
                        Args.USING, replaceUsing,
                        Args.PARTITION, match(TokenType.PARTITION_BY) ? parsePartitionedBy() : Boolean.FALSE,
                        Args.SETTINGS, matchTextSeq("SETTINGS") ? parseSettingsProperty() : Boolean.FALSE,
                        Args.DEFAULT, matchTextSeq("DEFAULT", "VALUES"),
                        Args.EXPRESSION, insertExpression(finalSetValues),
                        Args.CONFLICT, parseOnConflict(),
                        Args.RETURNING, returning != null ? returning : parseReturning(),
                        Args.OVERWRITE, overwrite,
                        Args.ALTERNATIVE, alternative,
                        Args.IGNORE, ignore,
                        Args.SOURCE, match(TokenType.TABLE) ? parseTable() : Boolean.FALSE),
                comments);
    }

    /** {@code set_values or self._parse_derived_table_values(allow_value_synonym=True) or self._parse_ddl_select()}. */
    private Expression insertExpression(Expression setValues) {
        if (setValues != null) {
            return setValues;
        }
        Expression e = parseDerivedTableValues(true);
        return e != null ? e : parseDdlSelect();
    }

    @Override
    protected Expression parseInsertTable() {
        Expression this_ = parseTable(true, false, null, false, false, true);
        if (this_ != null && this_.is(ExprType.TABLE) && match(TokenType.ALIAS, false)) {
            this_.set(Args.ALIAS, parseTableAlias());
        }
        return this_;
    }

    @Override
    protected Expression parseKill() {
        Expression kind = matchTexts(CONNECTION_QUERY) ? pyVar(prev.text()) : null;

        return expression(Expression.of(ExprType.KILL, Args.THIS, parsePrimary(), Args.KIND, kind));
    }

    @Override
    protected Expression parseOnConflict() {
        boolean conflict = matchTextSeq("ON", "CONFLICT");
        boolean duplicate = matchTextSeq("ON", "DUPLICATE", "KEY");

        if (!conflict && !duplicate) {
            return null;
        }

        List<Expression> conflictKeys = null;
        Expression constraint = null;

        if (conflict) {
            if (matchTextSeq("ON", "CONSTRAINT")) {
                constraint = parseIdVar();
            } else if (match(TokenType.L_PAREN)) {
                conflictKeys = parseCsv(() -> parseIndexedColumn());
                matchRParen();
            }
        }

        Expression indexPredicate = parseWhere();

        Expression action = parseVarFromOptions(d.CONFLICT_ACTIONS);
        List<Expression> expressions;
        if (prev.type() == TokenType.UPDATE) {
            match(TokenType.SET);
            expressions = parseCsv(() -> parseUpdateAssignment());
        } else {
            expressions = null;
        }

        return expression(Expression.of(ExprType.ON_CONFLICT,
                Args.DUPLICATE, duplicate,
                Args.EXPRESSIONS, expressions,
                Args.ACTION, action,
                Args.CONFLICT_KEYS, conflictKeys,
                Args.INDEX_PREDICATE, indexPredicate,
                Args.CONSTRAINT, constraint,
                Args.WHERE, parseWhere()));
    }

    @Override
    protected Expression parseReturning() {
        if (!match(TokenType.RETURNING)) {
            return null;
        }
        return expression(Expression.of(ExprType.RETURNING,
                Args.EXPRESSIONS, parseCsv(() -> parseExpression()),
                Args.INTO, match(TokenType.INTO) ? parseTablePart() : Boolean.FALSE));
    }

    @Override
    protected Expression parseRow() {
        if (!match(TokenType.FORMAT)) {
            return null;
        }
        return parseRowFormat();
    }

    @Override
    protected Expression parseSerdeProperties(boolean with) {
        int index = tokenIndex;
        boolean with_ = with || matchTextSeq("WITH");

        if (!match(TokenType.SERDE_PROPERTIES)) {
            retreat(index);
            return null;
        }
        return expression(Expression.of(ExprType.SERDE_PROPERTIES,
                Args.EXPRESSIONS, parseWrappedProperties(), Args.WITH_, with_));
    }

    @Override
    protected Expression parseRowFormat(boolean matchRow) {
        if (matchRow && !matchPair(TokenType.ROW, TokenType.FORMAT)) {
            return null;
        }

        if (matchTextSeq("SERDE")) {
            Expression this_ = parseString();

            Expression serdeProperties = parseSerdeProperties();

            return expression(Expression.of(ExprType.ROW_FORMAT_SERDE_PROPERTY,
                    Args.THIS, this_, Args.SERDE_PROPERTIES, serdeProperties));
        }

        matchTextSeq("DELIMITED");

        Map<Arg, Object> kwargs = new LinkedHashMap<>();

        if (matchTextSeq("FIELDS", "TERMINATED", "BY")) {
            kwargs.put(Args.FIELDS, parseString());
            if (matchTextSeq("ESCAPED", "BY")) {
                kwargs.put(Args.ESCAPED, parseString());
            }
        }
        if (matchTextSeq("COLLECTION", "ITEMS", "TERMINATED", "BY")) {
            kwargs.put(Args.COLLECTION_ITEMS, parseString());
        }
        if (matchTextSeq("MAP", "KEYS", "TERMINATED", "BY")) {
            kwargs.put(Args.MAP_KEYS, parseString());
        }
        if (matchTextSeq("LINES", "TERMINATED", "BY")) {
            kwargs.put(Args.LINES, parseString());
        }
        if (matchTextSeq("NULL", "DEFINED", "AS")) {
            kwargs.put(Args.NULL, parseString());
        }

        return expression(Expression.of(ExprType.ROW_FORMAT_DELIMITED_PROPERTY, kwargs));
    }

    @Override
    protected Expression parseLoad() {
        if (matchTextSeq("DATA")) {
            boolean local = matchTextSeq("LOCAL");
            matchTextSeq("INPATH");
            Expression inpath = parseString();
            boolean overwrite = match(TokenType.OVERWRITE);
            Boolean temp = null;
            if (match(TokenType.INTO)) {
                temp = match(TokenType.TEMPORARY);
                match(TokenType.TABLE);
            }

            return expression(Expression.of(ExprType.LOAD_DATA,
                    Args.THIS, parseTable(true),
                    Args.LOCAL, local,
                    Args.OVERWRITE, overwrite,
                    Args.TEMP, temp,
                    Args.INPATH, inpath,
                    Args.FILES, matchTextSeq("FROM", "FILES")
                            ? Expression.of(ExprType.PROPERTIES, Args.EXPRESSIONS, parseWrappedProperties())
                            : Boolean.FALSE,
                    Args.PARTITION, parsePartition(),
                    Args.INPUT_FORMAT, matchTextSeq("INPUTFORMAT") ? parseString() : Boolean.FALSE,
                    Args.SERDE, matchTextSeq("SERDE") ? parseString() : Boolean.FALSE));
        }
        return parseAsCommand(prev);
    }

    @Override
    protected Expression parseDelete() {
        Expression hint = parseHint();

        // This handles MySQL's "Multiple-Table Syntax"
        // https://dev.mysql.com/doc/refman/8.0/en/delete.html
        List<Expression> tables = null;
        if (!match(TokenType.FROM, false)) {
            tables = parseCsv(() -> parseTable());
            if (tables.isEmpty()) {
                tables = null;
            }
        }

        Expression returning = parseReturning();

        return expression(Expression.of(ExprType.DELETE,
                Args.HINT, hint,
                Args.TABLES, tables,
                Args.THIS, match(TokenType.FROM) ? parseTable(false, true) : Boolean.FALSE,
                Args.USING, match(TokenType.USING) ? parseCsv(() -> parseTable(false, true)) : Boolean.FALSE,
                Args.CLUSTER, match(TokenType.ON) ? parseOnProperty() : Boolean.FALSE,
                Args.WHERE, parseWhere(),
                Args.RETURNING, returning != null ? returning : parseReturning(),
                Args.ORDER, parseOrder(),
                Args.LIMIT, parseLimit()));
    }

    @Override
    protected Expression parseUpdateAssignment() {
        Expression this_ = parseComparison();
        if (match(TokenType.EQ)) {
            List<String> comments = prevComments;
            this_ = expression(
                    Expression.of(ExprType.EQ, Args.THIS, this_, Args.EXPRESSION, parseDisjunction()), comments);
        }
        return this_;
    }

    @Override
    protected Expression parseUpdate() {
        Expression hint = parseHint();
        Map<Arg, Object> kwargs = new LinkedHashMap<>();
        kwargs.put(Args.HINT, hint);
        kwargs.put(Args.THIS, parseTable(false, true, d.UPDATE_ALIAS_TOKENS));
        while (present(curr)) {
            if (match(TokenType.SET)) {
                kwargs.put(Args.EXPRESSIONS, parseCsv(() -> parseUpdateAssignment()));
            } else if (match(TokenType.RETURNING, false)) {
                kwargs.put(Args.RETURNING, parseReturning());
            } else if (match(TokenType.FROM, false)) {
                Expression from = parseFrom(true);
                Expression table = from != null ? from.getThis() : null;
                if (table != null && table.is(ExprType.SUBQUERY) && match(TokenType.JOIN, false)) {
                    List<Object> joins = pyList(parseJoins());
                    table.set(Args.JOINS, joins.isEmpty() ? null : joins);
                }

                kwargs.put(Args.FROM_, from);
            } else if (match(TokenType.WHERE, false)) {
                kwargs.put(Args.WHERE, parseWhere());
            } else if (match(TokenType.ORDER_BY, false)) {
                kwargs.put(Args.ORDER, parseOrder());
            } else if (match(TokenType.LIMIT, false)) {
                kwargs.put(Args.LIMIT, parseLimit());
            } else {
                break;
            }
        }

        return expression(Expression.of(ExprType.UPDATE, kwargs));
    }

    @Override
    protected Expression parseUse() {
        return expression(Expression.of(ExprType.USE,
                Args.KIND, parseVarFromOptions(d.USABLES, false),
                Args.THIS, parseTable(false)));
    }

    @Override
    protected Expression parseUncache() {
        if (!match(TokenType.TABLE)) {
            raiseError("Expecting TABLE after UNCACHE");
        }

        return expression(Expression.of(ExprType.UNCACHE,
                Args.EXISTS, parseExists(), Args.THIS, parseTable(true)));
    }

    @Override
    protected Expression parseCache() {
        boolean lazy = matchTextSeq("LAZY");
        match(TokenType.TABLE);
        Expression table = parseTable(true);

        List<Expression> options = new ArrayList<>();
        if (matchTextSeq("OPTIONS")) {
            matchLParen();
            Expression k = parseString();
            match(TokenType.EQ);
            Expression v = parseString();
            options = new ArrayList<>(Arrays.asList(k, v));
            matchRParen();
        }

        match(TokenType.ALIAS);
        return expression(Expression.of(ExprType.CACHE,
                Args.THIS, table, Args.LAZY, lazy, Args.OPTIONS, options,
                Args.EXPRESSION, parseSelect(true)));
    }

    @Override
    protected Expression parsePartition() {
        if (!matchTexts(d.PARTITION_KEYWORDS)) {
            return null;
        }

        return expression(Expression.of(ExprType.PARTITION,
                Args.SUBPARTITION, Py.upper(prev.text()).equals("SUBPARTITION"),
                Args.EXPRESSIONS, parseWrappedCsv(() -> parseDisjunction())));
    }

    private Expression parseValueExpression() {
        if (dd.SUPPORTS_VALUES_DEFAULT && match(TokenType.DEFAULT)) {
            return pyVar(Py.upper(prev.text()));
        }
        return parseExpression();
    }

    @Override
    protected Expression parseValue(boolean values) {
        if (match(TokenType.L_PAREN)) {
            List<Expression> expressions = parseCsv(this::parseValueExpression);
            matchRParen();
            return expression(Expression.of(ExprType.TUPLE, Args.EXPRESSIONS, expressions));
        }

        // In some dialects we can have VALUES 1, 2 which results in 1 column & 2 rows.
        Expression expression = parseExpression();
        if (expression != null) {
            return expression(Expression.of(ExprType.TUPLE, Args.EXPRESSIONS, List.of(expression)));
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // From ParserPartC

    private static final Set<TokenType> PIVOT_TOKENS = EnumSet.of(TokenType.PIVOT, TokenType.UNPIVOT);
    private static final Set<TokenType> FROM_OR_BETWEEN = EnumSet.of(TokenType.FROM, TokenType.BETWEEN);
    private static final Set<TokenType> PERCENT_OR_MOD = EnumSet.of(TokenType.PERCENT, TokenType.MOD);
    private static final Set<TokenType> FIRST_OR_NEXT = EnumSet.of(TokenType.FIRST, TokenType.NEXT);
    private static final Set<TokenType> INDEX_OR_KEY = EnumSet.of(TokenType.INDEX, TokenType.KEY);
    private static final Set<TokenType> ROW_TOKEN = EnumSet.of(TokenType.ROW);
    private static final Set<String> STRUCT_OR_VALUE = Set.of("STRUCT", "VALUE");
    private static final Set<String> FINAL_OR_RUNNING = Set.of("FINAL", "RUNNING");
    private static final Set<String> TO_OR_AND = Set.of("TO", "AND");
    private static final Set<String> SEED_OR_REPEATABLE = Set.of("SEED", "REPEATABLE");
    private static final Set<String> ROW_OR_ROWS = Set.of("ROW", "ROWS");

    /**
     * sqlglot temporarily adds a "PRIOR" entry to NO_PAREN_FUNCTION_PARSERS while parsing a CONNECT BY
     * condition. jglot keeps that per parser instance instead of mutating shared tables.
     */
    protected boolean priorParserActive;

    // ------------------------------------------------------------------------------------------
    // Private helpers

    /** Python's {@code cond and value()}: the value if {@code cond}, else {@code False}. */
    private static Object andThen(boolean cond, Supplier<?> value) {
        return cond ? value.get() : Boolean.FALSE;
    }

    /** Python's {@code expression.set(k, v)} when the node type may not declare {@code k}. */
    private static void setArg(Expression e, Arg arg, Object value) {
        if (e.accepts(arg)) {
            e.set(arg, value);
        }
    }

    private static void appendArg(Expression e, Arg arg, Object value) {
        if (e.accepts(arg)) {
            e.append(arg, value);
        }
    }

    private Expression queryModifiers(Expression this_) {
        return (Expression) parseQueryModifiers(this_);
    }

    @SuppressWarnings("unchecked")
    private Iterable<Expression> joinsOf(Set<TokenType> aliasTokens) {
        return (Iterable<Expression>) parseJoins(aliasTokens);
    }

    /** {@code list(iter(fn, None))}. */
    private static List<Expression> iterUntilNone(ParseFn fn) {
        List<Expression> out = new ArrayList<>();
        Expression e;
        while ((e = fn.parse()) != null) {
            out.add(e);
        }
        return out;
    }

    /** {@code exp.maybe_parse(sql, into=into, dialect=self.dialect)} for a string. */
    private Expression parseOneInto(ExprType into, String sql) {
        List<Expression> result = dialect.parseInto(into, sql, ParserOptions.DEFAULT);
        for (Expression expression : result) {
            if (expression == null) {
                throw new ParseError("No expression was parsed from '" + sql + "'");
            }
            return expression;
        }
        throw new ParseError("No expression was parsed from '" + sql + "'");
    }

    /** sqlglot's {@code Table.to_column(copy=...)}. */
    private static Expression tableToColumn(Expression table, boolean copy) {
        List<Expression> parts = table.parts();
        Expression lastPart = parts.get(parts.size() - 1);
        Expression col;
        if (lastPart.is(ExprType.IDENTIFIER)) {
            List<Expression> head = new ArrayList<>(parts.subList(0, Math.min(4, parts.size())));
            java.util.Collections.reverse(head);
            col = Expression.of(ExprType.COLUMN,
                    Args.THIS, Exp.toIdentifier(Py.seqGet(head, 0), null, copy),
                    Args.TABLE, Exp.toIdentifier(Py.seqGet(head, 1), null, copy),
                    Args.DB, Exp.toIdentifier(Py.seqGet(head, 2), null, copy),
                    Args.CATALOG, Exp.toIdentifier(Py.seqGet(head, 3), null, copy));
            if (parts.size() > 4) {
                List<Expression> dotParts = new ArrayList<>();
                dotParts.add(col);
                for (Expression field : parts.subList(4, parts.size())) {
                    dotParts.add(Exp.toIdentifier(field, null, copy));
                }
                col = Exp.dot(dotParts);
            }
        } else {
            // This branch will be reached if a function or array is wrapped in a `Table`
            col = lastPart;
        }

        Expression alias = table.expr(Args.ALIAS);
        if (alias != null) {
            col = Exp.alias(copy ? col.copy() : col, alias.getThis());
        }
        return col;
    }


    /** {@code itertools.product(*lists)}. */
    private static List<List<String>> product(List<List<String>> lists) {
        List<List<String>> result = new ArrayList<>();
        result.add(new ArrayList<>());
        for (List<String> pool : lists) {
            List<List<String>> next = new ArrayList<>();
            for (List<String> prefix : result) {
                for (String item : pool) {
                    List<String> combo = new ArrayList<>(prefix);
                    combo.add(item);
                    next.add(combo);
                }
            }
            result = next;
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------
    // Select

    @Override
    protected Object[] parseProjections() {
        return new Object[] {parseExpressions(), null};
    }

    @Override
    protected Expression parseWrappedSelect(boolean table) {
        Expression this_;
        if (matchSet(PIVOT_TOKENS)) {
            this_ = parseSimplifiedPivot(prev.type() == TokenType.UNPIVOT);
        } else {
            // Support parentheses for duckdb FROM-first syntax
            this_ = table && !match(TokenType.FROM, false)
                    ? parseTable(false, false, null, false, false, false, true)
                    : parseSelect(true, false, true, false);

            // Transform exp.Values into a exp.Table to pass through parse_query_modifiers
            // in case a modifier (e.g. join) is following
            if (table && this_ != null && this_.is(ExprType.VALUES) && !this_.alias().isEmpty()) {
                Expression alias = this_.expr(Args.ALIAS).pop();
                this_ = Expression.of(ExprType.TABLE, Args.THIS, this_, Args.ALIAS, alias);
            }

            this_ = queryModifiers(parseSetOperations(this_));
        }
        return this_;
    }

    @Override
    protected Expression parseSelect(boolean nested, boolean table, boolean parseSubqueryAlias,
            boolean parseSetOperation, boolean consumePipe) {
        Expression query = parseSelectQuery(nested, table, parseSubqueryAlias, parseSetOperation);

        if (consumePipe && match(TokenType.PIPE_GT, false)) {
            if (query != null && query.is(ExprType.QUERY)) {
                query = parsePipeSyntaxQuery(query);
                query = query != null && table ? query.subquery(null, false) : query;
            }
        }

        return query;
    }

    @Override
    protected Expression parseSelectQuery(boolean nested, boolean table, boolean parseSubqueryAlias,
            boolean parseSetOperation) {
        Expression cte = parseWith();

        if (cte != null) {
            Expression this_ = parseStatement();

            if (this_ == null) {
                raiseError("Failed to parse any statement following CTE");
                return cte;
            }

            while (this_.is(ExprType.SUBQUERY) && this_.isWrapper()) {
                this_ = this_.getThis();
            }

            if (this_.accepts(Args.WITH_)) {
                Expression innerCte = this_.expr(Args.WITH_);
                if (innerCte != null) {
                    List<Expression> merged = new ArrayList<>(cte.getExpressions());
                    merged.addAll(innerCte.getExpressions());
                    cte.set(Args.EXPRESSIONS, merged);
                    if (innerCte.has(Args.RECURSIVE)) {
                        cte.set(Args.RECURSIVE, Boolean.TRUE);
                    }
                }
                this_.set(Args.WITH_, cte);
            } else {
                raiseError(this_.key() + " does not support CTE");
                this_ = cte;
            }

            return this_;
        }

        // duckdb supports leading with FROM x
        Expression from = match(TokenType.FROM, false) ? parseFrom(true, false, true) : null;
        List<Expression> laterals = from != null ? iterUntilNone(this::parseLateral) : null;

        // Hive supports multi-table inserts: FROM x INSERT ... SELECT ... [INSERT ... SELECT ...]
        if (from != null && match(TokenType.INSERT, false)) {
            Expression source = from.getThis();
            setArg(source, Args.LATERALS, laterals == null || laterals.isEmpty() ? null : laterals);

            List<Expression> inserts = new ArrayList<>();
            while (match(TokenType.INSERT, false)) {
                inserts.add(parseStatement());
            }

            return expression(
                    Expression.of(ExprType.MULTITABLE_INSERTS, Args.EXPRESSIONS, inserts, Args.SOURCE, source),
                    from.comments());
        }

        Expression this_;
        if (match(TokenType.SELECT)) {
            List<String> comments = prevComments;

            Expression hint = parseHint();

            boolean all;
            boolean matchedDistinct;
            if (present(next) && !(next.type() == TokenType.DOT)) {
                all = match(TokenType.ALL);
                matchedDistinct = matchSet(d.DISTINCT_TOKENS);
            } else {
                all = false;
                matchedDistinct = false;
            }

            String kind = match(TokenType.ALIAS) && matchTexts(STRUCT_OR_VALUE) ? Py.upper(prev.text()) : null;

            Expression distinct = matchedDistinct
                    ? expression(Expression.of(ExprType.DISTINCT,
                            Args.ON, match(TokenType.ON) ? parseValue(false) : null))
                    : null;

            List<Expression> operationModifiers = new ArrayList<>();
            while (present(curr) && matchTexts(d.OPERATION_MODIFIERS)) {
                operationModifiers.add(Exp.var(Py.upper(prev.text())));
            }

            Expression limit = parseLimit(null, true);

            // Some dialects (e.g. Redshift, T-SQL) allow SELECT TOP N DISTINCT ...
            if (limit != null && !matchedDistinct && !all) {
                matchedDistinct = matchSet(d.DISTINCT_TOKENS);
                if (matchedDistinct) {
                    distinct = expression(Expression.of(ExprType.DISTINCT,
                            Args.ON, match(TokenType.ON) ? parseValue(false) : null));
                } else {
                    all = match(TokenType.ALL);
                }
            }

            if (all && distinct != null) {
                raiseError("Cannot specify both ALL and DISTINCT after SELECT");
            }

            Object[] projectionsAndExclude = parseProjections();
            Object projections = projectionsAndExclude[0];
            Object exclude = projectionsAndExclude[1];

            this_ = expression(Expression.of(ExprType.SELECT,
                    Args.KIND, kind,
                    Args.HINT, hint,
                    Args.DISTINCT, distinct,
                    Args.EXPRESSIONS, projections,
                    Args.LIMIT, limit,
                    Args.EXCLUDE, exclude,
                    Args.OPERATION_MODIFIERS, operationModifiers.isEmpty() ? null : operationModifiers));
            this_.setComments(comments);

            Expression into = parseIntoImpl();
            if (into != null) {
                this_.set(Args.INTO, into);
            }

            if (from == null) {
                from = parseFrom();
            }

            if (from != null) {
                this_.set(Args.FROM_, from);
            }

            this_ = queryModifiers(this_);
        } else if ((table || nested) && match(TokenType.L_PAREN)) {
            List<String> comments = prevComments;
            this_ = parseWrappedSelect(table);

            if (this_ != null) {
                this_.addComments(comments, true);
            }

            // We return early here so that the UNION isn't attached to the subquery by the
            // following call to _parse_set_operations, but instead becomes the parent node
            matchRParen();
            return parseSubquery(this_, parseSubqueryAlias);
        } else if (match(TokenType.VALUES, false)) {
            this_ = parseDerivedTableValues();
        } else if (from != null) {
            this_ = Exp.select("*").from(from.getThis(), false);
            this_ = queryModifiers(this_);
        } else if (match(TokenType.SUMMARIZE)) {
            boolean isTable = match(TokenType.TABLE);
            Expression summarized = parseSelect();
            if (summarized == null) {
                summarized = parseString();
            }
            if (summarized == null) {
                summarized = parseTable();
            }
            return expression(Expression.of(ExprType.SUMMARIZE, Args.THIS, summarized, Args.TABLE, isTable));
        } else if (match(TokenType.DESCRIBE)) {
            this_ = parseDescribe();
        } else {
            this_ = null;
        }

        if (laterals != null && !laterals.isEmpty() && this_ != null && this_.is(ExprType.SELECT)) {
            List<Object> all = new ArrayList<>(laterals);
            all.addAll(this_.list(Args.LATERALS));
            this_.set(Args.LATERALS, all);
        }

        return parseSetOperation ? parseSetOperations(this_) : this_;
    }

    // ------------------------------------------------------------------------------------------
    // WITH / CTE

    @Override
    protected Expression parseRecursiveWithSearch() {
        matchTextSeq("SEARCH");

        Object kind = matchTexts(d.RECURSIVE_CTE_SEARCH_KIND) ? Py.upper(prev.text()) : Boolean.FALSE;

        if (!Expression.truthy(kind)) {
            return null;
        }

        matchTextSeq("FIRST", "BY");

        return expression(Expression.of(ExprType.RECURSIVE_WITH_SEARCH,
                Args.KIND, kind,
                Args.THIS, parseIdVar(),
                Args.EXPRESSION, andThen(matchTextSeq("SET"), this::parseIdVar),
                Args.TO, andThen(matchTextSeq("TO"), this::parseBitwise),
                Args.DEFAULT, andThen(match(TokenType.DEFAULT), this::parseBitwise),
                Args.USING, andThen(matchTextSeq("USING"), this::parseIdVar)));
    }

    @Override
    protected Expression parseWith(boolean skipWithToken) {
        if (!skipWithToken && !match(TokenType.WITH)) {
            return null;
        }

        List<String> comments = prevComments;
        boolean recursive = match(TokenType.RECURSIVE);

        List<String> lastComments = null;
        List<Expression> expressions = new ArrayList<>();
        List<Expression> udfs = new ArrayList<>();
        while (true) {
            Expression cte = parseCte();
            if (cte != null) {
                if (cte.is(ExprType.FUNCTION_SPECIFICATION)) {
                    udfs.add(cte);
                } else {
                    expressions.add(cte);
                }

                if (lastComments != null && !lastComments.isEmpty()) {
                    cte.addComments(lastComments);
                }
            }

            if (!match(TokenType.COMMA) && !match(TokenType.WITH)) {
                break;
            } else {
                match(TokenType.WITH);
                recursive = match(TokenType.RECURSIVE) || recursive;
            }

            lastComments = prevComments;
        }

        Expression search = parseRecursiveWithSearch();

        // A SEARCH clause can be followed by a CYCLE clause
        Expression cycle = search != null
                && !Objects.equals(search.get(Args.KIND), "CYCLE")
                && matchTextSeq(false, "CYCLE")
                ? parseRecursiveWithSearch()
                : null;

        return expression(Expression.of(ExprType.WITH,
                Args.EXPRESSIONS, expressions,
                Args.RECURSIVE, recursive ? Boolean.TRUE : null,
                Args.SEARCH, search,
                Args.CYCLE, cycle,
                Args.UDFS, udfs.isEmpty() ? null : udfs), comments);
    }

    @Override
    protected Expression parseCte() {
        int index = tokenIndex;

        Expression alias = parseTableAlias(d.ID_VAR_TOKENS);
        if (alias == null || !alias.has(Args.THIS)) {
            raiseError("Expected CTE to have alias");
        }

        List<Expression> keyExpressions = matchTextSeq("USING", "KEY") ? parseWrappedIdVars() : null;

        if (!match(TokenType.ALIAS) && !d.OPTIONAL_ALIAS_TOKEN_CTE) {
            retreat(index);
            return null;
        }

        List<String> comments = prevComments;

        Boolean materialized;
        if (matchTextSeq("NOT", "MATERIALIZED")) {
            materialized = Boolean.FALSE;
        } else if (matchTextSeq("MATERIALIZED")) {
            materialized = Boolean.TRUE;
        } else {
            materialized = null;
        }

        Expression cte = expression(Expression.of(ExprType.CTE,
                Args.THIS, parseWrapped(this::parseStatement),
                Args.ALIAS, alias,
                Args.MATERIALIZED, materialized,
                Args.KEY_EXPRESSIONS, keyExpressions), comments);

        Expression values = cte.getThis();
        if (values != null && values.is(ExprType.VALUES)) {
            cte.set(Args.THIS, valuesToSelect(values));
        }

        return cte;
    }

    @Override
    protected Expression valuesToSelect(Expression values) {
        if (!values.alias().isEmpty()) {
            return Exp.select("*").from(values, true);
        }
        return Exp.select("*").from(Exp.tableAlias(values.copy(), "_values", null), true);
    }

    @Override
    protected Expression parseTableAlias(Set<TokenType> aliasTokens) {
        // In some dialects, LIMIT and OFFSET can act as both identifiers and keywords (clauses)
        // so this section tries to parse the clause version and if it fails, it treats the token
        // as an identifier (alias)
        if (canParseLimitOrOffset()) {
            return null;
        }

        // START is never treated as an implicit alias when followed by WITH, since that
        // would swallow the beginning of a START WITH ... CONNECT BY clause
        if (Py.upper(curr.text()).equals("START") && Py.upper(next.text()).equals("WITH")) {
            return null;
        }

        boolean anyToken = match(TokenType.ALIAS);
        Expression alias = parseIdVar(anyToken,
                aliasTokens != null && !aliasTokens.isEmpty() ? aliasTokens : d.TABLE_ALIAS_TOKENS);
        if (alias == null) {
            alias = parseStringAsIdentifier();
        }

        int index = tokenIndex;
        List<Expression> columns;
        if (match(TokenType.L_PAREN)) {
            columns = parseCsv(this::parseFunctionParameter);
            if (!columns.isEmpty()) {
                matchRParen();
            } else {
                retreat(index);
            }
        } else {
            columns = null;
        }

        if (alias == null && (columns == null || columns.isEmpty())) {
            return null;
        }

        Expression tableAlias = expression(Expression.of(ExprType.TABLE_ALIAS, Args.THIS, alias, Args.COLUMNS, columns));

        // We bubble up comments from the Identifier to the TableAlias
        if (alias != null && alias.is(ExprType.IDENTIFIER)) {
            tableAlias.addComments(alias.popComments());
        }

        return tableAlias;
    }

    @Override
    protected Expression parseSubquery(Expression this_, boolean parseAlias) {
        if (this_ == null) {
            return null;
        }

        return expression(Expression.of(ExprType.SUBQUERY,
                Args.THIS, this_,
                Args.PIVOTS, parsePivots(),
                Args.ALIAS, parseAlias ? parseTableAlias() : null,
                Args.SAMPLE, parseTableSample()));
    }

    @Override
    protected Expression implicitUnnestsToExplicit(Expression this_) {
        Set<String> refs = new HashSet<>();
        refs.add(NormalizeIdentifiers.normalizeIdentifiers(this_.expr(Args.FROM_).getThis().copy(), dialect)
                .aliasOrName());
        for (Expression join : new ArrayList<>(this_.exprs(Args.JOINS))) {
            Expression table = join.getThis();
            Expression normalizedTable = table.copy();
            normalizedTable.meta().put("maybe_column", Boolean.TRUE);
            normalizedTable = NormalizeIdentifiers.normalizeIdentifiers(normalizedTable, dialect);

            if (table.is(ExprType.TABLE) && !join.has(Args.ON)) {
                List<Expression> parts = normalizedTable.parts();
                if (parts.size() > 1 && refs.contains(parts.get(0).name())) {
                    Expression tableAsColumn = tableToColumn(table, true);
                    Expression unnest = Expression.of(ExprType.UNNEST, Args.EXPRESSIONS, List.of(tableAsColumn));

                    // Table.to_column creates a parent Alias node that we want to convert to
                    // a TableAlias and attach to the Unnest, so it matches the parser's output
                    if (Expression.isInstance(table.get(Args.ALIAS), ExprType.TABLE_ALIAS)) {
                        tableAsColumn.replace(tableAsColumn.getThis());
                        Exp.tableAlias(unnest, null, List.of(table.expr(Args.ALIAS).getThis()), false);
                    }

                    table.replace(unnest);
                }
            }

            refs.add(normalizedTable.aliasOrName());
        }

        return this_;
    }

    private boolean isModifiable(Expression e) {
        for (ExprType t : d.MODIFIABLES) {
            if (e.is(t)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Object parseQueryModifiers(Expression this_) {
        if (this_ != null && isModifiable(this_)) {
            for (Expression join : joinsOf(null)) {
                appendArg(this_, Args.JOINS, join);
            }
            Expression lateral;
            while ((lateral = parseLateral()) != null) {
                appendArg(this_, Args.LATERALS, lateral);
            }

            while (true) {
                if (matchSet(d.QUERY_MODIFIER_PARSERS, false)) {
                    Token modifierToken = curr;

                    // Defer LIMIT/FETCH after TOP until a set op is built so it applies to the whole result
                    // e.g., SELECT 1 AS x UNION ALL SELECT TOP 2 2 AS x LIMIT 1 -> limit applies to union
                    if (modifierToken.type() == TokenType.LIMIT || modifierToken.type() == TokenType.FETCH) {
                        Expression limit = this_.expr(Args.LIMIT);
                        if (limit != null && Expression.truthy(limit.metaGet("top"))) {
                            break;
                        }
                    }

                    ModifierResult result = queryModifierParser(modifierToken.type());
                    Arg key = result.key();
                    Object expression = result.value();

                    if (Expression.truthy(expression)) {
                        if (this_.has(key)) {
                            raiseError("Found multiple '" + Py.upper(modifierToken.text()) + "' clauses",
                                    modifierToken);
                        }

                        setArg(this_, key, expression);
                        if (key == Args.LIMIT) {
                            Expression limitExp = (Expression) expression;
                            Object offset = limitExp.get(Args.OFFSET);
                            setArg(limitExp, Args.OFFSET, null);

                            if (Expression.truthy(offset)) {
                                if (this_.has(Args.OFFSET)) {
                                    raiseError("Found multiple 'OFFSET' clauses", modifierToken);
                                }

                                Expression offsetExp = Expression.of(ExprType.OFFSET, Args.EXPRESSION, offset);
                                setArg(this_, Args.OFFSET, offsetExp);

                                List<Expression> limitByExpressions = limitExp.getExpressions();
                                setArg(limitExp, Args.EXPRESSIONS, null);
                                offsetExp.set(Args.EXPRESSIONS, limitByExpressions);
                            }
                        }
                        continue;
                    }
                }

                if (Py.upper(curr.text()).equals("START")) {
                    Token modifierToken = curr;
                    Expression connect = parseConnect();
                    if (connect != null) {
                        if (this_.has(Args.CONNECT)) {
                            raiseError("Found multiple 'START WITH' clauses", modifierToken);
                        }

                        setArg(this_, Args.CONNECT, connect);
                        continue;
                    }
                }
                break;
            }
        }

        if (d.SUPPORTS_IMPLICIT_UNNEST && this_ != null && this_.has(Args.FROM_)) {
            this_ = implicitUnnestsToExplicit(this_);
        }

        return this_;
    }

    // ------------------------------------------------------------------------------------------
    // Hints, INTO, FROM

    @Override
    protected Expression parseHintFallbackToString() {
        Token start = curr;
        while (present(curr)) {
            advance();
        }

        Token end = tokenList.get(tokenIndex - 1);
        return Expression.of(ExprType.HINT, Args.EXPRESSIONS, List.of(findSql(start, end)));
    }

    @Override
    protected Expression parseHintFunctionCall() {
        return parseFunctionCall();
    }

    @Override
    protected Expression parseHintBody() {
        int startIndex = tokenIndex;
        boolean shouldFallbackToString = false;

        List<Expression> hints = new ArrayList<>();
        try {
            while (true) {
                List<Expression> hint = parseCsv(() -> {
                    Expression call = parseHintFunctionCall();
                    return call != null ? call : parseVar(false, null, true);
                });
                if (hint.isEmpty()) {
                    break;
                }
                hints.addAll(hint);
            }
        } catch (ParseError e) {
            shouldFallbackToString = true;
        }

        if (shouldFallbackToString || present(curr)) {
            retreat(startIndex);
            return parseHintFallbackToString();
        }

        return expression(Expression.of(ExprType.HINT, Args.EXPRESSIONS, hints));
    }

    @Override
    protected Expression parseHint() {
        if (match(TokenType.HINT) && prevComments != null && !prevComments.isEmpty()) {
            return parseOneInto(ExprType.HINT, prevComments.get(0));
        }

        return null;
    }

    @Override
    protected Expression parseIntoImpl() {
        if (!match(TokenType.INTO)) {
            return null;
        }

        boolean temp = match(TokenType.TEMPORARY);
        boolean unlogged = matchTextSeq("UNLOGGED");
        match(TokenType.TABLE);

        return expression(Expression.of(ExprType.INTO,
                Args.THIS, parseTable(true),
                Args.TEMPORARY, temp,
                Args.UNLOGGED, unlogged));
    }

    @Override
    protected Expression parseFrom(boolean joins, boolean skipFromToken, boolean consumePipe) {
        if (!skipFromToken && !match(TokenType.FROM)) {
            return null;
        }

        List<String> comments = prevComments;
        return expression(Expression.of(ExprType.FROM,
                Args.THIS, parseTable(false, joins, null, false, false, false, consumePipe)), comments);
    }

    // ------------------------------------------------------------------------------------------
    // MATCH_RECOGNIZE, LATERAL, joins

    @Override
    protected Expression parseMatchRecognizeMeasure() {
        Object windowFrame = matchTexts(FINAL_OR_RUNNING) ? Py.upper(prev.text()) : Boolean.FALSE;
        return expression(Expression.of(ExprType.MATCH_RECOGNIZE_MEASURE,
                Args.WINDOW_FRAME, windowFrame,
                Args.THIS, parseExpression()));
    }

    @Override
    protected Expression parseMatchRecognize() {
        if (!match(TokenType.MATCH_RECOGNIZE)) {
            return null;
        }

        matchLParen();

        List<Expression> partition = parsePartitionBy();
        Expression order = parseOrder();

        List<Expression> measures = matchTextSeq("MEASURES") ? parseCsv(this::parseMatchRecognizeMeasure) : null;

        Expression rows;
        if (matchTextSeq("ONE", "ROW", "PER", "MATCH")) {
            rows = Exp.var("ONE ROW PER MATCH");
        } else if (matchTextSeq("ALL", "ROWS", "PER", "MATCH")) {
            String text = "ALL ROWS PER MATCH";
            if (matchTextSeq("SHOW", "EMPTY", "MATCHES")) {
                text += " SHOW EMPTY MATCHES";
            } else if (matchTextSeq("OMIT", "EMPTY", "MATCHES")) {
                text += " OMIT EMPTY MATCHES";
            } else if (matchTextSeq("WITH", "UNMATCHED", "ROWS")) {
                text += " WITH UNMATCHED ROWS";
            }
            rows = Exp.var(text);
        } else {
            rows = null;
        }

        Expression after;
        if (matchTextSeq("AFTER", "MATCH", "SKIP")) {
            String text = "AFTER MATCH SKIP";
            if (matchTextSeq("PAST", "LAST", "ROW")) {
                text += " PAST LAST ROW";
            } else if (matchTextSeq("TO", "NEXT", "ROW")) {
                text += " TO NEXT ROW";
            } else if (matchTextSeq("TO", "FIRST") || matchTextSeq("TO", "LAST")) {
                String direction = Py.upper(prev.text());
                Token patternVar = advanceAny();
                boolean hasPatternVar = patternVar != null && present(patternVar);
                if (!hasPatternVar) {
                    raiseError("Expecting pattern variable after AFTER MATCH SKIP TO " + direction);
                }
                text += " TO " + direction + " " + (hasPatternVar ? patternVar.text() : "");
            }
            after = Exp.var(text);
        } else {
            after = null;
        }

        Expression pattern;
        if (matchTextSeq("PATTERN")) {
            matchLParen();

            if (!present(curr)) {
                raiseError("Expecting )", curr);
            }

            int paren = 1;
            Token start = curr;
            Token end = prev;

            while (present(curr) && paren > 0) {
                if (curr.type() == TokenType.L_PAREN) {
                    paren += 1;
                }
                if (curr.type() == TokenType.R_PAREN) {
                    paren -= 1;
                }

                end = prev;
                advance();
            }

            if (paren > 0) {
                raiseError("Expecting )", curr);
            }

            pattern = Exp.var(findSql(start, end));
        } else {
            pattern = null;
        }

        List<Expression> define = matchTextSeq("DEFINE") ? parseCsv(this::parseNameAsExpression) : null;

        matchRParen();

        return expression(Expression.of(ExprType.MATCH_RECOGNIZE,
                Args.PARTITION_BY, partition,
                Args.ORDER, order,
                Args.MEASURES, measures,
                Args.ROWS, rows,
                Args.AFTER, after,
                Args.PATTERN, pattern,
                Args.DEFINE, define,
                Args.ALIAS, parseTableAlias()));
    }

    @Override
    protected Expression parseLateral() {
        Boolean crossApply = null;
        if (matchPair(TokenType.CROSS, TokenType.APPLY)) {
            crossApply = Boolean.TRUE;
        } else if (matchPair(TokenType.OUTER, TokenType.APPLY)) {
            crossApply = Boolean.FALSE;
        }

        Expression this_;
        Boolean view;
        Boolean outer;
        if (crossApply != null) {
            this_ = parseSelect(false, true);
            view = null;
            outer = null;
        } else if (match(TokenType.LATERAL)) {
            this_ = parseSelect(false, true);
            view = match(TokenType.VIEW);
            outer = match(TokenType.OUTER);
        } else {
            return null;
        }

        if (this_ == null) {
            this_ = parseUnnest();
            if (this_ == null) {
                this_ = parseFunction();
            }
            if (this_ == null) {
                this_ = parseIdVar(false);
            }

            while (match(TokenType.DOT)) {
                Expression expr = parseFunction();
                if (expr == null) {
                    expr = parseIdVar(false);
                }
                this_ = Expression.of(ExprType.DOT, Args.THIS, this_, Args.EXPRESSION, expr);
            }
        }

        Boolean ordinality = null;

        Expression tableAlias;
        if (Boolean.TRUE.equals(view)) {
            Expression table = parseIdVar(false);
            List<Expression> columns = match(TokenType.ALIAS) ? parseCsv(this::parseIdVar) : new ArrayList<>();
            tableAlias = expression(Expression.of(ExprType.TABLE_ALIAS, Args.THIS, table, Args.COLUMNS, columns));
        } else if (this_ != null && this_.is(ExprType.SUBQUERY, ExprType.UNNEST) && !this_.alias().isEmpty()) {
            // We move the alias from the lateral's child node to the lateral itself
            tableAlias = this_.expr(Args.ALIAS).pop();
        } else {
            ordinality = matchPair(TokenType.WITH, TokenType.ORDINALITY);
            tableAlias = parseTableAlias();
        }

        return expression(Expression.of(ExprType.LATERAL,
                Args.THIS, this_,
                Args.VIEW, view,
                Args.OUTER, outer,
                Args.ALIAS, tableAlias,
                Args.CROSS_APPLY, crossApply,
                Args.ORDINALITY, ordinality));
    }

    @Override
    protected Expression parseStream() {
        int index = tokenIndex;
        if (match(TokenType.STREAM)) {
            Expression this_ = tryParse(() -> parseTable());
            if (this_ != null) {
                return expression(Expression.of(ExprType.STREAM, Args.THIS, this_));
            }
            retreat(index);
        }
        return null;
    }

    @Override
    protected Object[] parseJoinParts() {
        Token method = matchSet(d.JOIN_METHODS) ? prev : null;
        Token side = matchSet(d.JOIN_SIDES) ? prev : null;
        Token kind = matchSet(d.JOIN_KINDS) ? prev : null;
        return new Object[] {method, side, kind};
    }

    private Expression parseColumnAsIdentifier() {
        Expression this_ = parseColumn();
        if (this_ != null && this_.is(ExprType.COLUMN)) {
            return this_.getThis();
        }
        return this_;
    }

    @Override
    protected List<Expression> parseUsingIdentifiers() {
        return parseWrappedCsv(this::parseColumnAsIdentifier, true);
    }

    @Override
    protected Expression parseJoin(boolean skipJoinToken, boolean parseBracket, Set<TokenType> aliasTokens) {
        if (match(TokenType.COMMA)) {
            Expression table = tryParse(() -> parseTable(false, false, aliasTokens));
            Expression crossJoin = table != null ? expression(Expression.of(ExprType.JOIN, Args.THIS, table)) : null;

            if (crossJoin != null && d.JOINS_HAVE_EQUAL_PRECEDENCE) {
                crossJoin.set(Args.KIND, "CROSS");
            }

            return crossJoin;
        }

        int index = tokenIndex;
        Object[] parts = parseJoinParts();
        Token method = (Token) parts[0];
        Token side = (Token) parts[1];
        Token kind = (Token) parts[2];
        boolean directed = matchTextSeq("DIRECTED");
        String hint = matchTexts(d.JOIN_HINTS) ? prev.text() : null;
        boolean join = match(TokenType.JOIN) || (kind != null && kind.type() == TokenType.STRAIGHT_JOIN);
        List<String> joinComments = prevComments;

        if (!skipJoinToken && !join) {
            retreat(index);
            kind = null;
            method = null;
            side = null;
        }

        boolean outerApply = matchPair(TokenType.OUTER, TokenType.APPLY, false);
        boolean crossApply = matchPair(TokenType.CROSS, TokenType.APPLY, false);

        if (!skipJoinToken && !join && !outerApply && !crossApply) {
            return null;
        }

        Map<Arg, Object> kwargs = new LinkedHashMap<>();
        kwargs.put(Args.THIS, parseTable(false, false, aliasTokens, parseBracket));
        if (kind != null && kind.type() == TokenType.ARRAY && match(TokenType.COMMA)) {
            kwargs.put(Args.EXPRESSIONS, parseCsv(() -> parseTable(false, false, aliasTokens, parseBracket)));
        }

        if (method != null) {
            kwargs.put(Args.METHOD, Py.upper(method.text()));
        }
        if (side != null) {
            kwargs.put(Args.SIDE, Py.upper(side.text()));
        }
        if (kind != null) {
            kwargs.put(Args.KIND, Py.upper(kind.text()));
        }
        if (hint != null && !hint.isEmpty()) {
            kwargs.put(Args.HINT, hint);
        }

        if (match(TokenType.MATCH_CONDITION)) {
            kwargs.put(Args.MATCH_CONDITION, parseWrapped(this::parseComparison));
        }

        Expression joinThis = (Expression) kwargs.get(Args.THIS);
        if (match(TokenType.ON)) {
            kwargs.put(Args.ON, parseDisjunction());
        } else if (match(TokenType.USING)) {
            kwargs.put(Args.USING, parseUsingIdentifiers());
        } else if (method == null
                && !(outerApply || crossApply)
                && !Expression.isInstance(joinThis, ExprType.UNNEST)
                && !(kind != null && (kind.type() == TokenType.CROSS || kind.type() == TokenType.ARRAY))) {
            index = tokenIndex;
            List<Expression> joins = new ArrayList<>();
            for (Expression j : joinsOf(aliasTokens)) {
                joins.add(j);
            }

            if (!joins.isEmpty() && match(TokenType.ON)) {
                kwargs.put(Args.ON, parseDisjunction());
            } else if (!joins.isEmpty() && match(TokenType.USING)) {
                kwargs.put(Args.USING, parseUsingIdentifiers());
            } else {
                joins = null;
                retreat(index);
            }

            setArg(joinThis, Args.JOINS, joins != null && !joins.isEmpty() ? joins : null);
        }

        kwargs.put(Args.PIVOTS, parsePivots());

        List<String> tokenComments = new ArrayList<>();
        for (Token token : new Token[] {method, side, kind}) {
            if (token != null) {
                tokenComments.addAll(token.comments());
            }
        }
        List<String> comments = new ArrayList<>(joinComments == null ? List.of() : joinComments);
        comments.addAll(tokenComments);

        Object kindValue = kwargs.get(Args.KIND);
        if (d.ADD_JOIN_ON_TRUE
                && !Expression.truthy(kwargs.get(Args.ON))
                && !Expression.truthy(kwargs.get(Args.USING))
                && !Expression.truthy(kwargs.get(Args.METHOD))
                && (kindValue == null || "INNER".equals(kindValue) || "OUTER".equals(kindValue))) {
            kwargs.put(Args.ON, Exp.bool(true));
        }

        if (directed) {
            kwargs.put(Args.DIRECTED, Boolean.TRUE);
        }

        return expression(Expression.of(ExprType.JOIN, kwargs), comments);
    }

    // ------------------------------------------------------------------------------------------
    // Indexes

    @Override
    protected Expression parseOpclass() {
        Expression this_ = parseDisjunction();

        if (matchTexts(d.OPCLASS_FOLLOW_KEYWORDS, false)) {
            return this_;
        }

        if (!matchSet(d.OPTYPE_FOLLOW_TOKENS, false)) {
            return expression(Expression.of(ExprType.OPCLASS, Args.THIS, this_, Args.EXPRESSION, parseTableParts()));
        }

        return this_;
    }

    @Override
    protected Expression parseIndexParams() {
        Expression using = match(TokenType.USING) ? parseVar(true) : null;

        List<Expression> columns;
        if (match(TokenType.L_PAREN, false)) {
            columns = parseWrappedCsv(this::parseWithOperator);
        } else {
            columns = null;
        }

        List<Expression> include = matchTextSeq("INCLUDE") ? parseWrappedIdVars() : null;
        List<Expression> partitionBy = parsePartitionBy();
        Object withStorage = andThen(match(TokenType.WITH), this::parseWrappedProperties);
        Expression tablespace = matchTextSeq("USING", "INDEX", "TABLESPACE") ? parseVar(true) : null;
        Expression where = parseWhere();

        Expression on = match(TokenType.ON) ? parseField() : null;

        return expression(Expression.of(ExprType.INDEX_PARAMETERS,
                Args.USING, using,
                Args.COLUMNS, columns,
                Args.INCLUDE, include,
                Args.PARTITION_BY, partitionBy,
                Args.WHERE, where,
                Args.WITH_STORAGE, withStorage,
                Args.TABLESPACE, tablespace,
                Args.ON, on));
    }

    @Override
    protected Expression parseIndex(Expression index, boolean anonymous) {
        Object unique;
        Object primary;
        Object amp;
        Expression table;
        if (index != null || anonymous) {
            unique = null;
            primary = null;
            amp = null;

            match(TokenType.ON);
            match(TokenType.TABLE); // hive
            table = parseTableParts(true);
        } else {
            unique = match(TokenType.UNIQUE);
            primary = matchTextSeq("PRIMARY");
            amp = matchTextSeq("AMP");

            if (!match(TokenType.INDEX)) {
                return null;
            }

            index = parseIdVar();
            table = null;
        }

        Expression params = parseIndexParams();

        return expression(Expression.of(ExprType.INDEX,
                Args.THIS, index,
                Args.TABLE, table,
                Args.UNIQUE, unique,
                Args.PRIMARY, primary,
                Args.AMP, amp,
                Args.PARAMS, params));
    }

    @Override
    protected List<Expression> parseTableHints() {
        List<Expression> hints = new ArrayList<>();
        if (matchPair(TokenType.WITH, TokenType.L_PAREN)) {
            // https://learn.microsoft.com/en-us/sql/t-sql/queries/hints-transact-sql-table?view=sql-server-ver16
            hints.add(expression(Expression.of(ExprType.WITH_TABLE_HINT,
                    Args.EXPRESSIONS, parseCsv(() -> {
                        Expression f = parseFunction();
                        return f != null ? f : parseVar(true);
                    }))));
            matchRParen();
        } else {
            // https://dev.mysql.com/doc/refman/8.0/en/index-hints.html
            while (matchSet(d.TABLE_INDEX_HINT_TOKENS)) {
                Expression hint = Expression.of(ExprType.INDEX_TABLE_HINT, Args.THIS, Py.upper(prev.text()));

                matchSet(INDEX_OR_KEY);
                if (match(TokenType.FOR)) {
                    Token any = advanceAny();
                    hint.set(Args.TARGET, any != null && present(any) ? Py.upper(prev.text()) : null);
                }

                hint.set(Args.EXPRESSIONS, parseWrappedIdVars());
                hints.add(hint);
            }
        }

        return hints.isEmpty() ? null : hints;
    }

    // ------------------------------------------------------------------------------------------
    // Tables

    @Override
    protected Expression parseTablePart(boolean schema) {
        Expression part = !schema ? parseFunction(null, false, false) : null;
        if (part == null) {
            part = parseIdVar(false);
        }
        if (part == null) {
            part = parseStringAsIdentifier();
        }
        if (part == null) {
            part = parsePlaceholder();
        }
        return part;
    }

    @Override
    protected Expression parseTablePartsFast() {
        int index = tokenIndex;
        List<Expression> parts = null;
        List<String> allComments = null;

        while (matchSet(d.IDENTIFIER_TOKENS)) {
            Token token = prev;
            List<String> comments = prevComments;

            boolean hasDot = match(TokenType.DOT);
            TokenType currTt = curr.type();

            if (!hasDot) {
                if (d.TABLE_POSTFIX_TOKENS.contains(currTt)) {
                    retreat(index);
                    return null;
                }
            } else if (!d.IDENTIFIER_TOKENS.contains(currTt)) {
                retreat(index);
                return null;
            }

            if (parts == null) {
                parts = new ArrayList<>();
            }

            if (comments != null && !comments.isEmpty()) {
                if (allComments == null) {
                    allComments = new ArrayList<>();
                }
                allComments.addAll(comments);
                prevComments = new ArrayList<>();
            }

            parts.add(expression(Expression.of(ExprType.IDENTIFIER,
                    Args.THIS, token.text(),
                    Args.QUOTED, token.type() == TokenType.IDENTIFIER), token));

            if (!hasDot) {
                break;
            }
        }

        if (parts == null) {
            return null;
        }

        int n = parts.size();

        Expression table;
        if (n == 1) {
            table = Expression.of(ExprType.TABLE, Args.THIS, parts.get(0));
        } else if (n == 2) {
            table = Expression.of(ExprType.TABLE, Args.THIS, parts.get(1), Args.DB, parts.get(0));
        } else {
            Expression this_ = parts.get(2);
            for (int i = 3; i < n; i++) {
                this_ = Expression.of(ExprType.DOT, Args.THIS, this_, Args.EXPRESSION, parts.get(i));
            }

            table = Expression.of(ExprType.TABLE, Args.THIS, this_, Args.DB, parts.get(1), Args.CATALOG, parts.get(0));
        }

        if (allComments != null && !allComments.isEmpty()) {
            table.addComments(allComments);
        }
        return table;
    }

    @Override
    protected Expression parseTableParts(boolean schema, boolean isDbReference, boolean wildcard, boolean fast) {
        if (fast) {
            return parseTablePartsFast();
        }

        Object catalog = null;
        Object db = null;
        Object table = parseTablePart(schema);

        while (match(TokenType.DOT)) {
            if (Expression.truthy(catalog)) {
                // This allows nesting the table in arbitrarily many dot expressions if needed
                table = expression(Expression.of(ExprType.DOT, Args.THIS, table, Args.EXPRESSION, parseTablePart(schema)));
            } else {
                catalog = db;
                db = table;
                // "" used for tsql FROM a..b case
                Expression part = parseTablePart(schema);
                table = part != null ? part : "";
            }
        }

        if (wildcard
                && isConnected()
                && (Expression.isInstance(table, ExprType.IDENTIFIER) || !Expression.truthy(table))
                && match(TokenType.STAR)) {
            if (table instanceof Expression id && id.is(ExprType.IDENTIFIER)) {
                id.set(Args.THIS, id.thisValue() + "*");
            } else {
                table = Expression.of(ExprType.IDENTIFIER, Args.THIS, "*");
            }
        }

        if (isDbReference) {
            catalog = db;
            db = table;
            table = null;
        }

        if (!Expression.truthy(table) && !isDbReference) {
            raiseError("Expected table name but got " + curr);
        }
        if (!Expression.truthy(db) && isDbReference) {
            raiseError("Expected database name but got " + curr);
        }

        Expression tableExp = expression(Expression.of(ExprType.TABLE, Args.THIS, table, Args.DB, db, Args.CATALOG, catalog));

        // Bubble up comments from identifier parts to the Table
        List<String> comments = new ArrayList<>();
        for (Expression part : tableExp.parts()) {
            List<String> partComments = part.popComments();
            if (partComments != null && !partComments.isEmpty()) {
                comments.addAll(partComments);
            }
        }
        if (!comments.isEmpty()) {
            tableExp.addComments(comments);
        }

        Expression changes = parseChanges();
        if (changes != null) {
            tableExp.set(Args.CHANGES, changes);
        }

        Expression atBefore = parseHistoricalData();
        if (atBefore != null) {
            tableExp.set(Args.WHEN, atBefore);
        }

        List<Expression> pivots = parsePivots();
        if (pivots != null && !pivots.isEmpty()) {
            tableExp.set(Args.PIVOTS, pivots);
        }

        return tableExp;
    }

    @Override
    protected Expression parseTable(boolean schema, boolean joins, Set<TokenType> aliasTokens, boolean parseBracket,
            boolean isDbReference, boolean parsePartition, boolean consumePipe) {
        Set<TokenType> tableAliasTokens = aliasTokens != null && !aliasTokens.isEmpty() ? aliasTokens : d.TABLE_ALIAS_TOKENS;

        if (!schema && !isDbReference && !consumePipe && !joins) {
            int index = tokenIndex;
            Expression table = parseTableParts(false, false, false, true);

            if (table != null) {
                TokenType currTt = curr.type();
                TokenType nextTt = next.type();

                Set<TokenType> fastTerminators = d.TABLE_TERMINATORS;

                // only return the table if we're sure there are no other operators
                // MATCH_CONDITION is a special case because it accepts any alias before it like LIMIT
                if (fastTerminators.contains(currTt) && nextTt != TokenType.MATCH_CONDITION) {
                    return table;
                }

                Set<TokenType> postfixTokens = d.TABLE_POSTFIX_TOKENS;

                if (!postfixTokens.contains(currTt) && !postfixTokens.contains(nextTt)) {
                    Expression alias = parseTableAlias(tableAliasTokens);
                    if (alias != null) {
                        table.set(Args.ALIAS, alias);
                    }

                    if (fastTerminators.contains(curr.type())) {
                        return table;
                    }
                }

                retreat(index);
            }
        }

        Expression stream = parseStream();
        if (stream != null) {
            return stream;
        }

        Expression lateral = parseLateral();
        if (lateral != null) {
            return lateral;
        }

        Expression unnest = parseUnnest();
        if (unnest != null) {
            return unnest;
        }

        Expression values = parseDerivedTableValues();
        if (values != null) {
            return values;
        }

        Expression subquery = parseSelect(false, true, true, true, consumePipe);
        if (subquery != null) {
            if (!subquery.has(Args.PIVOTS)) {
                setArg(subquery, Args.PIVOTS, parsePivots());
            }
            if (joins) {
                for (Expression join : joinsOf(null)) {
                    appendArg(subquery, Args.JOINS, join);
                }
            }
            return subquery;
        }

        Expression bracket = parseBracket ? parseBracket(null) : null;
        bracket = bracket != null ? expression(Expression.of(ExprType.TABLE, Args.THIS, bracket)) : null;

        List<Expression> rowsFromTables = matchTextSeq("ROWS", "FROM") ? parseWrappedCsv(this::parseTable) : null;
        Expression rowsFrom = rowsFromTables != null && !rowsFromTables.isEmpty()
                ? expression(Expression.of(ExprType.TABLE, Args.ROWS_FROM, rowsFromTables))
                : null;

        boolean only = match(TokenType.ONLY);

        Expression this_ = bracket;
        if (this_ == null) {
            this_ = rowsFrom;
        }
        if (this_ == null) {
            this_ = parseBracket(parseTableParts(schema, isDbReference));
        }

        if (only) {
            setArg(this_, Args.ONLY, Boolean.TRUE);
        }

        // Postgres supports a wildcard (table) suffix operator, which is a no-op in this context
        match(TokenType.STAR);

        parsePartition = parsePartition || d.SUPPORTS_PARTITION_SELECTION;
        if (parsePartition && match(TokenType.PARTITION, false)) {
            setArg(this_, Args.PARTITION, parsePartition());
        }

        if (schema) {
            return parseSchema(this_);
        }

        if (dd.ALIAS_POST_VERSION) {
            setArg(this_, Args.VERSION, parseVersion());
        }

        if (dd.ALIAS_POST_TABLESAMPLE) {
            setArg(this_, Args.SAMPLE, parseTableSample());
            if (this_.has(Args.SAMPLE) && dd.ALIAS_POST_VERSION && !this_.has(Args.VERSION)) {
                setArg(this_, Args.VERSION, parseVersion());
            }
        }

        Expression alias = parseTableAlias(tableAliasTokens);
        if (alias != null) {
            setArg(this_, Args.ALIAS, alias);

            // DuckDB requires the time-travel clause to come after the alias, e.g.
            // SELECT * FROM t AS a AT (VERSION => 1)
            if (this_.is(ExprType.TABLE) && !this_.has(Args.WHEN)) {
                this_.set(Args.WHEN, parseHistoricalData());
            }
        }

        if (match(TokenType.INDEXED_BY)) {
            setArg(this_, Args.INDEXED, parseTableParts());
        } else if (matchTextSeq("NOT", "INDEXED")) {
            setArg(this_, Args.INDEXED, Boolean.FALSE);
        }

        if (this_.is(ExprType.TABLE) && matchTextSeq("AT")) {
            return expression(Expression.of(ExprType.AT_INDEX,
                    Args.THIS, tableToColumn(this_, false),
                    Args.EXPRESSION, parseIdVar()));
        }

        setArg(this_, Args.HINTS, parseTableHints());

        if (!this_.has(Args.PIVOTS)) {
            setArg(this_, Args.PIVOTS, parsePivots());
        }

        if (!dd.ALIAS_POST_TABLESAMPLE) {
            setArg(this_, Args.SAMPLE, parseTableSample());
        }

        if (!dd.ALIAS_POST_VERSION) {
            setArg(this_, Args.VERSION, parseVersion());
        }

        if (joins) {
            for (Expression join : joinsOf(aliasTokens)) {
                appendArg(this_, Args.JOINS, join);
            }
        }

        if (matchPair(TokenType.WITH, TokenType.ORDINALITY)) {
            setArg(this_, Args.ORDINALITY, Boolean.TRUE);
            setArg(this_, Args.ALIAS, parseTableAlias());
        }

        // TABLE(<tvf>) is parsed into a Table wrapping exp.TableFromRows, so we
        // hoist the table args onto the latter and return it instead
        if (this_.is(ExprType.TABLE) && Expression.isInstance(this_.get(Args.THIS), ExprType.TABLE_FROM_ROWS)) {
            Expression tableFromRows = this_.getThis();
            ExprType tfr = ExprType.TABLE_FROM_ROWS;
            for (int i = 0; i < tfr.argCount(); i++) {
                Arg arg = tfr.argAt(i);
                if (arg != Args.THIS) {
                    tableFromRows.set(arg, this_.get(arg));
                }
            }

            this_ = tableFromRows;
        }

        return this_;
    }

    @Override
    protected Expression parseVersion() {
        String this_ = null;
        boolean found = false;
        for (Map.Entry<List<String>, String> entry : d.VERSION_PHRASES.entrySet()) {
            if (matchTextSeq(entry.getKey().toArray(new String[0]))) {
                this_ = entry.getValue();
                found = true;
                break;
            }
        }
        if (!found) {
            return null;
        }

        String kind;
        Expression expression;
        if (matchSet(FROM_OR_BETWEEN)) {
            kind = Py.upper(prev.text());
            Expression start = parseBitwise();
            matchTexts(TO_OR_AND);
            Expression end = parseBitwise();
            List<Expression> bounds = new ArrayList<>();
            bounds.add(start);
            bounds.add(end);
            expression = expression(Expression.of(ExprType.TUPLE, Args.EXPRESSIONS, bounds));
        } else if (matchTextSeq("CONTAINED", "IN")) {
            kind = "CONTAINED IN";
            expression = expression(Expression.of(ExprType.TUPLE,
                    Args.EXPRESSIONS, parseWrappedCsv(this::parseBitwise)));
        } else if (match(TokenType.ALL)) {
            kind = "ALL";
            expression = null;
        } else {
            matchTextSeq("AS", "OF");
            kind = "AS OF";
            expression = parseBitwise();
        }

        return expression(Expression.of(ExprType.VERSION, Args.THIS, this_, Args.EXPRESSION, expression, Args.KIND, kind));
    }

    @Override
    protected Expression parseHistoricalData() {
        // https://docs.snowflake.com/en/sql-reference/constructs/at-before
        int index = tokenIndex;
        Expression historicalData = null;
        if (matchTexts(d.HISTORICAL_DATA_PREFIX)) {
            String this_ = Py.upper(prev.text());
            Object kind = match(TokenType.L_PAREN) && matchTexts(d.HISTORICAL_DATA_KIND)
                    ? Py.upper(prev.text())
                    : Boolean.FALSE;
            Object expression = andThen(match(TokenType.FARROW), this::parseBitwise);

            if (Expression.truthy(expression)) {
                matchRParen();
                historicalData = expression(Expression.of(ExprType.HISTORICAL_DATA,
                        Args.THIS, this_, Args.KIND, kind, Args.EXPRESSION, expression));
            } else {
                retreat(index);
            }
        }

        return historicalData;
    }

    @Override
    protected Expression parseChanges() {
        if (!matchTextSeq("CHANGES", "(", "INFORMATION", "=>")) {
            return null;
        }

        Expression information = parseVar(true);
        matchRParen();

        return expression(Expression.of(ExprType.CHANGES,
                Args.INFORMATION, information,
                Args.AT_BEFORE, parseHistoricalData(),
                Args.END, parseHistoricalData()));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Expression parseUnnest(boolean withAlias) {
        if (!matchPair(TokenType.UNNEST, TokenType.L_PAREN, false)) {
            return null;
        }

        advance();

        List<Expression> expressions = parseWrappedCsv(this::parseEquality);
        Object offset = matchPair(TokenType.WITH, TokenType.ORDINALITY);

        Expression alias = withAlias ? parseTableAlias() : null;

        if (alias != null) {
            if (dd.UNNEST_COLUMN_ONLY) {
                if (alias.has(Args.COLUMNS)) {
                    raiseError("Unexpected extra column alias in unnest.");
                }

                List<Object> cols = new ArrayList<>();
                cols.add(alias.get(Args.THIS));
                alias.set(Args.COLUMNS, cols);
                alias.set(Args.THIS, null);
            }

            Object columns = alias.get(Args.COLUMNS);
            if (Expression.truthy(offset) && columns instanceof List<?> cl && expressions.size() < cl.size()) {
                offset = ((List<Object>) cl).remove(cl.size() - 1);
            }
        }

        if (!Expression.truthy(offset) && matchPair(TokenType.WITH, TokenType.OFFSET)) {
            match(TokenType.ALIAS);
            Expression id = parseIdVar(false, d.UNNEST_OFFSET_ALIAS_TOKENS);
            offset = id != null ? id : Exp.toIdentifier("offset");
        }

        return expression(Expression.of(ExprType.UNNEST,
                Args.EXPRESSIONS, expressions, Args.ALIAS, alias, Args.OFFSET, offset));
    }

    @Override
    protected Expression parseDerivedTableValues(boolean allowValueSynonym) {
        boolean isDerived = matchPair(TokenType.L_PAREN, TokenType.VALUES);
        if (!isDerived && !(
                // ClickHouse's `FORMAT Values` is equivalent to `VALUES`
                matchTextSeq("VALUES")
                || matchTextSeq("FORMAT", "VALUES")
                // MySQL accepts VALUE as a synonym for VALUES in INSERT statements.
                || (allowValueSynonym && matchTextSeq("VALUE")))) {
            return null;
        }

        List<Expression> expressions = parseCsv(this::parseValue);
        Expression alias = parseTableAlias();

        if (isDerived) {
            matchRParen();
        }

        if (alias == null) {
            alias = parseTableAlias();
        }
        return expression(Expression.of(ExprType.VALUES, Args.EXPRESSIONS, expressions, Args.ALIAS, alias));
    }

    @Override
    protected Expression parseTableSample(boolean asModifier) {
        if (!match(TokenType.TABLE_SAMPLE) && !(asModifier && matchTextSeq("USING", "SAMPLE"))) {
            return null;
        }

        Expression bucketNumerator = null;
        Expression bucketDenominator = null;
        Expression bucketField = null;
        Expression percent = null;
        Expression size = null;
        Object seed = null;

        Expression method = parseVar(false, ROW_TOKEN, true);
        boolean matchedLParen = match(TokenType.L_PAREN);

        Expression num;
        List<Expression> expressions;
        if (d.TABLESAMPLE_CSV) {
            num = null;
            expressions = parseCsv(this::parsePrimary);
        } else {
            expressions = null;
            if (match(TokenType.NUMBER, false)) {
                num = parseFactor(false);
            } else {
                num = parsePrimary();
                if (num == null) {
                    num = parsePlaceholder();
                }
            }
        }

        if (matchTextSeq("BUCKET")) {
            bucketNumerator = parseNumber();
            matchTextSeq("OUT", "OF");
            bucketDenominator = parseNumber();
            match(TokenType.ON);
            bucketField = parseField();
        } else if (matchSet(PERCENT_OR_MOD)) {
            percent = num;
        } else if (match(TokenType.ROWS) || !dd.TABLESAMPLE_SIZE_IS_PERCENT) {
            size = num;
        } else {
            percent = num;
        }

        if (matchedLParen) {
            matchRParen();
        }

        if (match(TokenType.L_PAREN)) {
            method = parseVar(false, null, true);
            seed = andThen(match(TokenType.COMMA), this::parseNumber);
            matchRParen();
        } else if (matchTexts(SEED_OR_REPEATABLE)) {
            seed = parseWrapped(this::parseNumber);
        }

        if (method == null && d.DEFAULT_SAMPLING_METHOD != null && !d.DEFAULT_SAMPLING_METHOD.isEmpty()) {
            method = Exp.var(d.DEFAULT_SAMPLING_METHOD);
        }

        return expression(Expression.of(ExprType.TABLE_SAMPLE,
                Args.EXPRESSIONS, expressions,
                Args.METHOD, method,
                Args.BUCKET_NUMERATOR, bucketNumerator,
                Args.BUCKET_DENOMINATOR, bucketDenominator,
                Args.BUCKET_FIELD, bucketField,
                Args.PERCENT, percent,
                Args.SIZE, size,
                Args.SEED, seed));
    }

    // ------------------------------------------------------------------------------------------
    // Pivots and joins

    @Override
    protected List<Expression> parsePivots() {
        if (!PIVOT_TOKENS.contains(curr.type())) {
            return null;
        }
        List<Expression> pivots = iterUntilNone(this::parsePivot);
        return pivots.isEmpty() ? null : pivots;
    }

    /**
     * {@code iter(lambda: self._parse_join(alias_tokens=alias_tokens), None)}: returns a lazy,
     * single-use {@code Iterable<Expression>} that parses one join per step.
     */
    @Override
    protected Object parseJoins(Set<TokenType> aliasTokens) {
        Iterator<Expression> it = new Iterator<>() {
            private Expression pending;
            private boolean done;

            @Override
            public boolean hasNext() {
                if (done) {
                    return false;
                }
                if (pending == null) {
                    pending = parseJoin(false, false, aliasTokens);
                    if (pending == null) {
                        done = true;
                        return false;
                    }
                }
                return true;
            }

            @Override
            public Expression next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Expression e = pending;
                pending = null;
                return e;
            }
        };
        return (Iterable<Expression>) () -> it;
    }

    @Override
    protected Expression parseUnpivotColumns() {
        if (!match(TokenType.INTO)) {
            return null;
        }

        return expression(Expression.of(ExprType.UNPIVOT_COLUMNS,
                Args.THIS, andThen(matchTextSeq("NAME"), this::parseColumn),
                Args.EXPRESSIONS, andThen(matchTextSeq("VALUE"), () -> parseCsv(this::parseColumn))));
    }

    private Expression parseSimplifiedPivotOn() {
        Expression this_ = parseBitwise();

        if (match(TokenType.IN)) {
            // PIVOT ... ON col IN (row_val1, row_val2)
            return parseIn(this_);
        }
        if (match(TokenType.ALIAS, false)) {
            // UNPIVOT ... ON (col1, col2, col3) AS row_val
            return parseAlias(this_);
        }

        return this_;
    }

    // https://duckdb.org/docs/sql/statements/pivot
    @Override
    protected Expression parseSimplifiedPivot(Boolean isUnpivot) {
        Expression this_ = parseTable();
        Object expressions = andThen(match(TokenType.ON), () -> parseCsv(this::parseSimplifiedPivotOn));
        Expression into = parseUnpivotColumns();
        Object using = andThen(match(TokenType.USING), () -> parseCsv(() -> parseAlias(parseColumn())));
        Expression group = parseGroup();

        return expression(Expression.of(ExprType.PIVOT,
                Args.THIS, this_,
                Args.EXPRESSIONS, expressions,
                Args.USING, using,
                Args.GROUP, group,
                Args.UNPIVOT, isUnpivot,
                Args.INTO, into));
    }

    private Expression parsePivotAliasedExpression() {
        Expression this_ = parseSelectOrExpression();

        match(TokenType.ALIAS);
        Expression alias = parseBitwise();
        if (alias != null) {
            if (alias.is(ExprType.COLUMN) && alias.text(Args.DB).isEmpty()) {
                alias = alias.getThis();
            }
            return expression(Expression.of(ExprType.PIVOT_ALIAS, Args.THIS, this_, Args.ALIAS, alias));
        }

        return this_;
    }

    @Override
    protected Expression parsePivotIn() {
        Expression value = parseColumn();

        if (!match(TokenType.IN)) {
            raiseError("Expecting IN");
        }

        if (match(TokenType.L_PAREN)) {
            List<Expression> exprs;
            if (match(TokenType.ANY)) {
                exprs = new ArrayList<>();
                exprs.add(Expression.of(ExprType.PIVOT_ANY, Args.THIS, parseOrder()));
            } else {
                exprs = parseCsv(this::parsePivotAliasedExpression);
            }
            matchRParen();
            return expression(Expression.of(ExprType.IN, Args.THIS, value, Args.EXPRESSIONS, exprs));
        }

        return expression(Expression.of(ExprType.IN, Args.THIS, value, Args.FIELD, parseIdVar()));
    }

    @Override
    protected Expression parsePivotAggregation() {
        Expression func = parseFunction();
        if (func == null) {
            if (prev.type() == TokenType.COMMA) {
                return null;
            }
            raiseError("Expecting an aggregation function in PIVOT");
        }

        return parseAlias(func);
    }

    @Override
    protected Expression parsePivot() {
        int index = tokenIndex;
        Boolean includeNulls = null;

        boolean unpivot;
        if (match(TokenType.PIVOT)) {
            unpivot = false;
        } else if (match(TokenType.UNPIVOT)) {
            unpivot = true;

            // https://docs.databricks.com/en/sql/language-manual/sql-ref-syntax-qry-select-unpivot.html#syntax
            if (matchTextSeq("INCLUDE", "NULLS")) {
                includeNulls = Boolean.TRUE;
            } else if (matchTextSeq("EXCLUDE", "NULLS")) {
                includeNulls = Boolean.FALSE;
            }
        } else {
            return null;
        }

        List<Expression> expressions;

        if (!match(TokenType.L_PAREN)) {
            retreat(index);
            return null;
        }

        if (unpivot) {
            expressions = parseCsv(this::parseColumn);
        } else {
            expressions = parseCsv(this::parsePivotAggregation);
        }

        if (expressions.isEmpty()) {
            raiseError("Failed to parse PIVOT's aggregation list");
        }

        if (!match(TokenType.FOR)) {
            raiseError("Expecting FOR");
        }

        List<Expression> fields = new ArrayList<>();
        while (true) {
            Expression field = tryParse(this::parsePivotIn);
            if (field == null) {
                break;
            }
            fields.add(field);
        }

        Object defaultOnNull = andThen(matchTextSeq("DEFAULT", "ON", "NULL"), () -> parseWrapped(this::parseBitwise));

        Expression group = parseGroup();

        matchRParen();

        Expression pivot = expression(Expression.of(ExprType.PIVOT,
                Args.EXPRESSIONS, expressions,
                Args.FIELDS, fields,
                Args.UNPIVOT, unpivot,
                Args.INCLUDE_NULLS, includeNulls,
                Args.DEFAULT_ON_NULL, defaultOnNull,
                Args.GROUP, group));

        if (unpivot) {
            List<Expression> targets = new ArrayList<>();
            for (Expression e : pivot.getExpressions()) {
                targets.add(unpivotTarget(e));
            }
            pivot.set(Args.EXPRESSIONS, targets);
            for (Expression pivotField : pivot.exprs(Args.FIELDS)) {
                if (pivotField.is(ExprType.IN)) {
                    pivotField.set(Args.THIS, unpivotTarget(pivotField.getThis()));
                }
            }

            pivot.set(Args.VALUE_COLUMNS_FIRST, d.UNPIVOT_VALUE_COLUMNS_FIRST);
        }

        if (!matchSet(PIVOT_TOKENS, false)) {
            pivot.set(Args.ALIAS, parseTableAlias());
        }

        if (!unpivot) {
            Collection<String> names = pivotColumnNames(expressions);

            List<Expression> columns = new ArrayList<>();
            List<List<String>> allFields = new ArrayList<>();
            for (Expression pivotField : pivot.exprs(Args.FIELDS)) {
                List<Expression> pivotFieldExpressions = pivotField.getExpressions();

                // The `PivotAny` expression corresponds to `ANY ORDER BY <column>`; we can't infer in this case.
                Expression first = Py.seqGet(pivotFieldExpressions, 0);
                if (first != null && first.is(ExprType.PIVOT_ANY)) {
                    continue;
                }

                List<String> fieldNames = new ArrayList<>();
                for (Expression fld : pivotFieldExpressions) {
                    // An explicit `<field> AS <alias>` names the output column directly,
                    // so it wins over the dialect's string-identifying convention
                    fieldNames.add(d.IDENTIFY_PIVOT_STRINGS && !fld.is(ExprType.PIVOT_ALIAS)
                            ? fld.sql(Dialects.base())
                            : fld.aliasOrName());
                }
                allFields.add(fieldNames);
            }

            if (!allFields.isEmpty()) {
                if (!names.isEmpty()) {
                    allFields.add(new ArrayList<>(names));
                }

                // Generate all possible combinations of the pivot columns
                // e.g PIVOT(sum(...) as total FOR year IN (2000, 2010) FOR country IN ('NL', 'US'))
                // generates the product between [[2000, 2010], ['NL', 'US'], ['total']]
                for (List<String> fldParts : product(allFields)) {
                    if (!names.isEmpty() && d.PREFIXED_PIVOT_COLUMNS) {
                        // Move the "name" to the front of the list
                        fldParts.add(0, fldParts.remove(fldParts.size() - 1));
                    }

                    columns.add(Exp.toIdentifier(String.join("_", fldParts)));
                }
            }

            pivot.set(Args.COLUMNS, columns);
            pivot.set(Args.IDENTIFY_PIVOT_STRINGS, d.IDENTIFY_PIVOT_STRINGS);
            pivot.set(Args.PREFIXED_PIVOT_COLUMNS, d.PREFIXED_PIVOT_COLUMNS);
            pivot.set(Args.PIVOT_COLUMN_NAMING, d.PIVOT_COLUMN_NAMING);
        }

        return pivot;
    }

    @Override
    protected Collection<String> pivotColumnNames(List<Expression> aggregations) {
        List<String> names = new ArrayList<>();
        for (Expression agg : aggregations) {
            String alias = agg.alias();
            if (alias != null && !alias.isEmpty()) {
                names.add(alias);
            }
        }
        return names;
    }

    // ------------------------------------------------------------------------------------------
    // WHERE / GROUP BY / HAVING / QUALIFY / CONNECT BY

    @Override
    protected Expression parsePrewhere(boolean skipWhereToken) {
        if (!skipWhereToken && !match(TokenType.PREWHERE)) {
            return null;
        }

        List<String> comments = prevComments;
        return expression(Expression.of(ExprType.PRE_WHERE, Args.THIS, parseDisjunction()), comments);
    }

    @Override
    protected Expression parseWhere(boolean skipWhereToken) {
        if (!skipWhereToken && !match(TokenType.WHERE)) {
            return null;
        }

        List<String> comments = prevComments;
        return expression(Expression.of(ExprType.WHERE, Args.THIS, parseDisjunction()), comments);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> elementList(Map<Arg, Object> elements, Arg key) {
        return (List<Object>) elements.computeIfAbsent(key, k -> new ArrayList<>());
    }

    @Override
    protected Expression parseGroup(boolean skipGroupByToken) {
        if (!skipGroupByToken && !match(TokenType.GROUP_BY)) {
            return null;
        }
        List<String> comments = prevComments;

        Map<Arg, Object> elements = new LinkedHashMap<>();

        if (match(TokenType.ALL)) {
            elements.put(Args.ALL, Boolean.TRUE);
        } else if (match(TokenType.DISTINCT)) {
            elements.put(Args.ALL, Boolean.FALSE);
        }

        while (true) {
            // Stop before consuming modifier tokens like LIMIT, OFFSET and WINDOW,
            // which are also valid identifiers
            if (matchSet(d.QUERY_MODIFIER_TOKENS, false)) {
                break;
            }

            elementList(elements, Args.EXPRESSIONS).addAll(parseCsv(() -> {
                Expression e = parseGroupingSets();
                if (e == null) {
                    e = parseCubeOrRollup();
                }
                if (e == null) {
                    e = parseDisjunction();
                }
                return e;
            }));

            int beforeWithIndex = tokenIndex;

            Expression cubeOrRollup = null;
            Expression groupingSets = null;
            if (match(TokenType.WITH) && (cubeOrRollup = parseCubeOrRollup(true)) != null) {
                Arg key = cubeOrRollup.is(ExprType.ROLLUP) ? Args.ROLLUP : Args.CUBE;
                elementList(elements, key).add(cubeOrRollup);
            } else if ((groupingSets = parseGroupingSets()) != null) {
                // Hive-style suffix syntax: GROUP BY a, b GROUPING SETS (...)
                elementList(elements, Args.GROUPING_SETS).add(groupingSets);
                break;
            } else if (matchTextSeq("TOTALS")) {
                elements.put(Args.TOTALS, Boolean.TRUE);
            }

            if (beforeWithIndex <= tokenIndex && tokenIndex <= beforeWithIndex + 1) {
                retreat(beforeWithIndex);
                break;
            }
        }

        return expression(Expression.of(ExprType.GROUP, elements), comments);
    }

    @Override
    protected Expression parseCubeOrRollup(boolean withPrefix) {
        ExprType kind;
        if (match(TokenType.CUBE)) {
            kind = ExprType.CUBE;
        } else if (match(TokenType.ROLLUP)) {
            kind = ExprType.ROLLUP;
        } else {
            return null;
        }

        return expression(Expression.of(kind,
                Args.EXPRESSIONS, withPrefix ? new ArrayList<>() : parseWrappedCsv(this::parseBitwise)));
    }

    @Override
    protected Expression parseGroupingSets() {
        if (match(TokenType.GROUPING_SETS)) {
            return expression(Expression.of(ExprType.GROUPING_SETS,
                    Args.EXPRESSIONS, parseWrappedCsv(this::parseGroupingSet)));
        }
        return null;
    }

    @Override
    protected Expression parseGroupingSet() {
        Expression e = parseGroupingSets();
        if (e == null) {
            e = parseCubeOrRollup();
        }
        if (e == null) {
            e = parseBitwise();
        }
        return e;
    }

    @Override
    protected Expression parseHaving(boolean skipHavingToken) {
        if (!skipHavingToken && !match(TokenType.HAVING)) {
            return null;
        }
        List<String> comments = prevComments;
        return expression(Expression.of(ExprType.HAVING, Args.THIS, parseDisjunction()), comments);
    }

    @Override
    protected Expression parseQualify() {
        if (!match(TokenType.QUALIFY)) {
            return null;
        }
        return expression(Expression.of(ExprType.QUALIFY, Args.THIS, parseDisjunction()));
    }

    /**
     * The temporary {@code NO_PAREN_FUNCTION_PARSERS["PRIOR"]} entry installed by
     * {@code _parse_connect_with_prior}.
     */
    protected Expression parsePriorNoParenFunction() {
        return expression(Expression.of(ExprType.PRIOR, Args.THIS, parseBitwise()));
    }

    /** Whether {@code NO_PAREN_FUNCTION_PARSERS} currently has {@code key} (including the temporary PRIOR). */
    protected boolean hasNoParenFunctionParser(String key) {
        return d.NO_PAREN_FUNCTION_PARSERS.contains(key) || (priorParserActive && "PRIOR".equals(key));
    }

    @Override
    protected Expression parseConnectWithPrior() {
        priorParserActive = true;
        Expression connect = parseDisjunction();
        priorParserActive = false;
        return connect;
    }

    @Override
    protected Expression parseConnect(boolean skipStartToken) {
        Expression start;
        if (skipStartToken) {
            start = null;
        } else if (matchTextSeq("START", "WITH")) {
            start = parseDisjunction();
        } else {
            return null;
        }

        match(TokenType.CONNECT_BY);
        boolean nocycle = matchTextSeq("NOCYCLE");
        Expression connect = parseConnectWithPrior();

        if (start == null && matchTextSeq("START", "WITH")) {
            start = parseDisjunction();
        }

        return expression(Expression.of(ExprType.CONNECT,
                Args.START, start, Args.CONNECT, connect, Args.NOCYCLE, nocycle));
    }

    @Override
    protected Expression parseNameAsExpression() {
        Expression this_ = parseIdVar(true);
        if (match(TokenType.ALIAS)) {
            this_ = expression(Expression.of(ExprType.ALIAS, Args.ALIAS, this_, Args.THIS, parseDisjunction()));
        }
        return this_;
    }

    @Override
    protected List<Expression> parseInterpolate() {
        if (matchTextSeq("INTERPOLATE")) {
            return parseWrappedCsv(this::parseNameAsExpression);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // ORDER BY / LIMIT / OFFSET / locks

    @Override
    protected Expression parseOrder(Expression this_, boolean skipOrderToken) {
        Boolean siblings = null;
        if (!skipOrderToken && !match(TokenType.ORDER_BY)) {
            if (!match(TokenType.ORDER_SIBLINGS_BY)) {
                return this_;
            }

            siblings = Boolean.TRUE;
        }

        List<String> comments = prevComments;
        return expression(Expression.of(ExprType.ORDER,
                Args.THIS, this_,
                Args.EXPRESSIONS, parseCsv(this::parseOrdered),
                Args.SIBLINGS, siblings), comments);
    }

    @Override
    protected Expression parseSort(ExprType expClass, TokenType token) {
        if (!match(token)) {
            return null;
        }
        return expression(Expression.of(expClass, Args.EXPRESSIONS, parseCsv(this::parseOrdered)));
    }

    @Override
    protected Expression parseOrdered(ParseFn parseMethod) {
        Expression this_ = parseMethod != null ? parseMethod.parse() : parseDisjunction();
        if (this_ == null) {
            return null;
        }

        if (Py.upper(this_.name()).equals("ALL") && dd.SUPPORTS_ORDER_BY_ALL) {
            this_ = Exp.var("ALL");
        }

        boolean asc = match(TokenType.ASC);
        Boolean desc = match(TokenType.DESC) ? Boolean.TRUE : (asc ? Boolean.FALSE : null);
        boolean isDesc = Boolean.TRUE.equals(desc);

        boolean isNullsFirst = matchTextSeq("NULLS", "FIRST");
        boolean isNullsLast = matchTextSeq("NULLS", "LAST");

        boolean nullsFirst = isNullsFirst;
        boolean explicitlyNullOrdered = isNullsFirst || isNullsLast;

        String nullOrdering = dd.NULL_ORDERING;
        if (!explicitlyNullOrdered
                && ((!isDesc && "nulls_are_small".equals(nullOrdering))
                        || (isDesc && !"nulls_are_small".equals(nullOrdering)))
                && !"nulls_are_last".equals(nullOrdering)) {
            nullsFirst = true;
        }

        Expression withFill;
        if (matchTextSeq("WITH", "FILL")) {
            withFill = expression(Expression.of(ExprType.WITH_FILL,
                    Args.FROM_, andThen(match(TokenType.FROM), this::parseBitwise),
                    Args.TO, andThen(matchTextSeq("TO"), this::parseBitwise),
                    Args.STEP, andThen(matchTextSeq("STEP"), this::parseBitwise),
                    Args.INTERPOLATE, parseInterpolate()));
        } else {
            withFill = null;
        }

        return expression(Expression.of(ExprType.ORDERED,
                Args.THIS, this_, Args.DESC, desc, Args.NULLS_FIRST, nullsFirst, Args.WITH_FILL, withFill));
    }

    @Override
    protected Expression parseLimitOptions() {
        boolean percent = matchSet(PERCENT_OR_MOD);
        boolean rows = matchTexts(ROW_OR_ROWS);
        matchTextSeq("ONLY");
        boolean withTies = matchTextSeq("WITH", "TIES");

        if (!(percent || rows || withTies)) {
            return null;
        }

        return expression(Expression.of(ExprType.LIMIT_OPTIONS,
                Args.PERCENT, percent, Args.ROWS, rows, Args.WITH_TIES, withTies));
    }

    @Override
    protected Expression parseLimit(Expression this_, boolean top, boolean skipLimitToken) {
        if (skipLimitToken || match(top ? TokenType.TOP : TokenType.LIMIT)) {
            List<String> comments = prevComments;
            Expression expression;
            if (top) {
                boolean limitParen = match(TokenType.L_PAREN);
                if (limitParen) {
                    expression = parseTerm();
                    if (expression == null) {
                        expression = parseSelect();
                    }
                } else {
                    expression = parseNumber();
                }

                if (limitParen) {
                    matchRParen();
                }
            } else {
                if (dd.SUPPORTS_LIMIT_ALL && match(TokenType.ALL)) {
                    expression = Exp.var("ALL");
                } else {
                    expression = parseTerm(false);
                }
            }
            Expression limitOptions = parseLimitOptions();

            Expression offset;
            if (match(TokenType.COMMA)) {
                offset = expression;
                expression = parseTerm();
            } else {
                offset = null;
            }

            Expression limitExp = expression(Expression.of(ExprType.LIMIT,
                    Args.THIS, this_,
                    Args.EXPRESSION, expression,
                    Args.OFFSET, offset,
                    Args.LIMIT_OPTIONS, limitOptions,
                    Args.EXPRESSIONS, parseLimitBy()), comments);

            if (top) {
                limitExp.meta().put("top", Boolean.TRUE);
            }

            return limitExp;
        }

        if (match(TokenType.FETCH)) {
            String direction = matchSet(FIRST_OR_NEXT) ? Py.upper(prev.text()) : "FIRST";

            Expression count = matchTexts(ROW_OR_ROWS, false) ? null : parseField(false, d.FETCH_TOKENS);

            return expression(Expression.of(ExprType.FETCH,
                    Args.DIRECTION, direction, Args.COUNT, count, Args.LIMIT_OPTIONS, parseLimitOptions()));
        }

        return this_;
    }

    @Override
    protected Expression parseOffset(Expression this_) {
        if (!match(TokenType.OFFSET)) {
            return this_;
        }

        Expression count = parseTerm();
        matchTexts(ROW_OR_ROWS);

        return expression(Expression.of(ExprType.OFFSET,
                Args.THIS, this_, Args.EXPRESSION, count, Args.EXPRESSIONS, parseLimitBy()));
    }

    @Override
    protected boolean canParseLimitOrOffset() {
        if (!matchSet(d.AMBIGUOUS_ALIAS_TOKENS, false)) {
            return false;
        }

        int index = tokenIndex;
        boolean result = tryParse(() -> parseLimit(), true) != null
                || tryParse(() -> parseOffset(), true) != null;
        retreat(index);

        // MATCH_CONDITION (...) is a special construct that should not be consumed by limit/offset
        if (next.type() == TokenType.MATCH_CONDITION) {
            result = false;
        }

        return result;
    }

    @Override
    protected boolean canParseNamedWindow() {
        // `WINDOW` is in ID_VAR_TOKENS so it could be mistakenly consumed as an implicit alias.
        // Refuse only when the following tokens look like a named-window clause: `WINDOW <id> AS (`.
        if (!match(TokenType.WINDOW, false)) {
            return false;
        }

        int size = tokenList.size();
        Token name = tokenIndex + 1 < size ? tokenList.get(tokenIndex + 1) : null;
        if (name == null || !d.ID_VAR_TOKENS.contains(name.type())) {
            return false;
        }

        Token aliasTok = tokenIndex + 2 < size ? tokenList.get(tokenIndex + 2) : null;
        if (aliasTok == null || aliasTok.type() != TokenType.ALIAS) {
            return false;
        }

        Token body = tokenIndex + 3 < size ? tokenList.get(tokenIndex + 3) : null;
        return body != null && body.type() == TokenType.L_PAREN;
    }

    @Override
    protected List<Expression> parseLimitBy() {
        return matchTextSeq("BY") ? parseCsv(this::parseBitwise) : null;
    }

    @Override
    protected List<Expression> parseLocks() {
        List<Expression> locks = new ArrayList<>();
        while (true) {
            Boolean update = null;
            Boolean key = null;
            if (matchTextSeq("FOR", "UPDATE")) {
                update = Boolean.TRUE;
            } else if (matchTextSeq("FOR", "SHARE") || matchTextSeq("LOCK", "IN", "SHARE", "MODE")) {
                update = Boolean.FALSE;
            } else if (matchTextSeq("FOR", "KEY", "SHARE")) {
                update = Boolean.FALSE;
                key = Boolean.TRUE;
            } else if (matchTextSeq("FOR", "NO", "KEY", "UPDATE")) {
                update = Boolean.TRUE;
                key = Boolean.TRUE;
            } else {
                break;
            }

            List<Expression> expressions = null;
            if (matchTextSeq("OF")) {
                expressions = parseCsv(() -> parseTable(true));
            }

            Object wait = null;
            if (matchTextSeq("NOWAIT")) {
                wait = Boolean.TRUE;
            } else if (matchTextSeq("WAIT")) {
                wait = parsePrimary();
            } else if (matchTextSeq("SKIP", "LOCKED")) {
                wait = Boolean.FALSE;
            }

            locks.add(expression(Expression.of(ExprType.LOCK,
                    Args.UPDATE, update, Args.EXPRESSIONS, expressions, Args.WAIT, wait, Args.KEY, key)));
        }

        return locks;
    }

    // sqlglot.expressions.INTERVAL_STRING_RE
    private static final Pattern INTERVAL_STRING_RE = Pattern.compile(
            "\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]+)\\s*", Pattern.UNICODE_CHARACTER_CLASS);
    // sqlglot.expressions.INTERVAL_DAY_TIME_RE
    private static final Pattern INTERVAL_DAY_TIME_RE = Pattern.compile(
            "\\s*-?\\s*\\d+(?:\\.\\d+)?\\s+(?:-?(?:\\d+:)?\\d+:\\d+(?:\\.\\d+)?|-?(?:\\d+:){1,2}|:)\\s*",
            Pattern.UNICODE_CHARACTER_CLASS);

    private static final String SQLGLOT_ANONYMOUS = "sqlglot.anonymous";

    private static final Set<TokenType> L_PAREN_OR_L_BRACKET = EnumSet.of(TokenType.L_PAREN, TokenType.L_BRACKET);
    private static final Set<TokenType> L_BRACKET_OR_L_PAREN = L_PAREN_OR_L_BRACKET;
    private static final Set<TokenType> R_BRACKET_OR_R_PAREN = EnumSet.of(TokenType.R_BRACKET, TokenType.R_PAREN);
    private static final Set<TokenType> STRING_OR_NUMBER = EnumSet.of(TokenType.STRING, TokenType.NUMBER);
    private static final Set<TokenType> VAR_ONLY = EnumSet.of(TokenType.VAR);
    private static final Set<TokenType> VAR_OR_ANY = EnumSet.of(TokenType.VAR, TokenType.ANY);
    private static final Set<TokenType> SELECT_ONLY = EnumSet.of(TokenType.SELECT);
    private static final Set<TokenType> IN_OR_OUT = EnumSet.of(TokenType.IN, TokenType.OUT);
    private static final Set<TokenType> ASC_OR_DESC = EnumSet.of(TokenType.ASC, TokenType.DESC);
    private static final Set<TokenType> DELETE_OR_UPDATE = EnumSet.of(TokenType.DELETE, TokenType.UPDATE);
    private static final Set<TokenType> NULL_OR_DEFAULT = EnumSet.of(TokenType.NULL, TokenType.DEFAULT);
    private static final Set<String> ON_OR_BY = Set.of("ON", "BY");
    private static final Set<String> ALIAS_OR_MATERIALIZED = Set.of("ALIAS", "MATERIALIZED");
    private static final Set<String> STORED_OR_VIRTUAL = Set.of("STORED", "VIRTUAL");
    private static final Set<String> FIRST_OR_AFTER = Set.of("FIRST", "AFTER");
    private static final Set<String> KEY_OR_INDEX = Set.of("KEY", "INDEX");
    private static final String[] POSITION_META_KEYS = {"line", "col", "start", "end"};

    // ------------------------------------------------------------------------------------------
    // Private helpers

    /** {@code t in s}, null-safe (immutable sets reject {@code contains(null)}). */
    private static boolean in(Set<TokenType> s, TokenType t) {
        return t != null && s.contains(t);
    }

    /**
     * {@code self._match_set(TABLE)} for a binary-operator table: advances and returns the node type
     * when the current token is a key, else null (allocation-free).
     */
    private ExprType matchBinary(Map<TokenType, ExprType> table) {
        if (table.isEmpty()) {
            return null;
        }
        ExprType t = table.get(curr.type());
        if (t != null) {
            advance();
        }
        return t;
    }

    /** {@code expr.update_positions(token)}. */
    private static Expression updatePositions(Expression e, Token token) {
        Map<String, Object> meta = e.meta();
        meta.put("line", token.line());
        meta.put("col", token.col());
        meta.put("start", token.start());
        meta.put("end", token.end());
        return e;
    }

    /** {@code expr.update_positions(other_expr)}. */
    private static Expression updatePositions(Expression e, Expression other) {
        if (other != null && other.hasMeta()) {
            Map<String, Object> otherMeta = other.meta();
            Map<String, Object> meta = e.meta();
            for (String k : POSITION_META_KEYS) {
                if (otherMeta.containsKey(k)) {
                    meta.put(k, otherMeta.get(k));
                }
            }
        }
        return e;
    }

    /** {@code exp.DataType.from_str(dtype, dialect=self.dialect, udt=udt)}. */
    private Expression dataTypeFromStr(String dtype, boolean udt) {
        return DataTypes.build(dtype, dialect, udt, false);
    }

    /** Python's {@code str.isalpha()}. */
    private static boolean isAlpha(String s) {
        if (s.isEmpty()) {
            return false;
        }
        return s.codePoints().allMatch(Character::isLetter);
    }


    private static boolean isQuoted(Expression e) {
        return e != null && e.has(Args.QUOTED);
    }

    // ------------------------------------------------------------------------------------------
    // Set operations

    @Override
    protected Expression parseSetOperation(Expression this_, boolean consumePipe) {
        int start = tokenIndex;
        Object[] joinParts = parseJoinParts();
        Object sideToken = joinParts == null ? null : joinParts[1];
        Object kindToken = joinParts == null ? null : joinParts[2];

        String side = sideToken instanceof Token st && present(st) ? st.text() : null;
        String kind = kindToken instanceof Token kt && present(kt) ? kt.text() : null;

        if (!matchSet(d.SET_OPERATIONS)) {
            retreat(start);
            return null;
        }

        TokenType tokenType = prev.type();

        ExprType operation;
        if (tokenType == TokenType.UNION) {
            operation = ExprType.UNION;
        } else if (tokenType == TokenType.EXCEPT) {
            operation = ExprType.EXCEPT;
        } else {
            operation = ExprType.INTERSECT;
        }

        List<String> comments = prev.comments();

        Boolean distinct;
        if (match(TokenType.DISTINCT)) {
            distinct = Boolean.TRUE;
        } else if (match(TokenType.ALL)) {
            distinct = Boolean.FALSE;
        } else {
            distinct = dd.SET_OP_DISTINCT_BY_DEFAULT.get(operation);
            if (distinct == null) {
                raiseError("Expected DISTINCT or ALL for " + operation.className());
            }
        }

        Boolean byName = matchTextSeq("BY", "NAME") || matchTextSeq("STRICT", "CORRESPONDING") ? Boolean.TRUE : null;
        if (matchTextSeq("CORRESPONDING")) {
            byName = Boolean.TRUE;
            if ((side == null || side.isEmpty()) && (kind == null || kind.isEmpty())) {
                kind = "INNER";
            }
        }

        List<Expression> onColumnList = null;
        if (byName != null && matchTexts(ON_OR_BY)) {
            onColumnList = parseWrappedCsv(this::parseColumn);
        }

        Expression expression = parseSelect(true, false, true, false, consumePipe);

        // Wrap VALUES operands in selects, both for consistency with the CTE canonicalization
        // in _parse_cte and so that alias pushdown can reach into set operation branches
        if (this_ != null && this_.is(ExprType.VALUES)) {
            this_ = valuesToSelect(this_);
        }
        if (expression != null && expression.is(ExprType.VALUES)) {
            expression = valuesToSelect(expression);
        }

        if (this_ != null && this_.is(ExprType.ALIAS) && this_.getThis() != null
                && this_.getThis().is(ExprType.SUBQUERY)) {
            Expression subquery = this_.getThis();
            subquery.set(Args.ALIAS, Expression.of(ExprType.TABLE_ALIAS, Args.THIS, this_.get(Args.ALIAS)));
            subquery.addComments(this_.popComments());
            this_ = subquery;
        }

        return expression(
                Expression.of(operation,
                        Args.THIS, this_,
                        Args.DISTINCT, distinct,
                        Args.BY_NAME, byName,
                        Args.EXPRESSION, expression,
                        Args.SIDE, side,
                        Args.KIND, kind,
                        Args.ON, onColumnList),
                comments);
    }

    @Override
    protected Expression parseSetOperations(Expression this_) {
        while (this_ != null) {
            Expression setop = parseSetOperation(this_);
            if (setop == null) {
                break;
            }
            this_ = setop;
        }

        if (this_ != null && this_.is(ExprType.SET_OPERATION) && d.MODIFIERS_ATTACHED_TO_SET_OP) {
            Expression expression = this_.getExpression();

            if (expression != null) {
                for (String arg : d.SET_OP_MODIFIERS) {
                    Arg key = Arg.forName(arg);
                    Object expr = expression.get(key);
                    if (Expression.truthy(expr)
                            && !(arg.equals("limit") && expr instanceof Expression le
                                    && Expression.truthy(le.metaGet("top")))) {
                        expression.set(key, null);
                        this_.set(key, expr);
                    }
                }
            }

            // A trailing LIMIT/FETCH can coexist with TOP on the final operand.
            if (curr.type() == TokenType.LIMIT || curr.type() == TokenType.FETCH) {
                this_ = (Expression) parseQueryModifiers(this_);
            }
        }

        return this_;
    }

    // ------------------------------------------------------------------------------------------
    // Operator precedence chain

    @Override
    protected Expression parseExpression() {
        return parseAlias(parseAssignment());
    }

    @Override
    protected Expression parseAssignment() {
        Expression this_ = parseDisjunction();
        if (this_ == null && d.ASSIGNMENT.containsKey(next.type())) {
            // This allows us to parse <non-identifier token> := <expr>
            this_ = Exp.column(advanceAny(true) != null ? prev.text() : null);
        }

        ExprType t;
        while ((t = matchBinary(d.ASSIGNMENT)) != null) {
            if (this_ != null && this_.is(ExprType.COLUMN) && this_.parts().size() == 1) {
                this_ = this_.getThis();
            }

            List<String> comments = prevComments;
            this_ = expression(Expression.of(t, Args.THIS, this_, Args.EXPRESSION, parseAssignment()), comments);
        }

        return this_;
    }

    @Override
    protected Expression parseDisjunction() {
        Expression this_ = parseConjunction();
        ExprType t;
        while ((t = matchBinary(d.DISJUNCTION)) != null) {
            List<String> comments = prevComments;
            this_ = expression(Expression.of(t, Args.THIS, this_, Args.EXPRESSION, parseConjunction()), comments);
        }
        return this_;
    }

    @Override
    protected Expression parseConjunction() {
        Expression this_ = parseEquality();
        ExprType t;
        while ((t = matchBinary(d.CONJUNCTION)) != null) {
            List<String> comments = prevComments;
            this_ = expression(Expression.of(t, Args.THIS, this_, Args.EXPRESSION, parseEquality()), comments);
        }
        return this_;
    }

    @Override
    protected Expression parseEquality() {
        Expression this_ = parseComparison();
        ExprType t;
        while ((t = matchBinary(d.EQUALITY)) != null) {
            List<String> comments = prevComments;
            this_ = expression(Expression.of(t, Args.THIS, this_, Args.EXPRESSION, parseComparison()), comments);
        }
        return this_;
    }

    @Override
    protected Expression parseComparison() {
        Expression this_ = parseRange();
        ExprType t;
        while ((t = matchBinary(d.COMPARISON)) != null) {
            List<String> comments = prevComments;
            this_ = expression(Expression.of(t, Args.THIS, this_, Args.EXPRESSION, parseRange()), comments);
        }
        return this_;
    }

    @Override
    protected Expression parseRange(Expression this_) {
        if (this_ == null) {
            this_ = parseBitwise();
        }

        while (true) {
            boolean negate = match(TokenType.NOT);
            if (matchSet(d.RANGE_PARSERS)) {
                Expression expression = rangeParser(prev.type(), this_);
                if (expression == null) {
                    return this_;
                }

                this_ = expression;
            } else if (match(TokenType.ISNULL) || (negate && match(TokenType.NULL))) {
                this_ = expression(Expression.of(ExprType.IS, Args.THIS, this_, Args.EXPRESSION, Exp.nul()));
            } else if (match(TokenType.NOTNULL)) {
                // Postgres supports ISNULL and NOTNULL for conditions.
                // https://blog.andreiavram.ro/postgresql-null-composite-type/
                if (dd.NORMALIZE_NOT_NULL) {
                    this_ = expression(Expression.of(ExprType.IS, Args.THIS, this_, Args.EXPRESSION, Exp.nul()));
                    this_ = expression(Expression.of(ExprType.NOT, Args.THIS, this_));
                } else {
                    this_ = expression(Expression.of(ExprType.IS, Args.THIS, this_, Args.EXPRESSION, Exp.nul(),
                            Args.NEGATE, Boolean.TRUE));
                }
            } else {
                if (negate) {
                    retreat(tokenIndex - 1);
                }
                break;
            }

            if (negate) {
                this_ = negateRange(this_);
                if (present(curr) && (curr.type() == TokenType.NOT || d.RANGE_PARSERS.contains(curr.type()))) {
                    this_ = expression(Expression.of(ExprType.PAREN, Args.THIS, this_));
                }
            }
        }

        return this_;
    }

    @Override
    protected Expression negateRange(Expression this_) {
        if (this_ == null) {
            return this_;
        }

        Expression expression = this_.is(ExprType.ESCAPE) ? this_.getThis() : this_;
        if (expression != null && expression.is(ExprType.LIKE, ExprType.I_LIKE)) {
            expression.set(Args.NEGATE, Boolean.TRUE);
            return this_;
        }

        return expression(Expression.of(ExprType.NOT, Args.THIS, this_));
    }

    @Override
    protected Expression parseIs(Expression this_) {
        int index = tokenIndex - 1;
        boolean negate = match(TokenType.NOT);

        if (matchTextSeq("DISTINCT", "FROM")) {
            ExprType klass = negate ? ExprType.NULL_SAFE_EQ : ExprType.NULL_SAFE_NEQ;
            return expression(Expression.of(klass, Args.THIS, this_, Args.EXPRESSION, parseBitwise()));
        }

        Expression expression;
        if (match(TokenType.JSON)) {
            Object kind = matchTexts(d.IS_JSON_PREDICATE_KIND) ? Py.upper(prev.text()) : Boolean.FALSE;

            Boolean with;
            if (matchTextSeq("WITH")) {
                with = Boolean.TRUE;
            } else if (matchTextSeq("WITHOUT")) {
                with = Boolean.FALSE;
            } else {
                with = null;
            }

            boolean unique = match(TokenType.UNIQUE);
            matchTextSeq("KEYS");
            expression = expression(Expression.of(ExprType.JSON, Args.THIS, kind, Args.WITH_, with, Args.UNIQUE, unique));
        } else {
            expression = parseNull();
            if (expression == null) {
                expression = parseBitwise();
            }
            if (expression == null) {
                retreat(index);
                return null;
            }
        }

        if (negate && expression.is(ExprType.NULL) && !dd.NORMALIZE_NOT_NULL) {
            this_ = expression(Expression.of(ExprType.IS, Args.THIS, this_, Args.EXPRESSION, expression,
                    Args.NEGATE, Boolean.TRUE));
        } else {
            this_ = expression(Expression.of(ExprType.IS, Args.THIS, this_, Args.EXPRESSION, expression));
            this_ = negate ? expression(Expression.of(ExprType.NOT, Args.THIS, this_)) : this_;
        }

        return parseColumnOps(this_);
    }

    @Override
    protected Expression parseIn(Expression this_, boolean alias) {
        Expression unnest = parseUnnest(false);
        if (unnest != null) {
            this_ = expression(Expression.of(ExprType.IN, Args.THIS, this_, Args.UNNEST, unnest));
        } else if (matchSet(L_PAREN_OR_L_BRACKET)) {
            boolean matchedLParen = prev.type() == TokenType.L_PAREN;
            List<Expression> expressions = parseCsv(() -> parseSelectOrExpression(alias));

            Expression query;
            if (expressions.size() == 1 && (query = expressions.get(0)).is(ExprType.QUERY)) {
                Expression modified = (Expression) parseQueryModifiers(query);
                this_ = expression(Expression.of(ExprType.IN, Args.THIS, this_, Args.QUERY, modified.subquery(null, false)));
            } else {
                this_ = expression(Expression.of(ExprType.IN, Args.THIS, this_, Args.EXPRESSIONS, expressions));
            }

            if (matchedLParen) {
                matchRParen(this_);
            } else if (!match(TokenType.R_BRACKET, true, this_)) {
                raiseError("Expecting ]");
            }
        } else {
            this_ = expression(Expression.of(ExprType.IN, Args.THIS, this_, Args.FIELD, parseColumn()));
        }

        return this_;
    }

    @Override
    protected Expression parseBetween(Expression this_) {
        Boolean symmetric = null;
        if (matchTextSeq("SYMMETRIC")) {
            symmetric = Boolean.TRUE;
        } else if (matchTextSeq("ASYMMETRIC")) {
            symmetric = Boolean.FALSE;
        }

        Expression low = parseBitwise();
        match(TokenType.AND);
        Expression high = parseBitwise();

        return expression(Expression.of(ExprType.BETWEEN, Args.THIS, this_, Args.LOW, low, Args.HIGH, high,
                Args.SYMMETRIC, symmetric));
    }

    @Override
    protected Expression parseEscape(Expression this_) {
        if (!match(TokenType.ESCAPE)) {
            return this_;
        }
        Expression escape = parseString();
        if (escape == null) {
            escape = parseNull();
        }
        return expression(Expression.of(ExprType.ESCAPE, Args.THIS, this_, Args.EXPRESSION, escape));
    }

    @Override
    protected Expression parseIntervalSpan(Expression this_, boolean parseFunctionUnit) {
        // handle day-time format interval span with omitted units:
        //   INTERVAL '<number days> hh[:][mm[:ss[.ff]]]' <maybe `unit TO unit`>
        Boolean intervalSpanUnitsOmitted = null;
        if (this_ != null
                && this_.isString()
                && d.SUPPORTS_OMITTED_INTERVAL_SPAN_UNIT
                && INTERVAL_DAY_TIME_RE.matcher(this_.name()).lookingAt()) {
            int index = tokenIndex;

            // Var "TO" Var
            Expression firstUnit = parseVar(true, null, true);
            Expression secondUnit = null;
            if (firstUnit != null && matchTextSeq("TO")) {
                secondUnit = parseVar(true, null, true);
            }

            intervalSpanUnitsOmitted = !(firstUnit != null && secondUnit != null);

            retreat(index);
        }

        int unitIndex = tokenIndex;
        Expression unit;
        if (intervalSpanUnitsOmitted != null && intervalSpanUnitsOmitted) {
            unit = null;
        } else {
            // Only attempt to parse a unit if the current token can actually be one, so that a
            // trailing operator isn't swallowed, e.g. INTERVAL '1 day' AND (x)
            boolean isUnit = present(curr)
                    && (curr.type() == TokenType.VAR || dd.VALID_INTERVAL_UNITS.contains(Py.upper(curr.text())));
            unit = parseFunctionUnit && isUnit ? parseFunction() : null;
            if (unit == null && isUnit) {
                unit = parseVar(true, null, true);
            }
        }

        // Most dialects support, e.g., the form INTERVAL '5' day, thus we try to parse
        // each INTERVAL expression into this canonical form so it's easy to transpile
        if (this_ != null && this_.isNumber()) {
            try {
                this_ = Exp.string(String.valueOf(this_.toPy()));
            } catch (IllegalArgumentException e) {
                raiseError("Invalid numeric interval literal: '" + this_.name() + "'");
            }
        } else if (this_ != null && this_.isString()) {
            List<String[]> parts = null;
            Matcher m = INTERVAL_STRING_RE.matcher(this_.name());
            while (m.find()) {
                if (parts == null) {
                    parts = new ArrayList<>(1);
                }
                parts.add(new String[] {m.group(1), m.group(2)});
            }
            if (parts != null && unit != null) {
                // Unconsume the eagerly-parsed unit, since the real unit was part of the string
                unit = null;
                retreat(unitIndex);
            }

            if (parts != null && parts.size() == 1) {
                this_ = Exp.string(parts.get(0)[0]);
                unit = expression(Expression.of(ExprType.VAR, Args.THIS, Py.upper(parts.get(0)[1])));
            }
        }

        if (d.INTERVAL_SPANS && matchTextSeq("TO")) {
            Expression spanEnd = parseFunction();
            if (spanEnd == null) {
                spanEnd = parseVar(true, null, true);
            }
            unit = expression(Expression.of(ExprType.INTERVAL_SPAN, Args.THIS, unit, Args.EXPRESSION, spanEnd));
        }

        return expression(Expression.of(ExprType.INTERVAL, Args.THIS, this_, Args.UNIT, unit));
    }

    @Override
    protected Expression parseInterval(boolean requireInterval, boolean parseFunctionUnit) {
        int index = tokenIndex;

        if (!match(TokenType.INTERVAL) && requireInterval) {
            return null;
        }

        Expression this_;
        if (match(TokenType.STRING, false)) {
            this_ = parsePrimary();
        } else {
            this_ = parseTerm();
        }

        if (this_ == null
                || (this_.is(ExprType.COLUMN)
                        && this_.text(Args.TABLE).isEmpty()
                        && !isQuoted(this_.getThis())
                        && present(curr)
                        && !dd.VALID_INTERVAL_UNITS.contains(Py.upper(curr.text())))) {
            retreat(index);
            return null;
        }

        Expression interval = parseIntervalSpan(this_, parseFunctionUnit);

        index = tokenIndex;
        match(TokenType.PLUS);

        // Convert INTERVAL 'val_1' unit_1 [+] ... [+] 'val_n' unit_n into a sum of intervals
        if (matchSet(STRING_OR_NUMBER, false)) {
            return expression(Expression.of(ExprType.ADD,
                    Args.THIS, interval,
                    Args.EXPRESSION, parseInterval(false, parseFunctionUnit)));
        }

        retreat(index);
        return interval;
    }

    @Override
    protected Expression parseBitwise() {
        Expression this_ = parseTerm();

        while (true) {
            ExprType t;
            if ((t = matchBinary(d.BITWISE)) != null) {
                this_ = expression(Expression.of(t, Args.THIS, this_, Args.EXPRESSION, parseTerm()));
            } else if (dd.DPIPE_IS_STRING_CONCAT && match(TokenType.DPIPE)) {
                this_ = expression(Expression.of(ExprType.D_PIPE,
                        Args.THIS, this_,
                        Args.EXPRESSION, parseTerm(),
                        Args.SAFE, !dd.STRICT_STRING_CONCAT));
            } else if (match(TokenType.DQMARK)) {
                this_ = expression(Expression.of(ExprType.COALESCE,
                        Args.THIS, this_,
                        Args.EXPRESSIONS, Py.ensureList(parseTerm())));
            } else if (matchPair(TokenType.LT, TokenType.LT)) {
                this_ = expression(Expression.of(ExprType.BITWISE_LEFT_SHIFT,
                        Args.THIS, this_, Args.EXPRESSION, parseTerm()));
            } else if (matchPair(TokenType.GT, TokenType.GT)) {
                this_ = expression(Expression.of(ExprType.BITWISE_RIGHT_SHIFT,
                        Args.THIS, this_, Args.EXPRESSION, parseTerm()));
            } else if (!d.JSON_OPERATORS.isEmpty() && matchSet(d.JSON_OPERATORS)) {
                TokenType op = prev.type();
                this_ = jsonOperator(op, this_, parseTerm());
            } else {
                break;
            }
        }

        return this_;
    }

    @Override
    protected Expression parseTerm(boolean parseMod) {
        Expression this_ = parseFactor(parseMod);

        ExprType klass;
        while ((klass = matchBinary(d.TERM)) != null) {
            List<String> comments = prevComments;
            Expression expression = parseFactor(parseMod);

            this_ = expression(Expression.of(klass, Args.THIS, this_, Args.EXPRESSION, expression), comments);

            if (this_.is(ExprType.COLLATE)) {
                normalizeCollate(this_);
            }
        }

        return this_;
    }

    @Override
    protected void normalizeCollate(Expression collate) {
        Expression expr = collate.getExpression();

        // Preserve collations such as pg_catalog."default" (Postgres) as columns, otherwise
        // fallback to Identifier / Var
        if (expr != null && expr.is(ExprType.COLUMN) && expr.parts().size() == 1) {
            Expression ident = expr.getThis();
            if (ident != null && ident.is(ExprType.IDENTIFIER)) {
                collate.set(Args.EXPRESSION, isQuoted(ident) ? ident : Exp.var(ident.name()));
            }
        }
    }

    @Override
    protected Expression parseFactor(boolean parseMod) {
        Expression this_ = parseAtTimeZone(parseFactorOperand());

        Map<TokenType, ExprType> factor = d.FACTOR;
        while (factor.containsKey(curr.type())) {
            if (!parseMod && curr.type() == TokenType.MOD) {
                break;
            }

            advance();
            ExprType klass = factor.get(prev.type());
            List<String> comments = prevComments;
            Expression expression = parseFactorOperand();

            if (expression == null && klass == ExprType.INT_DIV && isAlpha(prev.text())) {
                retreat(tokenIndex - 1);
                return this_;
            }

            this_ = expression(Expression.of(klass, Args.THIS, this_, Args.EXPRESSION, expression), comments);

            if (this_.is(ExprType.DIV)) {
                this_.set(Args.TYPED, dd.TYPED_DIVISION);
                this_.set(Args.SAFE, dd.SAFE_DIVISION);
            }
        }

        return this_;
    }

    @Override
    protected Expression parseFactorOperand() {
        return !d.EXPONENT.isEmpty() ? parseExponent() : parseUnary();
    }

    @Override
    protected Expression parseExponent() {
        Expression this_ = parseUnary();
        ExprType t;
        while ((t = matchBinary(d.EXPONENT)) != null) {
            List<String> comments = prevComments;
            this_ = expression(Expression.of(t, Args.THIS, this_, Args.EXPRESSION, parseUnary()), comments);
        }
        return this_;
    }

    @Override
    protected Expression parseUnary() {
        if (matchSet(d.UNARY_PARSERS)) {
            return unaryParser(prev.type());
        }
        return parseType();
    }

    // ------------------------------------------------------------------------------------------
    // Types

    @Override
    protected Expression parseType(boolean parseInterval, boolean fallbackToIdentifier) {
        if (!fallbackToIdentifier) {
            Expression atom = parseAtom();
            if (atom != null) {
                return atom;
            }
        }

        Expression interval = parseInterval ? parseInterval() : null;
        if (interval != null) {
            return parseColumnOps(interval);
        }

        int index = tokenIndex;
        Expression dataType = parseTypes(true, false, false, false);

        // parse_types() returns a Cast if we parsed BQ's inline constructor <type>(<values>) e.g.
        // STRUCT<a INT, b STRING>(1, 'foo'), which is canonicalized to CAST(<values> AS <type>)
        if (dataType != null && dataType.is(ExprType.CAST)) {
            // This constructor can contain ops directly after it, for instance struct unnesting:
            // STRUCT<a INT, b STRING>(1, 'foo').* --> CAST(STRUCT(1, 'foo') AS STRUCT<a iNT, b STRING).*
            return parseColumnOps(dataType);
        }

        if (dataType != null) {
            int index2 = tokenIndex;
            Expression this_ = parsePrimary();

            if (this_ != null && this_.is(ExprType.LITERAL)) {
                String literal = this_.name();
                this_ = parseColumnOps(this_);

                if (d.ZONE_AWARE_TIMESTAMP_CONSTRUCTOR && TIME_ZONE_RE.matcher(literal).find()) {
                    if (dataType.isType(DType.TIMESTAMP)) {
                        dataType = DataTypes.of(DType.TIMESTAMPTZ);
                    } else if (dataType.isType(DType.TIME)) {
                        dataType = DataTypes.of(DType.TIMETZ);
                    }
                }

                if (dataType.thisValue() instanceof DType dt && d.TYPE_LITERAL_PARSERS.contains(dt)) {
                    return typeLiteralParser(dt, this_, dataType);
                }

                return expression(Expression.of(ExprType.CAST, Args.THIS, this_, Args.TO, dataType));
            }

            // The expressions arg gets set by the parser when we have something like DECIMAL(38, 0)
            // in the input SQL. In that case, we'll produce these tokens: DECIMAL ( 38 , 0 )
            //
            // If the index difference here is greater than 1, that means the parser itself must have
            // consumed additional tokens such as the DECIMAL scale and precision in the above example.
            //
            // If it's not greater than 1, then it must be 1, because we've consumed at least the type
            // keyword, meaning that the expressions arg of the DataType must have gotten set by a
            // callable in the TYPE_CONVERTERS mapping. For example, Snowflake converts DECIMAL to
            // DECIMAL(38, 0)) in order to facilitate the data type's transpilation.
            //
            // In these cases, we don't really want to return the converted type, but instead retreat
            // and try to parse a Column or Identifier in the section below.
            if (!dataType.getExpressions().isEmpty() && index2 - index > 1) {
                retreat(index2);
                return parseColumnOps(dataType);
            }

            retreat(index);
        }

        if (fallbackToIdentifier) {
            return parseIdVar();
        }

        return parseColumn();
    }

    @Override
    protected Expression parseTypeSize() {
        Expression this_ = parseType();
        if (this_ == null) {
            return null;
        }

        if (this_.is(ExprType.COLUMN) && this_.text(Args.TABLE).isEmpty()) {
            this_ = Exp.var(Py.upper(this_.name()));
        }

        return expression(Expression.of(ExprType.DATA_TYPE_PARAM, Args.THIS, this_, Args.EXPRESSION, parseVar(true)));
    }

    @Override
    protected Expression parseUserDefinedType(Expression identifier) {
        String typeName = identifier.name();

        while (match(TokenType.DOT)) {
            typeName = typeName + "." + (advanceAny() != null ? prev.text() : "None");
        }

        return dataTypeFromStr(typeName, true);
    }

    @Override
    protected Expression parseTypes(boolean checkFunc, boolean schema, boolean allowIdentifiers, boolean withCollation) {
        int index = tokenIndex;
        Expression this_ = null;

        TokenType typeToken;
        if (matchSet(d.TYPE_TOKENS)) {
            typeToken = prev.type();
        } else {
            typeToken = null;
            Expression identifier = allowIdentifiers ? parseIdVar(false, VAR_ONLY) : null;
            if (identifier != null && identifier.is(ExprType.IDENTIFIER)) {
                if (isQuoted(identifier) && d.QUOTED_TYPES_TO_PRESERVE.contains(identifier.name())) {
                    this_ = DataTypes.build(identifier, null, true, true);
                } else {
                    List<Token> tokens;
                    try {
                        tokens = dialect.tokenize(identifier.name());
                    } catch (TokenError e) {
                        tokens = null;
                    }

                    if (tokens != null && !tokens.isEmpty()
                            && d.TYPE_TOKENS.contains(typeToken = tokens.get(0).type())) {
                        if (tokens.size() > 1) {
                            return dataTypeFromStr(identifier.name(), false);
                        }
                    } else if (dd.SUPPORTS_USER_DEFINED_TYPES) {
                        this_ = parseUserDefinedType(identifier);
                    } else {
                        retreat(tokenIndex - 1);
                        return null;
                    }
                }
            } else {
                return null;
            }
        }

        if (typeToken == TokenType.PSEUDO_TYPE) {
            return expression(Expression.of(ExprType.PSEUDO_TYPE, Args.THIS, Py.upper(prev.text())));
        }

        if (typeToken == TokenType.OBJECT_IDENTIFIER) {
            return expression(Expression.of(ExprType.OBJECT_IDENTIFIER, Args.THIS, Py.upper(prev.text())));
        }

        // https://materialize.com/docs/sql/types/map/
        if (typeToken == TokenType.MAP && match(TokenType.L_BRACKET)) {
            Expression keyType = parseTypes(checkFunc, schema, allowIdentifiers);
            if (!match(TokenType.FARROW)) {
                retreat(index);
                return null;
            }

            Expression valueType = parseTypes(checkFunc, schema, allowIdentifiers);
            if (!match(TokenType.R_BRACKET)) {
                retreat(index);
                return null;
            }

            return Expression.of(ExprType.DATA_TYPE,
                    Args.THIS, DType.MAP,
                    Args.EXPRESSIONS, Arrays.asList(keyType, valueType),
                    Args.NESTED, Boolean.TRUE);
        }

        boolean nested = in(d.NESTED_TYPE_TOKENS, typeToken);
        boolean isStruct = in(d.STRUCT_TYPE_TOKENS, typeToken);
        boolean isAggregate = in(d.AGGREGATE_TYPE_TOKENS, typeToken);
        List<Expression> expressions = null;
        boolean maybeFunc = false;

        if (match(TokenType.L_PAREN)) {
            if (isStruct) {
                expressions = parseCsv(() -> parseStructTypes(true));
            } else if (nested) {
                expressions = parseCsv(() -> parseTypes(checkFunc, schema, allowIdentifiers));
                if (typeToken == TokenType.NULLABLE && expressions.size() == 1) {
                    this_ = expressions.get(0);
                    this_.set(Args.NULLABLE, Boolean.TRUE);
                    matchRParen();
                    return this_;
                }
            } else if (in(d.ENUM_TYPE_TOKENS, typeToken)) {
                expressions = parseCsv(this::parseEquality);
            } else if (typeToken == TokenType.JSON) {
                // ClickHouse JSON type supports arguments: JSON(col Type, SKIP col, param=value)
                // https://clickhouse.com/docs/sql-reference/data-types/newjson
                expressions = parseCsv(this::parseJsonTypeArg);
            } else if (isAggregate) {
                Expression funcOrIdent = parseFunction(null, true);
                if (funcOrIdent == null) {
                    funcOrIdent = parseIdVar(false, VAR_OR_ANY);
                }
                if (funcOrIdent == null) {
                    return null;
                }
                expressions = new ArrayList<>();
                expressions.add(funcOrIdent);
                if (match(TokenType.COMMA)) {
                    expressions.addAll(parseCsv(() -> parseTypes(checkFunc, schema, allowIdentifiers)));
                }
            } else {
                expressions = parseCsv(this::parseTypeSize);

                // https://docs.snowflake.com/en/sql-reference/data-types-vector
                if (typeToken == TokenType.VECTOR && expressions.size() == 2) {
                    expressions = parseVectorExpressions(expressions);
                }
            }

            if (!match(TokenType.R_PAREN)) {
                retreat(index);
                return null;
            }

            maybeFunc = true;
        }

        List<Expression> values = null;

        if (nested && match(TokenType.LT)) {
            if (isStruct) {
                expressions = parseCsv(() -> parseStructTypes(true));
            } else {
                expressions = parseCsv(() -> parseTypes(checkFunc, schema, allowIdentifiers, true));
            }

            if (!match(TokenType.GT)) {
                raiseError("Expecting >");
            }

            if (matchSet(L_BRACKET_OR_L_PAREN)) {
                values = parseCsv(this::parseDisjunction);
                if (values.isEmpty() && isStruct) {
                    values = null;
                    retreat(tokenIndex - 1);
                } else {
                    matchSet(R_BRACKET_OR_R_PAREN);
                }
            }
        }

        if (in(d.TIMESTAMPS, typeToken)) {
            if (matchTextSeq("WITH", "TIME", "ZONE")) {
                maybeFunc = false;
                DType tzType = in(d.TIMES, typeToken) ? DType.TIMETZ : DType.TIMESTAMPTZ;
                this_ = Expression.of(ExprType.DATA_TYPE, Args.THIS, tzType, Args.EXPRESSIONS, expressions);
            } else if (matchTextSeq("WITH", "LOCAL", "TIME", "ZONE")) {
                maybeFunc = false;
                this_ = Expression.of(ExprType.DATA_TYPE, Args.THIS, DType.TIMESTAMPLTZ, Args.EXPRESSIONS, expressions);
            } else if (matchTextSeq("WITHOUT", "TIME", "ZONE")) {
                maybeFunc = false;
            }
        } else if (typeToken == TokenType.INTERVAL) {
            if (dd.VALID_INTERVAL_UNITS.contains(Py.upper(curr.text()))) {
                Expression unit = parseVar(false, null, true);
                if (matchTextSeq("TO")) {
                    unit = Expression.of(ExprType.INTERVAL_SPAN, Args.THIS, unit, Args.EXPRESSION, parseVar(false, null, true));
                }

                this_ = expression(Expression.of(ExprType.DATA_TYPE,
                        Args.THIS, expression(Expression.of(ExprType.INTERVAL, Args.UNIT, unit))));
            } else {
                this_ = expression(Expression.of(ExprType.DATA_TYPE, Args.THIS, DType.INTERVAL));
            }
        } else if (typeToken == TokenType.VOID) {
            this_ = Expression.of(ExprType.DATA_TYPE, Args.THIS, DType.NULL);
        }

        if (maybeFunc && checkFunc) {
            int index2 = tokenIndex;
            Expression peek = parseString();

            if (peek == null) {
                retreat(index);
                return null;
            }

            retreat(index2);
        }

        if (this_ == null) {
            if (matchTextSeq("UNSIGNED")) {
                TokenType unsignedTypeToken = d.SIGNED_TO_UNSIGNED_TYPE_TOKEN.get(typeToken);
                if (unsignedTypeToken == null) {
                    raiseError("Cannot convert " + typeToken.name() + " to unsigned.");
                }

                typeToken = unsignedTypeToken != null ? unsignedTypeToken : typeToken;
            }

            // NULLABLE without parentheses can be a column (Presto/Trino)
            if (typeToken == TokenType.NULLABLE && (expressions == null || expressions.isEmpty())) {
                retreat(index);
                return null;
            }

            this_ = Expression.of(ExprType.DATA_TYPE,
                    Args.THIS, DType.valueOf(typeToken.name()),
                    Args.EXPRESSIONS, expressions,
                    Args.NESTED, nested);

            // Empty arrays/structs are allowed
            if (values != null) {
                ExprType cls = isStruct ? ExprType.STRUCT : ExprType.ARRAY;
                this_ = Exp.cast(Expression.of(cls, Args.EXPRESSIONS, values), this_, false, null);
            }
        } else if (expressions != null && !expressions.isEmpty()) {
            this_.set(Args.EXPRESSIONS, expressions);
        }

        // https://materialize.com/docs/sql/types/list/#type-name
        while (match(TokenType.LIST)) {
            this_ = Expression.of(ExprType.DATA_TYPE, Args.THIS, DType.LIST, Args.EXPRESSIONS, List.of(this_),
                    Args.NESTED, Boolean.TRUE);
        }

        index = tokenIndex;

        // Postgres supports the INT ARRAY[3] syntax as a synonym for INT[3]
        boolean matchedArray = match(TokenType.ARRAY);

        while (present(curr)) {
            TokenType datatypeToken = prev.type();
            boolean matchedLBracket = match(TokenType.L_BRACKET);

            if ((!matchedLBracket && !matchedArray)
                    || (datatypeToken == TokenType.ARRAY && match(TokenType.R_BRACKET))) {
                // Postgres allows casting empty arrays such as ARRAY[]::INT[],
                // not to be confused with the fixed size array parsing
                break;
            }

            matchedArray = false;
            values = parseCsv(this::parseDisjunction);
            if (values.isEmpty()) {
                values = null;
            }
            if (values != null
                    && !schema
                    && (!dd.SUPPORTS_FIXED_SIZE_ARRAYS
                            || datatypeToken == TokenType.ARRAY
                            || !match(TokenType.R_BRACKET, false))) {
                // Retreating here means that we should not parse the following values as part of the data type, e.g. in DuckDB
                // ARRAY[1] should retreat and instead be parsed into exp.Array in contrast to INT[x][y] which denotes a fixed-size array data type
                retreat(index);
                break;
            }

            this_ = Expression.of(ExprType.DATA_TYPE,
                    Args.THIS, DType.ARRAY,
                    Args.EXPRESSIONS, List.of(this_),
                    Args.VALUES, values,
                    Args.NESTED, Boolean.TRUE);
            match(TokenType.R_BRACKET);
        }

        if (!d.TYPE_CONVERTERS.isEmpty() && this_.thisValue() instanceof DType dt && d.TYPE_CONVERTERS.contains(dt)) {
            this_ = typeConverter(dt, this_);
        }

        if (withCollation && this_.is(ExprType.DATA_TYPE) && match(TokenType.COLLATE)) {
            Expression collate = parseIdentifier();
            if (collate == null) {
                collate = parseColumn();
            }
            this_.set(Args.COLLATE, collate);
        }

        return this_;
    }

    @Override
    protected Expression parseJsonTypeArg() {
        // Parse a single argument to ClickHouse's JSON type.

        // SKIP col or SKIP REGEXP 'pattern'
        if (matchTextSeq("SKIP")) {
            boolean regexp = match(TokenType.RLIKE);
            Expression arg = parseColumn();
            if (arg != null && arg.is(ExprType.COLUMN)) {
                arg = arg.toDot(true);
            }
            return expression(Expression.of(ExprType.SKIP_JSON_COLUMN, Args.REGEXP, regexp, Args.EXPRESSION, arg));
        }

        Expression paramOrCol = parseColumn();
        if (paramOrCol == null || !paramOrCol.is(ExprType.COLUMN)) {
            return null;
        }

        // Parameter: name=value (e.g., max_dynamic_paths=2)
        if (paramOrCol.parts().size() == 1 && match(TokenType.EQ)) {
            String param = paramOrCol.name();
            Expression value = parsePrimary();
            return expression(Expression.of(ExprType.EQ, Args.THIS, Exp.var(param), Args.EXPRESSION, value));
        }

        // Column type hint: col_name Type
        Expression col = paramOrCol.toDot(true);
        Expression kind = parseTypes(false, false, false);
        return expression(Expression.of(ExprType.COLUMN_DEF, Args.THIS, col, Args.KIND, kind));
    }

    @Override
    protected List<Expression> parseVectorExpressions(List<Expression> expressions) {
        List<Expression> out = new ArrayList<>(expressions.size());
        out.add(dataTypeFromStr(expressions.get(0).name(), false));
        out.addAll(expressions.subList(1, expressions.size()));
        return out;
    }

    @Override
    protected Expression parseStructTypes(boolean typeRequired) {
        int index = tokenIndex;

        Expression this_;
        if (present(curr)
                && present(next)
                && d.TYPE_TOKENS.contains(curr.type())
                && d.TYPE_TOKENS.contains(next.type())) {
            // Takes care of special cases like `STRUCT<list ARRAY<...>>` where the identifier is also a
            // type token. Without this, the list will be parsed as a type and we'll eventually crash
            this_ = parseIdVar();
        } else {
            this_ = parseType(false, true);
            if (this_ == null) {
                this_ = parseIdVar();
            }
        }

        match(TokenType.COLON);

        if (typeRequired
                && !(this_ != null && this_.is(ExprType.DATA_TYPE))
                && !matchSet(d.TYPE_TOKENS, false)) {
            retreat(index);
            return parseTypes();
        }

        return parseColumnDef(this_);
    }

    @Override
    protected Expression parseAtTimeZone(Expression this_) {
        if (!matchTextSeq("AT", "TIME", "ZONE")) {
            return this_;
        }
        return parseAtTimeZone(expression(Expression.of(ExprType.AT_TIME_ZONE, Args.THIS, this_, Args.ZONE, parseUnary())));
    }

    // ------------------------------------------------------------------------------------------
    // Atoms, columns and column operators

    @Override
    protected Expression parseAtom() {
        if (d.IDENTIFIER_TOKENS.contains(curr.type())) {
            Expression column = parseColumn();
            if (column != null) {
                return column;
            }
        }

        Token token = curr;
        TokenType tokenType = token.type();

        if (!d.PRIMARY_PARSERS.contains(tokenType)) {
            return null;
        }

        TokenType nextType = next.type();

        if (d.COLUMN_OPERATORS.contains(nextType)
                || d.COLUMN_POSTFIX_TOKENS.contains(nextType)
                || (tokenType == TokenType.STRING && nextType == TokenType.STRING)) {
            return null;
        }

        advance();
        return primaryParser(tokenType, token);
    }

    @Override
    protected Expression parseColumn() {
        Expression column = parseColumnPartsFast();
        if (column == null) {
            Expression this_ = parseColumnReference();
            if (this_ == null) {
                this_ = parseBracket(this_);
            }
            column = this_ != null ? parseColumnOps(this_) : this_;
        }

        if (column != null) {
            if (dd.SUPPORTS_COLUMN_JOIN_MARKS && match(TokenType.JOIN_MARKER)) {
                column.set(Args.JOIN_MARK, Boolean.TRUE);
            }
            if (d.COLON_IS_VARIANT_EXTRACT) {
                column = parseColonAsVariantExtract(column);
            }
        }

        return column;
    }

    /**
     * Fast path for simple column and dot references (a, a.b, ...).
     *
     * <p>Greedily consumes VAR/IDENTIFIER tokens separated by DOTs, then checks that nothing complex
     * follows. If it does, retreats and returns null so the slow path can handle it. For >4 parts,
     * wraps in Dot nodes.
     */
    @Override
    protected Expression parseColumnPartsFast() {
        int index = tokenIndex;
        List<Expression> parts = null;
        List<String> allComments = null;
        Set<TokenType> identifierTokens = d.IDENTIFIER_TOKENS;

        while (matchSet(identifierTokens)) {
            Token token = prev;
            List<String> comments = prevComments;

            if (parts == null && hasNoParenFunctionParser(Py.upper(token.text()))) {
                retreat(index);
                return null;
            }

            boolean hasDot = match(TokenType.DOT);
            TokenType currTt = curr.type();

            if (!hasDot) {
                if (d.COLUMN_OPERATORS.contains(currTt) || d.COLUMN_POSTFIX_TOKENS.contains(currTt)) {
                    retreat(index);
                    return null;
                }
            } else if (!identifierTokens.contains(currTt)) {
                retreat(index);
                return null;
            }

            if (parts == null) {
                parts = new ArrayList<>(4);
            }

            if (comments != null && !comments.isEmpty()) {
                if (allComments == null) {
                    allComments = new ArrayList<>();
                }
                allComments.addAll(comments);
                prevComments = new ArrayList<>();
            }

            parts.add(expression(
                    Expression.of(ExprType.IDENTIFIER,
                            Args.THIS, token.text(),
                            Args.QUOTED, token.type() == TokenType.IDENTIFIER),
                    token));

            if (!hasDot) {
                break;
            }
        }

        if (parts == null) {
            return null;
        }

        int n = parts.size();

        Expression column;
        if (n == 1) {
            column = Expression.of(ExprType.COLUMN, Args.THIS, parts.get(0));
        } else if (n == 2) {
            column = Expression.of(ExprType.COLUMN, Args.THIS, parts.get(1), Args.TABLE, parts.get(0));
        } else if (n == 3) {
            column = Expression.of(ExprType.COLUMN, Args.THIS, parts.get(2), Args.TABLE, parts.get(1),
                    Args.DB, parts.get(0));
        } else {
            column = Expression.of(ExprType.COLUMN, Args.THIS, parts.get(3), Args.TABLE, parts.get(2),
                    Args.DB, parts.get(1), Args.CATALOG, parts.get(0));

            for (int i = 4; i < n; i++) {
                column = Expression.of(ExprType.DOT, Args.THIS, column, Args.EXPRESSION, parts.get(i));
            }
        }

        if (allComments != null && !allComments.isEmpty()) {
            column.addComments(allComments);
        }

        return column;
    }

    @Override
    protected Expression parseColumnReference() {
        Expression this_ = parseField();
        if (this_ == null
                && match(TokenType.VALUES, false)
                && d.VALUES_FOLLOWED_BY_PAREN
                && (!present(next) || next.type() != TokenType.L_PAREN)) {
            this_ = parseIdVar();
        }

        if (this_ != null && this_.is(ExprType.IDENTIFIER)) {
            // We bubble up comments from the Identifier to the Column
            Expression column = Expression.of(ExprType.COLUMN, Args.THIS, this_);
            this_ = expression(column, this_.popComments());
        }

        return this_;
    }

    @Override
    protected Object[] buildJsonExtract(Expression this_, List<Expression> pathParts) {
        if (pathParts.size() > 1) {
            this_ = expression(Expression.of(ExprType.JSON_EXTRACT,
                    Args.THIS, this_,
                    Args.EXPRESSION, Expression.of(ExprType.JSON_PATH, Args.EXPRESSIONS, pathParts),
                    Args.VARIANT_EXTRACT, Boolean.TRUE,
                    Args.REQUIRES_JSON, d.JSON_EXTRACT_REQUIRES_JSON_EXPRESSION));
            pathParts = new ArrayList<>();
            pathParts.add(Expression.of(ExprType.JSON_PATH_ROOT));
        }

        return new Object[] {this_, pathParts};
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Expression parseColonAsVariantExtract(Expression this_) {
        List<Expression> pathParts = new ArrayList<>();
        pathParts.add(Expression.of(ExprType.JSON_PATH_ROOT));

        while (match(TokenType.COLON)) {
            if (!d.COLON_CHAIN_IS_SINGLE_EXTRACT) {
                Object[] r = buildJsonExtract(this_, pathParts);
                this_ = (Expression) r[0];
                pathParts = (List<Expression>) r[1];
            }

            Expression key = parseIdVar(true, SELECT_ONLY);

            if (key != null) {
                boolean quoted = key.is(ExprType.IDENTIFIER) && isQuoted(key);
                pathParts.add(Expression.of(ExprType.JSON_PATH_KEY, Args.THIS, key.name(), Args.QUOTED, quoted));
            }

            while (true) {
                if (match(TokenType.DOT)) {
                    Expression nextKey = parseIdVar(true, SELECT_ONLY);

                    if (nextKey != null) {
                        boolean quoted = nextKey.is(ExprType.IDENTIFIER) && isQuoted(nextKey);
                        pathParts.add(Expression.of(ExprType.JSON_PATH_KEY, Args.THIS, nextKey.name(), Args.QUOTED, quoted));
                    }
                } else if (match(TokenType.L_BRACKET)) {
                    Expression bracketExpr = parseBracketKeyValue();

                    if (!match(TokenType.R_BRACKET)) {
                        raiseError("Expected ]");
                    }

                    if (bracketExpr != null) {
                        if (bracketExpr.isString()) {
                            pathParts.add(Expression.of(ExprType.JSON_PATH_KEY, Args.THIS, bracketExpr.name(),
                                    Args.QUOTED, Boolean.TRUE));
                        } else if (bracketExpr.isStar()) {
                            pathParts.add(Expression.of(ExprType.JSON_PATH_SUBSCRIPT,
                                    Args.THIS, Expression.of(ExprType.JSON_PATH_WILDCARD)));
                        } else if (bracketExpr.isNumber()) {
                            pathParts.add(Expression.of(ExprType.JSON_PATH_SUBSCRIPT, Args.THIS, bracketExpr.toPy()));
                        } else {
                            Object[] r = buildJsonExtract(this_, pathParts);
                            this_ = (Expression) r[0];
                            pathParts = (List<Expression>) r[1];

                            List<Expression> bracketExprs = new ArrayList<>(1);
                            bracketExprs.add(bracketExpr);
                            this_ = expression(Expression.of(ExprType.BRACKET,
                                    Args.THIS, this_,
                                    Args.EXPRESSIONS, bracketExprs,
                                    Args.JSON_ACCESS, Boolean.TRUE));
                        }
                    }
                } else if (match(TokenType.DCOLON)) {
                    Object[] r = buildJsonExtract(this_, pathParts);
                    this_ = (Expression) r[0];
                    pathParts = (List<Expression>) r[1];

                    Expression castType = parseTypes();
                    if (castType != null) {
                        this_ = expression(Expression.of(ExprType.CAST, Args.THIS, this_, Args.TO, castType));
                    } else {
                        raiseError("Expected type after '::'");
                    }
                } else {
                    break;
                }
            }
        }

        Object[] r = buildJsonExtract(this_, pathParts);
        this_ = (Expression) r[0];

        return this_;
    }

    @Override
    protected Expression parseDcolon() {
        return parseTypes();
    }

    @Override
    protected Expression parseColumnOps(Expression this_) {
        Set<TokenType> brackets = d.BRACKETS;
        while (brackets.contains(curr.type())) {
            this_ = parseBracket(this_);
        }

        Set<TokenType> columnOperators = d.COLUMN_OPERATORS;
        Set<TokenType> castColumnOperators = d.CAST_COLUMN_OPERATORS;
        while (present(curr)) {
            TokenType opToken = curr.type();

            if (!columnOperators.contains(opToken)) {
                break;
            }
            // COLUMN_OPERATORS[DOT] is None; every other entry is a callable
            boolean op = opToken != TokenType.DOT;
            advance();

            Expression field;
            if (castColumnOperators.contains(opToken)) {
                field = parseDcolon();
                if (field == null) {
                    raiseError("Expected type");
                }
            } else if (op && present(curr)) {
                field = parseColumnReference();
                if (field == null) {
                    field = parseBitwise();
                }
                if (field != null && field.is(ExprType.COLUMN) && match(TokenType.DOT, false)) {
                    field = parseColumnOps(field);
                }
            } else {
                boolean dot = isConnected() && prev.type() == TokenType.DOT;
                field = parseField(true, null, true);

                // In t.true, t.null we should produce an Identifier node
                if (dot && field != null && field.is(ExprType.NULL, ExprType.BOOLEAN)) {
                    field = expression(Expression.of(ExprType.IDENTIFIER, Args.THIS, prev.text()), field.comments());
                }
            }

            // Function calls can be qualified, e.g., x.y.FOO()
            // This converts the final AST to a series of Dots leading to the function call
            // https://cloud.google.com/bigquery/docs/reference/standard-sql/functions-reference#function_call_rules
            if (field != null && field.is(ExprType.FUNC, ExprType.WINDOW) && this_ != null) {
                this_ = this_.transform(n -> n.is(ExprType.COLUMN) ? n.toDot(false) : n);
            }

            if (op) {
                this_ = columnOperator(opToken, this_, field);
            } else if (this_ != null && this_.is(ExprType.COLUMN) && !this_.has(Args.CATALOG)) {
                this_ = expression(
                        Expression.of(ExprType.COLUMN,
                                Args.THIS, field,
                                Args.TABLE, this_.get(Args.THIS),
                                Args.DB, this_.get(Args.TABLE),
                                Args.CATALOG, this_.get(Args.DB)),
                        this_.comments());
            } else if (field != null && field.is(ExprType.WINDOW)) {
                // Move the exp.Dot's to the window's function
                Expression windowFunc = expression(Expression.of(ExprType.DOT, Args.THIS, this_, Args.EXPRESSION, field.get(Args.THIS)));
                field.set(Args.THIS, windowFunc);
                this_ = field;
            } else {
                this_ = expression(Expression.of(ExprType.DOT, Args.THIS, this_, Args.EXPRESSION, field));
            }

            if (field != null && field.comments() != null && !field.comments().isEmpty()) {
                this_.addComments(field.popComments());
            }

            this_ = parseBracket(this_);
        }

        return this_;
    }

    @Override
    protected Expression parseParen() {
        if (!match(TokenType.L_PAREN)) {
            return null;
        }

        List<String> comments = prevComments;
        Expression query = parseSelect();

        List<Expression> expressions;
        if (query != null) {
            expressions = new ArrayList<>(1);
            expressions.add(query);
        } else {
            expressions = parseExpressions();
        }

        Expression this_ = Py.seqGet(expressions, 0);

        if (this_ == null && match(TokenType.R_PAREN, false)) {
            this_ = expression(Expression.of(ExprType.TUPLE));
        } else if (expressions.size() > 1 || prev.type() == TokenType.COMMA) {
            this_ = expression(Expression.of(ExprType.TUPLE, Args.EXPRESSIONS, expressions));
        } else if (this_ != null && this_.is(ExprType.SELECT, ExprType.SET_OPERATION)) {
            this_ = parseSubquery(this_, false);
        } else if (this_ != null && this_.is(ExprType.SUBQUERY, ExprType.VALUES)) {
            this_ = parseSubquery((Expression) parseQueryModifiers(parseSetOperations(this_)), false);
        } else {
            this_ = expression(Expression.of(ExprType.PAREN, Args.THIS, this_));
        }

        if (this_ != null) {
            this_.addComments(comments);
        }

        matchRParen(this_);

        if (this_ != null && this_.is(ExprType.PAREN) && this_.getThis() != null && this_.getThis().is(ExprType.AGG_FUNC)) {
            return parseWindow(this_);
        }

        return this_;
    }

    @Override
    protected Expression parsePrimary() {
        if (matchSet(d.PRIMARY_PARSERS)) {
            TokenType tokenType = prev.type();
            Expression primary = primaryParser(tokenType, prev);

            if (tokenType == TokenType.STRING) {
                List<Expression> expressions = null;
                while (match(TokenType.STRING, false)) {
                    if (isConnected() && d.ADJACENT_STRINGS_CANNOT_BE_CONNECTED) {
                        raiseError("Adjacent string literals need to be separated by whitespace or comments");
                    }

                    advance();
                    if (expressions == null) {
                        expressions = new ArrayList<>();
                        expressions.add(primary);
                    }
                    expressions.add(Exp.string(prev.text()));
                }

                if (expressions != null && expressions.size() > 1) {
                    return expression(Expression.of(ExprType.CONCAT,
                            Args.EXPRESSIONS, expressions,
                            Args.COALESCE, dd.CONCAT_COALESCE));
                }
            }

            return primary;
        }

        if (matchPair(TokenType.DOT, TokenType.NUMBER)) {
            return Exp.number("0." + prev.text());
        }

        return parseParen();
    }

    @Override
    protected Expression parseField(boolean anyToken, Set<TokenType> tokens, boolean anonymousFunc) {
        boolean afterDot = d.SUPPORTS_DIGIT_PREFIXED_FIELD_NAMES && prev.type() == TokenType.DOT;

        Expression field;
        if (anonymousFunc) {
            field = parseFunction(null, anonymousFunc, true, anyToken);
            if (field == null) {
                field = parsePrimary();
            }
        } else {
            field = parsePrimary();
            if (field == null) {
                field = parseFunction(null, anonymousFunc, true, anyToken);
            }
        }

        if (field == null) {
            field = parseIdVar(anyToken, tokens);
        }

        if (afterDot && field != null && field.is(ExprType.LITERAL) && field.isNumber()) {
            String name = field.name();
            if (isConnected() && parseVar(true) != null) {
                name += prev.text();
            }

            field = updatePositions(Expression.of(ExprType.IDENTIFIER, Args.THIS, name, Args.QUOTED, Boolean.TRUE), field);
        }

        return field;
    }

    // ------------------------------------------------------------------------------------------
    // Functions

    @Override
    protected Expression parseFunction(Map<String, Object> functions, boolean anonymous, boolean optionalParens,
            boolean anyToken) {
        // This allows us to also parse {fn <function>} syntax (Snowflake, MySQL support this)
        // See: https://community.snowflake.com/s/article/SQL-Escape-Sequences
        boolean fnSyntax = false;
        if (match(TokenType.L_BRACE, false) && present(next) && Py.upper(next.text()).equals("FN")) {
            advance(2);
            fnSyntax = true;
        }

        Expression func = parseFunctionCall(functions, anonymous, optionalParens, anyToken);

        if (fnSyntax) {
            match(TokenType.R_BRACE);
        }

        return func;
    }

    @Override
    protected List<Expression> parseFunctionArgs(boolean alias) {
        return parseCsv(() -> parseLambda(alias));
    }

    /**
     * The contract types the connector as a {@link ParseFn}, but sqlglot's connector is a variadic
     * builder ({@code exp.and_} / {@code exp.or_}) applied to the parsed arguments, which a no-arg
     * callback cannot express. Use {@link #parseConnectorFunction(ExprType)} instead.
     */
    @Override
    protected Expression parseConnectorFunction(ParseFn connector) {
        throw new UnsupportedOperationException(
                "parseConnectorFunction(ParseFn) cannot receive the arguments; use parseConnectorFunction(ExprType)");
    }

    /** {@code _parse_connector_function(exp.and_)} / {@code (exp.or_)}: {@code connector} is AND or OR. */
    protected Expression parseConnectorFunction(ExprType connector) {
        List<Expression> args = parseFunctionArgs(false);
        if (args.isEmpty()) {
            raiseError("Expected at least one argument");
        }

        // Wrapped so the connector keeps its precedence in the parent context
        return Expression.of(ExprType.PAREN, Args.THIS, Exp.combine(connector, args, true));
    }

    /** Calls an entry of an explicit {@code functions} table passed to {@code _parse_function_call}. */
    @SuppressWarnings("unchecked")
    private Expression callExplicitFunction(Object function, String upper, List<Expression> args) {
        if (function instanceof ExprType t) {
            return Exp.fromArgList(t, args);
        }
        if (function instanceof Function<?, ?> f) {
            return ((Function<List<Expression>, Expression>) f).apply(args);
        }
        return callFunctionBuilder(upper, args);
    }

    @Override
    protected Expression parseFunctionCall(Map<String, Object> functions, boolean anonymous, boolean optionalParens,
            boolean anyToken) {
        if (!present(curr)) {
            return null;
        }

        List<String> comments = curr.comments();
        Token prevToken = prev;
        Token token = curr;
        TokenType tokenType = curr.type();
        String thisText = curr.text();
        String upper = Py.upper(thisText);

        boolean afterDot = prevToken.type() == TokenType.DOT;
        if (optionalParens
                && hasNoParenFunctionParser(upper)
                && !d.INVALID_FUNC_NAME_TOKENS.contains(tokenType)
                && !afterDot) {
            advance();
            return parseWindow(noParenFunctionParser(upper));
        }

        if (next.type() != TokenType.L_PAREN) {
            if (optionalParens && !afterDot) {
                ExprType noParen = d.NO_PAREN_FUNCTIONS.get(tokenType);
                if (noParen != null) {
                    advance();
                    return expression(Expression.of(noParen));
                }
            }

            return null;
        }

        if (anyToken) {
            if (d.RESERVED_TOKENS.contains(tokenType)) {
                return null;
            }
        } else if (!d.FUNC_TOKENS.contains(tokenType)) {
            return null;
        }

        advance(2);

        boolean hasParser = d.FUNCTION_PARSERS.contains(upper);
        Expression result;
        if (hasParser && !anonymous) {
            result = functionParser(upper);
        } else {
            ExprType subqueryPredicate = d.SUBQUERY_PREDICATES.get(tokenType);

            if (subqueryPredicate != null) {
                Expression expr = null;
                if (d.SUBQUERY_TOKENS.contains(curr.type())) {
                    expr = parseSelect();
                    matchRParen();
                } else if (present(prevToken)
                        && (prevToken.type() == TokenType.LIKE || prevToken.type() == TokenType.ILIKE)) {
                    // Backtrack one token since we've consumed the L_PAREN here. Instead, we'd like
                    // to parse "LIKE [ANY | ALL] (...)" as a whole into an exp.Tuple or exp.Paren
                    advance(-1);
                    expr = parseBitwise();
                }

                if (expr != null) {
                    return expression(Expression.of(subqueryPredicate, Args.THIS, expr), comments);
                }
            }

            Object function = null;
            boolean knownFunction;
            if (functions == null) {
                knownFunction = hasFunction(upper) && !anonymous;
            } else {
                function = functions.get(upper);
                knownFunction = Expression.truthy(function) && !anonymous;
            }

            boolean alias = !knownFunction || d.FUNCTIONS_WITH_ALIASED_ARGS.contains(upper);
            List<Expression> args = parseFunctionArgs(alias);

            List<String> postFuncComments = present(curr) ? curr.comments() : null;
            if (knownFunction && postFuncComments != null && !postFuncComments.isEmpty()) {
                // If the user-inputted comment "/* sqlglot.anonymous */" is following the function
                // call we'll construct it as exp.Anonymous, even if it's "known"
                for (String comment : postFuncComments) {
                    if (Py.lstrip(comment).startsWith(SQLGLOT_ANONYMOUS)) {
                        knownFunction = false;
                        break;
                    }
                }
            }

            if (alias && knownFunction) {
                args = kvToPropEq(args);
            }

            if (knownFunction) {
                // In Java the builder always has access to the dialect
                Expression func = functions == null
                        ? callFunctionBuilder(upper, args)
                        : callExplicitFunction(function, upper, args);

                func = validateExpression(func, args);
                String nameKey = dd.ORIGINAL_NAME_META_KEY;
                if (nameKey != null && !nameKey.isEmpty()) {
                    func.meta().put(nameKey, thisText);
                }

                result = func;
            } else {
                Object this_ = thisText;
                if (tokenType == TokenType.IDENTIFIER) {
                    this_ = updatePositions(
                            Expression.of(ExprType.IDENTIFIER, Args.THIS, thisText, Args.QUOTED, Boolean.TRUE), token);
                }

                result = expression(Expression.of(ExprType.ANONYMOUS, Args.THIS, this_, Args.EXPRESSIONS, args));
            }

            result = updatePositions(result, token);
        }

        if (result != null) {
            result.addComments(comments);
        }

        if (hasParser) {
            match(TokenType.R_PAREN, true, result);
        } else {
            matchRParen(result);
        }
        return parseWindow(result);
    }

    @Override
    protected Expression toPropEq(Expression expression, int index) {
        return expression;
    }

    private boolean isKeyValueDefinition(Expression e) {
        for (ExprType t : d.KEY_VALUE_DEFINITIONS) {
            if (e.is(t)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected List<Expression> kvToPropEq(List<Expression> expressions, boolean parseMap) {
        List<Expression> transformed = new ArrayList<>(expressions.size());

        for (int index = 0; index < expressions.size(); index++) {
            Expression e = expressions.get(index);
            if (e != null && isKeyValueDefinition(e)) {
                if (e.is(ExprType.ALIAS)) {
                    e = expression(Expression.of(ExprType.PROPERTY_EQ, Args.THIS, e.get(Args.ALIAS),
                            Args.EXPRESSION, e.get(Args.THIS)));
                }

                if (!e.is(ExprType.PROPERTY_EQ)) {
                    e = expression(Expression.of(ExprType.PROPERTY_EQ,
                            Args.THIS, parseMap ? e.get(Args.THIS) : Exp.toIdentifier(e.getThis().name()),
                            Args.EXPRESSION, e.get(Args.EXPRESSION)));
                }

                Expression key = e.getThis();
                if (key != null && key.is(ExprType.COLUMN)) {
                    key.replace(key.getThis());
                }
            } else {
                e = toPropEq(e, index);
            }

            transformed.add(e);
        }

        return transformed;
    }

    @Override
    protected Expression parseFunctionProperties() {
        // Skip the generic `key = value` fallback in _parse_property since this
        // runs post-AS where a function body like `name = expr` can be misread
        // as a property.
        List<Expression> properties = new ArrayList<>();
        while (true) {
            String keyword;
            Object prop;
            if (matchTexts(d.PROPERTY_PARSERS)) {
                keyword = Py.upper(prev.text());
                prop = propertyParser(keyword, Map.of());
            } else if (match(TokenType.DEFAULT) && matchTexts(d.PROPERTY_PARSERS)) {
                keyword = Py.upper(prev.text());
                prop = propertyParser(keyword, Map.of("default", Boolean.TRUE));
            } else {
                break;
            }
            if (!Expression.truthy(prop)) {
                raiseError("Failed to parse property '" + keyword + "'");
                break;
            }
            for (Object p : Py.ensureList(prop)) {
                properties.add((Expression) p);
            }
        }

        return !properties.isEmpty() ? expression(Expression.of(ExprType.PROPERTIES, Args.EXPRESSIONS, properties)) : null;
    }

    @Override
    protected Expression parseUserDefinedFunctionExpression() {
        return parseStatement();
    }

    @Override
    protected Expression parseFunctionParameter() {
        return parseColumnDef(parseIdVar(), false);
    }

    @Override
    protected Expression parseUserDefinedFunction(TokenType kind) {
        Expression this_ = parseTableParts(true);

        if (!match(TokenType.L_PAREN)) {
            return this_;
        }

        List<Expression> expressions = parseCsv(this::parseFunctionParameter);
        matchRParen();
        return expression(Expression.of(ExprType.USER_DEFINED_FUNCTION,
                Args.THIS, this_, Args.EXPRESSIONS, expressions, Args.WRAPPED, Boolean.TRUE));
    }

    @Override
    protected Expression parseMacroOverloads(Expression this_, Expression firstBody, boolean firstIsTable) {
        List<Expression> firstParams = this_.getExpressions();
        List<Expression> overloads = new ArrayList<>();
        overloads.add(expression(Expression.of(ExprType.MACRO_OVERLOAD,
                Args.THIS, firstBody,
                Args.EXPRESSIONS, firstParams.isEmpty() ? null : firstParams,
                Args.IS_TABLE, firstIsTable)));
        this_.set(Args.EXPRESSIONS, null);
        this_.set(Args.WRAPPED, Boolean.FALSE);

        while (match(TokenType.COMMA)) {
            if (!match(TokenType.L_PAREN)) {
                break;
            }

            List<Expression> params = parseCsv(this::parseFunctionParameter);
            matchRParen();

            if (!match(TokenType.ALIAS)) {
                break;
            }

            boolean isTable = match(TokenType.TABLE);
            Expression body = parseExpression();
            Expression macro = Expression.of(ExprType.MACRO_OVERLOAD, Args.THIS, body, Args.EXPRESSIONS, params,
                    Args.IS_TABLE, isTable);
            overloads.add(expression(macro));
        }

        return expression(Expression.of(ExprType.MACRO_OVERLOADS, Args.EXPRESSIONS, overloads));
    }

    @Override
    protected Expression parseIntroducer(Token token) {
        Expression literal = parsePrimary();
        if (literal != null) {
            return expression(Expression.of(ExprType.INTRODUCER, Args.THIS, token.text(), Args.EXPRESSION, literal), token);
        }

        return identifierExpression(token);
    }

    @Override
    protected Expression parseSessionParameter() {
        String kind = null;
        Expression this_ = parseIdVar();
        if (this_ == null) {
            this_ = parsePrimary();
        }

        if (this_ != null && match(TokenType.DOT)) {
            kind = this_.name();
            this_ = parseVar();
            if (this_ == null) {
                this_ = parsePrimary();
            }
        }

        return expression(Expression.of(ExprType.SESSION_PARAMETER, Args.THIS, this_, Args.KIND, kind));
    }

    @Override
    protected Expression parseLambdaArg() {
        return parseIdVar();
    }

    @Override
    protected Expression parseLambda(boolean alias) {
        TokenType nextTokenType = next.type();

        // Fast path: simple atom (column, literal, null, bool) followed by , or )
        if (d.LAMBDA_ARG_TERMINATORS.contains(nextTokenType)) {
            Expression atom = parseAtom();
            if (atom != null) {
                return atom;
            }
        }

        int index = tokenIndex;

        if (match(TokenType.L_PAREN)) {
            List<Expression> expressions = parseCsv(this::parseLambdaArg);

            if (!match(TokenType.R_PAREN)) {
                retreat(index);
            } else if (matchSet(d.LAMBDAS)) {
                return lambdaParser(prev.type(), expressions);
            } else {
                retreat(index);
            }
        } else if (d.TYPED_LAMBDA_ARGS || d.LAMBDAS.contains(nextTokenType)) {
            List<Expression> expressions = new ArrayList<>(1);
            expressions.add(parseLambdaArg());

            if (matchSet(d.LAMBDAS)) {
                return lambdaParser(prev.type(), expressions);
            }

            retreat(index);
        }

        Expression this_;

        if (match(TokenType.DISTINCT)) {
            this_ = expression(Expression.of(ExprType.DISTINCT, Args.EXPRESSIONS, parseCsv(this::parseDisjunction)));
        } else {
            match(TokenType.ALL); // ALL is the default/no-op aggregate modifier (SQL-92)
            this_ = parseSelectOrExpression(alias);
        }

        return parseLimit(parseRespectOrIgnoreNulls(parseOrder(parseHavingMax(parseRespectOrIgnoreNulls(this_)))));
    }

    // ------------------------------------------------------------------------------------------
    // Schemas, column definitions and constraints

    @Override
    protected Expression parseSchema(Expression this_) {
        int index = tokenIndex;
        if (!match(TokenType.L_PAREN)) {
            return this_;
        }

        // Disambiguate between schema and subquery/CTE, e.g. in INSERT INTO table (<expr>),
        // expr can be of both types
        if (matchSet(d.SELECT_START_TOKENS)) {
            retreat(index);
            return this_;
        }
        List<Expression> args = parseCsv(() -> {
            Expression c = parseConstraint();
            return c != null ? c : parseFieldDef();
        });
        matchRParen();
        return expression(Expression.of(ExprType.SCHEMA, Args.THIS, this_, Args.EXPRESSIONS, args));
    }

    @Override
    protected Expression parseFieldDef() {
        return parseColumnDef(parseField(true));
    }

    @Override
    protected Expression parseColumnDef(Expression this_, boolean computedColumn) {
        // column defs are not really columns, they're identifiers
        if (this_ != null && this_.is(ExprType.COLUMN)) {
            this_ = this_.getThis();
        }

        if (!computedColumn) {
            match(TokenType.ALIAS);
        }

        Expression kind = parseTypes(false, true);

        if (matchTextSeq("FOR", "ORDINALITY")) {
            return expression(Expression.of(ExprType.COLUMN_DEF, Args.THIS, this_, Args.ORDINALITY, Boolean.TRUE));
        }

        List<Expression> constraints = new ArrayList<>();

        if ((kind == null && match(TokenType.ALIAS)) || matchTexts(ALIAS_OR_MATERIALIZED)) {
            // Match storage before _parse_types so STORED is not treated as a data type
            // (needed for typeless columns, e.g. SQLite `b AS (a * 2) STORED`).
            boolean persisted = Py.upper(prev.text()).equals("MATERIALIZED");
            Expression expression = parseDisjunction();
            if (!persisted) {
                if (matchTextSeq("PERSISTED")) {
                    persisted = true;
                } else if (matchTexts(STORED_OR_VIRTUAL)) {
                    persisted = Py.upper(prev.text()).equals("STORED");
                }
            }
            Expression constraintKind = Expression.of(ExprType.COMPUTED_COLUMN_CONSTRAINT,
                    Args.THIS, expression,
                    Args.PERSISTED, persisted,
                    Args.DATA_TYPE, matchTextSeq("AUTO") ? Exp.var("AUTO") : parseTypes(),
                    Args.NOT_NULL, matchPair(TokenType.NOT, TokenType.NULL));
            constraints.add(expression(Expression.of(ExprType.COLUMN_CONSTRAINT, Args.KIND, constraintKind)));
        } else if (kind == null && matchSet(IN_OR_OUT, false)) {
            Expression inOutConstraint = expression(Expression.of(ExprType.IN_OUT_COLUMN_CONSTRAINT,
                    Args.INPUT_, match(TokenType.IN),
                    Args.OUTPUT, match(TokenType.OUT)));
            constraints.add(inOutConstraint);
            kind = parseTypes();
        } else if (kind != null
                && match(TokenType.ALIAS, false)
                && (!d.WRAPPED_TRANSFORM_COLUMN_CONSTRAINT || next.type() == TokenType.L_PAREN)) {
            advance();
            Expression computedThis = parseDisjunction();
            boolean persisted = matchTexts(STORED_OR_VIRTUAL) && Py.upper(prev.text()).equals("STORED");
            constraints.add(expression(Expression.of(ExprType.COLUMN_CONSTRAINT,
                    Args.KIND, Expression.of(ExprType.COMPUTED_COLUMN_CONSTRAINT,
                            Args.THIS, computedThis,
                            Args.PERSISTED, persisted))));
        }

        while (true) {
            Expression constraint = parseColumnConstraint();
            if (constraint == null) {
                break;
            }
            constraints.add(constraint);
        }

        if (kind == null && constraints.isEmpty()) {
            return this_;
        }

        Expression position = null;
        if (matchTexts(FIRST_OR_AFTER)) {
            String pos = prev.text();
            position = expression(Expression.of(ExprType.COLUMN_POSITION, Args.THIS, parseColumn(), Args.POSITION, pos));
        }

        return expression(Expression.of(ExprType.COLUMN_DEF,
                Args.THIS, this_,
                Args.KIND, kind,
                Args.CONSTRAINTS, constraints,
                Args.POSITION, position));
    }

    @Override
    protected Expression parseAutoIncrement() {
        Expression start = null;
        Expression increment = null;
        Boolean order = null;

        if (match(TokenType.L_PAREN, false)) {
            List<Expression> args = parseWrappedCsv(this::parseBitwise);
            start = Py.seqGet(args, 0);
            increment = Py.seqGet(args, 1);
        }

        // The remaining parts form an unordered bag and any of them can be omitted, in which
        // case the engine falls back to its own default, so they're parsed independently.
        while (true) {
            if (matchTextSeq("START")) {
                start = parseBitwise();
            } else if (matchTextSeq("INCREMENT")) {
                increment = parseBitwise();
            } else if (matchTextSeq("ORDER")) {
                order = Boolean.TRUE;
            } else if (matchTextSeq("NOORDER")) {
                order = Boolean.FALSE;
            } else {
                break;
            }
        }

        if (start != null || increment != null || order != null) {
            return Expression.of(ExprType.GENERATED_AS_IDENTITY_COLUMN_CONSTRAINT,
                    Args.START, start, Args.INCREMENT, increment, Args.THIS, Boolean.FALSE, Args.ORDER, order);
        }

        return Expression.of(ExprType.AUTO_INCREMENT_COLUMN_CONSTRAINT);
    }

    @Override
    protected Expression parseCheckConstraint() {
        if (!match(TokenType.L_PAREN, false)) {
            return null;
        }

        return expression(Expression.of(ExprType.CHECK_COLUMN_CONSTRAINT,
                Args.THIS, parseWrapped(() -> parseAssignment()),
                Args.ENFORCED, matchTextSeq("ENFORCED")));
    }

    @Override
    protected Expression parseAutoProperty() {
        if (!matchTextSeq("REFRESH")) {
            retreat(tokenIndex - 1);
            return null;
        }
        return expression(Expression.of(ExprType.AUTO_REFRESH_PROPERTY, Args.THIS, parseVar(false, null, true)));
    }

    @Override
    protected Expression parseCompress() {
        if (match(TokenType.L_PAREN, false)) {
            return expression(Expression.of(ExprType.COMPRESS_COLUMN_CONSTRAINT,
                    Args.THIS, parseWrappedCsv(this::parseBitwise)));
        }

        return expression(Expression.of(ExprType.COMPRESS_COLUMN_CONSTRAINT, Args.THIS, parseBitwise()));
    }

    @Override
    protected Expression parseGeneratedAsIdentity() {
        Expression this_;
        if (matchTextSeq("BY", "DEFAULT")) {
            boolean onNull = matchPair(TokenType.ON, TokenType.NULL);
            this_ = expression(Expression.of(ExprType.GENERATED_AS_IDENTITY_COLUMN_CONSTRAINT,
                    Args.THIS, Boolean.FALSE, Args.ON_NULL, onNull));
        } else {
            matchTextSeq("ALWAYS");
            this_ = expression(Expression.of(ExprType.GENERATED_AS_IDENTITY_COLUMN_CONSTRAINT, Args.THIS, Boolean.TRUE));
        }

        match(TokenType.ALIAS);

        if (matchTextSeq("ROW")) {
            boolean start = matchTextSeq("START");
            if (!start) {
                match(TokenType.END);
            }
            boolean hidden = matchTextSeq("HIDDEN");
            return expression(Expression.of(ExprType.GENERATED_AS_ROW_COLUMN_CONSTRAINT,
                    Args.START, start, Args.HIDDEN, hidden));
        }

        boolean identity = matchTextSeq("IDENTITY");

        if (match(TokenType.L_PAREN)) {
            if (matchTextSeq("START", "WITH")) {
                this_.set(Args.START, parseBitwise());
            }
            if (matchTextSeq("INCREMENT", "BY")) {
                this_.set(Args.INCREMENT, parseBitwise());
            }
            if (matchTextSeq("MINVALUE")) {
                this_.set(Args.MINVALUE, parseBitwise());
            }
            if (matchTextSeq("MAXVALUE")) {
                this_.set(Args.MAXVALUE, parseBitwise());
            }

            if (matchTextSeq("CYCLE")) {
                this_.set(Args.CYCLE, Boolean.TRUE);
            } else if (matchTextSeq("NO", "CYCLE")) {
                this_.set(Args.CYCLE, Boolean.FALSE);
            }

            if (!identity) {
                this_.set(Args.EXPRESSION, parseRange());
            } else if (!this_.has(Args.START) && match(TokenType.NUMBER, false)) {
                List<Expression> args = parseCsv(this::parseBitwise);
                this_.set(Args.START, Py.seqGet(args, 0));
                this_.set(Args.INCREMENT, Py.seqGet(args, 1));
            }

            matchRParen();
        }

        return this_;
    }

    @Override
    protected Expression parseInline() {
        matchTextSeq("LENGTH");
        return expression(Expression.of(ExprType.INLINE_LENGTH_COLUMN_CONSTRAINT, Args.THIS, parseBitwise()));
    }

    @Override
    protected Expression parseNotConstraint() {
        if (matchTextSeq("NULL")) {
            return expression(Expression.of(ExprType.NOT_NULL_COLUMN_CONSTRAINT));
        }
        if (matchTextSeq("CASESPECIFIC")) {
            return expression(Expression.of(ExprType.CASE_SPECIFIC_COLUMN_CONSTRAINT, Args.NOT_, Boolean.TRUE));
        }
        if (matchTextSeq("FOR", "REPLICATION")) {
            return expression(Expression.of(ExprType.NOT_FOR_REPLICATION_COLUMN_CONSTRAINT));
        }

        // Unconsume the `NOT` token
        retreat(tokenIndex - 1);
        return null;
    }

    @Override
    protected Expression parseColumnConstraint() {
        Expression this_ = match(TokenType.CONSTRAINT) && !matchTexts(d.RESERVED_CONSTRAINT_KINDS, false)
                ? parseIdVar()
                : null;

        boolean procedureOptionFollows = match(TokenType.WITH, false)
                && present(next)
                && d.PROCEDURE_OPTIONS.containsKey(Py.upper(next.text()));

        int index = tokenIndex;
        if (!procedureOptionFollows && matchTexts(d.CONSTRAINT_PARSERS)) {
            Expression constraint = constraintParser(Py.upper(prev.text()));
            if (constraint == null) {
                retreat(index);
                return null;
            }

            return expression(Expression.of(ExprType.COLUMN_CONSTRAINT, Args.THIS, this_, Args.KIND, constraint));
        }

        if (match(TokenType.CHARACTER_SET) || matchPair(TokenType.CHAR, TokenType.SET)) {
            return expression(Expression.of(ExprType.COLUMN_CONSTRAINT,
                    Args.THIS, this_,
                    Args.KIND, expression(Expression.of(ExprType.CHARACTER_SET_COLUMN_CONSTRAINT,
                            Args.THIS, parseVarOrString()))));
        }

        return this_;
    }

    @Override
    protected Expression parseConstraint() {
        if (!match(TokenType.CONSTRAINT) || matchTexts(d.RESERVED_CONSTRAINT_KINDS, false)) {
            return parseUnnamedConstraint(d.SCHEMA_UNNAMED_CONSTRAINTS);
        }

        return expression(Expression.of(ExprType.CONSTRAINT,
                Args.THIS, parseIdVar(),
                Args.EXPRESSIONS, parseUnnamedConstraints()));
    }

    @Override
    protected List<Expression> parseUnnamedConstraints() {
        List<Expression> constraints = new ArrayList<>();
        while (true) {
            Expression constraint = parseUnnamedConstraint();
            if (constraint == null) {
                constraint = parseFunction();
            }
            if (constraint == null) {
                break;
            }
            constraints.add(constraint);
        }

        return constraints;
    }

    @Override
    protected Expression parseUnnamedConstraint(Collection<String> constraints) {
        int index = tokenIndex;

        if (match(TokenType.IDENTIFIER, false)
                || !matchTexts(constraints != null && !constraints.isEmpty() ? constraints : d.CONSTRAINT_PARSERS)) {
            return null;
        }

        String constraintKey = Py.upper(prev.text());
        if (!d.CONSTRAINT_PARSERS.contains(constraintKey)) {
            raiseError("No parser found for schema constraint " + constraintKey + ".");
        }

        Expression result = constraintParser(constraintKey);
        if (result == null) {
            retreat(index);
        }

        return result;
    }

    @Override
    protected Expression parseUniqueKey() {
        if (present(curr)
                && curr.type() != TokenType.IDENTIFIER
                && d.CONSTRAINT_PARSERS.contains(Py.upper(curr.text()))) {
            return null;
        }
        return parseIdVar(false);
    }

    @Override
    protected Expression parseUnique() {
        matchTexts(KEY_OR_INDEX);
        boolean nulls = matchTextSeq("NULLS", "NOT", "DISTINCT");
        Expression this_ = parseSchema(parseUniqueKey());
        Object indexType = match(TokenType.USING) ? (advanceAny() != null ? prev.text() : null) : Boolean.FALSE;
        Expression onConflict = parseOnConflict();
        Collection<String> options = parseKeyConstraintOptions();
        return expression(Expression.of(ExprType.UNIQUE_COLUMN_CONSTRAINT,
                Args.NULLS, nulls,
                Args.THIS, this_,
                Args.INDEX_TYPE, indexType,
                Args.ON_CONFLICT, onConflict,
                Args.OPTIONS, options));
    }

    @Override
    protected Collection<String> parseKeyConstraintOptions() {
        List<String> options = new ArrayList<>();
        while (true) {
            if (!present(curr)) {
                break;
            }

            if (match(TokenType.ON)) {
                String action = null;
                String on = advanceAny() != null ? prev.text() : null;

                if (matchTextSeq("NO", "ACTION")) {
                    action = "NO ACTION";
                } else if (matchTextSeq("CASCADE")) {
                    action = "CASCADE";
                } else if (matchTextSeq("RESTRICT")) {
                    action = "RESTRICT";
                } else if (matchPair(TokenType.SET, TokenType.NULL)) {
                    action = "SET NULL";
                } else if (matchPair(TokenType.SET, TokenType.DEFAULT)) {
                    action = "SET DEFAULT";
                } else {
                    raiseError("Invalid key constraint");
                }

                options.add("ON " + Py.str(on) + " " + Py.str(action));
            } else {
                Expression var = parseVarFromOptions(d.KEY_CONSTRAINT_OPTIONS, false);
                if (var == null) {
                    break;
                }
                options.add(var.name());
            }
        }

        return options;
    }

    @Override
    protected Expression parseReferences(boolean match) {
        if (match && !match(TokenType.REFERENCES)) {
            return null;
        }

        List<Expression> expressions = null;
        Expression this_ = parseTable(true);
        Collection<String> options = parseKeyConstraintOptions();
        return expression(Expression.of(ExprType.REFERENCE, Args.THIS, this_, Args.EXPRESSIONS, expressions,
                Args.OPTIONS, options));
    }

    @Override
    protected Expression parseForeignKey() {
        List<Expression> expressions = !match(TokenType.REFERENCES, false) ? parseWrappedIdVars() : null;
        Expression reference = parseReferences();
        Map<String, String> onOptions = new LinkedHashMap<>();

        while (match(TokenType.ON)) {
            if (!matchSet(DELETE_OR_UPDATE)) {
                raiseError("Expected DELETE or UPDATE");
            }

            String kind = Py.lower(prev.text());

            String action;
            if (matchTextSeq("NO", "ACTION")) {
                action = "NO ACTION";
            } else if (match(TokenType.SET)) {
                matchSet(NULL_OR_DEFAULT);
                action = "SET " + Py.upper(prev.text());
            } else {
                advance();
                action = Py.upper(prev.text());
            }

            onOptions.put(kind, action);
        }

        Expression foreignKey = Expression.of(ExprType.FOREIGN_KEY,
                Args.EXPRESSIONS, expressions,
                Args.REFERENCE, reference,
                Args.OPTIONS, parseKeyConstraintOptions());
        for (Map.Entry<String, String> entry : onOptions.entrySet()) {
            Arg key = Arg.forName(entry.getKey());
            if (key != null && foreignKey.accepts(key)) {
                foreignKey.set(key, entry.getValue());
            }
        }
        return expression(foreignKey);
    }

    @Override
    protected Expression parsePrimaryKeyPart() {
        return parseField();
    }

    @Override
    protected Expression parsePeriodForSystemTime() {
        if (!matchTextSeq("FOR", "SYSTEM_TIME")) {
            retreat(tokenIndex - 1);
            return null;
        }

        List<Expression> idVars = parseWrappedIdVars();
        return expression(Expression.of(ExprType.PERIOD_FOR_SYSTEM_TIME_CONSTRAINT,
                Args.THIS, Py.seqGet(idVars, 0), Args.EXPRESSION, Py.seqGet(idVars, 1)));
    }

    @Override
    protected Expression parsePrimaryKey(boolean wrappedOptional, boolean inProps, boolean namedPrimaryKey) {
        Boolean desc = matchSet(ASC_OR_DESC) ? Boolean.valueOf(prev.type() == TokenType.DESC) : null;

        Expression this_ = null;
        if (namedPrimaryKey
                && !d.CONSTRAINT_PARSERS.contains(Py.upper(curr.text()))
                && present(next)
                && next.type() == TokenType.L_PAREN) {
            this_ = parseIdVar();
        }

        if (!inProps && !match(TokenType.L_PAREN, false)) {
            return expression(Expression.of(ExprType.PRIMARY_KEY_COLUMN_CONSTRAINT,
                    Args.DESC, desc, Args.OPTIONS, parseKeyConstraintOptions()));
        }

        List<Expression> expressions = parseWrappedCsv(this::parsePrimaryKeyPart, wrappedOptional);

        return expression(Expression.of(ExprType.PRIMARY_KEY,
                Args.THIS, this_,
                Args.EXPRESSIONS, expressions,
                Args.INCLUDE, parseIndexParams(),
                Args.OPTIONS, parseKeyConstraintOptions()));
    }

    // ---------------------------------------------------------------------------------------
    // From ParserPartE

    private static final Set<TokenType> FORMAT_OR_COMMA = EnumSet.of(TokenType.FORMAT, TokenType.COMMA);
    private static final Set<TokenType> ROWS_OR_RANGE = EnumSet.of(TokenType.ROWS, TokenType.RANGE);
    private static final Set<TokenType> FROM_OR_COMMA = EnumSet.of(TokenType.FROM, TokenType.COMMA);
    private static final Set<TokenType> NULL_OR_UNKNOWN = EnumSet.of(TokenType.NULL, TokenType.UNKNOWN);
    private static final Set<TokenType> CHARSET_NAME_TOKENS = EnumSet.of(TokenType.BINARY, TokenType.IDENTIFIER);
    private static final Set<String> MAX_OR_MIN = Set.of("MAX", "MIN");



    /** {@code exp.DataType.from_str(dtype, dialect=dialect, udt=udt)}. */
    private static Expression dataTypeFromStr(String dtype, Dialect dialect, boolean udt) {
        return DataTypes.build(dtype, dialect, udt, false);
    }

    // ------------------------------------------------------------------------------------------
    // Brackets and slices

    @Override
    protected Expression parseBracketKeyValue(boolean isMap) {
        return parseSlice(parseAlias(parseDisjunction(), true));
    }

    /**
     * Parses a datetime column in ODBC format. We parse the column into the corresponding types,
     * for example {@code {d'yyyy-mm-dd'}} will be parsed as a {@code Date} column, exactly the same
     * as we did for {@code DATE('yyyy-mm-dd')}.
     */
    @Override
    protected Expression parseOdbcDatetimeLiteral() {
        match(TokenType.VAR);
        ExprType expClass = d.ODBC_DATETIME_LITERALS.get(Py.lower(prev.text()));
        Expression expression = expression(Expression.of(expClass, Args.THIS, parseString()));
        if (!match(TokenType.R_BRACE)) {
            raiseError("Expected }");
        }
        return expression;
    }

    @Override
    protected Expression parseBracket(Expression this_) {
        if (!matchSet(d.BRACKETS)) {
            return this_;
        }

        boolean parseMap;
        if (d.MAP_KEYS_ARE_ARBITRARY_EXPRESSIONS) {
            Token mapToken = Py.seqGet(tokenList, tokenIndex - 2);
            parseMap = mapToken != null && Py.upper(mapToken.text()).equals("MAP");
        } else {
            parseMap = false;
        }

        TokenType bracketKind = prev.type();
        if (bracketKind == TokenType.L_BRACE
                && present(curr)
                && curr.type() == TokenType.VAR
                && d.ODBC_DATETIME_LITERALS.containsKey(Py.lower(curr.text()))) {
            return parseOdbcDatetimeLiteral();
        }

        List<Expression> expressions = parseCsv(() -> parseBracketKeyValue(bracketKind == TokenType.L_BRACE));

        if (bracketKind == TokenType.L_BRACKET && !match(TokenType.R_BRACKET)) {
            raiseError("Expected ]");
        } else if (bracketKind == TokenType.L_BRACE && !match(TokenType.R_BRACE)) {
            raiseError("Expected }");
        }

        // https://duckdb.org/docs/sql/data_types/struct.html#creating-structs
        if (bracketKind == TokenType.L_BRACE) {
            this_ = expression(Expression.of(ExprType.STRUCT, Args.EXPRESSIONS, kvToPropEq(expressions, parseMap)));
        } else if (this_ == null) {
            this_ = buildArrayConstructor(ExprType.ARRAY, expressions, bracketKind);
        } else {
            ExprType constructorType = d.ARRAY_CONSTRUCTORS.get(Py.upper(this_.name()));
            if (constructorType != null) {
                return buildArrayConstructor(constructorType, expressions, bracketKind);
            }

            expressions = Exp.applyIndexOffset(this_, expressions, -dd.INDEX_OFFSET, dialect);
            this_ = expression(Expression.of(ExprType.BRACKET, Args.THIS, this_, Args.EXPRESSIONS, expressions),
                    this_.popComments());
        }

        addComments(this_);
        return parseBracket(this_);
    }

    @Override
    protected Expression parseSlice(Expression this_) {
        if (!match(TokenType.COLON)) {
            return this_;
        }

        Expression end;
        if (matchPair(TokenType.DASH, TokenType.COLON, false)) {
            advance();
            end = Expression.of(ExprType.NEG, Args.THIS, Exp.number("1"));
        } else {
            end = parseAssignment();
        }
        Expression step = match(TokenType.COLON) ? parseUnary() : null;
        return build(ExprType.SLICE, Args.THIS, this_, Args.EXPRESSION, end, Args.STEP, step);
    }

    // ------------------------------------------------------------------------------------------
    // CASE / IF / NEXT VALUE FOR

    @Override
    protected Expression parseCase() {
        if (match(TokenType.DOT, false)) {
            // Avoid raising on valid expressions like case.*, supported by, e.g., spark & snowflake
            retreat(tokenIndex - 1);
            return null;
        }

        List<Expression> ifs = new ArrayList<>();
        Expression default_ = null;

        List<String> comments = prevComments;
        Expression expression = parseDisjunction();

        while (match(TokenType.WHEN)) {
            Expression this_ = parseDisjunction();
            match(TokenType.THEN);
            Expression then = parseDisjunction();
            ifs.add(build(ExprType.IF, Args.THIS, this_, Args.TRUE, then));
        }

        if (match(TokenType.ELSE)) {
            default_ = parseDisjunction();
        }

        if (!match(TokenType.END)) {
            if (default_ != null && default_.is(ExprType.INTERVAL)
                    && Py.upper(default_.getThis().sql(Dialects.base())).equals("END")) {
                default_ = Exp.column("interval");
            } else {
                raiseError("Expected END after CASE", prev);
            }
        }

        return expression(
                Expression.of(ExprType.CASE, Args.THIS, expression, Args.IFS, ifs, Args.DEFAULT, default_), comments);
    }

    @Override
    protected Expression parseIf() {
        Expression this_;
        if (match(TokenType.L_PAREN)) {
            List<Expression> args = parseCsv(() -> parseAlias(parseAssignment(), true));
            this_ = validateExpression(Exp.fromArgList(ExprType.IF, args), args);
            matchRParen();
        } else {
            int index = tokenIndex - 1;

            if (d.NO_PAREN_IF_COMMANDS && index == 0) {
                return parseAsCommand(prev);
            }

            Expression condition = parseDisjunction();

            if (condition == null || !match(TokenType.THEN)) {
                retreat(index);
                return null;
            }

            Expression true_ = parseDisjunction();
            Expression false_ = match(TokenType.ELSE) ? parseDisjunction() : null;
            match(TokenType.END);
            this_ = build(ExprType.IF, Args.THIS, condition, Args.TRUE, true_, Args.FALSE, false_);
        }

        return this_;
    }

    @Override
    protected Expression parseNextValueFor() {
        if (!matchTextSeq("VALUE", "FOR")) {
            retreat(tokenIndex - 1);
            return null;
        }

        Expression column = parseColumn();
        Object order = match(TokenType.OVER) ? (Object) parseWrapped(() -> parseOrder()) : Boolean.FALSE;
        return build(ExprType.NEXT_VALUE_FOR, Args.THIS, column, Args.ORDER, order);
    }

    // ------------------------------------------------------------------------------------------
    // Special syntax functions

    @Override
    protected Expression parseExtract() {
        Expression this_ = parseFunction();
        if (this_ == null) {
            this_ = parseVarOrString(true);
        }

        if (match(TokenType.FROM)) {
            return build(ExprType.EXTRACT, Args.THIS, this_, Args.EXPRESSION, parseBitwise());
        }

        if (!match(TokenType.COMMA)) {
            raiseError("Expected FROM or comma after EXTRACT", prev);
        }

        return build(ExprType.EXTRACT, Args.THIS, this_, Args.EXPRESSION, parseBitwise());
    }

    @Override
    protected Expression parseGapFill() {
        match(TokenType.TABLE);
        Expression this_ = parseTable();

        match(TokenType.COMMA);
        List<Expression> args = new ArrayList<>();
        args.add(this_);
        args.addAll(parseCsv(() -> parseLambda()));

        Expression gapFill = Exp.fromArgList(ExprType.GAP_FILL, args);
        return validateExpression(gapFill, args);
    }

    @Override
    protected Expression parseChar() {
        List<Expression> expressions = parseCsv(this::parseAssignment);
        Object charset = match(TokenType.USING) ? (Object) parseCharsetName() : Boolean.FALSE;
        return build(ExprType.CHR, Args.EXPRESSIONS, expressions, Args.CHARSET, charset);
    }

    /**
     * Parse a charset name after USING or CHARACTER SET. Dialects that need to preserve quoting for
     * specific name shapes override this.
     */
    @Override
    protected Expression parseCharsetName() {
        return parseVar(false, CHARSET_NAME_TOKENS);
    }

    @Override
    protected Expression parseWeightString() {
        String name = tokenList.get(tokenIndex - 2).text();
        List<Expression> args = parseCsv(this::parseAssignment);

        // MySQL also accepts an undocumented WEIGHT_STRING(str, n, n, n) form
        if (args.size() != 1) {
            return build(ExprType.ANONYMOUS, Args.THIS, name, Args.EXPRESSIONS, args);
        }

        Object to = match(TokenType.ALIAS) ? (Object) parseTypes() : Boolean.FALSE;
        return build(ExprType.WEIGHT_STRING, Args.THIS, args.get(0), Args.TO, to);
    }

    @Override
    protected Expression parseCast(boolean strict, Boolean safe) {
        Expression this_ = parseAssignment();

        if (!match(TokenType.ALIAS)) {
            if (match(TokenType.COMMA)) {
                return build(ExprType.CAST_TO_STR_TYPE, Args.THIS, this_, Args.TO, parseString());
            }

            raiseError("Expected AS after CAST");
        }

        Expression fmt = null;
        Expression to = parseTypes(false, false, true, true);

        Expression default_ = null;
        if (match(TokenType.DEFAULT)) {
            default_ = parseBitwise();
            matchTextSeq("ON", "CONVERSION", "ERROR");
        }

        if (matchSet(FORMAT_OR_COMMA)) {
            Expression fmtString = parseWrapped(this::parseString, true);
            fmt = parseAtTimeZone(fmtString);

            if (to == null) {
                to = DataTypes.of(DType.UNKNOWN);
            }
            Object toThis = to.thisValue();
            if (toThis instanceof DType dt && DataTypes.TEMPORAL_TYPES.contains(dt)) {
                String fmtText = fmtString != null ? (String) fmtString.thisValue() : "";
                this_ = expression(Expression.of(
                        toThis == DType.DATE ? ExprType.STR_TO_DATE : ExprType.STR_TO_TIME,
                        Args.THIS, this_,
                        Args.FORMAT, Exp.string(TimeFormat.formatTime(
                                fmtText,
                                dd.FORMAT_MAPPING.isEmpty() ? dd.TIME_MAPPING : dd.FORMAT_MAPPING,
                                dialect.formatTrie())),
                        Args.SAFE, safe));

                if (fmt != null && fmt.is(ExprType.AT_TIME_ZONE) && this_.is(ExprType.STR_TO_TIME)) {
                    this_.set(Args.ZONE, fmt.get(Args.ZONE));
                }
                return this_;
            }
        } else if (to == null) {
            raiseError("Expected TYPE after CAST");
        } else if (to.is(ExprType.IDENTIFIER)) {
            to = dataTypeFromStr(to.name(), dialect, true);
        } else {
            to = parseCharCharset(to);
        }

        Map<Arg, Object> kwargs = new LinkedHashMap<>();
        kwargs.put(Args.THIS, this_);
        kwargs.put(Args.TO, to);
        kwargs.put(Args.FORMAT, fmt);
        kwargs.put(Args.SAFE, safe);
        kwargs.put(Args.ACTION, parseVarFromOptions(d.CAST_ACTIONS, false));
        kwargs.put(Args.DEFAULT, default_);
        return buildCast(strict, kwargs);
    }

    @Override
    protected Expression parseStringAgg() {
        List<Expression> args;
        if (match(TokenType.DISTINCT)) {
            args = new ArrayList<>();
            List<Expression> distinctExpressions = new ArrayList<>();
            distinctExpressions.add(parseDisjunction());
            args.add(build(ExprType.DISTINCT, Args.EXPRESSIONS, distinctExpressions));
            if (match(TokenType.COMMA)) {
                args.addAll(parseCsv(this::parseDisjunction));
            }
        } else {
            args = parseCsv(this::parseDisjunction);
        }

        Expression onOverflow;
        if (matchTextSeq("ON", "OVERFLOW")) {
            // trino: LISTAGG(expression [, separator] [ON OVERFLOW overflow_behavior])
            if (matchTextSeq("ERROR")) {
                onOverflow = Exp.var("ERROR");
            } else {
                matchTextSeq("TRUNCATE");
                Expression truncThis = parseString();
                boolean withCount = matchTextSeq("WITH", "COUNT") || !matchTextSeq("WITHOUT", "COUNT");
                onOverflow = build(ExprType.OVERFLOW_TRUNCATE_BEHAVIOR, Args.THIS, truncThis,
                        Args.WITH_COUNT, withCount);
            }
        } else {
            onOverflow = null;
        }

        int index = tokenIndex;
        if (!match(TokenType.R_PAREN) && !args.isEmpty()) {
            // postgres: STRING_AGG([DISTINCT] expression, separator [ORDER BY expression1 {ASC | DESC} [, ...]])
            // bigquery: STRING_AGG([DISTINCT] expression [, separator] [ORDER BY key [{ASC | DESC}] [, ... ]] [LIMIT n])
            // The order is parsed through `this` as a canonicalization for WITHIN GROUPs
            args.set(0, parseLimit(parseOrder(args.get(0))));
            return build(ExprType.GROUP_CONCAT, Args.THIS, args.get(0), Args.SEPARATOR, Py.seqGet(args, 1));
        }

        // Checks if we can parse an order clause: WITHIN GROUP (ORDER BY <order_by_expression_list> [ASC | DESC]).
        // This is done "manually", instead of letting _parse_window parse it into an exp.WithinGroup node, so that
        // the STRING_AGG call is parsed like in MySQL / SQLite and can thus be transpiled more easily to them.
        if (!matchTextSeq("WITHIN", "GROUP")) {
            retreat(index);
            return validateExpression(Exp.fromArgList(ExprType.GROUP_CONCAT, args), args);
        }

        // The corresponding match_r_paren will be called in parse_function (caller)
        matchLParen();

        return build(ExprType.GROUP_CONCAT,
                Args.THIS, parseOrder(Py.seqGet(args, 0)),
                Args.SEPARATOR, Py.seqGet(args, 1),
                Args.ON_OVERFLOW, onOverflow);
    }

    @Override
    protected Expression parseConvert(boolean strict, Boolean safe) {
        Expression this_ = parseBitwise();

        Expression to;
        if (match(TokenType.USING)) {
            to = Expression.of(ExprType.DATA_TYPE, Args.THIS, DType.CHARACTER_SET, Args.KIND, parseCharsetName());
        } else if (match(TokenType.COMMA)) {
            to = parseCharCharset(parseTypes());
        } else {
            to = null;
        }

        Map<Arg, Object> kwargs = new LinkedHashMap<>();
        kwargs.put(Args.THIS, this_);
        kwargs.put(Args.TO, to);
        kwargs.put(Args.SAFE, safe);
        return buildCast(strict, kwargs);
    }

    @Override
    protected Expression parseCharCharset(Expression to) {
        // MySQL: CHAR[(N)] CHARACTER SET charset_name, the length is kept
        if (to != null
                && to.is(ExprType.DATA_TYPE)
                && to.thisValue() == DType.CHAR
                && (match(TokenType.CHARACTER_SET) || matchTextSeq("CHARACTER", "SET"))) {
            to.set(Args.THIS, DType.CHARACTER_SET);
            to.set(Args.KIND, parseVarOrString());
        }
        return to;
    }

    @Override
    protected Expression parseXmlElement() {
        Boolean evalname;
        Expression this_;
        if (matchTextSeq("EVALNAME")) {
            evalname = Boolean.TRUE;
            this_ = parseBitwise();
        } else {
            evalname = null;
            matchTextSeq("NAME");
            this_ = parseIdVar();
        }

        Object expressions = match(TokenType.COMMA) ? (Object) parseCsv(this::parseBitwise) : Boolean.FALSE;
        return build(ExprType.XML_ELEMENT, Args.THIS, this_, Args.EXPRESSIONS, expressions, Args.EVALNAME, evalname);
    }

    @Override
    protected Expression parseXmlTable() {
        List<Expression> namespaces = null;
        List<Expression> passing = null;
        List<Expression> columns = null;

        if (matchTextSeq("XMLNAMESPACES", "(")) {
            namespaces = parseXmlNamespace();
            matchTextSeq(")", ",");
        }

        Expression this_ = parseString();

        if (matchTextSeq("PASSING")) {
            // The BY VALUE keywords are optional and are provided for semantic clarity
            matchTextSeq("BY", "VALUE");
            passing = parseCsv(this::parseColumn);
        }

        boolean byRef = matchTextSeq("RETURNING", "SEQUENCE", "BY", "REF");

        if (matchTextSeq("COLUMNS")) {
            columns = parseCsv(this::parseFieldDef);
        }

        return build(ExprType.XML_TABLE,
                Args.THIS, this_,
                Args.NAMESPACES, namespaces,
                Args.PASSING, passing,
                Args.COLUMNS, columns,
                Args.BY_REF, byRef);
    }

    @Override
    protected List<Expression> parseXmlNamespace() {
        List<Expression> namespaces = new ArrayList<>();

        while (true) {
            Expression uri;
            if (match(TokenType.DEFAULT)) {
                uri = parseString();
            } else {
                uri = parseAlias(parseString());
            }
            namespaces.add(build(ExprType.XML_NAMESPACE, Args.THIS, uri));
            if (!match(TokenType.COMMA)) {
                break;
            }
        }

        return namespaces;
    }

    @Override
    protected Expression parseDecode() {
        List<Expression> args = parseCsv(this::parseDisjunction);

        if (args.size() < 3) {
            return build(ExprType.DECODE, Args.THIS, Py.seqGet(args, 0), Args.CHARSET, Py.seqGet(args, 1));
        }

        return build(ExprType.DECODE_CASE, Args.EXPRESSIONS, args);
    }

    // ------------------------------------------------------------------------------------------
    // JSON

    @Override
    protected Expression parseJsonKeyValue() {
        matchTextSeq("KEY");
        Expression key = parseColumn();
        matchSet(d.JSON_KEY_VALUE_SEPARATOR_TOKENS);
        matchTextSeq("VALUE");
        Expression value = parseBitwise();

        if (key == null && value == null) {
            return null;
        }
        return build(ExprType.JSON_KEY_VALUE, Args.THIS, key, Args.EXPRESSION, value);
    }

    @Override
    protected Expression parseFormatJson(Expression this_) {
        if (this_ == null || !matchTextSeq("FORMAT", "JSON")) {
            return this_;
        }

        return build(ExprType.FORMAT_JSON, Args.THIS, this_);
    }

    @Override
    protected Expression parseOnCondition() {
        String[] tokens = d.ON_CONDITION_TOKENS.toArray(new String[0]);
        Object empty;
        Object error;
        // MySQL uses "X ON EMPTY Y ON ERROR" (e.g. JSON_VALUE) while Oracle uses the opposite (e.g. JSON_EXISTS)
        if (dd.ON_CONDITION_EMPTY_BEFORE_ERROR) {
            empty = parseOnHandling("EMPTY", tokens);
            error = parseOnHandling("ERROR", tokens);
        } else {
            error = parseOnHandling("ERROR", tokens);
            empty = parseOnHandling("EMPTY", tokens);
        }

        Object null_ = parseOnHandling("NULL", tokens);

        if (!Expression.truthy(empty) && !Expression.truthy(error) && !Expression.truthy(null_)) {
            return null;
        }

        return build(ExprType.ON_CONDITION, Args.EMPTY, empty, Args.ERROR, error, Args.NULL, null_);
    }

    @Override
    protected Object parseOnHandling(String on, String... values) {
        // Parses the "X ON Y" or "DEFAULT <expr> ON Y syntax, e.g. NULL ON NULL (Oracle, T-SQL, MySQL)
        for (String value : values) {
            if (matchTextSeq(value, "ON", on)) {
                return value + " ON " + on;
            }
        }

        int index = tokenIndex;
        if (match(TokenType.DEFAULT)) {
            Expression defaultValue = parseBitwise();
            if (matchTextSeq("ON", on)) {
                return defaultValue;
            }

            retreat(index);
        }

        return null;
    }

    @Override
    protected Object parseJsonObject(Object agg) {
        Expression star = parseStar();
        List<Expression> expressions;
        if (star != null) {
            expressions = new ArrayList<>(List.of(star));
        } else {
            expressions = parseCsv(() -> parseFormatJson(parseJsonKeyValue()));
        }
        Object nullHandling = parseOnHandling("NULL", "NULL", "ABSENT");

        Boolean uniqueKeys = null;
        if (matchTextSeq("WITH", "UNIQUE")) {
            uniqueKeys = Boolean.TRUE;
        } else if (matchTextSeq("WITHOUT", "UNIQUE")) {
            uniqueKeys = Boolean.FALSE;
        }

        matchTextSeq("KEYS");

        Object returnType = matchTextSeq("RETURNING") ? (Object) parseFormatJson(parseType()) : Boolean.FALSE;
        Object encoding = matchTextSeq("ENCODING") ? (Object) parseVar() : Boolean.FALSE;

        return build(Expression.truthy(agg) ? ExprType.JSON_OBJECT_AGG : ExprType.JSON_OBJECT,
                Args.EXPRESSIONS, expressions,
                Args.NULL_HANDLING, nullHandling,
                Args.UNIQUE_KEYS, uniqueKeys,
                Args.RETURN_TYPE, returnType,
                Args.ENCODING, encoding);
    }

    // Note: this is currently incomplete; it only implements the "JSON_value_column" part
    @Override
    protected Expression parseJsonColumnDef() {
        Expression this_;
        Boolean ordinality;
        Expression kind;
        Boolean nested;
        if (!matchTextSeq("NESTED")) {
            this_ = parseIdVar();
            ordinality = matchPair(TokenType.FOR, TokenType.ORDINALITY);
            kind = parseTypes(false, false, false);
            nested = null;
        } else {
            this_ = null;
            ordinality = null;
            kind = null;
            nested = Boolean.TRUE;
        }

        boolean formatJson = matchTextSeq("FORMAT", "JSON");
        Object path = matchTextSeq("PATH") ? (Object) parseString() : Boolean.FALSE;
        Object nestedSchema = nested != null && nested ? parseJsonSchema() : nested;

        return build(ExprType.JSON_COLUMN_DEF,
                Args.THIS, this_,
                Args.KIND, kind,
                Args.PATH, path,
                Args.NESTED_SCHEMA, nestedSchema,
                Args.ORDINALITY, ordinality,
                Args.FORMAT_JSON, formatJson);
    }

    @Override
    protected Expression parseJsonSchema() {
        matchTextSeq("COLUMNS");
        return build(ExprType.JSON_SCHEMA, Args.EXPRESSIONS, parseWrappedCsv(this::parseJsonColumnDef, true));
    }

    @Override
    protected Expression parseJsonTable() {
        Expression this_ = parseFormatJson(parseBitwise());
        Object path = match(TokenType.COMMA) ? (Object) parseString() : Boolean.FALSE;
        Object errorHandling = parseOnHandling("ERROR", "ERROR", "NULL");
        Object emptyHandling = parseOnHandling("EMPTY", "ERROR", "NULL");
        Expression schema = parseJsonSchema();

        return Expression.of(ExprType.JSON_TABLE,
                Args.THIS, this_,
                Args.SCHEMA, schema,
                Args.PATH, path,
                Args.ERROR_HANDLING, errorHandling,
                Args.EMPTY_HANDLING, emptyHandling);
    }

    @Override
    protected Expression parseMatchAgainst() {
        List<Expression> expressions;
        if (matchTextSeq("TABLE")) {
            // parse SingleStore MATCH(TABLE ...) syntax
            // https://docs.singlestore.com/cloud/reference/sql-reference/full-text-search-functions/match/
            expressions = new ArrayList<>();
            Expression table = parseTable();
            if (table != null) {
                expressions = new ArrayList<>(List.of(table));
            }
        } else {
            expressions = parseCsv(this::parseColumn);
        }

        matchTextSeq(")", "AGAINST", "(");

        Expression this_ = parseString();

        String modifier;
        if (matchTextSeq("IN", "NATURAL", "LANGUAGE", "MODE")) {
            modifier = "IN NATURAL LANGUAGE MODE";
            if (matchTextSeq("WITH", "QUERY", "EXPANSION")) {
                modifier = modifier + " WITH QUERY EXPANSION";
            }
        } else if (matchTextSeq("IN", "BOOLEAN", "MODE")) {
            modifier = "IN BOOLEAN MODE";
        } else if (matchTextSeq("WITH", "QUERY", "EXPANSION")) {
            modifier = "WITH QUERY EXPANSION";
        } else {
            modifier = null;
        }

        return build(ExprType.MATCH_AGAINST, Args.THIS, this_, Args.EXPRESSIONS, expressions, Args.MODIFIER, modifier);
    }

    // https://learn.microsoft.com/en-us/sql/t-sql/functions/openjson-transact-sql?view=sql-server-ver16
    @Override
    protected Expression parseOpenJson() {
        Expression this_ = parseBitwise();
        Object path = match(TokenType.COMMA) ? (Object) parseString() : Boolean.FALSE;

        List<Expression> expressions = null;
        if (matchPair(TokenType.R_PAREN, TokenType.WITH)) {
            matchLParen();
            expressions = parseCsv(this::parseOpenJsonColumnDef);
        }

        return build(ExprType.OPEN_JSON, Args.THIS, this_, Args.PATH, path, Args.EXPRESSIONS, expressions);
    }

    /** The nested {@code _parse_open_json_column_def} of {@code _parse_open_json}. */
    private Expression parseOpenJsonColumnDef() {
        Expression this_ = parseField(true);
        Expression kind = parseTypes();
        Expression path = parseString();
        boolean asJson = matchPair(TokenType.ALIAS, TokenType.JSON);

        return build(ExprType.OPEN_JSON_COLUMN_DEF,
                Args.THIS, this_, Args.KIND, kind, Args.PATH, path, Args.AS_JSON, asJson);
    }

    @Override
    protected Expression parsePosition(boolean haystackFirst) {
        List<Expression> args = parseCsv(this::parseBitwise);

        if (match(TokenType.IN)) {
            return build(ExprType.STR_POSITION, Args.THIS, parseBitwise(), Args.SUBSTR, Py.seqGet(args, 0));
        }

        Expression haystack;
        Expression needle;
        if (haystackFirst) {
            haystack = Py.seqGet(args, 0);
            needle = Py.seqGet(args, 1);
        } else {
            haystack = Py.seqGet(args, 1);
            needle = Py.seqGet(args, 0);
        }

        return build(ExprType.STR_POSITION,
                Args.THIS, haystack, Args.SUBSTR, needle, Args.POSITION, Py.seqGet(args, 2));
    }

    @Override
    protected Expression parseJoinHint(String funcName) {
        List<Expression> args = parseCsv(() -> parseTable());
        return Expression.of(ExprType.JOIN_HINT, Args.THIS, Py.upper(funcName), Args.EXPRESSIONS, args);
    }

    @Override
    protected Expression parseSubstring() {
        // Postgres supports the form: substring(string [from int] [for int])
        // (despite being undocumented, the reverse order also works)
        // https://www.postgresql.org/docs/9.1/functions-string.html @ Table 9-6

        List<Expression> args = parseCsv(this::parseBitwise);

        Expression start = null;
        Expression length = null;

        while (present(curr)) {
            if (match(TokenType.FROM)) {
                start = parseBitwise();
            } else if (match(TokenType.FOR)) {
                if (start == null) {
                    start = Exp.number(1);
                }
                length = parseBitwise();
            } else {
                break;
            }
        }

        if (start != null) {
            args.add(start);
        }
        if (length != null) {
            args.add(length);
        }

        return validateExpression(Exp.fromArgList(ExprType.SUBSTRING, args), args);
    }

    @Override
    protected Expression parseTrim() {
        // https://www.w3resource.com/sql/character-functions/trim.php
        // https://docs.oracle.com/javadb/10.8.3.0/ref/rreftrimfunc.html

        String position = null;
        Expression collation = null;
        Expression expression = null;

        if (matchTexts(d.TRIM_TYPES)) {
            position = Py.upper(prev.text());
        }

        Expression this_ = parseBitwise();
        if (matchSet(FROM_OR_COMMA)) {
            boolean invertOrder = prev.type() == TokenType.FROM || d.TRIM_PATTERN_FIRST;
            expression = parseBitwise();

            if (invertOrder) {
                Expression tmp = this_;
                this_ = expression;
                expression = tmp;
            }
        }

        if (match(TokenType.COLLATE)) {
            collation = parseBitwise();
        }

        return build(ExprType.TRIM,
                Args.THIS, this_, Args.POSITION, position, Args.EXPRESSION, expression, Args.COLLATION, collation);
    }

    // ------------------------------------------------------------------------------------------
    // Windows

    @Override
    protected List<Expression> parseWindowClause() {
        return match(TokenType.WINDOW) ? parseCsv(this::parseNamedWindow) : null;
    }

    @Override
    protected Expression parseNamedWindow() {
        return parseWindow(parseIdVar(), true);
    }

    @Override
    protected Expression parseRespectOrIgnoreNulls(Expression this_) {
        if (curr.type() == TokenType.VAR) {
            if (matchTextSeq("IGNORE", "NULLS")) {
                return build(ExprType.IGNORE_NULLS, Args.THIS, this_);
            }
            if (matchTextSeq("RESPECT", "NULLS")) {
                return build(ExprType.RESPECT_NULLS, Args.THIS, this_);
            }
        }
        return this_;
    }

    @Override
    protected Expression parseHavingMax(Expression this_) {
        if (match(TokenType.HAVING)) {
            matchTexts(MAX_OR_MIN);
            boolean max = !Py.upper(prev.text()).equals("MIN");
            return build(ExprType.HAVING_MAX, Args.THIS, this_, Args.EXPRESSION, parseColumn(), Args.MAX, max);
        }

        return this_;
    }

    @Override
    protected Expression parseWindow(Expression this_, boolean alias) {
        Expression func = this_;
        List<String> comments = func != null ? func.comments() : null;

        // https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/img_text/nth_value.html
        if (d.SUPPORTS_NTH_VALUE_FROM_MODIFIER && this_ != null && this_.is(ExprType.NTH_VALUE)) {
            if (matchTextSeq("FROM", "FIRST")) {
                this_.set(Args.FROM_FIRST, Boolean.TRUE);
            } else if (matchTextSeq("FROM", "LAST")) {
                this_.set(Args.FROM_FIRST, Boolean.FALSE);
            }
        }

        // T-SQL allows the OVER (...) syntax after WITHIN GROUP.
        // https://learn.microsoft.com/en-us/sql/t-sql/functions/percentile-disc-transact-sql?view=sql-server-ver16
        if (matchTextSeq("WITHIN", "GROUP")) {
            Expression order = parseWrapped(() -> parseOrder());
            this_ = build(ExprType.WITHIN_GROUP, Args.THIS, this_, Args.EXPRESSION, order);
        }

        if (matchPair(TokenType.FILTER, TokenType.L_PAREN)) {
            match(TokenType.WHERE);
            this_ = build(ExprType.FILTER, Args.THIS, this_, Args.EXPRESSION, parseWhere(true));
            matchRParen();
        }

        // SQL spec defines an optional [ { IGNORE | RESPECT } NULLS ] OVER
        // Some dialects choose to implement and some do not.
        // https://dev.mysql.com/doc/refman/8.0/en/window-function-descriptions.html

        // There is some code above in _parse_lambda that handles
        //   SELECT FIRST_VALUE(TABLE.COLUMN IGNORE|RESPECT NULLS) OVER ...

        // The below changes handle
        //   SELECT FIRST_VALUE(TABLE.COLUMN) IGNORE|RESPECT NULLS OVER ...

        // Oracle allows both formats
        //   (https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/img_text/first_value.html)
        //   and Snowflake chose to do the same for familiarity
        //   https://docs.snowflake.com/en/sql-reference/functions/first_value.html#usage-notes
        if (this_ != null && this_.is(ExprType.AGG_FUNC)) {
            Expression ignoreRespect = Scopes.findInScope(this_, ExprType.IGNORE_NULLS, ExprType.RESPECT_NULLS);

            if (ignoreRespect != null && ignoreRespect != this_) {
                ignoreRespect.replace(ignoreRespect.getThis());
                this_ = build(ignoreRespect.type(), Args.THIS, this_);
            }
        }

        this_ = parseRespectOrIgnoreNulls(this_);

        // bigquery select from window x AS (partition by ...)
        String over;
        if (alias) {
            over = null;
            match(TokenType.ALIAS);
        } else if (!matchSet(d.WINDOW_BEFORE_PAREN_TOKENS)) {
            return this_;
        } else {
            over = Py.upper(prev.text());
        }

        if (comments != null && !comments.isEmpty() && func != null) {
            func.popComments();
        }

        if (!match(TokenType.L_PAREN)) {
            return expression(
                    Expression.of(ExprType.WINDOW, Args.THIS, this_, Args.ALIAS, parseIdVar(false), Args.OVER, over),
                    comments);
        }

        Expression windowAlias = parseIdVar(false, d.WINDOW_ALIAS_TOKENS);

        Boolean first = match(TokenType.FIRST) ? Boolean.TRUE : null;
        if (matchTextSeq("LAST")) {
            first = Boolean.FALSE;
        }

        Object[] partitionAndOrder = parsePartitionAndOrder();
        Object partition = partitionAndOrder[0];
        Object order = partitionAndOrder[1];
        String kind = (matchSet(ROWS_OR_RANGE) || matchTextSeq("GROUPS")) ? prev.text() : null;

        Expression spec;
        if (kind != null && !kind.isEmpty()) {
            match(TokenType.BETWEEN);
            Map<String, Object> start = parseWindowSpec();

            Map<String, Object> end = match(TokenType.AND) ? parseWindowSpec() : new HashMap<>();
            Expression exclude = matchTextSeq("EXCLUDE") ? parseVarFromOptions(d.WINDOW_EXCLUDE_OPTIONS) : null;

            spec = build(ExprType.WINDOW_SPEC,
                    Args.KIND, kind,
                    Args.START, start.get("value"),
                    Args.START_SIDE, start.get("side"),
                    Args.END, end.get("value"),
                    Args.END_SIDE, end.get("side"),
                    Args.EXCLUDE, exclude);
        } else {
            spec = null;
        }

        matchRParen();

        Expression window = expression(
                Expression.of(ExprType.WINDOW,
                        Args.THIS, this_,
                        Args.PARTITION_BY, partition,
                        Args.ORDER, order,
                        Args.SPEC, spec,
                        Args.ALIAS, windowAlias,
                        Args.OVER, over,
                        Args.FIRST, first),
                comments);

        // This covers Oracle's FIRST/LAST syntax: aggregate KEEP (...) OVER (...)
        if (matchSet(d.WINDOW_BEFORE_PAREN_TOKENS, false)) {
            return parseWindow(window, alias);
        }

        return window;
    }

    @Override
    protected Object[] parsePartitionAndOrder() {
        List<Expression> partition = parsePartitionBy();
        Expression order = parseOrder();
        return new Object[] {partition, order};
    }

    @Override
    protected Map<String, Object> parseWindowSpec() {
        match(TokenType.BETWEEN);

        Object value;
        if (matchTextSeq("UNBOUNDED")) {
            value = "UNBOUNDED";
        } else if (matchTextSeq("CURRENT", "ROW")) {
            value = "CURRENT ROW";
        } else {
            value = parseBitwise();
        }
        Object side = matchTexts(d.WINDOW_SIDES) ? prev.text() : null;

        Map<String, Object> spec = new HashMap<>();
        spec.put("value", value);
        spec.put("side", side);
        return spec;
    }

    // ------------------------------------------------------------------------------------------
    // Aliases and identifiers

    @Override
    protected Expression parseAlias(Expression this_, boolean explicit) {
        // In some dialects, LIMIT and OFFSET can act as both identifiers and keywords (clauses)
        // so this section tries to parse the clause version and if it fails, it treats the token
        // as an identifier (alias)
        if (canParseLimitOrOffset()) {
            return this_;
        }

        // WINDOW is in ID_VAR_TOKENS, so it can be consumed as an implicit alias. Detect the
        // named-window clause shape (`WINDOW <ident> AS (...)`) and avoid swallowing it.
        if (canParseNamedWindow()) {
            return this_;
        }

        boolean anyToken = match(TokenType.ALIAS);
        List<String> comments = prevComments;

        if (explicit && !anyToken) {
            return this_;
        }

        if (match(TokenType.L_PAREN)) {
            Expression aliases = expression(
                    Expression.of(ExprType.ALIASES,
                            Args.THIS, this_,
                            Args.EXPRESSIONS, parseCsv(() -> parseIdVar(anyToken))),
                    comments);
            matchRParen(aliases);
            return aliases;
        }

        Expression alias = parseIdVar(anyToken, d.ALIAS_TOKENS);
        if (alias == null && d.STRING_ALIASES) {
            alias = parseStringAsIdentifier();
        }

        if (alias != null) {
            // Python extends the previous token's comment list in place.
            comments.addAll(alias.popComments());
            this_ = expression(Expression.of(ExprType.ALIAS, Args.THIS, this_, Args.ALIAS, alias), comments);
            Expression column = this_.getThis();

            // Moves the comment next to the alias in `expr /* comment */ AS alias`
            List<String> thisComments = this_.comments();
            if ((thisComments == null || thisComments.isEmpty())
                    && column != null
                    && column.comments() != null
                    && !column.comments().isEmpty()) {
                this_.setComments(column.popComments());
            }
        }

        return this_;
    }

    @Override
    protected Expression parseIdVar(boolean anyToken, Set<TokenType> tokens) {
        Expression expression = parseIdentifier();
        if (expression == null
                && ((anyToken && advanceAny() != null)
                        || matchSet(tokens != null && !tokens.isEmpty() ? tokens : d.ID_VAR_TOKENS))) {
            boolean quoted = prev.type() == TokenType.STRING;
            expression = identifierExpression(null, quoted);
        }

        return expression;
    }

    @Override
    protected Expression parseString() {
        if (matchSet(d.STRING_PARSERS)) {
            return stringParser(prev.type(), prev);
        }
        return parsePlaceholder();
    }

    @Override
    protected Expression parseStringAsIdentifier() {
        if (!match(TokenType.STRING)) {
            return null;
        }
        Expression output = Exp.toIdentifier(prev.text(), true, true);
        updatePositions(output, prev);
        return output;
    }

    @Override
    protected Expression parseNumber() {
        if (matchSet(d.NUMERIC_PARSERS)) {
            return numericParser(prev.type(), prev);
        }
        return parsePlaceholder();
    }

    @Override
    protected Expression parseIdentifier() {
        if (match(TokenType.IDENTIFIER)) {
            return identifierExpression(null, true);
        }
        return parsePlaceholder();
    }

    @Override
    protected Expression parseVar(boolean anyToken, Set<TokenType> tokens, boolean upper) {
        if ((anyToken && advanceAny() != null)
                || match(TokenType.VAR)
                || (tokens != null && !tokens.isEmpty() ? matchSet(tokens) : false)) {
            return build(ExprType.VAR, Args.THIS, upper ? Py.upper(prev.text()) : prev.text());
        }
        return parsePlaceholder();
    }

    @Override
    protected Token advanceAny(boolean ignoreReserved) {
        if (present(curr) && (ignoreReserved || !d.RESERVED_TOKENS.contains(curr.type()))) {
            advance();
            return prev;
        }
        return null;
    }

    @Override
    protected Expression parseVarOrString(boolean upper) {
        Expression result = parseString();
        return result != null ? result : parseVar(true, null, upper);
    }

    @Override
    protected Expression parsePrimaryOrVar() {
        Expression result = parsePrimary();
        return result != null ? result : parseVar(true);
    }

    @Override
    protected Expression parseNull() {
        if (matchSet(NULL_OR_UNKNOWN)) {
            return primaryParser(TokenType.NULL, prev);
        }
        return parsePlaceholder();
    }

    @Override
    protected Expression parseBoolean() {
        if (match(TokenType.TRUE)) {
            return primaryParser(TokenType.TRUE, prev);
        }
        if (match(TokenType.FALSE)) {
            return primaryParser(TokenType.FALSE, prev);
        }
        return parsePlaceholder();
    }

    @Override
    protected Expression parseStar() {
        if (match(TokenType.STAR)) {
            return primaryParser(TokenType.STAR, prev);
        }
        return parsePlaceholder();
    }

    @Override
    protected Expression parseParameter() {
        Expression this_ = parseIdentifier();
        if (this_ == null) {
            this_ = parsePrimaryOrVar();
        }
        return build(ExprType.PARAMETER, Args.THIS, this_);
    }

    @Override
    protected Expression parsePlaceholder() {
        if (matchSet(d.PLACEHOLDER_PARSERS)) {
            Expression placeholder = placeholderParser(prev.type());
            if (placeholder != null) {
                return placeholder;
            }
            advance(-1);
        }
        return null;
    }

    @Override
    protected List<Expression> parseStarOp(String... keywords) {
        if (!matchTexts(List.of(keywords))) {
            return null;
        }
        if (match(TokenType.L_PAREN, false)) {
            return parseWrappedCsv(this::parseExpression);
        }

        Expression expression = parseAlias(parseDisjunction(), true);
        return expression != null ? new ArrayList<>(List.of(expression)) : null;
    }

    @Override
    protected List<Expression> parseWrappedIdVars(boolean optional) {
        return parseWrappedCsv(() -> parseIdVar(), optional);
    }

    @Override
    protected List<Expression> parseExpressions() {
        return parseCsv(this::parseExpression);
    }

    @Override
    protected Expression parseSelectOrExpression(boolean alias) {
        Expression result = parseSetOperations(alias ? parseAlias(parseAssignment(), true) : parseAssignment());
        return result != null ? result : parseSelect();
    }

    @Override
    protected Expression parseDdlSelect() {
        return (Expression) parseQueryModifiers(parseSetOperations(parseSelect(true, false, false)));
    }

    // ---------------------------------------------------------------------------------------
    // From ParserPartF

    private static final Set<String> TRANSACTION_WORK = Set.of("TRANSACTION", "WORK");

    private static final Set<TokenType> FROM_IN = Collections.unmodifiableSet(EnumSet.of(TokenType.FROM, TokenType.IN));
    private static final Set<TokenType> ASC_DESC = Collections.unmodifiableSet(EnumSet.of(TokenType.ASC, TokenType.DESC));

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }


    /** {@code exists or None}. */
    private static Object orNone(Object v) {
        return Expression.truthy(v) ? v : null;
    }


    /** sqlglot's {@code Limit.is_limit_all}. */
    private static boolean isLimitAll(Expression limit) {
        Expression le = limit.expr(Args.EXPRESSION);
        return le != null && le.is(ExprType.VAR) && upper(le.name()).equals("ALL");
    }

    private static BigDecimal toDecimal(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        return new BigDecimal(v.toString());
    }

    private static Object pyAdd(Object a, Object b) {
        if (a instanceof BigInteger x && b instanceof BigInteger y) {
            return x.add(y);
        }
        return toDecimal(a).add(toDecimal(b)).toPlainString();
    }

    // ------------------------------------------------------------------------------------------

    @Override
    protected Expression parseTransaction() {
        String this_ = null;
        if (matchTexts(d.TRANSACTION_KIND)) {
            this_ = prev.text();
        }

        matchTexts(TRANSACTION_WORK);

        List<String> modes = new ArrayList<>();
        while (true) {
            List<String> mode = new ArrayList<>();
            while (match(TokenType.VAR) || match(TokenType.NOT)) {
                mode.add(prev.text());
            }

            if (!mode.isEmpty()) {
                modes.add(String.join(" ", mode));
            }
            if (!match(TokenType.COMMA)) {
                break;
            }
        }

        return build(ExprType.TRANSACTION, Args.THIS, this_, Args.MODES, modes);
    }

    @Override
    protected Expression parseCommitOrRollback() {
        Boolean chain = null;
        Expression savepoint = null;
        boolean isRollback = prev.type() == TokenType.ROLLBACK;

        matchTexts(TRANSACTION_WORK);

        if (matchTextSeq("TO")) {
            matchTextSeq("SAVEPOINT");
            savepoint = parseIdVar();
        }

        if (match(TokenType.AND)) {
            chain = !matchTextSeq("NO");
            matchTextSeq("CHAIN");
        }

        if (isRollback) {
            return build(ExprType.ROLLBACK, Args.SAVEPOINT, savepoint);
        }

        return build(ExprType.COMMIT, Args.CHAIN, chain);
    }

    @Override
    protected Expression parseRefresh() {
        Token start = prev;

        String kind;
        if (matchTextSeq("EXTERNAL", "TABLE")) {
            kind = "EXTERNAL TABLE";
        } else if (match(TokenType.TABLE)) {
            kind = "TABLE";
        } else if (matchTextSeq("MATERIALIZED", "VIEW")) {
            kind = "MATERIALIZED VIEW";
        } else {
            kind = "";
        }

        Expression this_ = parseString();
        if (kind.isEmpty() && !(this_ != null && this_.is(ExprType.LITERAL))) {
            return parseAsCommand(start);
        }

        return build(ExprType.REFRESH, Args.THIS, this_ != null ? this_ : parseTable(), Args.KIND, kind);
    }

    @Override
    protected Object parseColumnDefWithExists() {
        int start = tokenIndex;
        match(TokenType.COLUMN);

        Boolean existsColumn = parseExists(true);
        Expression expression = parseFieldDef();

        if (!(expression != null && expression.is(ExprType.COLUMN_DEF))) {
            retreat(start);
            return null;
        }

        expression.set(Args.EXISTS, existsColumn);

        return expression;
    }

    @Override
    protected Expression parseAddColumn() {
        if (!upper(prev.text()).equals("ADD")) {
            return null;
        }

        return (Expression) parseColumnDefWithExists();
    }

    @Override
    protected Expression parseDropColumn() {
        if (!match(TokenType.DROP)) {
            return null;
        }

        String kind = null;
        if (!d.ALTER_DROP_REQUIRES_COLUMN) {
            // A bare `DROP [IF EXISTS] c [CASCADE | RESTRICT]` drops a column
            int index = tokenIndex;
            parseExists();
            if (curr.type() != TokenType.COLUMN
                    && curr.type() != TokenType.CONSTRAINT
                    && matchSet(d.ID_VAR_TOKENS)
                    && (!present(curr)
                            || match(TokenType.COMMA, false)
                            || matchTexts(CASCADE_RESTRICT, false))) {
                kind = "COLUMN";
            }
            retreat(index);
        }

        return parseDrop(false, kind);
    }

    @Override
    protected Expression parseAlterDropAction() {
        Expression drop = parseDropColumn();

        if (drop != null
                && drop.is(ExprType.DROP)
                && "COLUMN".equals(drop.get(Args.KIND))
                && !dd.ALTER_TABLE_DROP_REQUIRED_FOR_EACH_COLUMN
                && match(TokenType.COMMA)) {
            List<Object> tables = new ArrayList<>(drop.list(Args.TABLES));
            tables.addAll(parseCsv(this::parseColumn));
            drop.set(Args.TABLES, tables);
        }

        return drop;
    }

    // https://docs.aws.amazon.com/athena/latest/ug/alter-table-drop-partition.html
    @Override
    protected Expression parseDropPartition(Boolean exists) {
        return build(ExprType.DROP_PARTITION, Args.EXPRESSIONS, parseCsv(this::parsePartition), Args.EXISTS, exists);
    }

    private Expression parseAddAlteration() {
        matchTextSeq("ADD");
        if (matchSet(d.ADD_CONSTRAINT_TOKENS, false) || matchTexts(d.ADD_CONSTRAINT_KEYWORDS, false)) {
            return build(ExprType.ADD_CONSTRAINT, Args.EXPRESSIONS, parseCsv(this::parseConstraint));
        }

        Expression columnDef = parseAddColumn();
        if (columnDef != null && columnDef.is(ExprType.COLUMN_DEF)) {
            return columnDef;
        }

        Boolean exists = parseExists(true);
        if (matchPair(TokenType.PARTITION, TokenType.L_PAREN, false)) {
            return build(ExprType.ADD_PARTITION,
                    Args.EXISTS, exists,
                    Args.THIS, parseField(true),
                    Args.LOCATION, matchTextSeq(false, "LOCATION") ? parseProperty() : Boolean.FALSE);
        }

        return null;
    }

    @Override
    protected List<Expression> parseAlterTableAdd() {
        if (!matchSet(d.ADD_CONSTRAINT_TOKENS, false)
                && (!dd.ALTER_TABLE_ADD_REQUIRED_FOR_EACH_COLUMN || matchTextSeq("COLUMNS"))) {
            Expression schema = parseSchema();

            return schema != null
                    ? Py.ensureList(schema)
                    : parseCsv(() -> (Expression) parseColumnDefWithExists());
        }

        return parseCsv(this::parseAddAlteration);
    }

    @Override
    protected Expression parseAlterTableAlter() {
        if (matchTexts(d.ALTER_ALTER_PARSERS)) {
            return alterAlterParser(upper(prev.text()));
        }

        // Many dialects support the ALTER [COLUMN] syntax, so if there is no
        // keyword after ALTER we default to parsing this statement
        match(TokenType.COLUMN);
        Boolean exists = parseExists();
        Expression column = parseField(true);

        if (matchPair(TokenType.DROP, TokenType.DEFAULT)) {
            return build(ExprType.ALTER_COLUMN, Args.THIS, column, Args.DROP, Boolean.TRUE, Args.EXISTS, orNone(exists));
        }
        if (matchPair(TokenType.SET, TokenType.DEFAULT)) {
            return build(ExprType.ALTER_COLUMN,
                    Args.THIS, column, Args.DEFAULT, parseDisjunction(), Args.EXISTS, orNone(exists));
        }
        if (match(TokenType.COMMENT)) {
            return build(ExprType.ALTER_COLUMN,
                    Args.THIS, column, Args.COMMENT, parseString(), Args.EXISTS, orNone(exists));
        }
        if (matchTextSeq("DROP", "NOT", "NULL")) {
            return build(ExprType.ALTER_COLUMN,
                    Args.THIS, column, Args.DROP, Boolean.TRUE, Args.ALLOW_NULL, Boolean.TRUE,
                    Args.EXISTS, orNone(exists));
        }
        if (matchTextSeq("SET", "NOT", "NULL")) {
            return build(ExprType.ALTER_COLUMN,
                    Args.THIS, column, Args.ALLOW_NULL, Boolean.FALSE, Args.EXISTS, orNone(exists));
        }

        if (matchTextSeq("SET", "VISIBLE")) {
            return build(ExprType.ALTER_COLUMN,
                    Args.THIS, column, Args.VISIBLE, "VISIBLE", Args.EXISTS, orNone(exists));
        }
        if (matchTextSeq("SET", "INVISIBLE")) {
            return build(ExprType.ALTER_COLUMN,
                    Args.THIS, column, Args.VISIBLE, "INVISIBLE", Args.EXISTS, orNone(exists));
        }

        matchTextSeq("SET", "DATA");
        Expression dtype = matchTextSeq("TYPE") || !d.ALTER_COLUMN_TYPE_REQUIRES_KEYWORD ? parseTypes() : null;
        if (dtype == null) {
            return null;
        }

        return build(ExprType.ALTER_COLUMN,
                Args.THIS, column,
                Args.DTYPE, dtype,
                Args.COLLATE, match(TokenType.COLLATE) ? parseTerm() : Boolean.FALSE,
                Args.USING, match(TokenType.USING) ? parseDisjunction() : Boolean.FALSE,
                Args.EXISTS, orNone(exists));
    }

    @Override
    protected Expression parseAlterDiststyle() {
        if (matchTexts(Set.of("ALL", "EVEN", "AUTO"))) {
            return build(ExprType.ALTER_DIST_STYLE, Args.THIS, Exp.var(upper(prev.text())));
        }

        matchTextSeq("KEY", "DISTKEY");
        return build(ExprType.ALTER_DIST_STYLE, Args.THIS, parseColumn());
    }

    @Override
    protected Expression parseAlterSortkey(Boolean compound) {
        if (Expression.truthy(compound)) {
            matchTextSeq("SORTKEY");
        }

        if (match(TokenType.L_PAREN, false)) {
            return build(ExprType.ALTER_SORT_KEY, Args.EXPRESSIONS, parseWrappedIdVars(), Args.COMPOUND, compound);
        }

        matchTexts(Set.of("AUTO", "NONE"));
        return build(ExprType.ALTER_SORT_KEY, Args.THIS, Exp.var(upper(prev.text())), Args.COMPOUND, compound);
    }

    @Override
    protected List<Expression> parseAlterTableDrop() {
        int index = tokenIndex - 1;

        Boolean partitionExists = parseExists();
        if (match(TokenType.PARTITION, false)) {
            return parseCsv(() -> parseDropPartition(partitionExists));
        }

        retreat(index);
        return parseCsv(this::parseAlterDropAction);
    }

    @Override
    protected Expression parseAlterTableRename() {
        if (match(TokenType.COLUMN) || (!d.ALTER_RENAME_REQUIRES_COLUMN && !matchTextSeq(false, "TO"))) {
            Boolean exists = parseExists();
            Expression oldColumn = parseColumn();
            boolean to = matchTextSeq("TO");
            Expression newColumn = parseColumn();

            if (oldColumn == null || !to || newColumn == null) {
                return null;
            }

            return build(ExprType.RENAME_COLUMN, Args.THIS, oldColumn, Args.TO, newColumn, Args.EXISTS, exists);
        }

        matchTextSeq("TO");
        return build(ExprType.ALTER_RENAME, Args.THIS, parseTable(true));
    }

    @Override
    protected Expression parseAlterTableSet() {
        Expression alterSet = expression(Expression.of(ExprType.ALTER_SET));

        if (match(TokenType.L_PAREN, false) || matchTextSeq("TABLE", "PROPERTIES")) {
            alterSet.set(Args.EXPRESSIONS, parseWrappedCsv(this::parseAssignment));
        } else if (matchTextSeq(false, "FILESTREAM_ON")) {
            alterSet.set(Args.EXPRESSIONS, listOf(parseAssignment()));
        } else if (matchTexts(Set.of("LOGGED", "UNLOGGED"))) {
            alterSet.set(Args.OPTION, Exp.var(upper(prev.text())));
        } else if (matchTextSeq("WITHOUT") && matchTexts(Set.of("CLUSTER", "OIDS"))) {
            alterSet.set(Args.OPTION, Exp.var("WITHOUT " + upper(prev.text())));
        } else if (matchTextSeq("LOCATION")) {
            alterSet.set(Args.LOCATION, parseField());
        } else if (matchTextSeq("ACCESS", "METHOD")) {
            alterSet.set(Args.ACCESS_METHOD, parseField());
        } else if (matchTextSeq("TABLESPACE")) {
            alterSet.set(Args.TABLESPACE, parseField());
        } else if (matchTextSeq("FILE", "FORMAT") || matchTextSeq("FILEFORMAT")) {
            alterSet.set(Args.FILE_FORMAT, listOf(parseField()));
        } else if (matchTextSeq("STAGE_FILE_FORMAT")) {
            alterSet.set(Args.FILE_FORMAT, parseWrappedOptions());
        } else if (matchTextSeq("STAGE_COPY_OPTIONS")) {
            alterSet.set(Args.COPY_OPTIONS, parseWrappedOptions());
        } else if (matchTextSeq("TAG") || matchTextSeq("TAGS")) {
            alterSet.set(Args.TAG, parseCsv(this::parseAssignment));
        } else {
            if (matchTextSeq("SERDE")) {
                alterSet.set(Args.SERDE, parseField());
            }

            Expression properties = parseWrapped(() -> parseProperties(), true);
            alterSet.set(Args.EXPRESSIONS, listOf(properties));
        }

        return alterSet;
    }

    /** Python's {@code [x]}: a one-element mutable list that may hold None. */
    private static List<Object> listOf(Object x) {
        List<Object> l = new ArrayList<>(1);
        l.add(x);
        return l;
    }

    @Override
    protected Expression parseAlterSession() {
        // Parse ALTER SESSION SET/UNSET statements.
        if (match(TokenType.SET)) {
            List<Expression> expressions = parseCsv(() -> parseSetItemAssignment());
            return build(ExprType.ALTER_SESSION, Args.EXPRESSIONS, expressions, Args.UNSET, Boolean.FALSE);
        }

        matchTextSeq("UNSET");
        List<Expression> expressions = parseCsv(() -> build(ExprType.SET_ITEM, Args.THIS, parseIdVar(true)));
        return build(ExprType.ALTER_SESSION, Args.EXPRESSIONS, expressions, Args.UNSET, Boolean.TRUE);
    }

    @Override
    protected Expression parseAlter() {
        Token start = prev;

        boolean iceberg = matchTextSeq("ICEBERG");

        Token alterToken = matchSet(d.ALTERABLES) ? prev : null;
        if (alterToken == null) {
            return parseAsCommand(start);
        }
        if (iceberg && alterToken.type() != TokenType.TABLE) {
            return parseAsCommand(start);
        }

        Boolean exists = parseExists();
        boolean only = matchTextSeq("ONLY");

        Expression this_;
        Boolean check;
        Expression cluster;
        String parser;
        if (alterToken.type() == TokenType.SESSION) {
            this_ = null;
            check = null;
            cluster = null;
            String key = upper(alterToken.text());
            parser = d.ALTER_PARSERS.contains(key) ? key : null;
        } else {
            this_ = parseTable(true, false, null, false, false, d.ALTER_TABLE_PARTITIONS);
            check = matchTextSeq("WITH", "CHECK");
            cluster = match(TokenType.ON) ? parseOnProperty() : null;
            parser = matchTexts(d.ALTER_PARSERS) ? upper(prev.text()) : null;
        }

        List<Object> options = new ArrayList<>();
        Expression properties;
        if (parser == null && alterToken.type() == TokenType.TABLE && !d.ALTER_TABLE_REQUIRES_ACTION) {
            // Table options may precede the actions, e.g. MySQL's ENGINE=InnoDB, ADD COLUMN a INT.
            // A clause the property parser rejects, like PARTITION BY RANGE ... MAXVALUE, stays a Command
            properties = tryParse(() -> parseProperties());
            options = properties != null ? new ArrayList<>(properties.getExpressions()) : new ArrayList<>();
        }

        List<Object> actions = Py.ensureList(parser != null ? alterParser(parser) : null);
        if (!actions.isEmpty() || !options.isEmpty()) {
            // Actions of different kinds and table options may alternate, e.g. MySQL's
            // ADD c INT, AUTO_INCREMENT=5, ADD d INT. The comma before an item may have already
            // been consumed by the previous parser
            while (d.ALTER_TABLE_MIXED_ACTIONS
                    && (match(TokenType.COMMA) || prev.type() == TokenType.COMMA)) {
                if (!matchTexts(d.PROPERTY_PARSERS, false) && matchTexts(d.ALTER_PARSERS)) {
                    String key = upper(prev.text());
                    List<Object> parsed = Py.ensureList(tryParse(() -> alterParser(key)));
                    boolean anyCommand = false;
                    for (Object action : parsed) {
                        if (Expression.isInstance(action, ExprType.COMMAND)) {
                            anyCommand = true;
                            break;
                        }
                    }
                    if (parsed.isEmpty() || anyCommand) {
                        return parseAsCommand(start);
                    }

                    actions.addAll(parsed);
                } else if ((properties = tryParse(() -> parseProperties())) != null) {
                    options.addAll(properties.getExpressions());
                } else {
                    break;
                }
            }

            boolean notValid = matchTextSeq("NOT", "VALID");
            if ((properties = tryParse(() -> parseProperties())) != null) {
                options.addAll(properties.getExpressions());
            }
            boolean cascade = dd.ALTER_TABLE_SUPPORTS_CASCADE && matchTextSeq("CASCADE");

            if (!present(curr)) {
                return build(ExprType.ALTER,
                        Args.THIS, this_,
                        Args.KIND, upper(alterToken.text()),
                        Args.EXISTS, exists,
                        Args.ACTIONS, actions,
                        Args.ONLY, only,
                        Args.OPTIONS, options,
                        Args.CLUSTER, cluster,
                        Args.NOT_VALID, notValid,
                        Args.CHECK, check,
                        Args.CASCADE, cascade,
                        Args.ICEBERG, iceberg);
            }
        }

        return parseAsCommand(start);
    }

    @Override
    protected Expression parseAnalyze() {
        Token start = prev;
        // https://duckdb.org/docs/sql/statements/analyze
        if (!present(curr)) {
            return expression(Expression.of(ExprType.ANALYZE));
        }

        List<String> options = new ArrayList<>();
        while (matchTexts(d.ANALYZE_STYLES)) {
            if (upper(prev.text()).equals("BUFFER_USAGE_LIMIT")) {
                options.add("BUFFER_USAGE_LIMIT " + Py.str(parseNumber()));
            } else {
                options.add(upper(prev.text()));
            }
        }

        Object tables = null;
        Expression innerExpression = null;

        String kind = present(curr) ? upper(curr.text()) : null;

        if (match(TokenType.TABLE)) {
            tables = parseCsv(() -> parseTableParts());
        } else if (match(TokenType.INDEX)) {
            tables = parseTableParts();
        } else if (matchTextSeq("TABLES")) {
            if (matchSet(FROM_IN)) {
                kind = kind + " " + upper(prev.text());
                tables = parseTable(true, false, null, false, true);
            }
        } else if (matchTextSeq("DATABASE")) {
            tables = parseTable(true, false, null, false, true);
        } else if (matchTextSeq("CLUSTER")) {
            tables = parseTable();
        // Try matching inner expr keywords before fallback to parse table.
        } else if (matchTexts(d.ANALYZE_EXPRESSION_PARSERS)) {
            kind = null;
            innerExpression = analyzeExpressionParser(upper(prev.text()));
        } else {
            // Empty kind  https://prestodb.io/docs/current/sql/analyze.html
            kind = null;
            tables = parseCsv(() -> parseTableParts());
        }

        Expression partition = tryParse(this::parsePartition);
        if (partition == null && matchTexts(d.PARTITION_KEYWORDS)) {
            return parseAsCommand(start);
        }

        // https://docs.starrocks.io/docs/sql-reference/sql-statements/cbo_stats/ANALYZE_TABLE/
        String mode;
        if (matchTextSeq("WITH", "SYNC", "MODE") || matchTextSeq("WITH", "ASYNC", "MODE")) {
            mode = "WITH " + upper(tokenList.get(tokenIndex - 2).text()) + " MODE";
        } else {
            mode = null;
        }

        if (matchTexts(d.ANALYZE_EXPRESSION_PARSERS)) {
            innerExpression = analyzeExpressionParser(upper(prev.text()));
        }

        Expression properties = parseProperties();
        return build(ExprType.ANALYZE,
                Args.KIND, kind,
                Args.TABLES, Py.ensureList(tables),
                Args.MODE, mode,
                Args.PARTITION, partition,
                Args.PROPERTIES, properties,
                Args.EXPRESSION, innerExpression,
                Args.OPTIONS, options);
    }

    // https://spark.apache.org/docs/3.5.1/sql-ref-syntax-aux-analyze-table.html
    @Override
    protected Expression parseAnalyzeStatistics() {
        String this_ = null;
        String kind = upper(prev.text());
        String option = matchTextSeq("DELTA") ? upper(prev.text()) : null;
        List<Expression> expressions = new ArrayList<>();

        if (!matchTextSeq("STATISTICS")) {
            raiseError("Expecting token STATISTICS");
        }

        if (matchTextSeq("NOSCAN")) {
            this_ = "NOSCAN";
        } else if (match(TokenType.FOR)) {
            if (matchTextSeq("ALL", "COLUMNS")) {
                this_ = "FOR ALL COLUMNS";
            }
            if (matchTextSeq("COLUMNS")) {
                this_ = "FOR COLUMNS";
                expressions = parseCsv(this::parseColumnReference);
            }
        } else if (matchTextSeq("SAMPLE")) {
            Expression sample = parseNumber();
            expressions = new ArrayList<>();
            expressions.add(build(ExprType.ANALYZE_SAMPLE,
                    Args.SAMPLE, sample,
                    Args.KIND, match(TokenType.PERCENT) ? upper(prev.text()) : null));
        }

        return build(ExprType.ANALYZE_STATISTICS,
                Args.KIND, kind, Args.OPTION, option, Args.THIS, this_, Args.EXPRESSIONS, expressions);
    }

    // https://docs.oracle.com/en/database/oracle/oracle-database/21/sqlrf/ANALYZE.html
    @Override
    protected Expression parseAnalyzeValidate() {
        String kind = null;
        String this_ = null;
        Expression expression = null;
        if (matchTextSeq("REF", "UPDATE")) {
            kind = "REF";
            this_ = "UPDATE";
            if (matchTextSeq("SET", "DANGLING", "TO", "NULL")) {
                this_ = "UPDATE SET DANGLING TO NULL";
            }
        } else if (matchTextSeq("STRUCTURE")) {
            kind = "STRUCTURE";
            if (matchTextSeq("CASCADE", "FAST")) {
                this_ = "CASCADE FAST";
            } else if (matchTextSeq("CASCADE", "COMPLETE") && matchTexts(Set.of("ONLINE", "OFFLINE"))) {
                this_ = "CASCADE COMPLETE " + upper(prev.text());
                expression = parseIntoImpl();
            }
        }

        return build(ExprType.ANALYZE_VALIDATE, Args.KIND, kind, Args.THIS, this_, Args.EXPRESSION, expression);
    }

    @Override
    protected Expression parseAnalyzeColumns() {
        String this_ = upper(prev.text());
        if (matchTextSeq("COLUMNS")) {
            return build(ExprType.ANALYZE_COLUMNS, Args.THIS, this_ + " " + upper(prev.text()));
        }
        return null;
    }

    @Override
    protected Expression parseAnalyzeDelete() {
        String kind = matchTextSeq("SYSTEM") ? upper(prev.text()) : null;
        if (matchTextSeq("STATISTICS")) {
            return build(ExprType.ANALYZE_DELETE, Args.KIND, kind);
        }
        return null;
    }

    @Override
    protected Expression parseAnalyzeList() {
        if (matchTextSeq("CHAINED", "ROWS")) {
            return build(ExprType.ANALYZE_LIST_CHAINED_ROWS, Args.EXPRESSION, parseIntoImpl());
        }
        return null;
    }

    // https://dev.mysql.com/doc/refman/8.4/en/analyze-table.html
    @Override
    protected Expression parseAnalyzeHistogram() {
        String this_ = upper(prev.text());
        Expression expression = null;
        List<Expression> expressions = new ArrayList<>();
        String updateOptions = null;

        if (matchTextSeq("HISTOGRAM", "ON")) {
            expressions = parseCsv(this::parseColumnReference);
            List<String> withExpressions = new ArrayList<>();
            while (match(TokenType.WITH)) {
                // https://docs.starrocks.io/docs/sql-reference/sql-statements/cbo_stats/ANALYZE_TABLE/
                if (matchTexts(Set.of("SYNC", "ASYNC"))) {
                    if (matchTextSeq(false, "MODE")) {
                        withExpressions.add(upper(prev.text()) + " MODE");
                        advance();
                    }
                } else {
                    Expression buckets = parseNumber();
                    if (matchTextSeq("BUCKETS")) {
                        withExpressions.add(Py.str(buckets) + " BUCKETS");
                    }
                }
            }
            if (!withExpressions.isEmpty()) {
                expression = build(ExprType.ANALYZE_WITH, Args.EXPRESSIONS, withExpressions);
            }

            if (matchTexts(Set.of("MANUAL", "AUTO")) && match(TokenType.UPDATE, false)) {
                updateOptions = upper(prev.text());
                advance();
            } else if (matchTextSeq("USING", "DATA")) {
                expression = build(ExprType.USING_DATA, Args.THIS, parseString());
            }
        }

        return build(ExprType.ANALYZE_HISTOGRAM,
                Args.THIS, this_,
                Args.EXPRESSIONS, expressions,
                Args.EXPRESSION, expression,
                Args.UPDATE_OPTIONS, updateOptions);
    }

    @Override
    protected Expression parseMerge() {
        match(TokenType.INTO);
        Expression target = parseTable();

        if (target != null && match(TokenType.ALIAS, false)) {
            target.set(Args.ALIAS, parseTableAlias());
        }

        match(TokenType.USING);
        Expression using = parseTable();

        return build(ExprType.MERGE,
                Args.THIS, target,
                Args.USING, using,
                Args.ON, match(TokenType.ON) ? parseDisjunction() : Boolean.FALSE,
                Args.USING_COND, match(TokenType.USING) ? parseUsingIdentifiers() : Boolean.FALSE,
                Args.WHENS, parseWhenMatched(),
                Args.RETURNING, parseReturning());
    }

    @Override
    protected Expression parseWhenMatched() {
        List<Expression> whens = new ArrayList<>();

        while (match(TokenType.WHEN)) {
            boolean matched = !match(TokenType.NOT);
            matchTextSeq("MATCHED");
            boolean source = matchTextSeq("BY", "TARGET") ? false : matchTextSeq("BY", "SOURCE");
            Expression condition = match(TokenType.AND) ? parseDisjunction() : null;

            match(TokenType.THEN);

            Expression then;
            if (match(TokenType.INSERT)) {
                Expression this_ = parseStar();
                if (this_ != null) {
                    then = build(ExprType.INSERT, Args.THIS, this_);
                } else {
                    then = build(ExprType.INSERT,
                            Args.THIS, matchTextSeq("ROW") ? Exp.var("ROW") : parseValue(false),
                            Args.EXPRESSION, matchTextSeq("VALUES") ? parseValue() : Boolean.FALSE,
                            Args.WHERE, parseWhere());
                }
            } else if (match(TokenType.UPDATE)) {
                Expression expressions = parseStar();
                if (expressions != null) {
                    then = build(ExprType.UPDATE, Args.EXPRESSIONS, expressions);
                } else {
                    then = build(ExprType.UPDATE,
                            Args.EXPRESSIONS, match(TokenType.SET)
                                    ? parseCsv(this::parseUpdateAssignment)
                                    : Boolean.FALSE,
                            Args.WHERE, parseWhere());
                }
            } else if (match(TokenType.DELETE)) {
                then = build(ExprType.VAR, Args.THIS, prev.text());
            } else {
                then = parseVarFromOptions(d.CONFLICT_ACTIONS);
            }

            whens.add(build(ExprType.WHEN,
                    Args.MATCHED, matched, Args.SOURCE, source, Args.CONDITION, condition, Args.THEN, then));
        }
        return build(ExprType.WHENS, Args.EXPRESSIONS, whens);
    }

    @Override
    protected Expression parseShow() {
        String parser = findParser(dialect.showTrie());
        if (parser != null) {
            return showParser(parser);
        }
        return parseAsCommand(prev);
    }

    @Override
    protected Expression parseSetItemAssignment(String kind) {
        int index = tokenIndex;

        if (("GLOBAL".equals(kind) || "SESSION".equals(kind)) && matchTextSeq("TRANSACTION")) {
            return parseSetTransaction("GLOBAL".equals(kind));
        }

        Expression left = parsePrimary();
        if (left == null) {
            left = parseColumn();
        }
        boolean assignmentDelimiter = matchTexts(d.SET_ASSIGNMENT_DELIMITERS);

        if (left == null || (d.SET_REQUIRES_ASSIGNMENT_DELIMITER && !assignmentDelimiter)) {
            retreat(index);
            return null;
        }

        Expression right = parseStatement();
        if (right == null) {
            right = parseIdVar();
        }
        if (right != null && right.is(ExprType.COLUMN, ExprType.IDENTIFIER)) {
            right = Exp.var(right.name());
        }

        Expression this_ = build(ExprType.EQ, Args.THIS, left, Args.EXPRESSION, right);
        return build(ExprType.SET_ITEM, Args.THIS, this_, Args.KIND, kind);
    }

    @Override
    protected Expression parseSetTransaction(boolean global) {
        matchTextSeq("TRANSACTION");
        List<Expression> characteristics = parseCsv(() -> parseVarFromOptions(d.TRANSACTION_CHARACTERISTICS));
        return build(ExprType.SET_ITEM,
                Args.EXPRESSIONS, characteristics, Args.KIND, "TRANSACTION", Args.GLOBAL_, global);
    }

    @Override
    protected Expression parseSetItem() {
        String parser = findParser(dialect.setTrie());
        return parser != null ? setParser(parser) : parseSetItemAssignment(null);
    }

    @Override
    protected Expression parseSet(boolean unset, boolean tag) {
        int index = tokenIndex;
        Expression set = build(ExprType.SET,
                Args.EXPRESSIONS, parseCsv(this::parseSetItem), Args.UNSET, unset, Args.TAG, tag);

        if (present(curr)) {
            retreat(index);
            return parseAsCommand(prev);
        }

        return set;
    }

    @Override
    protected Expression parseVarFromOptions(Map<String, List<List<String>>> options, boolean raiseUnmatched) {
        Token start = curr;
        if (!present(start)) {
            return null;
        }

        String option = upper(start.text());
        List<List<String>> continuations =
                d.TEXT_MATCH_EXCLUDED_TOKENS.contains(start.type()) ? null : options.get(option);

        int index = tokenIndex;
        advance();
        boolean matched = false;
        if (continuations != null) {
            for (List<String> keywords : continuations) {
                if (matchTextSeq(keywords.toArray(new String[0]))) {
                    option = option + " " + String.join(" ", keywords);
                    matched = true;
                    break;
                }
            }
        }
        if (!matched) {
            if (continuations == null || !continuations.isEmpty()) {
                if (raiseUnmatched) {
                    raiseError("Unknown option " + option);
                }

                retreat(index);
                return null;
            }
        }

        return Exp.var(option);
    }

    @Override
    protected Expression parseAsCommand(Token start) {
        while (present(curr)) {
            advance();
        }
        String text = findSql(start, prev);
        int size = start.text().length();
        warnUnsupported();
        return Expression.of(ExprType.COMMAND,
                Args.THIS, Py.slice(text, null, size), Args.EXPRESSION, Py.slice(text, size));
    }

    @Override
    protected Expression parseDictProperty(String this_) {
        List<Expression> settings = new ArrayList<>();

        matchLParen();
        Expression kind = parseIdVar();

        if (match(TokenType.L_PAREN)) {
            while (true) {
                Expression key = parseIdVar();
                Expression value = parseFunction();
                if (value == null) {
                    value = parsePrimaryOrVar();
                }
                if (key == null && value == null) {
                    break;
                }
                settings.add(build(ExprType.DICT_SUB_PROPERTY, Args.THIS, key, Args.VALUE, value));
            }
            match(TokenType.R_PAREN);
        }

        matchRParen();

        return build(ExprType.DICT_PROPERTY,
                Args.THIS, this_, Args.KIND, kind != null ? kind.thisValue() : null, Args.SETTINGS, settings);
    }

    @Override
    protected Expression parseDictRange(String this_) {
        matchLParen();
        boolean hasMin = matchTextSeq("MIN");
        Expression min;
        Expression max;
        if (hasMin) {
            min = parseVar();
            if (min == null) {
                min = parsePrimary();
            }
            matchTextSeq("MAX");
            max = parseVar();
            if (max == null) {
                max = parsePrimary();
            }
        } else {
            max = parseVar();
            if (max == null) {
                max = parsePrimary();
            }
            min = Exp.number(0);
        }
        matchRParen();
        return build(ExprType.DICT_RANGE, Args.THIS, this_, Args.MIN, min, Args.MAX, max);
    }

    @Override
    protected Expression parseComprehension(Expression this_) {
        int index = tokenIndex;
        Expression expression = parseIdVar();
        Expression position = match(TokenType.COMMA) ? parseIdVar() : null;

        if (!match(TokenType.IN)) {
            retreat(index - 1);
            return null;
        }
        Expression iterator = parseColumn();
        Expression condition = matchTextSeq("IF") ? parseDisjunction() : null;
        return build(ExprType.COMPREHENSION,
                Args.THIS, this_,
                Args.EXPRESSION, expression,
                Args.POSITION, position,
                Args.ITERATOR, iterator,
                Args.CONDITION, condition);
    }

    @Override
    protected Expression parseHeredoc() {
        if (match(TokenType.HEREDOC_STRING)) {
            return build(ExprType.HEREDOC, Args.THIS, prev.text());
        }

        if (!matchTextSeq("$")) {
            return null;
        }

        List<String> tags = new ArrayList<>();
        tags.add("$");
        String tagText = null;

        if (isConnected()) {
            advance();
            tags.add(upper(prev.text()));
        } else {
            raiseError("No closing $ found");
        }

        if (!tags.get(tags.size() - 1).equals("$")) {
            if (isConnected() && matchTextSeq("$")) {
                tagText = tags.get(tags.size() - 1);
                tags.add("$");
            } else {
                raiseError("No closing $ found");
            }
        }

        Token heredocStart = curr;

        String[] tagArray = tags.toArray(new String[0]);
        while (present(curr)) {
            if (matchTextSeq(false, tagArray)) {
                String this_ = findSql(heredocStart, prev);
                advance(tags.size());
                return build(ExprType.HEREDOC, Args.THIS, this_, Args.TAG, tagText);
            }

            advance();
        }

        raiseError("No closing " + String.join("", tags) + " found");
        return null;
    }

    @Override
    protected Expression replaceLambda(Expression node, List<Expression> expressions) {
        if (node == null) {
            return node;
        }

        Map<String, Object> lambdaTypes = new LinkedHashMap<>();
        for (Expression e : expressions) {
            Object to = e.get(Args.TO);
            lambdaTypes.put(e.name(), Expression.truthy(to) ? to : Boolean.FALSE);
        }

        for (Expression column : node.findAll(ExprType.COLUMN)) {
            Object typ = lambdaTypes.get(column.parts().get(0).name());
            if (typ != null) {
                Expression dotOrId = !column.text(Args.TABLE).isEmpty() ? column.toDot(true) : column.getThis();

                if (Expression.truthy(typ)) {
                    dotOrId = build(ExprType.CAST, Args.THIS, dotOrId, Args.TO, typ);
                }

                Expression parent = column.parent();

                boolean broke = false;
                while (parent != null && parent.is(ExprType.DOT)) {
                    Expression grand = parent.parent();
                    if (!(grand != null && grand.is(ExprType.DOT))) {
                        parent.replace(dotOrId);
                        broke = true;
                        break;
                    }
                    parent = grand;
                }
                if (!broke) {
                    if (column == node) {
                        node = dotOrId;
                    } else {
                        column.replace(dotOrId);
                    }
                }
            }
        }
        return node;
    }

    @Override
    protected Expression parseTruncateTable() {
        Token start = prev;

        // Not to be confused with TRUNCATE(number, decimals) function call
        if (match(TokenType.L_PAREN)) {
            retreat(tokenIndex - 2);
            return parseFunction();
        }

        // Clickhouse supports TRUNCATE DATABASE as well
        boolean isDatabase = match(TokenType.DATABASE);

        match(TokenType.TABLE);

        Boolean exists = parseExists(false);

        List<Expression> expressions = parseCsv(() -> parseTable(true, false, null, false, isDatabase));

        Expression cluster = match(TokenType.ON) ? parseOnProperty() : null;

        String identity;
        if (matchTextSeq("RESTART", "IDENTITY")) {
            identity = "RESTART";
        } else if (matchTextSeq("CONTINUE", "IDENTITY")) {
            identity = "CONTINUE";
        } else {
            identity = null;
        }

        String option;
        if (matchTextSeq("CASCADE") || matchTextSeq("RESTRICT")) {
            option = prev.text();
        } else {
            option = null;
        }

        Expression partition = parsePartition();

        // Fallback case
        if (present(curr)) {
            return parseAsCommand(start);
        }

        return build(ExprType.TRUNCATE_TABLE,
                Args.EXPRESSIONS, expressions,
                Args.IS_DATABASE, isDatabase,
                Args.EXISTS, exists,
                Args.CLUSTER, cluster,
                Args.IDENTITY, identity,
                Args.OPTION, option,
                Args.PARTITION, partition);
    }

    @Override
    protected Expression parseIndexedColumn() {
        return parseOrdered(this::parseOpclass);
    }

    @Override
    protected Expression parseWithOperator() {
        Expression this_ = parseIndexedColumn();

        if (!match(TokenType.WITH)) {
            return this_;
        }

        Expression op = parseVar(true, d.RESERVED_TOKENS);

        return build(ExprType.WITH_OPERATOR, Args.THIS, this_, Args.OP, op);
    }

    @Override
    protected List<Expression> parseWrappedOptions() {
        match(TokenType.EQ);
        match(TokenType.L_PAREN);

        List<Expression> opts = new ArrayList<>();
        Object option;
        while (present(curr) && !match(TokenType.R_PAREN)) {
            if (matchTextSeq("FORMAT_NAME", "=")) {
                // The FORMAT_NAME can be set to an identifier for Snowflake and T-SQL
                option = parseFormatName();
            } else {
                option = parseProperty();
            }

            if (option == null) {
                raiseError("Unable to parse option");
                break;
            }

            opts.addAll(Py.<Expression>ensureList(option));
        }

        return opts;
    }

    @Override
    protected List<Expression> parseCopyParameters() {
        TokenType sep = dd.COPY_PARAMS_ARE_CSV ? TokenType.COMMA : null;

        List<Expression> options = new ArrayList<>();
        while (present(curr) && !match(TokenType.R_PAREN, false)) {
            Expression option = parseVar(true);
            String prevText = upper(prev.text());

            // Different dialects might separate options and values by white space, "=" and "AS"
            match(TokenType.EQ);
            match(TokenType.ALIAS);

            Expression param = build(ExprType.COPY_PARAMETER, Args.THIS, option);

            if (d.COPY_INTO_VARLEN_OPTIONS.contains(prevText) && match(TokenType.L_PAREN, false)) {
                // Snowflake FILE_FORMAT case, Databricks COPY & FORMAT options
                param.set(Args.EXPRESSIONS, parseWrappedOptions());
            } else if (prevText.equals("FILE_FORMAT")) {
                // T-SQL's external file format case
                param.set(Args.EXPRESSION, parseField());
            } else if (prevText.equals("FORMAT")
                    && prev.type() == TokenType.ALIAS
                    && matchTexts(Set.of("AVRO", "JSON"))) {
                param.set(Args.THIS, Exp.var("FORMAT AS " + upper(prev.text())));
                param.set(Args.EXPRESSION, parseField());
            } else {
                Expression value = parseUnquotedField();
                param.set(Args.EXPRESSION, value != null ? value : parseBracket());
            }

            options.add(param);

            if (sep != null) {
                match(sep);
            }
        }

        return options;
    }

    @Override
    protected Expression parseCredentials() {
        Expression expr = expression(Expression.of(ExprType.CREDENTIALS));

        if (matchTextSeq("STORAGE_INTEGRATION", "=")) {
            expr.set(Args.STORAGE, parseField());
        }
        if (matchTextSeq("CREDENTIALS")) {
            // Snowflake case: CREDENTIALS = (...), Redshift case: CREDENTIALS <string>
            Object creds = match(TokenType.EQ) ? parseWrappedOptions() : parseField();
            expr.set(Args.CREDENTIALS, creds);
        }
        if (matchTextSeq("ENCRYPTION")) {
            expr.set(Args.ENCRYPTION, parseWrappedOptions());
        }
        if (matchTextSeq("IAM_ROLE")) {
            expr.set(Args.IAM_ROLE, match(TokenType.DEFAULT) ? Exp.var(prev.text()) : parseField());
        }
        if (matchTextSeq("REGION")) {
            expr.set(Args.REGION, parseField());
        }

        return expr;
    }

    @Override
    protected Expression parseFileLocation() {
        return parseField();
    }

    @Override
    protected Expression parseCopy() {
        Token start = prev;

        match(TokenType.INTO);

        Expression this_ = match(TokenType.L_PAREN, false)
                ? parseSelect(true, false, false)
                : parseTable(true);

        boolean kind = match(TokenType.FROM) || !matchTextSeq("TO");

        List<Expression> files = parseCsv(this::parseFileLocation);
        if (match(TokenType.EQ, false)) {
            // Backtrack one token since we've consumed the lhs of a parameter assignment here.
            // This can happen for Snowflake dialect. Instead, we'd like to parse the parameter
            // list via `_parse_wrapped(..)` below.
            advance(-1);
            files = new ArrayList<>();
        }

        Expression credentials = parseCredentials();

        matchTextSeq("WITH");

        List<Expression> params = parseWrapped(this::parseCopyParameters, true);

        // Fallback case
        if (present(curr)) {
            return parseAsCommand(start);
        }

        return build(ExprType.COPY,
                Args.THIS, this_, Args.KIND, kind, Args.CREDENTIALS, credentials, Args.FILES, files,
                Args.PARAMS, params);
    }

    @Override
    protected Expression parseNormalize() {
        return build(ExprType.NORMALIZE,
                Args.THIS, parseBitwise(), Args.FORM, match(TokenType.COMMA) ? parseVar() : Boolean.FALSE);
    }

    @Override
    protected Expression parseCeilFloor(ExprType exprType) {
        List<Expression> args = parseCsv(() -> parseLambda());

        Expression this_ = Py.seqGet(args, 0);
        Expression decimals = Py.seqGet(args, 1);

        return Expression.of(exprType,
                Args.THIS, this_,
                Args.DECIMALS, decimals,
                Args.TO, matchTextSeq("TO") ? parseVar() : null);
    }

    @Override
    protected Expression parseStarOps() {
        Token starToken = prev;

        if (matchTextSeq(false, "COLUMNS", "(")) {
            Expression this_ = parseFunction();
            if (this_ != null && this_.is(ExprType.COLUMNS)) {
                this_.set(Args.UNPACK, Boolean.TRUE);
            }
            return this_;
        }

        int index = tokenIndex;
        Expression ilike = match(TokenType.ILIKE) ? parseString() : null;
        if (ilike == null) {
            // ILIKE without a string pattern is not a star filter, e.g. `* ILIKE (foo)`
            retreat(index);
        }

        return updatePositions(build(ExprType.STAR,
                Args.ILIKE, ilike,
                Args.EXCEPT_, parseStarOp("EXCEPT", "EXCLUDE"),
                Args.REPLACE, parseStarOp("REPLACE"),
                Args.RENAME, parseStarOp("RENAME")), starToken);
    }

    @Override
    protected Expression parseGrantPrivilege() {
        List<String> privilegeParts = new ArrayList<>();

        // Keep consuming consecutive keywords until comma (end of this privilege) or ON
        // (end of privilege list) or L_PAREN (start of column list) are met
        while (present(curr) && !matchSet(d.PRIVILEGE_FOLLOW_TOKENS, false)) {
            privilegeParts.add(upper(curr.text()));
            advance();
        }

        if (privilegeParts.isEmpty()) {
            raiseError("Expected privilege");
            return null;
        }

        Expression this_ = Exp.var(String.join(" ", privilegeParts));
        List<Expression> expressions = match(TokenType.L_PAREN, false) ? parseWrappedCsv(this::parseColumn) : null;

        return build(ExprType.GRANT_PRIVILEGE, Args.THIS, this_, Args.EXPRESSIONS, expressions);
    }

    @Override
    protected Expression parseGrantPrincipal() {
        Object kind = matchTexts(Set.of("ROLE", "GROUP")) ? upper(prev.text()) : Boolean.FALSE;
        Expression principal = parseIdVar();

        if (principal == null) {
            return null;
        }

        return build(ExprType.GRANT_PRINCIPAL, Args.THIS, principal, Args.KIND, kind);
    }

    @Override
    protected Object[] parseGrantRevokeCommon() {
        List<Expression> privileges = parseCsv(this::parseGrantPrivilege);

        match(TokenType.ON);
        String kind = matchSet(d.CREATABLES) ? upper(prev.text()) : null;

        // Attempt to parse the securable e.g. MySQL allows names
        // such as "foo.*", "*.*" which are not easily parseable yet
        Expression securable = tryParse(() -> parseTableParts());

        return new Object[] {privileges, kind, securable};
    }

    @Override
    protected Expression parseGrant() {
        Token start = prev;

        Object[] common = parseGrantRevokeCommon();
        Object privileges = common[0];
        Object kind = common[1];
        Expression securable = (Expression) common[2];

        if (securable == null || !matchTextSeq("TO")) {
            return parseAsCommand(start);
        }

        List<Expression> principals = parseCsv(this::parseGrantPrincipal);

        boolean grantOption = matchTextSeq("WITH", "GRANT", "OPTION");

        if (present(curr)) {
            return parseAsCommand(start);
        }

        return build(ExprType.GRANT,
                Args.PRIVILEGES, privileges,
                Args.KIND, kind,
                Args.SECURABLE, securable,
                Args.PRINCIPALS, principals,
                Args.GRANT_OPTION, grantOption);
    }

    @Override
    protected Expression parseRevoke() {
        Token start = prev;

        boolean grantOption = matchTextSeq("GRANT", "OPTION", "FOR");

        Object[] common = parseGrantRevokeCommon();
        Object privileges = common[0];
        Object kind = common[1];
        Expression securable = (Expression) common[2];

        if (securable == null || !matchTextSeq("FROM")) {
            return parseAsCommand(start);
        }

        List<Expression> principals = parseCsv(this::parseGrantPrincipal);

        String cascade = null;
        if (matchTexts(CASCADE_RESTRICT)) {
            cascade = upper(prev.text());
        }

        if (present(curr)) {
            return parseAsCommand(start);
        }

        return build(ExprType.REVOKE,
                Args.PRIVILEGES, privileges,
                Args.KIND, kind,
                Args.SECURABLE, securable,
                Args.PRINCIPALS, principals,
                Args.GRANT_OPTION, grantOption,
                Args.CASCADE, cascade);
    }

    private Expression parseOverlayArg(String text) {
        return match(TokenType.COMMA) || matchTextSeq(text) ? parseBitwise() : null;
    }

    @Override
    protected Expression parseOverlay() {
        return build(ExprType.OVERLAY,
                Args.THIS, parseBitwise(),
                Args.EXPRESSION, parseOverlayArg("PLACING"),
                Args.FROM_, parseOverlayArg("FROM"),
                Args.FOR_, parseOverlayArg("FOR"));
    }

    @Override
    protected Expression parseFormatName() {
        // Note: Although not specified in the docs, Snowflake does accept a string/identifier
        // for FILE_FORMAT = <format_name>
        Expression value = parseString();
        return build(ExprType.PROPERTY,
                Args.THIS, Exp.var("FORMAT_NAME"), Args.VALUE, value != null ? value : parseTableParts());
    }

    @Override
    protected Expression parseDistinctArgFunction(ExprType func, int distinctIndex) {
        boolean isDistinct = match(TokenType.DISTINCT);
        if (!isDistinct) {
            match(TokenType.ALL);
        }

        List<Expression> args = new ArrayList<>();
        args.add(parseLambda());
        if (match(TokenType.COMMA)) {
            args.addAll(parseFunctionArgs());
        }

        Expression target = Py.seqGet(args, distinctIndex);
        if (isDistinct && target != null) {
            args.set(distinctIndex, build(ExprType.DISTINCT, Args.EXPRESSIONS, List.of(target)));
        }

        return Exp.fromArgList(func, args);
    }

    @Override
    protected Expression identifierExpression(Token token, Boolean quoted) {
        Token tok = token != null && present(token) ? token : prev;
        return expression(Expression.of(ExprType.IDENTIFIER, Args.THIS, tok.text(), Args.QUOTED, quoted), tok);
    }

    @Override
    protected Expression buildPipeCte(Expression query, List<Expression> expressions, Expression aliasCte) {
        Object newCte;
        if (aliasCte != null) {
            newCte = aliasCte;
        } else {
            pipeCteCounter += 1;
            newCte = "__tmp" + pipeCteCounter;
        }

        Expression with = query.expr(Args.WITH_);
        Expression ctes = with != null ? with.pop() : null;

        Expression newSelect = Exp.select(expressions.toArray()).from(newCte, false);
        if (ctes != null) {
            newSelect.set(Args.WITH_, ctes);
        }

        return newSelect.with(newCte, query, false, true, false);
    }

    @Override
    protected Expression parsePipeSyntaxSelect(Expression query) {
        Expression select = parseSelect(false, false, true, true, false);
        if (select == null) {
            return query;
        }

        return buildPipeCte(query.select(false, true, select.getExpressions().toArray()), listOfStar());
    }

    private static List<Expression> listOfStar() {
        List<Expression> l = new ArrayList<>(1);
        l.add(Expression.of(ExprType.STAR));
        return l;
    }

    @Override
    protected Expression parsePipeSyntaxLimit(Expression query) {
        Expression limit = parseLimit();
        Expression offset = parseOffset();
        if (limit != null && limit.is(ExprType.LIMIT)) {
            Object rawCurrLimit = query.get(Args.LIMIT);
            Expression currLimit = rawCurrLimit instanceof Expression e ? e : limit;
            boolean currIsLimitAll = currLimit.is(ExprType.LIMIT) && isLimitAll(currLimit);

            // LIMIT ALL means "no cap", so it's never tighter than a numeric limit.
            boolean shouldReplace;
            if (isLimitAll(limit)) {
                shouldReplace = currIsLimitAll;
            } else {
                shouldReplace = currIsLimitAll
                        || toDecimal(currLimit.getExpression().toPy())
                                        .compareTo(toDecimal(limit.getExpression().toPy())) >= 0;
            }

            if (shouldReplace) {
                query.limit(limit, false);
            }
        }
        if (offset != null) {
            Expression currOffsetNode = query.expr(Args.OFFSET);
            Object currOffset = currOffsetNode != null ? currOffsetNode.getExpression().toPy() : BigInteger.ZERO;
            query.offset(Exp.number(pyAdd(currOffset, offset.getExpression().toPy())), false);
        }

        return query;
    }

    @Override
    protected Expression parsePipeSyntaxAggregateFields() {
        Expression this_ = parseDisjunction();
        if (matchTextSeq(false, "GROUP", "AND")) {
            return this_;
        }

        Expression aliased = parseAlias(this_);

        if (matchSet(ASC_DESC, false)) {
            return parseOrdered(() -> aliased);
        }

        return aliased;
    }

    @Override
    protected Expression parsePipeSyntaxAggregateGroupOrderBy(Expression query, boolean groupByExists) {
        List<Expression> expr = parseCsv(this::parsePipeSyntaxAggregateFields);
        List<Expression> aggregatesOrGroups = new ArrayList<>();
        List<Expression> orders = new ArrayList<>();
        for (Expression element : expr) {
            Expression this_;
            if (element.is(ExprType.ORDERED)) {
                this_ = element.getThis();
                if (this_ != null && this_.is(ExprType.ALIAS)) {
                    element.set(Args.THIS, this_.get(Args.ALIAS));
                }
                orders.add(element);
            } else {
                this_ = element;
            }
            aggregatesOrGroups.add(this_);
        }

        if (groupByExists) {
            List<Object> projections = new ArrayList<>(aggregatesOrGroups);
            projections.addAll(query.getExpressions());
            List<Object> groups = new ArrayList<>();
            for (Expression projection : aggregatesOrGroups) {
                Object alias = projection.get(Args.ALIAS);
                groups.add(alias != null ? alias : projection);
            }
            query.select(false, false, projections.toArray()).groupBy(true, false, groups.toArray());
        } else {
            query.select(false, false, aggregatesOrGroups.toArray());
        }

        if (!orders.isEmpty()) {
            return query.orderBy(false, false, orders.toArray());
        }

        return query;
    }

    @Override
    protected Expression parsePipeSyntaxAggregate(Expression query) {
        matchTextSeq("AGGREGATE");
        query = parsePipeSyntaxAggregateGroupOrderBy(query, false);

        if (match(TokenType.GROUP_BY) || (matchTextSeq("GROUP", "AND") && match(TokenType.ORDER_BY))) {
            query = parsePipeSyntaxAggregateGroupOrderBy(query);
        }

        return buildPipeCte(query, listOfStar());
    }

    private Expression parseAndUnwrapQuery() {
        Expression expr = parseParen();
        return expr != null ? expr.assertIs(ExprType.SUBQUERY).unnest() : null;
    }

    /**
     * {@code query.union/except_/intersect(*setops, copy=False, **template.args)}: sqlglot reduces
     * the operands into nested set operations of the template's type, each built with the
     * template's remaining args (distinct, by_name, side, kind, on, ...).
     */
    private static Expression applySetOperation(Expression query, List<Expression> setops, Expression template) {
        Object distinct = template.get(Args.DISTINCT);
        if (distinct == null) {
            distinct = Boolean.TRUE;
        }
        Expression result = query;
        for (Expression setop : setops) {
            Map<Arg, Object> args = new LinkedHashMap<>();
            args.put(Args.THIS, result);
            args.put(Args.EXPRESSION, setop);
            args.put(Args.DISTINCT, distinct);
            for (Map.Entry<Arg, Object> entry : template.argEntries()) {
                Arg k = entry.getKey();
                if (k != Args.THIS && k != Args.EXPRESSION && k != Args.DISTINCT) {
                    args.put(k, entry.getValue());
                }
            }
            result = Expression.of(template.type(), args);
        }
        return result;
    }

    @Override
    protected Expression parsePipeSyntaxSetOperator(Expression query) {
        Expression firstSetop = parseSetOperation(query);
        if (firstSetop == null) {
            return null;
        }

        firstSetop.getThis().pop();

        List<Expression> setops = new ArrayList<>();
        setops.add(firstSetop.getExpression().pop().assertIs(ExprType.SUBQUERY).unnest());
        setops.addAll(parseCsv(this::parseAndUnwrapQuery));

        query = buildPipeCte(query, listOfStar());
        Expression with = query.expr(Args.WITH_);
        Expression ctes = with != null ? with.pop() : null;

        // Union, Except and Intersect each map to the matching builder (union/except_/intersect)
        query = applySetOperation(query, setops, firstSetop);

        query.set(Args.WITH_, ctes);

        return buildPipeCte(query, listOfStar());
    }

    @Override
    protected Expression parsePipeSyntaxJoin(Expression query) {
        Expression join = parseJoin();
        if (join == null) {
            return null;
        }

        if (query.is(ExprType.SELECT)) {
            return query.join(join, null, null, null, null, false);
        }

        return query;
    }

    @Override
    protected Expression parsePipeSyntaxPivot(Expression query) {
        List<Expression> pivots = parsePivots();
        if (pivots == null || pivots.isEmpty()) {
            return query;
        }

        Expression from = query.expr(Args.FROM_);
        if (from != null) {
            from.getThis().set(Args.PIVOTS, pivots);
        }

        return buildPipeCte(query, listOfStar());
    }

    @Override
    protected Expression parsePipeSyntaxExtend(Expression query) {
        matchTextSeq("EXTEND");
        List<Object> projections = new ArrayList<>();
        projections.add(Expression.of(ExprType.STAR));
        projections.addAll(parseExpressions());
        query.select(false, false, projections.toArray());
        return buildPipeCte(query, listOfStar());
    }

    @Override
    protected Expression parsePipeSyntaxTablesample(Expression query) {
        Expression sample = parseTableSample();

        Expression with = query.expr(Args.WITH_);
        if (with != null) {
            List<Expression> ctes = with.getExpressions();
            ctes.get(ctes.size() - 1).getThis().set(Args.SAMPLE, sample);
        } else {
            query.set(Args.SAMPLE, sample);
        }

        return query;
    }

    @Override
    protected Expression parsePipeSyntaxQuery(Expression query) {
        if (query.is(ExprType.SUBQUERY)) {
            query = Exp.select("*").from(query, false);
        }

        if (!query.has(Args.FROM_)) {
            query = Exp.select("*").from(query.subquery(null, false), false);
        }

        while (match(TokenType.PIPE_GT)) {
            int startIndex = tokenIndex;
            String startText = upper(curr.text());
            if (!d.PIPE_SYNTAX_TRANSFORM_PARSERS.contains(startText)) {
                // The set operators (UNION, etc) and the JOIN operator have a few common starting
                // keywords, making it tricky to disambiguate them without lookahead. The approach
                // here is to try and parse a set operation and if that fails, then try to parse a
                // join operator. If that fails as well, then the operator is not supported.
                Expression parsedQuery = parsePipeSyntaxSetOperator(query);
                if (parsedQuery == null) {
                    parsedQuery = parsePipeSyntaxJoin(query);
                }
                if (parsedQuery == null) {
                    retreat(startIndex);
                    raiseError("Unsupported pipe syntax operator: '" + startText + "'.");
                    break;
                }
                query = parsedQuery;
            } else {
                query = pipeSyntaxTransformParser(startText, query);
            }
        }

        return query;
    }

    @Override
    protected Expression parseDeclareitem() {
        matchTexts(Set.of("VAR", "VARIABLE"));

        List<Expression> vars = parseCsv(() -> parseIdVar());
        if (vars.isEmpty()) {
            return null;
        }

        match(TokenType.ALIAS);
        Expression kind = match(TokenType.TABLE) ? parseSchema() : parseTypes();
        Object default_ = match(TokenType.DEFAULT) || match(TokenType.EQ) ? parseBitwise() : Boolean.FALSE;

        return build(ExprType.DECLARE_ITEM, Args.THIS, vars, Args.KIND, kind, Args.DEFAULT, default_);
    }

    @Override
    protected Expression parseDeclare() {
        Token start = prev;
        boolean replace = matchTextSeq("OR", "REPLACE");
        List<Expression> expressions = tryParse(() -> parseCsv(this::parseDeclareitem));

        if (expressions == null || expressions.isEmpty() || present(curr)) {
            return parseAsCommand(start);
        }

        return build(ExprType.DECLARE, Args.EXPRESSIONS, expressions, Args.REPLACE, replace);
    }

    @Override
    protected Expression buildCast(boolean strict, Map<Arg, Object> kwargs) {
        ExprType expClass = strict ? ExprType.CAST : ExprType.TRY_CAST;
        Map<Arg, Object> args = kwargs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(kwargs);

        if (expClass == ExprType.TRY_CAST) {
            args.put(Args.REQUIRES_STRING, dd.TRY_CAST_REQUIRES_STRING);
        }

        return expression(Expression.of(expClass, args));
    }

    @Override
    protected Expression parseJsonValue(boolean jsonPath) {
        Expression this_ = parseBitwise();
        match(TokenType.COMMA);
        Expression path = parseBitwise();

        Object returning = match(TokenType.RETURNING) ? parseTypes() : Boolean.FALSE;

        return build(ExprType.JSON_VALUE,
                Args.THIS, this_,
                Args.PATH, jsonPath ? dialect.toJsonPath(path) : path,
                Args.RETURNING, returning,
                Args.ON_CONDITION, parseOnCondition());
    }

    /** {@code concat_exprs} closure of {@code _parse_group_concat} ({@code args} is the enclosing list). */
    private Expression concatExprs(Expression node, List<Expression> exprs, List<Expression> args) {
        if (node != null && node.is(ExprType.DISTINCT) && node.getExpressions().size() > 1) {
            List<Expression> concatExprs = new ArrayList<>();
            concatExprs.add(build(ExprType.CONCAT,
                    Args.EXPRESSIONS, node.getExpressions(),
                    Args.SAFE, Boolean.TRUE,
                    Args.COALESCE, dd.CONCAT_COALESCE));
            node.set(Args.EXPRESSIONS, concatExprs);
            return node;
        }
        if (exprs.size() == 1) {
            return exprs.get(0);
        }
        return build(ExprType.CONCAT, Args.EXPRESSIONS, args, Args.SAFE, Boolean.TRUE, Args.COALESCE, dd.CONCAT_COALESCE);
    }

    @Override
    protected Expression parseGroupConcat() {
        List<Expression> args = parseCsv(() -> parseLambda());

        Expression this_;
        if (!args.isEmpty()) {
            Expression last = args.get(args.size() - 1);
            Expression order = last.is(ExprType.ORDER) ? last : null;

            if (order != null) {
                // Order By is the last (or only) expression in the list and has consumed the 'expr' before it,
                // remove 'expr' from exp.Order and add it back to args
                args.set(args.size() - 1, order.getThis());
                order.set(Args.THIS, concatExprs(order.getThis(), args, args));
            }

            this_ = order != null ? order : concatExprs(args.get(0), args, args);
        } else {
            this_ = null;
        }

        Expression separator = match(TokenType.SEPARATOR) ? parseField() : null;

        return build(ExprType.GROUP_CONCAT, Args.THIS, this_, Args.SEPARATOR, separator);
    }

    @Override
    protected Expression parseInitcap() {
        Expression expr = Exp.fromArgList(ExprType.INITCAP, parseFunctionArgs());

        // attach dialect's default delimiters
        if (expr.get(Args.EXPRESSION) == null) {
            expr.set(Args.EXPRESSION, Exp.string(dd.INITCAP_DEFAULT_DELIMITER_CHARS));
        }

        return expr;
    }

    @Override
    protected Expression parseOperator(Expression this_) {
        if (!match(TokenType.L_PAREN)) {
            retreat(tokenIndex - 1);
            return null;
        }

        StringBuilder op = new StringBuilder();
        while (present(curr) && !match(TokenType.R_PAREN)) {
            op.append(curr.text());
            advance();
        }

        List<String> comments = prevComments;
        return expression(Expression.of(ExprType.OPERATOR,
                Args.THIS, this_, Args.OPERATOR, op.toString(), Args.EXPRESSION, parseBitwise()), comments);
    }

}
