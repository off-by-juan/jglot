package io.github.offbyjuan.jglot.dialects.clickhouse;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.DialectHelpers;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;
import io.github.offbyjuan.jglot.tokens.Token;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.util.Py;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ClickHouse parser: a port of sqlglot's {@code sqlglot.parsers.clickhouse}. */
public class ClickHouseParser extends Parser {
    private static final Set<String> REFRESH_CADENCES = Set.of("EVERY", "AFTER");

    public ClickHouseParser(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }

    // ------------------------------------------------------------------------------------------
    // Module-level builders

    private Expression buildDatetimeFormat(ExprType exprType, List<Expression> args) {
        Expression expr = DialectHelpers.buildFormattedTime(exprType, null, args, dialect);

        Expression timezone = Py.seqGet(args, 2);
        if (timezone != null) {
            expr.set(Args.ZONE, timezone);
        }

        return expr;
    }

    private static Expression buildCountIf(List<Expression> args) {
        if (args.size() == 1) {
            return Expression.of(ExprType.COUNT_IF, Args.THIS, Py.seqGet(args, 0));
        }

        return Expression.of(ExprType.COMBINED_AGG_FUNC, Args.THIS, "countIf", Args.EXPRESSIONS, args);
    }

    private static Expression buildStrToDate(List<Expression> args) {
        if (args.size() == 3) {
            return Exp.anonymous("STR_TO_DATE", args);
        }

        Expression strtodate = Exp.fromArgList(ExprType.STR_TO_DATE, args);
        return Exp.cast(strtodate, DataTypes.of(DType.DATETIME));
    }

    private static Expression buildTimestampTruncUnit(String unit, List<Expression> args) {
        return Expression.of(ExprType.TIMESTAMP_TRUNC,
                Args.THIS, Py.seqGet(args, 0), Args.UNIT, Exp.var(unit), Args.ZONE, Py.seqGet(args, 1));
    }

    private static Expression buildWithoutExtraArgs(ExprType exprType, String name, List<Expression> args) {
        // The optional mode / timezone arguments have no counterpart in expr_type
        return args.size() == 1
                ? Expression.of(exprType, Args.THIS, args.get(0))
                : Exp.anonymous(name, args);
    }

    private static Expression buildSplitByChar(List<Expression> args) {
        Expression sep = Py.seqGet(args, 0);
        if (sep != null && sep.is(ExprType.LITERAL)) {
            Object sepValue = sep.toPy();
            if (sepValue instanceof String s && s.getBytes(StandardCharsets.UTF_8).length == 1) {
                return buildSplit(ExprType.SPLIT, args);
            }
        }

        return Exp.anonymous("splitByChar", args);
    }

    private static Expression buildSplit(ExprType expClass, List<Expression> args) {
        return Expression.of(expClass,
                Args.THIS, Py.seqGet(args, 1), Args.EXPRESSION, Py.seqGet(args, 0), Args.LIMIT, Py.seqGet(args, 2));
    }

    private static Expression buildArrayLambdaFunc(ExprType expClass, String name, List<Expression> args) {
        // Other dialects can't express arrayMap((x, y) -> x + y, arr1, arr2), which
        // iterates over multiple arrays at once, so we keep it as an anonymous function
        if (args.size() > 2) {
            return Exp.anonymous(name, args);
        }

        return Expression.of(expClass, Args.THIS, Py.seqGet(args, 1), Args.EXPRESSION, Py.seqGet(args, 0));
    }

    // ------------------------------------------------------------------------------------------
    // FUNCTIONS

