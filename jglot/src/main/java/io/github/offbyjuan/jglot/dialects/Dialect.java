package io.github.offbyjuan.jglot.dialects;

import io.github.offbyjuan.jglot.dialects.data.DialectData;
import io.github.offbyjuan.jglot.dialects.data.GeneratorData;
import io.github.offbyjuan.jglot.dialects.data.ParserData;
import io.github.offbyjuan.jglot.dialects.data.TokenizerData;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;
import io.github.offbyjuan.jglot.tokens.Token;
import io.github.offbyjuan.jglot.tokens.Tokenizer;
import io.github.offbyjuan.jglot.tokens.TokenizerConfig;
import io.github.offbyjuan.jglot.util.TimeFormat;
import io.github.offbyjuan.jglot.util.Trie;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A SQL dialect: its tokenizer, parser and generator tables plus dialect-level settings. Equivalent
 * to sqlglot's {@code Dialect}.
 *
 * <p>Dialect instances are immutable and thread-safe. The built-in dialects are singletons obtained
 * from {@link Dialects}; all the lookup tables they hold are built once, at class-load time. Each
 * call to {@link #parse} or {@link #generate} creates its own short-lived parser or generator.
 */
public class Dialect {
    /** How unquoted (and possibly quoted) identifiers are resolved by the engine. */
    public enum NormalizationStrategy {
        LOWERCASE,
        UPPERCASE,
        CASE_SENSITIVE,
        CASE_INSENSITIVE,
        CASE_INSENSITIVE_UPPERCASE
    }

    private final String name;
    private final DialectData data;
    private final TokenizerConfig tokenizerConfig;
    private final Tokenizer tokenizer;
    private final ParserData parserData;
    private final GeneratorData generatorData;
    private final Trie timeTrie;
    private final Trie formatTrie;
    private final Trie inverseTimeTrie;
    private final Trie inverseFormatTrie;
    private final Trie showTrie;
    private final Trie setTrie;
    private final NormalizationStrategy normalizationStrategy;
    private final int[] version;

    /**
     * Creates a dialect whose resolved data tables are those generated for sqlglot dialect {@code key}
     * (the empty string is sqlglot's base dialect).
     */
    protected Dialect(String name, String dataKey) {
        this.name = name;
        this.data = DialectData.forDialect(dataKey);
        this.tokenizerConfig = TokenizerData.forDialect(dataKey);
        this.tokenizer = new Tokenizer(tokenizerConfig);
        this.parserData = ParserData.forDialect(dataKey);
        this.generatorData = GeneratorData.forDialect(dataKey);
        this.timeTrie = Trie.ofStrings(data.TIME_MAPPING.keySet());
        this.formatTrie = data.FORMAT_MAPPING.isEmpty() ? timeTrie : Trie.ofStrings(data.FORMAT_MAPPING.keySet());
        this.inverseTimeTrie = Trie.ofStrings(data.INVERSE_TIME_MAPPING.keySet());
        this.inverseFormatTrie = Trie.ofStrings(data.INVERSE_FORMAT_MAPPING.keySet());
        this.showTrie = wordTrie(parserData.SHOW_PARSERS);
        this.setTrie = wordTrie(parserData.SET_PARSERS);
        this.normalizationStrategy = NormalizationStrategy.valueOf(data.NORMALIZATION_STRATEGY.toUpperCase(Locale.ROOT));
        this.version = new int[] {Integer.MAX_VALUE, 0, 0};
    }

    /** sqlglot's base dialect. */
    protected Dialect() {
        this("", "");
    }

    private static Trie wordTrie(java.util.Set<String> keys) {
        List<List<String>> words = new ArrayList<>();
        for (String k : keys) {
            words.add(List.of(k.split(" ")));
        }
        return Trie.ofWords(words);
    }

    /** The dialect's registry name, e.g. {@code "postgres"} ({@code ""} for the base dialect). */
    public String name() {
        return name;
    }

    public DialectData data() {
        return data;
    }

    public ParserData parserData() {
        return parserData;
    }

    public GeneratorData generatorData() {
        return generatorData;
    }

    public TokenizerConfig tokenizerConfig() {
        return tokenizerConfig;
    }

    public Tokenizer tokenizer() {
        return tokenizer;
    }

    public Trie timeTrie() {
        return timeTrie;
    }

    public Trie formatTrie() {
        return formatTrie;
    }

    public Trie inverseTimeTrie() {
        return inverseTimeTrie;
    }

    public Trie inverseFormatTrie() {
        return inverseFormatTrie;
    }

    public Trie showTrie() {
        return showTrie;
    }

    public Trie setTrie() {
        return setTrie;
    }

    public NormalizationStrategy normalizationStrategy() {
        return normalizationStrategy;
    }

    /** The configured engine version as {major, minor, patch}; unbounded by default. */
    public int[] version() {
        return version.clone();
    }

    // ------------------------------------------------------------------------------------------
    // Component factories (overridden by dialects with their own parser/generator classes)

    public Parser newParser(ParserOptions options) {
        return new Parser(this, options);
    }

    public Generator newGenerator(GeneratorOptions options) {
        return new Generator(this, options);
    }

    // ------------------------------------------------------------------------------------------
    // Parsing and generation

    public List<Token> tokenize(String sql) {
        return tokenizer.tokenize(sql);
    }

    /** Parses {@code sql} into one tree per statement. */
    public List<Expression> parse(String sql, ParserOptions options) {
        return newParser(options).parse(tokenize(sql), sql);
    }

    public List<Expression> parse(String sql) {
        return parse(sql, ParserOptions.DEFAULT);
    }

    /** Parses {@code sql} into a node of a specific type, e.g. {@code ExprType.CONDITION}. */
    public List<Expression> parseInto(ExprType type, String sql, ParserOptions options) {
        return newParser(options).parseInto(type, tokenize(sql), sql);
    }

    /** Generates SQL for {@code expression} in this dialect. The input tree is not modified. */
    public String generate(Expression expression, GeneratorOptions options) {
        return newGenerator(options).generate(expression, true);
    }

    public String generate(Expression expression) {
        return generate(expression, GeneratorOptions.DEFAULT);
    }

    // ------------------------------------------------------------------------------------------
    // Dialect behaviour (sqlglot Dialect methods)

    /** Converts a time format in this dialect to Python strftime format. */
    public Expression formatTime(Expression expression) {
        if (expression != null && expression.isString()) {
            return Exp.string(TimeFormat.formatTime((String) expression.thisValue(), data.TIME_MAPPING, timeTrie));
        }
        return expression;
    }

    /** {@code format_time} for a quoted string, e.g. {@code "'yyyy-MM-dd'"}. */
    public Expression formatTime(String quoted) {
        return Exp.string(TimeFormat.formatTime(quoted.substring(1, quoted.length() - 1), data.TIME_MAPPING, timeTrie));
    }

    /** Normalizes an identifier the way this dialect resolves it (e.g. lower-cases unquoted names). */
    public Expression normalizeIdentifier(Expression expression) {
        if (expression != null
                && expression.type() == ExprType.IDENTIFIER
                && normalizationStrategy != NormalizationStrategy.CASE_SENSITIVE
                && (!expression.flag(Args.QUOTED)
                        || normalizationStrategy == NormalizationStrategy.CASE_INSENSITIVE
                        || normalizationStrategy == NormalizationStrategy.CASE_INSENSITIVE_UPPERCASE)) {
            String text = (String) expression.thisValue();
            boolean upper = normalizationStrategy == NormalizationStrategy.UPPERCASE
                    || normalizationStrategy == NormalizationStrategy.CASE_INSENSITIVE_UPPERCASE;
            String normalized;
            if (data.ASCII_ONLY_NORMALIZATION) {
                normalized = upper ? asciiUpper(text) : asciiLower(text);
            } else {
                normalized = upper ? text.toUpperCase(Locale.ROOT) : text.toLowerCase(Locale.ROOT);
            }
            expression.set(Args.THIS, normalized);
        }
        return expression;
    }

    private static String asciiUpper(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        }
        return sb.toString();
    }

    private static String asciiLower(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return sb.toString();
    }

    /** Whether {@code text} contains characters that this dialect treats case-sensitively. */
    public boolean caseSensitive(String text) {
        if (normalizationStrategy == NormalizationStrategy.CASE_INSENSITIVE) {
            return false;
        }
        boolean upperStrategy = normalizationStrategy == NormalizationStrategy.UPPERCASE;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (upperStrategy ? Character.isLowerCase(c) : Character.isUpperCase(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an identifier can be quoted. {@code identify} is {@code true}, {@code false},
     * {@code "safe"} or {@code "unsafe"}.
     */
    public boolean canQuote(Expression identifier, Object identify) {
        if (identifier.flag(Args.QUOTED)) {
            return true;
        }
        if (!Expression.truthy(identify)) {
            return false;
        }
        if (identifier.parent() != null && identifier.parent().is(ExprType.FUNC)) {
            return false;
        }
        if (Boolean.TRUE.equals(identify)) {
            return true;
        }
        String text = (String) identifier.thisValue();
        boolean isSafe = !caseSensitive(text) && Exp.isSafeIdentifier(text);
        if ("safe".equals(identify)) {
            return isSafe;
        }
        if ("unsafe".equals(identify)) {
            return !isSafe;
        }
        throw new IllegalArgumentException("Unexpected argument for identify: '" + identify + "'");
    }

    /** Adds quotes to an identifier if needed ({@code identify=false} only quotes unsafe ones). */
    public Expression quoteIdentifier(Expression expression, boolean identify) {
        if (expression != null && expression.type() == ExprType.IDENTIFIER) {
            expression.set(Args.QUOTED, canQuote(expression, identify ? Boolean.TRUE : "unsafe"));
        }
        return expression;
    }

    /** Parses a JSON path literal into a {@code JSON_PATH} node, or returns the input unchanged. */
    public Expression toJsonPath(Expression path) {
        return JsonPathSupport.toJsonPath(this, path);
    }

    /** Default aliases for the columns of a VALUES clause: {@code _col_0, _col_1, ...}. */
    public List<Expression> generateValuesAliases(Expression values) {
        List<Expression> out = new ArrayList<>();
        int n = values.getExpressions().get(0).getExpressions().size();
        for (int i = 0; i < n; i++) {
            out.add(Exp.identifier("_col_" + i));
        }
        return out;
    }

    @Override
    public String toString() {
        return name.isEmpty() ? "Dialect" : name;
    }
}
