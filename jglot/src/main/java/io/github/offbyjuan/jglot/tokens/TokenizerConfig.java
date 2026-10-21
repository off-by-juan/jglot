package io.github.offbyjuan.jglot.tokens;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, fully-resolved tokenizer settings for one dialect. Mirrors the class attributes of
 * sqlglot's {@code Tokenizer} after {@code __init_subclass__} has derived the lookup tables.
 * Instances are built once per dialect and shared by all threads.
 */
public final class TokenizerConfig {
    /** Format-string delimiter: closing text and the token type it produces. */
    public record FormatString(String end, TokenType type) {}

    final TokenType[] asciiSingleTokens = new TokenType[128];
    final Map<Character, TokenType> singleTokens;
    final Map<String, TokenType> keywords;
    final Map<String, String> quotes;
    final Map<String, FormatString> formatStrings;
    final Map<Character, String> identifiers;
    final Map<String, String> comments;
    final Set<Character> stringEscapes;
    final java.util.List<String> stringEscapesOrdered;
    final Set<Character> byteStringEscapes;
    final Set<Character> identifierEscapes;
    final Set<TokenType> commands;
    final Set<TokenType> commandPrefixTokens;
    final boolean nestedComments;
    final boolean dashCommentRequiresBoundary;
    final boolean commentsTerminateAtNewlineOnly;
    final String hintStart;
    final Set<TokenType> tokensPrecedingHint;
    final boolean hasBitStrings;
    final boolean hasHexStrings;
    final Map<String, String> numericLiterals;
    final Set<Character> varSingleTokens;
    final boolean stringEscapesAllowedInRawStrings;
    final boolean heredocTagIsIdentifier;
    final TokenType heredocStringAlternative;
    final KeywordTrie keywordTrie;
    final boolean numbersCanBeUnderscoreSeparated;
    final boolean numbersCanHaveDecimals;
    final boolean identifiersCanStartWithDigit;
    final Map<String, String> unescapedSequences;
    final Map<Character, int[]> numericEscapes;
    final boolean dropUnknownEscapes;
    final boolean numericEscapesAreBytes;
    final boolean byteStringsAreBytes;
    final String loneSurrogateReplacement;
    final boolean[] escapeIsAscii = new boolean[128];

    private TokenizerConfig(Builder b) {
        this.singleTokens = Map.copyOf(b.singleTokens);
        for (Map.Entry<Character, TokenType> e : singleTokens.entrySet()) {
            char c = e.getKey();
            if (c < 128) {
                asciiSingleTokens[c] = e.getValue();
            }
        }
        this.keywords = Map.copyOf(b.keywords);
        this.quotes = Collections.unmodifiableMap(new LinkedHashMap<>(b.quotes));
        this.formatStrings = Collections.unmodifiableMap(new LinkedHashMap<>(b.formatStrings));
        this.identifiers = Collections.unmodifiableMap(new LinkedHashMap<>(b.identifiers));
        this.comments = Collections.unmodifiableMap(new LinkedHashMap<>(b.comments));
        this.stringEscapes = Set.copyOf(b.stringEscapes);
        this.stringEscapesOrdered = java.util.List.copyOf(b.stringEscapesOrdered);
        this.byteStringEscapes = Set.copyOf(b.byteStringEscapes);
        this.identifierEscapes = Set.copyOf(b.identifierEscapes);
        this.commands = Collections.unmodifiableSet(enumSet(b.commands));
        this.commandPrefixTokens = Collections.unmodifiableSet(enumSet(b.commandPrefixTokens));
        this.nestedComments = b.nestedComments;
        this.dashCommentRequiresBoundary = b.dashCommentRequiresBoundary;
        this.commentsTerminateAtNewlineOnly = b.commentsTerminateAtNewlineOnly;
        this.hintStart = b.hintStart;
        this.tokensPrecedingHint = Collections.unmodifiableSet(enumSet(b.tokensPrecedingHint));
        this.hasBitStrings = b.hasBitStrings;
        this.hasHexStrings = b.hasHexStrings;
        this.numericLiterals = Map.copyOf(b.numericLiterals);
        this.varSingleTokens = Set.copyOf(b.varSingleTokens);
        this.stringEscapesAllowedInRawStrings = b.stringEscapesAllowedInRawStrings;
        this.heredocTagIsIdentifier = b.heredocTagIsIdentifier;
        this.heredocStringAlternative = b.heredocStringAlternative;
        this.keywordTrie = KeywordTrie.of(b.keywordTrieKeys);
        this.numbersCanBeUnderscoreSeparated = b.numbersCanBeUnderscoreSeparated;
        this.numbersCanHaveDecimals = b.numbersCanHaveDecimals;
        this.identifiersCanStartWithDigit = b.identifiersCanStartWithDigit;
        this.unescapedSequences = Map.copyOf(b.unescapedSequences);
        this.numericEscapes = Map.copyOf(b.numericEscapes);
        this.dropUnknownEscapes = b.dropUnknownEscapes;
        this.numericEscapesAreBytes = b.numericEscapesAreBytes;
        this.byteStringsAreBytes = b.byteStringsAreBytes;
        this.loneSurrogateReplacement = b.loneSurrogateReplacement;
    }