    @Override
    protected Expression buildFunction(String name, List<Expression> args) {
        switch (name) {
            case "REGEXPEXTRACT", "REGEXP_EXTRACT", "REGEXP_SUBSTR":
                return Expression.of(ExprType.REGEXP_EXTRACT,
                        Args.THIS, Py.seqGet(args, 0),
                        Args.EXPRESSION, Py.seqGet(args, 1),
                        Args.GROUP, Py.seqGet(args, 2));
            case "TOSTARTOFMICROSECOND", "TOSTARTOFMILLISECOND", "TOSTARTOFSECOND", "TOSTARTOFMINUTE",
                    "TOSTARTOFHOUR", "TOSTARTOFDAY", "TOSTARTOFMONTH", "TOSTARTOFQUARTER", "TOSTARTOFYEAR":
                return buildTimestampTruncUnit(name.substring("TOSTARTOF".length()), args);
            case "ARRAYFILTER":
                return buildArrayLambdaFunc(ExprType.ARRAY_FILTER, "arrayFilter", args);
            case "ARRAYMAP":
                return buildArrayLambdaFunc(ExprType.TRANSFORM, "arrayMap", args);
            case "COUNTIF":
                return buildCountIf(args);
            case "DATE_ADD", "DATEADD":
                return DialectHelpers.buildDateDelta(ExprType.DATE_ADD, null, null, false, args, dialect);
            case "DATE_DIFF", "DATEDIFF":
                return DialectHelpers.buildDateDelta(ExprType.DATE_DIFF, null, null, true, args, dialect);
            case "DATE_FORMAT", "FORMATDATETIME":
                return buildDatetimeFormat(ExprType.TIME_TO_STR, args);
            case "DATE_SUB", "DATESUB":
                return DialectHelpers.buildDateDelta(ExprType.DATE_SUB, null, null, false, args, dialect);
            case "ILIKE":
                return DialectHelpers.buildLike(ExprType.I_LIKE, false, args, dialect);
            case "JSONEXTRACTRAW":
                return DialectHelpers.buildJsonExtractPath(ExprType.JSON_EXTRACT, false, true, null, args, dialect);
            case "JSONEXTRACTSTRING":
                return DialectHelpers.buildJsonExtractPath(ExprType.JSON_EXTRACT_SCALAR, false, true, null, args,
                        dialect);
            case "LENGTH":
                return Expression.of(ExprType.LENGTH, Args.THIS, Py.seqGet(args, 0), Args.BINARY, Boolean.TRUE);
            case "LIKE":
                return DialectHelpers.buildLike(ExprType.LIKE, false, args, dialect);
            case "MAP":
                return buildVarMap(args);
            case "NOTLIKE":
                return DialectHelpers.buildLike(ExprType.LIKE, true, args, dialect);
            case "PARSEDATETIME":
                return buildDatetimeFormat(ExprType.PARSE_DATETIME, args);
            case "STR_TO_DATE":
                return buildStrToDate(args);
            case "TIMESTAMP_SUB", "TIMESTAMPSUB":
                return DialectHelpers.buildDateDelta(ExprType.TIMESTAMP_SUB, null, null, false, args, dialect);
            case "TIMESTAMP_ADD", "TIMESTAMPADD":
                return DialectHelpers.buildDateDelta(ExprType.TIMESTAMP_ADD, null, null, false, args, dialect);
            case "TODAYOFWEEK":
                return buildWithoutExtraArgs(ExprType.DAY_OF_WEEK_ISO, "toDayOfWeek", args);
            case "TOISOWEEK":
                return buildWithoutExtraArgs(ExprType.WEEK_OF_YEAR, "toISOWeek", args);
            case "TOISOYEAR":
                return buildWithoutExtraArgs(ExprType.YEAR_OF_WEEK_ISO, "toISOYear", args);
            case "TOMONDAY":
                return buildTimestampTruncUnit("WEEK", args);
            case "SHA256":
                return Expression.of(ExprType.SHA2, Args.THIS, Py.seqGet(args, 0), Args.LENGTH, Exp.number(256));
            case "SHA512":
                return Expression.of(ExprType.SHA2, Args.THIS, Py.seqGet(args, 0), Args.LENGTH, Exp.number(512));
            case "SPLITBYCHAR":
                return buildSplitByChar(args);
            case "SPLITBYREGEXP":
                return buildSplit(ExprType.REGEXP_SPLIT, args);
            case "SPLITBYSTRING":
                return buildSplit(ExprType.SPLIT, args);
            case "TRIMLEFT":
                return buildTrim(args);
            case "TRIMRIGHT":
                return buildTrim(args, false);
            default:
                return super.buildFunction(name, args);
        }
    }

    /** {@code _resolve_clickhouse_agg}: splits an aggregate name into (function, suffixes) or null. */
    protected Object[] resolveClickhouseAgg(String name) {
        // ClickHouse allows chaining multiple combinators on aggregate functions.
        // See https://clickhouse.com/docs/sql-reference/aggregate-functions/combinators
        // N.B. this resolution allows any suffix stack, including ones that ClickHouse rejects
        // syntactically such as sumMergeMerge (due to repeated adjacent suffixes)

        // Until we are able to identify a 1- or 0-suffix aggregate function by name,
        // repeatedly strip and queue suffixes (checking longer suffixes first, see comment on
        // AGG_FUNCTIONS_SUFFIXES_SORTED). This loop only runs for 2 or more suffixes,
        // as AGG_FUNC_MAPPING memoizes all 0- and 1-suffix
        Map<String, List<String>> mapping = d.AGG_FUNC_MAPPING;
        Deque<String> accumulatedSuffixes = new ArrayDeque<>();
        List<String> parts;
        while ((parts = mapping.get(name)) == null) {
            boolean found = false;
            for (String suffix : d.AGG_FUNCTIONS_SUFFIXES) {
                if (name.endsWith(suffix) && name.length() != suffix.length()) {
                    accumulatedSuffixes.addFirst(suffix);
                    name = name.substring(0, name.length() - suffix.length());
                    found = true;
                    break;
                }
            }
            if (!found) {
                return null;
            }
        }

        // We now have a 0- or 1-suffix aggregate
        String aggFuncName = parts.get(0);
        String innerSuffix = parts.size() > 1 ? parts.get(1) : null;
        // The generated data encodes Python's None as the string "None"
        if (innerSuffix != null && !innerSuffix.isEmpty() && !innerSuffix.equals("None")) {
            // this is a 1-suffix aggregate (either naturally or via repeated suffix
            // stripping). prepend the innermost suffix.
            accumulatedSuffixes.addFirst(innerSuffix);
        }

        return new Object[] {aggFuncName, new ArrayList<>(accumulatedSuffixes)};
    }

