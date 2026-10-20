package io.github.offbyjuan.jglot.parser;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.data.DialectData;
import io.github.offbyjuan.jglot.dialects.data.ParserData;
import io.github.offbyjuan.jglot.errors.ErrorLevel;
import io.github.offbyjuan.jglot.errors.ParseError;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.tokens.Token;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.util.Trie;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Parser state, token navigation, error handling and dispatch hooks: the hand-written core of the
 * port of sqlglot's {@code Parser}. A parser instance is single-use per call and never shared
 * between threads; all dialect tables live in the immutable {@link ParserData}.
 */
public abstract class ParserCore {
    protected static final Logger LOGGER = Logger.getLogger("jglot");

    /** sqlglot's falsy SENTINEL token, used for "no token" (before the first/after the last). */
    public static final Token SENTINEL_NONE = new Token(TokenType.SENTINEL, "");

    /** A parse callback, e.g. {@code this::parseIdVar}. */
    @FunctionalInterface
    public interface ParseFn {
        Expression parse();
    }

    /** A statement-level parse callback taking the parser (sqlglot passes {@code self}). */
    @FunctionalInterface
    public interface StatementFn {
        Expression parse(ParserCore parser);
    }

    protected final Dialect dialect;
    /** The dialect's resolved parser tables and flags ({@code self.X} in sqlglot's Parser). */
    protected final ParserData d;
    /** The dialect's resolved settings ({@code self.dialect.X} in sqlglot). */
    protected final DialectData dd;

    protected ErrorLevel errorLevel;
    protected final int errorMessageContext;
    protected final int maxErrors;
    protected final int maxNodes;

    protected String sqlText = "";
    protected List<ParseError> errors = new ArrayList<>();
    protected List<Token> tokenList = new ArrayList<>();
    protected int tokenCount;
    protected int tokenIndex;
    protected Token curr = SENTINEL_NONE;
    protected Token next = SENTINEL_NONE;
    protected Token prev = SENTINEL_NONE;
    protected List<String> prevComments = new ArrayList<>();
    protected int pipeCteCounter;
    protected List<List<Token>> chunks = new ArrayList<>();
    protected int chunkIndex;
    protected int nodeCount;

    protected ParserCore(Dialect dialect, ParserOptions options) {
        this.dialect = dialect;
        this.d = dialect.parserData();
        this.dd = dialect.data();
        this.errorLevel = options.errorLevel() == null ? ErrorLevel.IMMEDIATE : options.errorLevel();
        this.errorMessageContext = options.errorMessageContext();
        this.maxErrors = options.maxErrors();
        this.maxNodes = options.maxNodes();
    }

    public Dialect dialect() {
        return dialect;
    }

    protected static RuntimeException notPorted(String pythonMethod) {
        return new UnsupportedOperationException("not yet ported from sqlglot: Parser." + pythonMethod);
    }

    /** Python truthiness of a token: anything but the sentinel. */
    protected static boolean present(Token t) {
        return t != null && t.type() != TokenType.SENTINEL;
    }

    // ------------------------------------------------------------------------------------------
    // State and navigation

    public void reset() {
        sqlText = "";
        errors = new ArrayList<>();
        tokenList = new ArrayList<>();
        tokenCount = 0;
        tokenIndex = 0;
        curr = SENTINEL_NONE;
        next = SENTINEL_NONE;
        prev = SENTINEL_NONE;
        prevComments = new ArrayList<>();
        pipeCteCounter = 0;
        chunks = new ArrayList<>();
        chunkIndex = 0;
        nodeCount = 0;
    }

    protected void advance(int times) {
        int i = tokenIndex + times;
        tokenIndex = i;
        List<Token> toks = tokenList;
        int size = tokenCount;
        curr = i >= 0 && i < size ? toks.get(i) : SENTINEL_NONE;
        next = i + 1 >= 0 && i + 1 < size ? toks.get(i + 1) : SENTINEL_NONE;

        if (i > 0 && i - 1 < size) {
            Token p = toks.get(i - 1);
            prev = p;
            prevComments = p.comments();
        } else {
            prev = SENTINEL_NONE;
            prevComments = new ArrayList<>();
        }
    }

