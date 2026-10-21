package io.github.offbyjuan.jglot.dialects;

import io.github.offbyjuan.jglot.dialects.bigquery.BigQuery;
import io.github.offbyjuan.jglot.dialects.data.DialectData;
import io.github.offbyjuan.jglot.dialects.hive.Hive;
import io.github.offbyjuan.jglot.errors.ParseError;
import io.github.offbyjuan.jglot.errors.TokenError;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.tokens.Token;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.tokens.Tokenizer;
import io.github.offbyjuan.jglot.tokens.TokenizerConfig;
import io.github.offbyjuan.jglot.util.Py;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** JSON path parsing for {@link Dialect#toJsonPath}: a port of sqlglot's {@code jsonpath} module. */
public final class JsonPathSupport {
    private JsonPathSupport() {}

    private static final Logger LOGGER = Logger.getLogger("jglot");

    /** sqlglot's {@code ALL_JSON_PATH_PARTS}: the JSONPathPart types with a generator transform. */
    public static final Set<ExprType> ALL_JSON_PATH_PARTS = Collections.unmodifiableSet(EnumSet.of(
            ExprType.JSON_PATH_FILTER,
            ExprType.JSON_PATH_KEY,
            ExprType.JSON_PATH_RECURSIVE,
            ExprType.JSON_PATH_ROOT,
            ExprType.JSON_PATH_SCRIPT,
            ExprType.JSON_PATH_SELECTOR,
            ExprType.JSON_PATH_SLICE,
            ExprType.JSON_PATH_SUBSCRIPT,
            ExprType.JSON_PATH_UNION,
            ExprType.JSON_PATH_WILDCARD));

    /** sqlglot's {@code Dialect.to_json_path}. */
    static Expression toJsonPath(Dialect dialect, Expression path) {
        if (path != null && path.is(ExprType.LITERAL)) {
            String pathText = path.name();
            if (path.isNumber()) {
                pathText = "[" + pathText + "]";
            }
            try {
                return parse(pathText, dialect);
            } catch (ParseError | TokenError e) {
                String stripped = Py.lstrip(pathText);
                if (dialect.data().STRICT_JSON_PATH_SYNTAX
                        && !(stripped.startsWith("lax") || stripped.startsWith("strict"))) {
                    LOGGER.warning("Invalid JSON path syntax. " + e.getMessage());
                }
            }
        }
        return path;
    }

    // ------------------------------------------------------------------------------------------
    // JSONPathTokenizer

    /** A dialect's {@code JSONPathTokenizer}: the tokenizer plus its {@code VAR_TOKENS}. */
    record JsonPathTokenizer(Tokenizer tokenizer, Set<TokenType> varTokens) {
        List<Token> tokenize(String sql) {
            return tokenizer.tokenize(sql);
        }
    }

    private static final Map<Character, TokenType> BASE_SINGLE_TOKENS;

    static {
        Map<Character, TokenType> m = new LinkedHashMap<>();
        m.put('(', TokenType.L_PAREN);
        m.put(')', TokenType.R_PAREN);
        m.put('[', TokenType.L_BRACKET);
        m.put(']', TokenType.R_BRACKET);
        m.put(':', TokenType.COLON);
        m.put(',', TokenType.COMMA);
        m.put('-', TokenType.DASH);
        m.put('.', TokenType.DOT);
        m.put('?', TokenType.PLACEHOLDER);
        m.put('@', TokenType.PARAMETER);
        m.put('\'', TokenType.QUOTE);
        m.put('"', TokenType.QUOTE);
        m.put('$', TokenType.DOLLAR);
        m.put('*', TokenType.STAR);
        BASE_SINGLE_TOKENS = Collections.unmodifiableMap(m);
    }

    /**
     * sqlglot's {@code dialect.jsonpath_tokenizer()}. The JSONPathTokenizer class is resolved along
     * the dialect class hierarchy (Hive, BigQuery, Snowflake and Databricks customize it); the
     * dialect-level settings come from the dialect itself.
     */
    static JsonPathTokenizer jsonPathTokenizer(Dialect dialect) {
        DialectData dd = dialect.data();
        String name = dialect.name();

        Map<Character, TokenType> singleTokens = new LinkedHashMap<>(BASE_SINGLE_TOKENS);
        Map<String, TokenType> keywords = new LinkedHashMap<>();
        keywords.put("..", TokenType.DOT);
        List<String> identifiers = new ArrayList<>(List.of("\""));
        Set<TokenType> varTokens = EnumSet.of(TokenType.VAR);

        if (dialect instanceof Hive) {
            // Hive.JSONPathTokenizer (inherited by Spark2, Spark, Databricks)
            varTokens.add(TokenType.DASH);
        }
        if ("databricks".equals(name)) {
            identifiers = new ArrayList<>(List.of("`", "\""));
        }
        if (dialect instanceof BigQuery) {
            varTokens.add(TokenType.DASH);
            varTokens.add(TokenType.NUMBER);
        }
        if ("snowflake".equals(name)) {
            singleTokens.remove('$');
        }

        TokenizerConfig.Builder b = TokenizerConfig.builder();
        for (Map.Entry<Character, TokenType> e : singleTokens.entrySet()) {
            b.single(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, TokenType> e : keywords.entrySet()) {
            b.kw(e.getKey(), e.getValue());
        }
        // QUOTES = ["'"]
        b.quotes.put("'", "'");
        // _FORMAT_STRINGS: national strings for each quote
        for (String q : b.quotes.keySet()) {
            String end = b.quotes.get(q);
            b.format("n" + q, end, TokenType.NATIONAL_STRING);
            b.format("N" + q, end, TokenType.NATIONAL_STRING);
        }
        for (String id : identifiers) {
            b.identifiers.put(id.charAt(0), id);
        }
        // COMMENTS = ["--", ("/*", "*/")] plus the Jinja comment; HINT_START isn't a keyword here
        b.comments.put("--", null);
        b.comments.put("/*", "*/");
        b.comments.put("{#", "#}");
        if (keywords.containsKey(b.hintStart)) {
            b.comments.put(b.hintStart, "*/");
        }
        // STRING_ESCAPES = IDENTIFIER_ESCAPES = ["\\"]; BYTE_STRING_ESCAPES defaults to STRING_ESCAPES
        b.stringEscapes.add('\\');
        b.stringEscapesOrdered.add("\\");
        b.byteStringEscapes.add('\\');
        b.identifierEscapes.add('\\');
        b.commands.addAll(List.of(TokenType.COMMAND, TokenType.EXECUTE, TokenType.FETCH, TokenType.SHOW,
                TokenType.RENAME));
        b.commandPrefixTokens.addAll(List.of(TokenType.SEMICOLON, TokenType.BEGIN));
        b.tokensPrecedingHint.addAll(List.of(TokenType.SELECT, TokenType.INSERT, TokenType.UPDATE,
                TokenType.DELETE));

        // _KEYWORD_TRIE
        List<String> trieCandidates = new ArrayList<>(keywords.keySet());
        trieCandidates.addAll(b.comments.keySet());
        trieCandidates.addAll(b.quotes.keySet());
        trieCandidates.addAll(b.formatStrings.keySet());
        for (String key : trieCandidates) {
            boolean include = key.contains(" ");
            if (!include) {
                for (Character single : singleTokens.keySet()) {
                    if (key.indexOf(single) >= 0) {
                        include = true;
                        break;
                    }
                }
            }
            if (include) {
                String upper = key.toUpperCase(Locale.ROOT);
                if (!b.keywordTrieKeys.contains(upper)) {
                    b.keywordTrieKeys.add(upper);
                }
            }
        }

        b.nestedComments = true;
        b.dashCommentRequiresBoundary = false;
        b.commentsTerminateAtNewlineOnly = false;
        b.hasBitStrings = false;
        b.hasHexStrings = false;
        b.stringEscapesAllowedInRawStrings = true;
        b.heredocTagIsIdentifier = false;
        b.heredocStringAlternative = TokenType.VAR;
        b.numbersCanBeUnderscoreSeparated = dd.NUMBERS_CAN_BE_UNDERSCORE_SEPARATED;
        // In tokenizers like JSONPath, dots are always key separators, never decimal points
        b.numbersCanHaveDecimals = false;
        b.identifiersCanStartWithDigit = dd.IDENTIFIERS_CAN_START_WITH_DIGIT;
        b.unescapedSequences.putAll(dd.UNESCAPED_SEQUENCES);
        b.dropUnknownEscapes = false;
        b.numericEscapesAreBytes = false;
        b.byteStringsAreBytes = dd.BYTE_STRING_IS_BYTES_TYPE;
        b.loneSurrogateReplacement = "";

        return new JsonPathTokenizer(new Tokenizer(b.build()), Collections.unmodifiableSet(varTokens));
    }

    // ------------------------------------------------------------------------------------------
    // Parser

    /** sqlglot's {@code jsonpath.parse}: parses a JSON path string into a {@code JSON_PATH} node. */
    public static Expression parse(String path, Dialect dialect) {
        Dialect d = dialect == null ? Dialects.base() : dialect;
        JsonPathTokenizer tokenizer = jsonPathTokenizer(d);
        return new PathParser(path, d, tokenizer).parse();
    }

    private static final class PathParser {
        final String path;
        final Dialect dialect;
        final JsonPathTokenizer tokenizer;
        final List<Token> tokens;
        final int size;
        int i;

        PathParser(String path, Dialect dialect, JsonPathTokenizer tokenizer) {
            this.path = path;
            this.dialect = dialect;
            this.tokenizer = tokenizer;
            this.tokens = tokenizer.tokenize(path);
            this.size = tokens.size();
        }

        TokenType curr() {
            return i < size ? tokens.get(i).type() : null;
        }

        Token prev() {
            return tokens.get(i - 1);
        }

        Token advance() {
            i++;
            return prev();
        }

        String error(String msg) {
            return msg + " at index " + i + ": " + path;
        }

        Token match(TokenType tokenType) {
            return match(tokenType, false);
        }

        Token match(TokenType tokenType, boolean raiseUnmatched) {
            if (curr() == tokenType) {
                return advance();
            }
            if (raiseUnmatched) {
                throw new ParseError(error("Expected TokenType." + tokenType.name()));
            }
            return null;
        }

        Token matchSet(Set<TokenType> types) {
            TokenType c = curr();
            return c != null && types.contains(c) ? advance() : null;
        }

        /** Returns a String, an Expression, a Number, or Boolean.FALSE (nothing parsed). */
        Object parseLiteral() {
            Token token = match(TokenType.STRING);
            if (token == null) {
                token = match(TokenType.IDENTIFIER);
            }
            if (token != null) {
                return token.text();
            }
            if (match(TokenType.STAR) != null) {
                return Expression.of(ExprType.JSON_PATH_WILDCARD);
            }
            if (match(TokenType.PLACEHOLDER) != null || match(TokenType.L_PAREN) != null) {
                boolean script = prev().text().equals("(");
                int start = i;

                while (true) {
                    if (match(TokenType.L_BRACKET) != null) {
                        parseBracket(); // nested call which we can throw away
                    }
                    TokenType c = curr();
                    if (c == TokenType.R_BRACKET || c == null) {
                        break;
                    }
                    advance();
                }

                ExprType exprType = script ? ExprType.JSON_PATH_SCRIPT : ExprType.JSON_PATH_FILTER;
                int end = i < size ? tokens.get(i).end() : tokens.get(size - 1).end();
                return Expression.of(exprType, Args.THIS, Py.slice(path, tokens.get(start).start(), end));
            }

            String number = match(TokenType.DASH) != null ? "-" : "";

            token = match(TokenType.NUMBER);
            if (token != null) {
                number += token.text();
            }

            if (!number.isEmpty()) {
                return pyInt(number);
            }

            return Boolean.FALSE;
        }

        Object parseSlice() {
            Object start = parseLiteral();
            Object end = match(TokenType.COLON) != null ? parseLiteral() : null;
            Object step = match(TokenType.COLON) != null ? parseLiteral() : null;

            if (end == null && step == null) {
                return start;
            }

            return Expression.of(ExprType.JSON_PATH_SLICE, Args.START, start, Args.END, end, Args.STEP, step);
        }

        Expression parseBracket() {
            Object literal = parseSlice();
            Expression node;

            if (literal instanceof String || !Boolean.FALSE.equals(literal)) {
                List<Object> indexes = new ArrayList<>();
                indexes.add(literal);
                while (match(TokenType.COMMA) != null) {
                    literal = parseSlice();

                    if (literal instanceof String || !Boolean.FALSE.equals(literal)) {
                        indexes.add(literal);
                    }
                }

                if (indexes.size() == 1) {
                    Object first = indexes.get(0);
                    if (first instanceof String) {
                        node = Expression.of(ExprType.JSON_PATH_KEY, Args.THIS, first);
                    } else if (first instanceof Expression fe && fe.is(ExprType.JSON_PATH_PART)
                            && fe.is(ExprType.JSON_PATH_SCRIPT, ExprType.JSON_PATH_FILTER)) {
                        node = Expression.of(ExprType.JSON_PATH_SELECTOR, Args.THIS, first);
                    } else {
                        node = Expression.of(ExprType.JSON_PATH_SUBSCRIPT, Args.THIS, first);
                    }
                } else {
                    node = Expression.of(ExprType.JSON_PATH_UNION, Args.EXPRESSIONS, indexes);
                }
            } else {
                throw new ParseError(error("Cannot have empty segment"));
            }

            match(TokenType.R_BRACKET, true);

            return node;
        }

        /**
         * Consumes & returns the text for a var. In BigQuery it's valid to have a key with spaces in
         * it, e.g JSON_QUERY(..., '$. a b c ') should produce a single JSONPathKey(' a b c '). This is
         * done by merging "consecutive" vars until a key separator is found (dot, colon etc) or the
         * path string is exhausted.
         */
        String parseVarText() {
            int prevIndex = i - 2;

            while (matchSet(tokenizer.varTokens()) != null) {
                // consume
            }

            int start = prevIndex < 0 ? 0 : tokens.get(prevIndex).end() + 1;

            String text;
            if (i >= tokens.size()) {
                // This key is the last token for the path, so it's text is the remaining path
                text = Py.slice(path, start);
            } else {
                text = Py.slice(path, start, tokens.get(i).start());
            }

            return text;
        }

        Expression parse() {
            // We canonicalize the JSON path AST so that it always starts with a
            // "root" element, so paths like "field" will be generated as "$.field"
            match(TokenType.DOLLAR);
            List<Expression> expressions = new ArrayList<>();
            expressions.add(Expression.of(ExprType.JSON_PATH_ROOT));

            while (curr() != null) {
                if (match(TokenType.DOT) != null || match(TokenType.COLON) != null) {
                    boolean recursive = prev().text().equals("..");

                    Object value;
                    if (matchSet(tokenizer.varTokens()) != null) {
                        value = parseVarText();
                    } else if (match(TokenType.IDENTIFIER) != null) {
                        value = prev().text();
                    } else if (match(TokenType.STAR) != null) {
                        value = Expression.of(ExprType.JSON_PATH_WILDCARD);
                    } else {
                        value = null;
                    }

                    if (recursive) {
                        expressions.add(Expression.of(ExprType.JSON_PATH_RECURSIVE, Args.THIS, value));
                    } else if (Expression.truthy(value)) {
                        expressions.add(Expression.of(ExprType.JSON_PATH_KEY, Args.THIS, value));
                    } else if (!dialect.data().JSON_PATH_SINGLE_DOT_IS_WILDCARD) {
                        throw new ParseError(error("Expected key name or * after DOT"));
                    }
                } else if (match(TokenType.L_BRACKET) != null) {
                    expressions.add(parseBracket());
                } else if (matchSet(tokenizer.varTokens()) != null) {
                    expressions.add(Expression.of(ExprType.JSON_PATH_KEY, Args.THIS, parseVarText()));
                } else if (match(TokenType.IDENTIFIER) != null) {
                    expressions.add(Expression.of(ExprType.JSON_PATH_KEY, Args.THIS, prev().text()));
                } else if (match(TokenType.STAR) != null) {
                    expressions.add(Expression.of(ExprType.JSON_PATH_WILDCARD));
                } else {
                    throw new ParseError(error("Unexpected TokenType." + tokens.get(i).type().name()));
                }
            }

            return Expression.of(ExprType.JSON_PATH, Args.EXPRESSIONS, expressions);
        }
    }

    /** Python's {@code int(text)}: an Integer when it fits, otherwise a BigInteger. */
    private static Number pyInt(String text) {
        BigInteger value;
        try {
            value = new BigInteger(text.strip().replace("_", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid literal for int() with base 10: '" + text + "'");
        }
        return value.bitLength() < 32 ? Integer.valueOf(value.intValue()) : value;
    }
}