    // ------------------------------------------------------------------------------------------
    // FUNCTION_PARSERS / PROPERTY_PARSERS / RANGE_PARSERS / COLUMN_OPERATORS / ...

    @Override
    protected Expression functionParser(String key) {
        return switch (key) {
            case "ARRAYJOIN" -> expression(Expression.of(ExprType.EXPLODE, Args.THIS, parseExpression()));
            case "GROUPCONCAT" -> parseGroupConcat();
            case "QUANTILE", "MEDIAN" -> parseQuantile();
            case "COLUMNS" -> parseColumns();
            case "TUPLE" -> Exp.fromArgList(ExprType.STRUCT, parseFunctionArgs(true));
            case "AND" -> parseConnectorFunction(ExprType.AND);
            case "OR" -> parseConnectorFunction(ExprType.OR);
            case "XOR" -> Exp.xor(parseFunctionArgs(false).toArray(new Expression[0]));
            case "VIEW" -> expression(Exp.anonymous("view", listOf(parseSelect())));
            default -> super.functionParser(key);
        };
    }

    private static List<Expression> listOf(Expression e) {
        List<Expression> l = new ArrayList<>();
        l.add(e);
        return l;
    }

    @Override
    protected Object propertyParser(String key, Map<String, Object> kwargs) {
        return switch (key) {
            case "ENGINE" -> parseEngineProperty();
            case "REFRESH" -> parseAutoRefreshProperty();
            case "UUID" -> expression(Expression.of(ExprType.UUID_PROPERTY, Args.THIS, parseString()));
            default -> super.propertyParser(key, kwargs);
        };
    }

    @Override
    protected Expression rangeParser(TokenType t, Expression this_) {
        if (t == TokenType.GLOBAL) {
            return parseGlobalIn(this_);
        }
        return super.rangeParser(t, this_);
    }

    @Override
    protected Expression columnOperator(TokenType t, Expression this_, Expression other) {
        if (t == TokenType.DOTCARET) {
            return expression(Expression.of(ExprType.NESTED_JSON_SELECT, Args.THIS, this_, Args.EXPRESSION, other));
        }
        return super.columnOperator(t, this_, other);
    }

    @Override
    protected ModifierResult queryModifierParser(TokenType t) {
        return switch (t) {
            case SETTINGS -> {
                advance();
                yield new ModifierResult(Args.SETTINGS, parseCsv(this::parseAssignment));
            }
            case FORMAT -> {
                advance();
                yield new ModifierResult(Args.FORMAT, parseIdVar());
            }
            default -> super.queryModifierParser(t);
        };
    }

    @Override
    protected Expression constraintParser(String key) {
        return switch (key) {
            case "INDEX" -> parseIndexConstraint(null);
            case "CODEC" -> parseCompress();
            case "ASSUME" -> parseAssumeConstraint();
            default -> super.constraintParser(key);
        };
    }

    @Override
    protected Object alterParser(String key) {
        return switch (key) {
            case "MODIFY" -> parseAlterTableAlter();
            case "REPLACE" -> parseAlterTableReplace();
            default -> super.alterParser(key);
        };
    }

    @Override
    protected Expression placeholderParser(TokenType t) {
        if (t == TokenType.L_BRACE) {
            return parseQueryParameter();
        }
        return super.placeholderParser(t);
    }

    @Override
    protected Expression statementParser(TokenType t) {
        if (t == TokenType.DETACH) {
            return parseDetach();
        }
        return super.statementParser(t);
    }

    // ------------------------------------------------------------------------------------------
    // Parse methods

    protected Expression parseWrappedSelectOrAssignment() {
        return parseWrapped(() -> {
            Expression e = parseSelect();
            return e != null ? e : parseAssignment();
        }, true);
    }

    @Override
    protected Expression parseCheckConstraint() {
        return expression(Expression.of(ExprType.CHECK_COLUMN_CONSTRAINT, Args.THIS, parseWrappedSelectOrAssignment()));
    }