    protected void advance() {
        advance(1);
    }

    protected void advanceChunk() {
        tokenIndex = -1;
        tokenList = chunks.get(chunkIndex);
        tokenCount = tokenList.size();
        chunkIndex++;
        advance();
    }

    protected void retreat(int idx) {
        if (idx != tokenIndex) {
            advance(idx - tokenIndex);
        }
    }

    protected void addComments(Expression expression) {
        if (expression != null && prevComments != null && !prevComments.isEmpty()) {
            expression.addComments(prevComments);
            prevComments = new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Matching

    protected boolean match(TokenType tokenType, boolean advance, Expression expression) {
        if (curr.type() == tokenType) {
            if (advance) {
                advance();
            }
            addComments(expression);
            return true;
        }
        return false;
    }

    protected boolean match(TokenType tokenType) {
        return match(tokenType, true, null);
    }

    protected boolean match(TokenType tokenType, boolean advance) {
        return match(tokenType, advance, null);
    }

    protected boolean matchSet(Collection<TokenType> types, boolean advance) {
        if (types.contains(curr.type())) {
            if (advance) {
                advance();
            }
            return true;
        }
        return false;
    }

    protected boolean matchSet(Collection<TokenType> types) {
        return matchSet(types, true);
    }

    protected boolean matchPair(TokenType a, TokenType b, boolean advance) {
        if (curr.type() == a && next.type() == b) {
            if (advance) {
                advance(2);
            }
            return true;
        }
        return false;
    }

    protected boolean matchPair(TokenType a, TokenType b) {
        return matchPair(a, b, true);
    }

    protected boolean matchTexts(Collection<String> texts, boolean advance) {
        if (!d.TEXT_MATCH_EXCLUDED_TOKENS.contains(curr.type())
                && texts.contains(curr.text().toUpperCase(Locale.ROOT))) {
            if (advance) {
                advance();
            }
            return true;
        }
        return false;
    }

    protected boolean matchTexts(Collection<String> texts) {
        return matchTexts(texts, true);
    }

    /** {@code _match_text_seq(*texts, advance=advance)}. */
    protected boolean matchTextSeq(boolean advance, String... texts) {
        int idx = tokenIndex;
        Set<TokenType> excluded = d.TEXT_MATCH_EXCLUDED_TOKENS;
        for (String text : texts) {
            if (!excluded.contains(curr.type()) && curr.text().toUpperCase(Locale.ROOT).equals(text)) {
                advance();
            } else {
                retreat(idx);
                return false;
            }
        }
        if (!advance) {
            retreat(idx);
        }
        return true;
    }

    protected boolean matchTextSeq(String... texts) {
        return matchTextSeq(true, texts);
    }

    protected boolean isConnected() {
        return present(prev) && present(curr) && prev.end() + 1 == curr.start();
    }

    protected String findSql(Token start, Token end) {
        int s = Math.max(0, start.start());
        int e = Math.min(sqlText.length(), end.end() + 1);
        return s <= e ? sqlText.substring(s, e) : "";
    }

    protected void matchLParen(Expression expression) {
        if (!match(TokenType.L_PAREN, true, expression)) {
            raiseError("Expecting (");
        }
    }

    protected void matchLParen() {
        matchLParen(null);
    }

    protected void matchRParen(Expression expression) {
        if (!match(TokenType.R_PAREN, true, expression)) {
            raiseError("Expecting )");
        }
    }

    protected void matchRParen() {
        matchRParen(null);
    }

    /**
     * sqlglot's {@code _find_parser}: matches the longest multi-word key in {@code trie} starting at
     * the current token and returns it (space-joined), or null after retreating if none matches.
     */
    protected String findParser(Trie trie) {
        if (!present(curr)) {
            return null;
        }
        int idx = tokenIndex;
        List<String> words = new ArrayList<>();
        Trie t = trie;
        while (true) {
            String c = curr.text().toUpperCase(Locale.ROOT);
            words.add(c);
            advance();
            Trie.Step step = t.inTrie(List.of(c.split(" ", -1)));
            t = step.trie();
            if (step.result() == Trie.Result.FAILED) {
                break;
            }
            if (step.result() == Trie.Result.EXISTS) {
                return String.join(" ", words);
            }
        }
        retreat(idx);
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Errors and validation

    public void raiseError(String message, Token token) {
        Token tok = present(token) ? token : present(curr) ? curr : present(prev) ? prev : Token.string("");
        String[] h = highlightSql(sqlText, tok.start(), tok.end(), errorMessageContext);
        String formatted = message + ". Line " + tok.line() + ", Col: " + tok.col() + ".\n  " + h[0];
        ParseError error = ParseError.of(formatted, message, tok.line(), tok.col(), h[1], h[2], h[3], null);
        if (errorLevel == ErrorLevel.IMMEDIATE) {
            throw error;
        }
        errors.add(error);
    }

    public void raiseError(String message) {
        raiseError(message, SENTINEL_NONE);
    }

    static final String ANSI_UNDERLINE = "\033[4m";
    static final String ANSI_RESET = "\033[0m";

    /** sqlglot's {@code highlight_sql} for a single position: {formatted, start, highlight, end}. */
    static String[] highlightSql(String sql, int start, int end, int contextLength) {
        String startContext = "";
        String endContext = "";
        int firstHighlightStart = 0;
        StringBuilder formatted = new StringBuilder();
        int previousPartEnd = 0;
        int len = sql.length();
        start = Math.max(0, Math.min(start, len));
        end = Math.min(end, len - 1);

        if (start > 0) {
            firstHighlightStart = start;
            startContext = sql.substring(Math.max(0, firstHighlightStart - contextLength), firstHighlightStart);
            formatted.append(startContext);
            previousPartEnd = firstHighlightStart;
        }

        int highlightStart = Math.max(start, previousPartEnd);
        int highlightEnd = end + 1;
        if (highlightStart < highlightEnd) {
            if (highlightStart > previousPartEnd) {
                formatted.append(sql, previousPartEnd, highlightStart);
            }
            formatted.append(ANSI_UNDERLINE).append(sql, highlightStart, highlightEnd).append(ANSI_RESET);
            previousPartEnd = highlightEnd;
        }

        if (previousPartEnd < len) {
            endContext = sql.substring(previousPartEnd, Math.min(len, previousPartEnd + contextLength));
            formatted.append(endContext);
        }

        String highlight = sql.substring(Math.min(firstHighlightStart, len), Math.min(previousPartEnd, len));
        return new String[] {formatted.toString(), startContext, highlight, endContext};
    }

    /** Validates required args (and, for functions, arity) of a freshly built node. */
    public Expression validateExpression(Expression expression, List<?> args) {
        if (maxNodes > -1) {
            nodeCount++;
            if (nodeCount > maxNodes) {
                raiseError("Maximum number of AST nodes (" + maxNodes + ") exceeded");
            }
        }
        if (errorLevel != ErrorLevel.IGNORE) {
            for (String msg : errorMessages(expression, args)) {
                raiseError(msg);
            }
        }
        return expression;
    }

    public Expression validateExpression(Expression expression) {
        return validateExpression(expression, null);
    }

    /** sqlglot's {@code Expression.error_messages}. */
    public static List<String> errorMessages(Expression e, List<?> args) {
        List<String> out = null;
        ExprType t = e.type();
        for (int i = 0; i < t.argCount(); i++) {
            if (!t.isRequired(i)) {
                continue;
            }
            Object v = e.get(t.argAt(i));
            if (v == null || (v instanceof List<?> l && l.isEmpty())) {
                if (out == null) {
                    out = new ArrayList<>();
                }
                out.add("Required keyword: '" + t.argAt(i).name() + "' missing for <class 'sqlglot.expressions."
                        + t.className() + "'>");
            }
        }
        if (args != null && t.isA(ExprType.FUNC) && args.size() > t.argCount() && !t.isVarLenArgs()) {
            if (out == null) {
                out = new ArrayList<>();
            }
            out.add("The number of provided arguments (" + args.size()
                    + ") is greater than the maximum number of supported arguments (" + t.argCount() + ")");
        }
        return out == null ? List.of() : out;
    }

    /** Logs or raises any errors collected so far, depending on the error level. */
    public void checkErrors() {
        if (errorLevel == ErrorLevel.WARN) {
            for (ParseError e : errors) {
                LOGGER.severe(e.getMessage());
            }
        } else if (errorLevel == ErrorLevel.RAISE && !errors.isEmpty()) {
            throw new ParseError(ParseError.concatMessages(errors, maxErrors), ParseError.merge(errors));
        }
    }

    /**
     * sqlglot's {@code self.expression(instance, token, comments)}: attaches position metadata and
     * pending comments, then validates.
     */
    protected Expression expression(Expression instance, Token token, List<String> comments) {
        if (token != null && present(token)) {
            Map<String, Object> meta = instance.meta();
            meta.put("line", token.line());
            meta.put("col", token.col());
            meta.put("start", token.start());
            meta.put("end", token.end());
        }
        if (comments != null && !comments.isEmpty()) {
            instance.addComments(comments);
        } else {
            addComments(instance);
        }
        if (!instance.type().isPrimitive()) {
            instance = validateExpression(instance);
        }
        return instance;
    }

    protected Expression expression(Expression instance) {
        return expression(instance, null, null);
    }

    protected Expression expression(Expression instance, Token token) {
        return expression(instance, token, null);
    }

    protected Expression expression(Expression instance, List<String> comments) {
        return expression(instance, null, comments);
    }

    /** Builds a node from (Arg, value) pairs and passes it through {@link #expression(Expression)}. */
    protected Expression build(ExprType type, Object... argsAndValues) {
        return expression(Expression.of(type, argsAndValues));
    }

    // ------------------------------------------------------------------------------------------
    // Speculative parsing

    /** sqlglot's {@code _try_parse}: runs {@code fn} with IMMEDIATE errors, retreating on failure. */
    protected <T> T tryParse(Supplier<T> fn, boolean retreat) {
        int idx = tokenIndex;
        ErrorLevel level = errorLevel;
        T result = null;
        errorLevel = ErrorLevel.IMMEDIATE;
        try {
            result = fn.get();
        } catch (ParseError e) {
            result = null;
        } finally {
            if (!Expression.truthy(result) || retreat) {
                retreat(idx);
            }
            errorLevel = level;
        }
        return result;
    }

    protected <T> T tryParse(Supplier<T> fn) {
        return tryParse(fn, false);
    }

    // ------------------------------------------------------------------------------------------
    // Entry points

    /** Parses tokens into one syntax tree per statement (null entries for empty statements). */
    public List<Expression> parse(List<Token> rawTokens, String sql) {
        return parseImpl(p -> ((ParserCore) p).parseStatement(), rawTokens, sql);
    }

    /** Parses tokens into a specific node type, e.g. {@code ExprType.CONDITION}. */
    public List<Expression> parseInto(ExprType type, List<Token> rawTokens, String sql) {
        if (!d.EXPRESSION_PARSERS.contains(type)) {
            throw new IllegalArgumentException("No parser registered for " + type);
        }
        try {
            return parseImpl(p -> ((ParserCore) p).expressionParser(type), rawTokens, sql);
        } catch (ParseError e) {
            List<ParseError.Detail> details = new ArrayList<>();
            for (ParseError.Detail dt : e.errors()) {
                details.add(new ParseError.Detail(dt.description(), dt.line(), dt.col(), dt.startContext(),
                        dt.highlight(), dt.endContext(), type.className()));
            }
            ParseError err = new ParseError("Failed to parse '" + (sql == null ? rawTokens : sql) + "' into " + type,
                    details);
            err.initCause(e);
            throw err;
        }
    }

    protected List<Expression> parseBatchStatements(StatementFn parseMethod, boolean sepFirstStatement) {
        List<Expression> expressions = new ArrayList<>();

        if (sepFirstStatement) {
            match(TokenType.BEGIN);
            expressions.add(parseMethod.parse(this));
        }

        int chunksLength = chunks.size();
        while (chunkIndex < chunksLength) {
            advanceChunk();

            if (match(TokenType.ELSE, false)) {
                return expressions;
            }

            if (!expressions.isEmpty() && !present(next) && match(TokenType.END)) {
                expressions.add(Expression.of(ExprType.END_STATEMENT));
                continue;
            }

            expressions.add(parseMethod.parse(this));

            if (tokenIndex < tokenCount) {
                raiseError("Invalid expression / Unexpected token");
            }

            checkErrors();
        }

        return expressions;
    }

    protected List<Expression> parseImpl(StatementFn parseMethod, List<Token> rawTokens, String sql) {
        reset();
        this.sqlText = sql == null ? "" : sql;

        int total = rawTokens.size();
        List<List<Token>> cs = new ArrayList<>();
        cs.add(new ArrayList<>());

        for (int i = 0; i < total; i++) {
            Token token = rawTokens.get(i);
            if (token.type() == TokenType.SEMICOLON) {
                if (!token.comments().isEmpty()) {
                    List<Token> c = new ArrayList<>();
                    c.add(token);
                    cs.add(c);
                }
                if (i < total - 1) {
                    cs.add(new ArrayList<>());
                }
            } else {
                cs.get(cs.size() - 1).add(token);
            }
        }

        this.chunks = cs;
        return parseBatchStatements(parseMethod, false);
    }

    protected void warnUnsupported() {
        if (tokenCount <= 1) {
            return;
        }
        String s = findSql(tokenList.get(0), tokenList.get(tokenCount - 1));
        if (s.length() > errorMessageContext) {
            s = s.substring(0, errorMessageContext);
        }
        LOGGER.warning("'" + s + "' contains unsupported syntax. Falling back to parsing as a 'Command'.");
    }

    // ------------------------------------------------------------------------------------------
    // Generic list helpers

    /** sqlglot's {@code _parse_csv}. */
    protected List<Expression> parseCsv(ParseFn parseMethod, TokenType sep) {
        Expression result = parseMethod.parse();
        List<Expression> items = new ArrayList<>();
        if (result != null) {
            items.add(result);
        }
        while (match(sep)) {
            if (result != null) {
                addComments(result);
            }
            result = parseMethod.parse();
            if (result != null) {
                items.add(result);
            }
        }
        return items;
    }

    protected List<Expression> parseCsv(ParseFn parseMethod) {
        return parseCsv(parseMethod, TokenType.COMMA);
    }

    /** {@code _parse_csv} for parse methods returning arbitrary values (e.g. lists or strings). */
    protected <T> List<T> parseCsvOf(Supplier<T> parseMethod, TokenType sep) {
        T result = parseMethod.get();
        List<T> items = new ArrayList<>();
        if (result != null) {
            items.add(result);
        }
        while (match(sep)) {
            if (result instanceof Expression e) {
                addComments(e);
            }
            result = parseMethod.get();
            if (result != null) {
                items.add(result);
            }
        }
        return items;
    }

    /** sqlglot's {@code _parse_wrapped}. */
    protected <T> T parseWrapped(Supplier<T> parseMethod, boolean optional) {
        boolean wrapped = match(TokenType.L_PAREN);
        if (!wrapped && !optional) {
            raiseError("Expecting (");
        }
        T result = parseMethod.get();
        if (wrapped) {
            matchRParen();
        }
        return result;
    }

    protected <T> T parseWrapped(Supplier<T> parseMethod) {
        return parseWrapped(parseMethod, false);
    }

    /** sqlglot's {@code _parse_wrapped_csv}. */
    protected List<Expression> parseWrappedCsv(ParseFn parseMethod, TokenType sep, boolean optional) {
        return parseWrapped(() -> parseCsv(parseMethod, sep), optional);
    }

    protected List<Expression> parseWrappedCsv(ParseFn parseMethod) {
        return parseWrappedCsv(parseMethod, TokenType.COMMA, false);
    }

    protected List<Expression> parseWrappedCsv(ParseFn parseMethod, boolean optional) {
        return parseWrappedCsv(parseMethod, TokenType.COMMA, optional);
    }

    // ------------------------------------------------------------------------------------------
    // FUNCTIONS table

    /** Whether the dialect's FUNCTIONS table has an entry for {@code upperName}. */
    protected boolean hasFunction(String upperName) {
        return d.FUNCTIONS_FROM_ARG_LIST.containsKey(upperName) || d.FUNCTIONS_CUSTOM.contains(upperName);
    }

    /**
     * Calls the dialect's FUNCTIONS builder for {@code upperName}: either the generic
     * {@code Func.from_arg_list} of a known type, or a hand-written {@link #buildFunction}.
     */
    protected Expression callFunctionBuilder(String upperName, List<Expression> args) {
        ExprType t = d.FUNCTIONS_FROM_ARG_LIST.get(upperName);
        if (t != null) {
            return Exp.fromArgList(t, args);
        }
        return buildFunction(upperName, args);
    }

    /** The ExprType a FUNCTIONS entry builds via {@code from_arg_list}, or null if it is custom. */
    protected ExprType functionFromArgListType(String upperName) {
        return d.FUNCTIONS_FROM_ARG_LIST.get(upperName);
    }

    // ------------------------------------------------------------------------------------------
    // Dispatch hooks for sqlglot's callable tables. A key is only dispatched when it is present in
    // the corresponding ParserData key set; dialect parsers override these to add or replace
    // entries and call super for the rest.

    /** Custom (non {@code from_arg_list}) entries of {@code FUNCTIONS}. */
    protected Expression buildFunction(String name, List<Expression> args) {
        throw notPorted("FUNCTIONS[" + name + "]");
    }

    /** {@code LAMBDAS[t](self, expressions)}. */
    protected Expression lambdaParser(TokenType t, List<Expression> expressions) {
        throw notPorted("LAMBDAS[" + t + "]");
    }

    /** {@code COLUMN_OPERATORS[t](self, this, other)} (never called for DOT, whose entry is None). */
    protected Expression columnOperator(TokenType t, Expression this_, Expression other) {
        throw notPorted("COLUMN_OPERATORS[" + t + "]");
    }

    /** {@code JSON_OPERATORS[t](self, this, other)}. */
    protected Expression jsonOperator(TokenType t, Expression this_, Expression other) {
        throw notPorted("JSON_OPERATORS[" + t + "]");
    }

    /** {@code EXPRESSION_PARSERS[type](self)}. */
    protected Expression expressionParser(ExprType type) {
        throw notPorted("EXPRESSION_PARSERS[" + type + "]");
    }

    /** {@code STATEMENT_PARSERS[t](self)}. */
    protected Expression statementParser(TokenType t) {
        throw notPorted("STATEMENT_PARSERS[" + t + "]");
    }

    /** {@code UNARY_PARSERS[t](self)}. */
    protected Expression unaryParser(TokenType t) {
        throw notPorted("UNARY_PARSERS[" + t + "]");
    }

    /** {@code STRING_PARSERS[t](self, token)}. */
    protected Expression stringParser(TokenType t, Token token) {
        throw notPorted("STRING_PARSERS[" + t + "]");
    }

    /** {@code NUMERIC_PARSERS[t](self, token)}. */
    protected Expression numericParser(TokenType t, Token token) {
        throw notPorted("NUMERIC_PARSERS[" + t + "]");
    }

    /** {@code PRIMARY_PARSERS[t](self, token)}. */
    protected Expression primaryParser(TokenType t, Token token) {
        throw notPorted("PRIMARY_PARSERS[" + t + "]");
    }

    /** {@code PLACEHOLDER_PARSERS[t](self)}. */
    protected Expression placeholderParser(TokenType t) {
        throw notPorted("PLACEHOLDER_PARSERS[" + t + "]");
    }

    /** {@code RANGE_PARSERS[t](self, this)}. */
    protected Expression rangeParser(TokenType t, Expression this_) {
        throw notPorted("RANGE_PARSERS[" + t + "]");
    }

    /** {@code PIPE_SYNTAX_TRANSFORM_PARSERS[key](self, query)}. */
    protected Expression pipeSyntaxTransformParser(String key, Expression query) {
        throw notPorted("PIPE_SYNTAX_TRANSFORM_PARSERS[" + key + "]");
    }

    /**
     * {@code PROPERTY_PARSERS[key](self, **kwargs)}. Returns an Expression, a List of expressions, or
     * null. {@code kwargs} holds the optional keyword arguments some property parsers accept.
     */
    protected Object propertyParser(String key, Map<String, Object> kwargs) {
        throw notPorted("PROPERTY_PARSERS[" + key + "]");
    }

    /** {@code CONSTRAINT_PARSERS[key](self)}. */
    protected Expression constraintParser(String key) {
        throw notPorted("CONSTRAINT_PARSERS[" + key + "]");
    }

    /** {@code ALTER_PARSERS[key](self)}; may return an Expression or a List. */
    protected Object alterParser(String key) {
        throw notPorted("ALTER_PARSERS[" + key + "]");
    }

    /** {@code ALTER_ALTER_PARSERS[key](self)}. */
    protected Expression alterAlterParser(String key) {
        throw notPorted("ALTER_ALTER_PARSERS[" + key + "]");
    }

    /** {@code NO_PAREN_FUNCTION_PARSERS[key](self)}. */
    protected Expression noParenFunctionParser(String key) {
        throw notPorted("NO_PAREN_FUNCTION_PARSERS[" + key + "]");
    }

    /** {@code FUNCTION_PARSERS[key](self)}. */
    protected Expression functionParser(String key) {
        throw notPorted("FUNCTION_PARSERS[" + key + "]");
    }

    /** {@code QUERY_MODIFIER_PARSERS[t](self)}: returns the (arg key, value) pair. */
    protected ModifierResult queryModifierParser(TokenType t) {
        throw notPorted("QUERY_MODIFIER_PARSERS[" + t + "]");
    }

    /** A (key, value) pair produced by a query modifier parser. */
    public record ModifierResult(Arg key, Object value) {}

    /** {@code SET_PARSERS[key](self)}. */
    protected Expression setParser(String key) {
        throw notPorted("SET_PARSERS[" + key + "]");
    }

    /** {@code SHOW_PARSERS[key](self)}. */
    protected Expression showParser(String key) {
        throw notPorted("SHOW_PARSERS[" + key + "]");
    }

    /** {@code TYPE_LITERAL_PARSERS[t](self, this, data_type)}. */
    protected Expression typeLiteralParser(DType t, Expression this_, Expression dataType) {
        throw notPorted("TYPE_LITERAL_PARSERS[" + t + "]");
    }

    /** {@code TYPE_CONVERTERS[t](data_type)}. */
    protected Expression typeConverter(DType t, Expression dataType) {
        throw notPorted("TYPE_CONVERTERS[" + t + "]");
    }

    /** {@code ANALYZE_EXPRESSION_PARSERS[key](self)}. */
    protected Expression analyzeExpressionParser(String key) {
        throw notPorted("ANALYZE_EXPRESSION_PARSERS[" + key + "]");
    }

    /** sqlglot's {@code _parse_statement}; ported in the Parser fragments. */
    protected abstract Expression parseStatement();
}