    private static Set<TokenType> enumSet(Set<TokenType> s) {
        return s.isEmpty() ? EnumSet.noneOf(TokenType.class) : EnumSet.copyOf(s);
    }

    public Map<String, TokenType> keywords() {
        return keywords;
    }

    public Map<Character, TokenType> singleTokens() {
        return singleTokens;
    }

    public Map<String, String> quotes() {
        return quotes;
    }

    public Map<String, FormatString> formatStrings() {
        return formatStrings;
    }

    public Map<Character, String> identifiers() {
        return identifiers;
    }

    public Set<Character> stringEscapes() {
        return stringEscapes;
    }

    /** sqlglot's {@code STRING_ESCAPES} list, in declaration order. */
    public java.util.List<String> stringEscapesOrdered() {
        return stringEscapesOrdered;
    }

    public Set<Character> byteStringEscapes() {
        return byteStringEscapes;
    }

    public Set<Character> identifierEscapes() {
        return identifierEscapes;
    }

    public Set<TokenType> commands() {
        return commands;
    }

    public Map<Character, int[]> numericEscapes() {
        return numericEscapes;
    }

    public boolean dropUnknownEscapes() {
        return dropUnknownEscapes;
    }

    public Map<String, String> unescapedSequences() {
        return unescapedSequences;
    }

    public String hintStart() {
        return hintStart;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder; generated dialect catalogs populate it with fully-resolved values. */
    public static final class Builder {
        public final Map<Character, TokenType> singleTokens = new HashMap<>();
        public final Map<String, TokenType> keywords = new HashMap<>();
        public final Map<String, String> quotes = new LinkedHashMap<>();
        public final Map<String, FormatString> formatStrings = new LinkedHashMap<>();
        public final Map<Character, String> identifiers = new LinkedHashMap<>();
        public final Map<String, String> comments = new LinkedHashMap<>();
        public final java.util.HashSet<Character> stringEscapes = new java.util.HashSet<>();
        public final java.util.HashSet<Character> byteStringEscapes = new java.util.HashSet<>();
        public final java.util.ArrayList<String> stringEscapesOrdered = new java.util.ArrayList<>();
        public final java.util.HashSet<Character> identifierEscapes = new java.util.HashSet<>();
        public final java.util.HashSet<TokenType> commands = new java.util.HashSet<>();
        public final java.util.HashSet<TokenType> commandPrefixTokens = new java.util.HashSet<>();
        public boolean nestedComments = true;
        public boolean dashCommentRequiresBoundary;
        public boolean commentsTerminateAtNewlineOnly;
        public String hintStart = "/*+";
        public final java.util.HashSet<TokenType> tokensPrecedingHint = new java.util.HashSet<>();
        public boolean hasBitStrings;
        public boolean hasHexStrings;
        public final Map<String, String> numericLiterals = new HashMap<>();
        public final java.util.HashSet<Character> varSingleTokens = new java.util.HashSet<>();
        public boolean stringEscapesAllowedInRawStrings = true;
        public boolean heredocTagIsIdentifier;
        public TokenType heredocStringAlternative = TokenType.VAR;
        public final java.util.ArrayList<String> keywordTrieKeys = new java.util.ArrayList<>();
        public boolean numbersCanBeUnderscoreSeparated;
        public boolean numbersCanHaveDecimals = true;
        public boolean identifiersCanStartWithDigit;
        public final Map<String, String> unescapedSequences = new HashMap<>();
        public final Map<Character, int[]> numericEscapes = new HashMap<>();
        public boolean dropUnknownEscapes;
        public boolean numericEscapesAreBytes;
        public boolean byteStringsAreBytes;
        public String loneSurrogateReplacement = "";

        public Builder single(char c, TokenType t) {
            singleTokens.put(c, t);
            return this;
        }

        public Builder kw(String k, TokenType t) {
            keywords.put(k, t);
            return this;
        }

        public Builder format(String start, String end, TokenType t) {
            formatStrings.put(start, new FormatString(end, t));
            return this;
        }

        public TokenizerConfig build() {
            return new TokenizerConfig(this);
        }
    }

    /** A character trie over upper-cased multi-character keywords, comment and quote starts. */
    static final class KeywordTrie {
        private char[] keys = new char[0];
        private KeywordTrie[] kids = new KeywordTrie[0];
        boolean terminal;

        static KeywordTrie of(Iterable<String> words) {
            KeywordTrie root = new KeywordTrie();
            for (String w : words) {
                KeywordTrie cur = root;
                for (int i = 0; i < w.length(); i++) {
                    cur = cur.childOrCreate(w.charAt(i));
                }
                cur.terminal = true;
            }
            return root;
        }

        private KeywordTrie childOrCreate(char c) {
            KeywordTrie k = child(c);
            if (k != null) {
                return k;
            }
            int n = keys.length;
            keys = java.util.Arrays.copyOf(keys, n + 1);
            kids = java.util.Arrays.copyOf(kids, n + 1);
            keys[n] = c;
            kids[n] = new KeywordTrie();
            return kids[n];
        }

        KeywordTrie child(char c) {
            char[] k = keys;
            for (int i = 0; i < k.length; i++) {
                if (k[i] == c) {
                    return kids[i];
                }
            }
            return null;
        }
    }
}