    protected Expression parseAssumeConstraint() {
        return expression(Expression.of(ExprType.ASSUME_COLUMN_CONSTRAINT, Args.THIS, parseWrappedSelectOrAssignment()));
    }

    protected Expression parseEngineProperty() {
        match(TokenType.EQ);
        return expression(Expression.of(ExprType.ENGINE_PROPERTY, Args.THIS, parseField(true, null, true)));
    }

    // https://clickhouse.com/docs/en/sql-reference/statements/create/function
    @Override
    protected Expression parseUserDefinedFunctionExpression() {
        return parseLambda();
    }

    @Override
    protected Expression parseTypes(boolean checkFunc, boolean schema, boolean allowIdentifiers, boolean withCollation) {
        Expression dtype = super.parseTypes(checkFunc, schema, allowIdentifiers, withCollation);
        if (dtype != null && dtype.is(ExprType.DATA_TYPE) && !Boolean.TRUE.equals(dtype.get(Args.NULLABLE))) {
            // Mark every type as non-nullable which is ClickHouse's default, unless it's
            // already marked as nullable. This marker helps us transpile types from other
            // dialects to ClickHouse, so that we can e.g. produce `CAST(x AS Nullable(String))`
            // from `CAST(x AS TEXT)`. If there is a `NULL` value in `x`, the former would
            // fail in ClickHouse without the `Nullable` type constructor.
            dtype.set(Args.NULLABLE, Boolean.FALSE);
        }

        return dtype;
    }

    @Override
    protected Expression parseExtract() {
        int index = tokenIndex;
        Expression this_ = parseBitwise();
        if (match(TokenType.FROM)) {
            retreat(index);
            return super.parseExtract();
        }

        // We return Anonymous here because extract and regexpExtract have different semantics,
        // so parsing extract(foo, bar) into RegexpExtract can potentially break queries. E.g.,
        // `extract('foobar', 'b')` works, but ClickHouse crashes for `regexpExtract('foobar', 'b')`.
        match(TokenType.COMMA);
        List<Expression> args = new ArrayList<>();
        args.add(this_);
        args.add(parseBitwise());
        return expression(Exp.anonymous("extract", args));
    }

    @Override
    protected Expression parseAssignment() {
        Expression this_ = super.parseAssignment();

        if (match(TokenType.PLACEHOLDER)) {
            Expression trueExpr = parseAssignment();
            Object falseExpr = match(TokenType.COLON) ? parseAssignment() : Boolean.FALSE;
            return expression(Expression.of(ExprType.IF, Args.THIS, this_, Args.TRUE, trueExpr, Args.FALSE, falseExpr));
        }

        return this_;
    }

    /**
     * Parse a placeholder expression like SELECT {abc: UInt32} or FROM {table: Identifier}
     * https://clickhouse.com/docs/en/sql-reference/syntax#defining-and-using-query-parameters
     */
    protected Expression parseQueryParameter() {
        int index = tokenIndex;

        Expression this_ = parseIdVar();
        match(TokenType.COLON);
        Object kind = parseTypes(false, false, false, false);
        if (kind == null) {
            kind = matchTextSeq("IDENTIFIER") ? "Identifier" : Boolean.FALSE;
        }

        if (!Expression.truthy(kind)) {
            retreat(index);
            return null;
        } else if (!match(TokenType.R_BRACE)) {
            raiseError("Expecting }");
        }

        if (this_ != null && this_.is(ExprType.IDENTIFIER) && !this_.flag(Args.QUOTED)) {
            this_ = Exp.var(this_.name());
        }

        return expression(Expression.of(ExprType.PLACEHOLDER, Args.THIS, this_, Args.KIND, kind));
    }

    @Override
    protected Expression parseBracket(Expression this_) {
        if (this_ != null) {
            Expression bracketJsonType = null;

            while (matchPair(TokenType.L_BRACKET, TokenType.R_BRACKET)) {
                Expression inner = bracketJsonType != null
                        ? bracketJsonType
                        : Expression.of(ExprType.DATA_TYPE, Args.THIS, DType.JSON, Args.NULLABLE, Boolean.FALSE);
                bracketJsonType = Expression.of(ExprType.DATA_TYPE,
                        Args.THIS, DType.ARRAY,
                        Args.EXPRESSIONS, listOf(inner),
                        Args.NESTED, Boolean.TRUE);
            }

            if (bracketJsonType != null) {
                return expression(Expression.of(ExprType.JSON_CAST, Args.THIS, this_, Args.TO, bracketJsonType));
            }
        }

        boolean lBrace = match(TokenType.L_BRACE, false);
        Expression bracket = super.parseBracket(this_);

        if (lBrace && bracket != null && bracket.is(ExprType.STRUCT)) {
            Expression keys = Expression.of(ExprType.ARRAY);
            Expression values = Expression.of(ExprType.ARRAY);
            Expression varmap = Expression.of(ExprType.VAR_MAP, Args.KEYS, keys, Args.VALUES, values);
            for (Expression expression : bracket.getExpressions()) {
                if (!expression.is(ExprType.PROPERTY_EQ)) {
                    break;
                }

                keys.append(Args.EXPRESSIONS, Exp.string(expression.name()));
                values.append(Args.EXPRESSIONS, expression.getExpression());
            }

            return varmap;
        }

        return bracket;
    }

