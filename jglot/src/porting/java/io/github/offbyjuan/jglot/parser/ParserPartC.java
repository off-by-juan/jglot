package io.github.offbyjuan.jglot.parser;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.errors.ParseError;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.optimizer.NormalizeIdentifiers;
import io.github.offbyjuan.jglot.tokens.Token;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.util.Py;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Parser part C ("queries"): sqlglot/parser.py {@code _parse_projections} through {@code _parse_locks}. */
abstract class ParserPartC extends ParserMethods {
    ParserPartC(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }

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

    /** sqlglot's module-level {@code _unpivot_target}. */
    private static Expression unpivotTarget(Expression expr) {
        // UNPIVOT's pre-FOR values and FOR field are new output names, not column references.
        if (expr.is(ExprType.COLUMN) && expr.text(Args.TABLE).isEmpty()) {
            return expr.getThis();
        }
        if (expr.is(ExprType.TUPLE)) {
            List<Expression> targets = new ArrayList<>();
            for (Expression e : expr.getExpressions()) {
                targets.add(unpivotTarget(e));
            }
            expr.set(Args.EXPRESSIONS, targets);
        }
        return expr;
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
                        Exp.tableAlias(unnest, null, List.of(table.expr(Args.ALIAS).getThis()));
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
}
