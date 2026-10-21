package io.github.offbyjuan.jglot.parser;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.optimizer.Scopes;
import io.github.offbyjuan.jglot.tokens.Token;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.util.Py;
import io.github.offbyjuan.jglot.util.TimeFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parser part E (sqlglot/parser.py 8101-9136): brackets, slices, CASE/IF, special syntax
 * functions (CAST, EXTRACT, STRING_AGG, CONVERT, XML, JSON, ...), windows, aliases and
 * identifier / literal helpers.
 */
abstract class ParserPartE extends ParserMethods {
    ParserPartE(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }

    private static final Set<TokenType> FORMAT_OR_COMMA = EnumSet.of(TokenType.FORMAT, TokenType.COMMA);
    private static final Set<TokenType> ROWS_OR_RANGE = EnumSet.of(TokenType.ROWS, TokenType.RANGE);
    private static final Set<TokenType> FROM_OR_COMMA = EnumSet.of(TokenType.FROM, TokenType.COMMA);
    private static final Set<TokenType> NULL_OR_UNKNOWN = EnumSet.of(TokenType.NULL, TokenType.UNKNOWN);
    private static final Set<TokenType> CHARSET_NAME_TOKENS = EnumSet.of(TokenType.BINARY, TokenType.IDENTIFIER);
    private static final Set<String> MAX_OR_MIN = Set.of("MAX", "MIN");

    // ------------------------------------------------------------------------------------------
    // Private helpers (sqlglot module functions / Expression methods without a Java API yet)

    /** sqlglot.parser's {@code build_array_constructor}. */
    private Expression buildArrayConstructor(ExprType expClass, List<Expression> args, TokenType bracketKind) {
        Expression arrayExp = Expression.of(expClass, Args.EXPRESSIONS, args);

        if (expClass == ExprType.ARRAY) {
            arrayExp.set(Args.VALUE_CONSTRUCTOR,
                    args.size() == 1 && args.get(0) != null && args.get(0).is(ExprType.QUERY));
            if (dd.HAS_DISTINCT_ARRAY_CONSTRUCTORS) {
                arrayExp.set(Args.BRACKET_NOTATION, bracketKind == TokenType.L_BRACKET);
            }
        }

        return arrayExp;
    }

    /** {@code Expression.update_positions(token)}. */
    private static Expression updatePositions(Expression e, Token token) {
        Map<String, Object> meta = e.meta();
        meta.put("line", token.line());
        meta.put("col", token.col());
        meta.put("start", token.start());
        meta.put("end", token.end());
        return e;
    }

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
}