    protected Expression parseGlobalIn(Expression this_) {
        int index = tokenIndex - 1;
        boolean isNegated = match(TokenType.NOT);
        if (!match(TokenType.IN)) {
            retreat(index);
            return null;
        }

        Expression inExpr = parseIn(this_);
        inExpr.set(Args.IS_GLOBAL, Boolean.TRUE);
        return isNegated ? expression(Expression.of(ExprType.NOT, Args.THIS, inExpr)) : inExpr;
    }

    @Override
    protected Expression parseTable(boolean schema, boolean joins, Set<TokenType> aliasTokens, boolean parseBracket,
            boolean isDbReference, boolean parsePartition, boolean consumePipe) {
        Expression this_ = super.parseTable(schema, joins, aliasTokens, parseBracket, isDbReference, false, false);

        if (this_ != null && this_.is(ExprType.TABLE)) {
            Expression inner = this_.getThis();
            Expression alias = this_.expr(Args.ALIAS);

            if (inner != null && inner.is(ExprType.GENERATE_SERIES) && alias != null && !alias.has(Args.COLUMNS)) {
                alias.set(Args.COLUMNS, listOf(Exp.toIdentifier("generate_series")));
            }
        }

        if (match(TokenType.FINAL)) {
            this_ = expression(Expression.of(ExprType.FINAL, Args.THIS, this_));
        }

        return this_;
    }

    @Override
    protected Expression parsePosition(boolean haystackFirst) {
        return super.parsePosition(true);
    }

    // https://clickhouse.com/docs/en/sql-reference/statements/select/with/
    @Override
    protected Expression parseCte() {
        // WITH <identifier> AS <subquery expression>
        Expression cte = tryParse(() -> super.parseCte());

        if (cte == null) {
            // WITH <expression> AS <identifier>
            Expression this_ = parseAssignment();
            Expression alias = parseTableAlias();
            cte = expression(Expression.of(ExprType.CTE, Args.THIS, this_, Args.ALIAS, alias, Args.SCALAR, Boolean.TRUE));
        }

        return cte;
    }

    @Override
    protected Object[] parseJoinParts() {
        Token isGlobal = match(TokenType.GLOBAL) ? prev : null;

        Token kindPre = matchSet(d.JOIN_KINDS) ? prev : null;
        Token side = matchSet(d.JOIN_SIDES) ? prev : null;
        Token kind = matchSet(d.JOIN_KINDS) ? prev : null;

        return new Object[] {isGlobal, side != null ? side : kind, kindPre != null ? kindPre : kind};
    }

    @Override
    protected Expression parseJoin(boolean skipJoinToken, boolean parseBracket, Set<TokenType> aliasTokens) {
        Expression join = super.parseJoin(skipJoinToken, true, aliasTokens);
        if (join != null) {
            Object method = join.get(Args.METHOD);
            join.set(Args.METHOD, null);
            join.set(Args.GLOBAL_, method);

            // tbl ARRAY JOIN arr <-- this should be a `Column` reference, not a `Table`
            // https://clickhouse.com/docs/en/sql-reference/statements/select/array-join
            if (Py.upper(join.text(Args.KIND)).equals("ARRAY")) {
                for (Expression table : join.findAll(ExprType.TABLE)) {
                    table.replace(tableToColumn(table));
                }
            }
        }

        return join;
    }

    /** sqlglot's {@code Table.to_column()} (copy=True). */
    private static Expression tableToColumn(Expression table) {
        List<Expression> parts = table.parts();
        Expression lastPart = parts.get(parts.size() - 1);
        Expression col;
        if (lastPart.is(ExprType.IDENTIFIER)) {
            List<Expression> head = new ArrayList<>(parts.subList(0, Math.min(4, parts.size())));
            Collections.reverse(head);
            col = Expression.of(ExprType.COLUMN,
                    Args.THIS, Exp.toIdentifier(Py.seqGet(head, 0), null, true),
                    Args.TABLE, Exp.toIdentifier(Py.seqGet(head, 1), null, true),
                    Args.DB, Exp.toIdentifier(Py.seqGet(head, 2), null, true),
                    Args.CATALOG, Exp.toIdentifier(Py.seqGet(head, 3), null, true));
            if (parts.size() > 4) {
                List<Expression> dotParts = new ArrayList<>();
                dotParts.add(col);
                for (Expression field : parts.subList(4, parts.size())) {
                    dotParts.add(Exp.toIdentifier(field, null, true));
                }
                col = Exp.dot(dotParts);
            }
        } else {
            // This branch will be reached if a function or array is wrapped in a `Table`
            col = lastPart;
        }

        Expression alias = table.expr(Args.ALIAS);
        if (alias != null) {
            col = Exp.alias(col.copy(), alias.getThis());
        }
        return col;
    }

