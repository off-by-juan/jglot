package io.github.offbyjuan.jglot.parser;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.errors.TokenError;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser part D: set operations, the operator-precedence chain, types, columns, functions, lambdas,
 * schemas, column definitions and constraints (sqlglot/parser.py 6025-8099).
 */
abstract class ParserPartD extends ParserMethods {
    ParserPartD(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }

    // sqlglot.parser.TIME_ZONE_RE
    private static final Pattern TIME_ZONE_RE = Pattern.compile(":.*?[a-zA-Z\\+\\-]", Pattern.UNICODE_CHARACTER_CLASS);
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

    /** Python f-string formatting of an optional value ({@code None} for null). */
    private static String pyStr(Object o) {
        return o == null ? "None" : o.toString();
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

            if (parts == null && !d.NO_PAREN_FUNCTION_PARSERS.isEmpty()
                    && d.NO_PAREN_FUNCTION_PARSERS.contains(Py.upper(token.text()))) {
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
                && d.NO_PAREN_FUNCTION_PARSERS.contains(upper)
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

                options.add("ON " + pyStr(on) + " " + pyStr(action));
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
}
