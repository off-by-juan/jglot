package io.github.offbyjuan.jglot.parser;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.tokens.Token;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.util.Py;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parser part A: sqlglot/parser.py lines 1-1979, i.e. the module-level builder functions and the
 * callable tables of class {@code Parser} (as dispatch-hook switches).
 */
abstract class ParserPartA extends ParserMethods {
    ParserPartA(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }

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
}