    @Override
    protected Expression parseFunction(Map<String, Object> functions, boolean anonymous, boolean optionalParens,
            boolean anyToken) {
        Expression expr = super.parseFunction(functions, anonymous, optionalParens, anyToken);

        Expression func = expr != null && expr.is(ExprType.WINDOW) ? expr.getThis() : expr;

        // Aggregate functions can be split in 2 parts: <func_name><suffix[es]>
        Object[] parts = func != null && func.is(ExprType.ANONYMOUS) ? resolveClickhouseAgg(func.name()) : null;

        if (parts != null) {
            Expression anonFunc = func;
            List<Expression> params = parseFuncParams(anonFunc);
            boolean hasParams = params != null && !params.isEmpty();

            ExprType expClass;
            if (!((List<?>) parts[1]).isEmpty()) {
                expClass = hasParams ? ExprType.COMBINED_PARAMETERIZED_AGG : ExprType.COMBINED_AGG_FUNC;
            } else {
                expClass = hasParams ? ExprType.PARAMETERIZED_AGG : ExprType.ANONYMOUS_AGG_FUNC;
            }

            Expression instance = Expression.of(expClass,
                    Args.THIS, anonFunc.thisValue(), Args.EXPRESSIONS, anonFunc.get(Args.EXPRESSIONS));
            if (hasParams) {
                instance.set(Args.PARAMS, params);
            }
            func = expression(instance);

            if (expr.is(ExprType.WINDOW)) {
                // The window's func was parsed as Anonymous in base parser, fix its
                // type to be ClickHouse style CombinedAnonymousAggFunc / AnonymousAggFunc
                expr.set(Args.THIS, func);
            } else if (hasParams) {
                // Params have blocked super()._parse_function() from parsing the following window
                // (if that exists) as they're standing between the function call and the window spec
                expr = parseWindow(func);
            } else {
                expr = func;
            }
        }

        return expr;
    }

    protected List<Expression> parseFuncParams(Expression this_) {
        if (matchPair(TokenType.R_PAREN, TokenType.L_PAREN)) {
            return parseCsv(this::parseLambda);
        }

        if (match(TokenType.L_PAREN)) {
            List<Expression> params = parseCsv(this::parseLambda);
            matchRParen(this_);
            return params;
        }

        return null;
    }

    @Override
    protected Expression parseGroupConcat() {
        List<Expression> args = parseCsv(this::parseLambda);
        List<Expression> params = parseFuncParams(null);

        if (params != null && !params.isEmpty()) {
            // groupConcat(sep [, limit])(expr)
            Expression separator = Py.seqGet(args, 0);
            Expression limit = Py.seqGet(args, 1);
            Expression this_ = Py.seqGet(params, 0);
            if (limit != null) {
                this_ = Expression.of(ExprType.LIMIT, Args.THIS, this_, Args.EXPRESSION, limit);
            }
            return expression(Expression.of(ExprType.GROUP_CONCAT, Args.THIS, this_, Args.SEPARATOR, separator));
        }

        // groupConcat(expr)
        return expression(Expression.of(ExprType.GROUP_CONCAT, Args.THIS, Py.seqGet(args, 0)));
    }

    protected Expression parseQuantile() {
        Expression this_ = parseLambda();
        List<Expression> params = parseFuncParams(null);
        if (params != null && !params.isEmpty()) {
            return expression(Expression.of(ExprType.QUANTILE, Args.THIS, params.get(0), Args.QUANTILE, this_));
        }
        return expression(Expression.of(ExprType.QUANTILE, Args.THIS, this_, Args.QUANTILE, Exp.number(0.5)));
    }

    @Override
    protected List<Expression> parseWrappedIdVars(boolean optional) {
        return super.parseWrappedIdVars(true);
    }

    @Override
    protected Expression parseColumnDef(Expression this_, boolean computedColumn) {
        if (match(TokenType.DOT)) {
            return Expression.of(ExprType.DOT, Args.THIS, this_, Args.EXPRESSION, parseIdVar());
        }

        return super.parseColumnDef(this_, computedColumn);
    }

    @Override
    protected Expression parsePrimaryKey(boolean wrappedOptional, boolean inProps, boolean namedPrimaryKey) {
        return super.parsePrimaryKey(wrappedOptional || inProps, inProps, namedPrimaryKey);
    }

    @Override
    protected Expression parseOnProperty() {
        int index = tokenIndex;
        if (matchTextSeq("CLUSTER")) {
            Expression this_ = parseString();
            if (this_ == null) {
                this_ = parseIdVar();
            }
            if (this_ != null) {
                return expression(Expression.of(ExprType.ON_CLUSTER, Args.THIS, this_));
            } else {
                retreat(index);
            }
        }
        return null;
    }

    protected Expression parseAutoRefreshProperty() {
        int index = tokenIndex - 1;
        String cadence = matchTexts(REFRESH_CADENCES) ? Py.upper(prev.text()) : null;
        Expression interval = cadence != null ? parseInterval(false, false) : null;

        if (cadence != null && interval == null) {
            retreat(index);
            return null;
        }

        Expression offset = null;
        if (matchTextSeq("OFFSET")) {
            offset = parseInterval(false, false);
            if (offset == null) {
                retreat(index);
                return null;
            }
        }

        Expression randomize = null;
        if (matchTextSeq("RANDOMIZE", "FOR")) {
            randomize = parseInterval(false, false);
            if (randomize == null) {
                retreat(index);
                return null;
            }
        }

        List<Expression> dependencies = null;
        if (matchTextSeq("DEPENDS", "ON")) {
            dependencies = parseCsv(() -> parseTableParts(true, false, false, false));
            if (dependencies.isEmpty()) {
                retreat(index);
                return null;
            }
        }

        if (cadence == null && (dependencies == null || dependencies.isEmpty())) {
            retreat(index);
            return null;
        }

        Expression settings = matchTextSeq("SETTINGS") ? parseSettingsProperty() : null;

        return expression(Expression.of(ExprType.AUTO_REFRESH_PROPERTY,
                Args.THIS, interval,
                Args.CADENCE, cadence,
                Args.OFFSET, offset,
                Args.RANDOMIZE, randomize,
                Args.EXPRESSIONS, dependencies,
                Args.SETTINGS, settings,
                Args.APPEND, matchTextSeq("APPEND")));
    }

    protected Expression parseIndexConstraint(String kind) {
        // INDEX name1 expr TYPE type1(args) GRANULARITY value
        Expression this_ = parseIdVar();
        Expression expression = parseAssignment();

        Object indexType;
        if (matchTextSeq("TYPE")) {
            Expression f = parseFunction();
            indexType = f != null ? f : parseVar();
        } else {
            indexType = Boolean.FALSE;
        }

        Object granularity = matchTextSeq("GRANULARITY") ? parseTerm() : Boolean.FALSE;

        return expression(Expression.of(ExprType.INDEX_COLUMN_CONSTRAINT,
                Args.THIS, this_, Args.EXPRESSION, expression, Args.INDEX_TYPE, indexType,
                Args.GRANULARITY, granularity));
    }

    @Override
    protected Expression parsePartition() {
        // https://clickhouse.com/docs/en/sql-reference/statements/alter/partition#how-to-set-partition-expression
        if (!match(TokenType.PARTITION)) {
            return null;
        }

        List<Expression> expressions;
        if (matchTextSeq("ID")) {
            // Corresponds to the PARTITION ID <string_value> syntax
            expressions = listOf(expression(Expression.of(ExprType.PARTITION_ID, Args.THIS, parseString())));
        } else {
            expressions = parseExpressions();
        }

        return expression(Expression.of(ExprType.PARTITION, Args.EXPRESSIONS, expressions));
    }

    protected Expression parseAlterTableReplace() {
        Expression partition = parsePartition();

        if (partition == null || !match(TokenType.FROM)) {
            return null;
        }

        return expression(Expression.of(ExprType.REPLACE_PARTITION,
                Args.EXPRESSION, partition, Args.SOURCE, parseTableParts()));
    }

    @Override
    protected Expression parseAlterTableAlter() {
        // MODIFY forms other than MODIFY COLUMN (SQL SECURITY, ORDER BY, TTL, COMMENT, ...)
        // are parsed as properties, since ALTER can now reach this path too
        if (Py.upper(prev.text()).equals("MODIFY") && !match(TokenType.COLUMN, false)) {
            Expression properties = parseProperties();
            if (properties != null) {
                return expression(Expression.of(ExprType.ALTER_MODIFY_SQL_SECURITY,
                        Args.EXPRESSIONS, properties.get(Args.EXPRESSIONS)));
            }
            return null;
        }

        // https://clickhouse.com/docs/sql-reference/statements/alter/column#modify-column
        Expression alter = super.parseAlterTableAlter();
        return present(curr) ? null : alter;
    }

    @Override
    protected Expression parseDefiner() {
        match(TokenType.EQ);
        if (match(TokenType.CURRENT_USER)) {
            return Expression.of(ExprType.DEFINER_PROPERTY, Args.THIS, Expression.of(ExprType.VAR, Args.THIS, Py.upper(prev.text())));
        }
        return Expression.of(ExprType.DEFINER_PROPERTY, Args.THIS, parseString());
    }

    protected Expression parseProjectionDef() {
        if (!match(TokenType.PROJECTION)) {
            return null;
        }

        Expression this_ = parseIdVar();
        Expression expression = parseWrapped(this::parseStatement);
        return expression(Expression.of(ExprType.PROJECTION_DEF, Args.THIS, this_, Args.EXPRESSION, expression));
    }

    @Override
    protected Expression parseConstraint() {
        Expression c = super.parseConstraint();
        return c != null ? c : parseProjectionDef();
    }

    @Override
    protected Expression parseAlias(Expression this_, boolean explicit) {
        // In clickhouse "SELECT <expr> APPLY(...)" is a query modifier,
        // so "APPLY" shouldn't be parsed as <expr>'s alias. However, "SELECT <expr> apply" is a valid alias
        if (matchPair(TokenType.APPLY, TokenType.L_PAREN, false)) {
            return this_;
        }

        return super.parseAlias(this_, explicit);
    }

    @Override
    protected Expression parseExpression() {
        Expression this_ = super.parseExpression();

        // Clickhouse allows "SELECT <expr> [APPLY(func)] [...]]" modifier
        while (matchPair(TokenType.APPLY, TokenType.L_PAREN)) {
            this_ = Expression.of(ExprType.APPLY, Args.THIS, this_, Args.EXPRESSION, parseVar(true, null, false));
            match(TokenType.R_PAREN);
        }

        return this_;
    }

    protected Expression parseColumns() {
        Expression this_ = expression(Expression.of(ExprType.COLUMNS, Args.THIS, parseLambda()));

        while (present(next) && matchTextSeq(")", "APPLY", "(")) {
            match(TokenType.R_PAREN);
            this_ = Expression.of(ExprType.APPLY, Args.THIS, this_, Args.EXPRESSION, parseVar(true, null, false));
        }
        return this_;
    }

    @Override
    protected Expression parseValue(boolean values) {
        Expression value = super.parseValue(values);
        if (value == null) {
            return null;
        }

        // In Clickhouse "SELECT * FROM VALUES (1, 2, 3)" generates a table with a single column, in contrast
        // to other dialects. For this case, we canonicalize the values into a tuple-of-tuples AST if it's not already one.
        // In INSERT INTO statements the same clause actually references multiple columns (opposite semantics),
        // but the final result is not altered by the extra parentheses.
        // Note: Clickhouse allows VALUES([structure], value, ...) so the branch checks for the last expression
        // A single-value tuple is generated as "(x)", which is parsed back into a Paren
        // rather than a Tuple, so it's unwrapped here to keep this rewrite idempotent
        List<Expression> expressions = value.getExpressions();
        if (values && !expressions.get(expressions.size() - 1).is(ExprType.TUPLE)) {
            List<Expression> tuples = new ArrayList<>();
            for (Expression expr : new ArrayList<>(expressions)) {
                tuples.add(expression(Expression.of(ExprType.TUPLE, Args.EXPRESSIONS, listOf(expr.unnest()))));
            }
            value.set(Args.EXPRESSIONS, tuples);
        }

        return value;
    }

    @Override
    protected Expression parsePartitionedBy() {
        // ClickHouse allows custom expressions as partition key
        // https://clickhouse.com/docs/engines/table-engines/mergetree-family/custom-partitioning-key
        return expression(Expression.of(ExprType.PARTITIONED_BY_PROPERTY, Args.THIS, parseAssignment()));
    }

    protected Expression parseDetach() {
        Object kind = matchSet(d.DB_CREATABLES) ? Py.upper(prev.text()) : Boolean.FALSE;
        Boolean exists = parseExists();
        Expression this_ = parseTableParts();

        Expression cluster = match(TokenType.ON) ? parseOnProperty() : null;
        boolean permanent = matchTextSeq("PERMANENTLY");
        boolean sync = matchTextSeq("SYNC");
        return expression(Expression.of(ExprType.DETACH,
                Args.THIS, this_,
                Args.KIND, kind,
                Args.EXISTS, exists,
                Args.CLUSTER, cluster,
                Args.PERMANENT, permanent,
                Args.SYNC, sync));
    }
}
