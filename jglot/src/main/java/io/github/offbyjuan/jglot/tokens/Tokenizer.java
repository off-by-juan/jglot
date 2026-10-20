package io.github.offbyjuan.jglot.tokens;

import io.github.offbyjuan.jglot.errors.TokenError;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Converts SQL text into tokens. A {@code Tokenizer} is immutable and thread-safe: every call to
 * {@link #tokenize(String)} uses fresh scanning state. This is a faithful port of sqlglot's
 * {@code TokenizerCore}.
 */
public final class Tokenizer {
    private final TokenizerConfig config;

    public Tokenizer(TokenizerConfig config) {
        this.config = config;
    }

    public TokenizerConfig config() {
        return config;
    }

    public List<Token> tokenize(String sql) {
        Scanner s = new Scanner(config, sql);
        try {
            s.scan(false);
        } catch (TokenError e) {
            throw e;
        } catch (RuntimeException e) {
            int start = Math.max(s.current - 50, 0);
            int end = Math.min(s.current + 50, s.size - 1);
            String context = end > start ? sql.substring(start, end) : "";
            throw new TokenError("Error tokenizing '" + context + "'", e);
        }
        return s.tokens;
    }

    // -------------------------------------------------------------------------------------------
    // Character classification matching Python's str methods closely enough for SQL.

    static boolean isSpace(char c) {
        if (c == ' ' || (c >= '\t' && c <= '\r') || (c >= 0x1c && c <= 0x1f)) {
            return true;
        }
        if (c < 128) {
            return false;
        }
        return c == 0x85 || c == 0xa0 || Character.isSpaceChar(c);
    }

    static boolean isAlnum(char c) {
        if (c < 128) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
        }
        if (Character.isLetterOrDigit(c)) {
            return true;
        }
        int t = Character.getType(c);
        return t == Character.LETTER_NUMBER || t == Character.OTHER_NUMBER;
    }

    static boolean isIdentifierStart(char c) {
        if (c < 128) {
            return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }
        return Character.isUnicodeIdentifierStart(c);
    }

    static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    static char upper(char c) {
        return c >= 'a' && c <= 'z' ? (char) (c - 32) : c;
    }

    private static int digitValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return 16;
    }

    private static boolean isOctal(char c) {
        return c >= '0' && c <= '7';
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 128) {
                return false;
            }
        }
        return true;
    }

    /** Python's {@code s.encode("utf-8").decode("latin-1")}: one char per UTF-8 byte. */
    private static String utf8AsLatin1(String s) {
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    /** End-of-input marker: Python uses "" for the char/peek past the end. */
    private static final char EOF = '￿';

    private static final class Scanner {
        final TokenizerConfig cfg;
        final String sql;
        final int size;
        List<Token> tokens = new ArrayList<>();
        int start;
        int current;
        int line = 1;
        int col;
        List<String> comments = new ArrayList<>();
        char ch = EOF;
        boolean end;
        char peek = EOF;
        int prevTokenLine = -1;

        Scanner(TokenizerConfig cfg, String sql) {
            this.cfg = cfg;
            this.sql = sql;
            this.size = sql.length();
        }

        boolean inSingleTokens(char c) {
            if (c == EOF) {
                return false;
            }
            if (c < 128) {
                return cfg.asciiSingleTokens[c] != null;
            }
            return cfg.singleTokens.containsKey(c);
        }

        TokenType singleToken(char c) {
            if (c < 128) {
                return cfg.asciiSingleTokens[c];
            }
            return cfg.singleTokens.get(c);
        }

        void scan(boolean checkSemicolon) {
            while (size > 0 && !end) {
                int cur = current;
                while (cur < size) {
                    char c = sql.charAt(cur);
                    if (c == ' ' || c == '\t') {
                        cur++;
                    } else {
                        break;
                    }
                }
                int offset = cur > current ? cur - current : 1;
                start = cur;
                advance(offset, false);

                if (!isSpace(ch)) {
                    if (isDigit(ch)) {
                        scanNumber();
                    } else if (cfg.identifiers.containsKey(ch)) {
                        scanIdentifier(cfg.identifiers.get(ch));
                    } else {
                        scanKeywords();
                    }
                }

                if (checkSemicolon && peek == ';') {
                    break;
                }
            }

            if (!tokens.isEmpty() && !comments.isEmpty()) {
                tokens.get(tokens.size() - 1).comments().addAll(comments);
            }
        }

        String chars(int n) {
            if (n == 1) {
                return ch == EOF ? "" : String.valueOf(ch);
            }
            int s = current - 1;
            int e = s + n;
            return e <= size ? sql.substring(s, e) : "";
        }

        boolean charsEquals(int n, String target) {
            if (n == 1) {
                return target.length() == 1 && ch == target.charAt(0);
            }
            int s = current - 1;
            int e = s + n;
            return e <= size && s >= 0 && sql.startsWith(target, s) && target.length() == n;
        }

        void advance(int i, boolean alnum) {
            char c = ch;
            if (c == '\n' || c == '\r') {
                if (!(c == '\r' && peek == '\n')) {
                    col = i;
                    line++;
                }
            } else {
                col += i;
            }

            current += i;
            end = current >= size;
            ch = current - 1 >= 0 && current - 1 < size ? sql.charAt(current - 1) : EOF;
            peek = end ? EOF : sql.charAt(current);

            if (alnum && ch != EOF && isAlnum(ch)) {
                int lcol = col;
                int lcur = current;
                boolean lend = end;
                char lpeek = peek;
                while (lpeek != EOF && isAlnum(lpeek)) {
                    lcol++;
                    lcur++;
                    lend = lcur >= size;
                    lpeek = lend ? EOF : sql.charAt(lcur);
                }
                col = lcol;
                current = lcur;
                end = lend;
                peek = lpeek;
                ch = sql.charAt(lcur - 1);
            }
        }

        void advance() {
            advance(1, false);
        }

        String text() {
            return sql.substring(start, Math.min(current, size));
        }

        void add(TokenType type, String text) {
            prevTokenLine = line;

            if (!comments.isEmpty() && type == TokenType.SEMICOLON && !tokens.isEmpty()) {
                tokens.get(tokens.size() - 1).comments().addAll(comments);
                comments = new ArrayList<>();
            }

            if (text == null) {
                text = sql.substring(start, Math.min(current, size));
            }

            tokens.add(new Token(type, text, line, col, start, current - 1, comments));
            comments = new ArrayList<>();

            if (cfg.commands.contains(type)
                    && peek != ';'
                    && (tokens.size() == 1 || cfg.commandPrefixTokens.contains(tokens.get(tokens.size() - 2).type()))) {
                int s = current;
                int n = tokens.size();
                scan(true);
                tokens = new ArrayList<>(tokens.subList(0, n));
                String t = sql.substring(s, Math.min(current, size)).strip();
                if (!t.isEmpty()) {
                    add(TokenType.STRING, t);
                }
            }
        }

        void add(TokenType type) {
            add(type, null);
        }

        void scanKeywords() {
            int sz = 0;
            String word = null;
            StringBuilder chars = new StringBuilder();
            chars.append(ch);
            char c = ch;
            boolean prevSpace = false;
            boolean skip = false;
            TokenizerConfig.KeywordTrie trie = cfg.keywordTrie;
            boolean singleToken = inSingleTokens(c);

            while (chars.length() > 0) {
                if (!skip) {
                    TokenizerConfig.KeywordTrie sub = trie.child(upper(c));
                    if (sub == null) {
                        break;
                    }
                    trie = sub;
                    if (trie.terminal) {
                        word = chars.toString();
                    }
                }

                int e = current + sz;
                sz++;

                if (e < size) {
                    c = sql.charAt(e);
                    singleToken = singleToken || inSingleTokens(c);
                    boolean space = isSpace(c);

                    if (!space || !prevSpace) {
                        if (space) {
                            c = ' ';
                        }
                        chars.append(c);
                        prevSpace = space;
                        skip = false;
                    } else {
                        skip = true;
                    }
                } else {
                    c = EOF;
                    break;
                }
            }

            if (word != null) {
                if (scanString(word)) {
                    return;
                }
                if (scanComment(word)) {
                    return;
                }
                if (prevSpace || singleToken || c == EOF) {
                    String upperWord = word.toUpperCase(java.util.Locale.ROOT);
                    TokenType kt = cfg.keywords.get(upperWord);
                    if (kt != null) {
                        advance(sz - 1, false);
                        add(kt, upperWord);
                        return;
                    }
                }
            }

            if (inSingleTokens(ch)) {
                add(singleToken(ch), String.valueOf(ch));
                return;
            }

            scanVar();
        }

        boolean scanComment(String commentStart) {
            if (!cfg.comments.containsKey(commentStart)) {
                return false;
            }

            if (commentStart.equals("--") && cfg.dashCommentRequiresBoundary) {
                int bi = current + 1;
                if (bi < size) {
                    char bc = sql.charAt(bi);
                    if (!(isSpace(bc) || bc < 32 || bc == 127)) {
                        return false;
                    }
                }
            }

            int commentStartLine = line;
            int commentStartSize = commentStart.length();
            String commentEnd = cfg.comments.get(commentStart);

            if (commentEnd != null) {
                advance(commentStartSize, false);

                int count = 1;
                int commentEndSize = commentEnd.length();
                boolean nested = cfg.nestedComments;

                while (!end) {
                    if (charsEquals(commentEndSize, commentEnd)) {
                        count--;
                        if (count == 0) {
                            break;
                        }
                    }

                    advance(1, true);

                    if (nested && !end && charsEquals(commentEndSize, commentStart)) {
                        advance(commentStartSize, false);
                        count++;
                    }
                }

                String t = text();
                int from = commentStartSize;
                int to = t.length() - commentEndSize + 1;
                comments.add(to >= from ? t.substring(from, to) : "");
                advance(commentEndSize - 1, false);
            } else {
                boolean terminatesAtCr = !cfg.commentsTerminateAtNewlineOnly;
                while (!end && peek != '\n' && (!terminatesAtCr || peek != '\r')) {
                    advance(1, true);
                }
                String t = text();
                comments.add(t.length() >= commentStartSize ? t.substring(commentStartSize) : "");
            }

            if (commentStart.equals(cfg.hintStart)
                    && !tokens.isEmpty()
                    && cfg.tokensPrecedingHint.contains(tokens.get(tokens.size() - 1).type())) {
                add(TokenType.HINT);
            }

            if (commentStartLine == prevTokenLine) {
                tokens.get(tokens.size() - 1).comments().addAll(comments);
                comments = new ArrayList<>();
                prevTokenLine = line;
            }

            return true;
        }

        void scanNumber() {
            if (ch == '0') {
                char p = upper(peek);
                if (p == 'B' && cfg.hasBitStrings) {
                    scanBits();
                    return;
                } else if (p == 'X') {
                    if (cfg.hasHexStrings) {
                        scanHex();
                    } else {
                        add(TokenType.NUMBER);
                    }
                    return;
                }
            }

            boolean decimal = false;
            int scientific = 0;
            boolean underscoreSeparated = false;
            String numberText = "";
            StringBuilder numericLiteral = new StringBuilder();
            TokenType numericType = null;

            while (true) {
                if (peek != EOF && isDigit(peek)) {
                    int e = current + 1;
                    while (e < size && isDigit(sql.charAt(e))) {
                        e++;
                    }
                    advance(e - current, false);
                } else if (peek == '.' && !decimal) {
                    if ((!tokens.isEmpty() && tokens.get(tokens.size() - 1).type() == TokenType.PARAMETER)
                            || !cfg.numbersCanHaveDecimals) {
                        break;
                    }
                    decimal = true;
                    advance();
                } else if ((peek == '-' || peek == '+') && scientific == 1) {
                    if (current + 1 < size && isDigit(sql.charAt(current + 1))) {
                        scientific++;
                        advance();
                    } else {
                        break;
                    }
                } else if (upper(peek) == 'E' && scientific == 0) {
                    scientific++;
                    advance();
                } else if (peek == '_' && cfg.numbersCanBeUnderscoreSeparated) {
                    underscoreSeparated = true;
                    advance();
                } else if (peek != EOF && isIdentifierStart(peek)) {
                    numberText = text();

                    while (peek != EOF && !isSpace(peek) && !inSingleTokens(peek)) {
                        numericLiteral.append(peek);
                        advance();
                    }

                    String lit = numericLiteral.toString();
                    String kw = cfg.numericLiterals.get(lit.toUpperCase(java.util.Locale.ROOT));
                    numericType = kw == null ? null : cfg.keywords.get(kw);

                    if (numericType != null) {
                        break;
                    } else if (cfg.identifiersCanStartWithDigit) {
                        add(TokenType.VAR);
                        return;
                    }

                    advance(-lit.length(), false);
                    numericLiteral.setLength(0);
                    break;
                } else {
                    break;
                }
            }

            if (numberText.isEmpty()) {
                numberText = text();
            }

            if (underscoreSeparated) {
                numberText = numberText.replace("_", "");
            }

            add(TokenType.NUMBER, numberText);

            if (numericType != null) {
                add(TokenType.DCOLON, "::");
                add(numericType, numericLiteral.toString());
            }
        }

        void scanBits() {
            advance();
            String value = extractValue();
            if (isValidInt(value, 2)) {
                add(TokenType.BIT_STRING, value.substring(2));
            } else {
                add(TokenType.IDENTIFIER);
            }
        }

        void scanHex() {
            advance();
            String value = extractValue();
            if (isValidInt(value, 16)) {
                add(TokenType.HEX_STRING, value.substring(2));
            } else {
                add(TokenType.IDENTIFIER);
            }
        }

        /** Python's int(value, base) with optional 0b/0x prefix and underscores. */
        static boolean isValidInt(String value, int base) {
            String v = value.strip();
            if (v.startsWith("+") || v.startsWith("-")) {
                v = v.substring(1);
            }
            String lower = v.toLowerCase(java.util.Locale.ROOT);
            if (base == 2 && lower.startsWith("0b")) {
                v = v.substring(2);
            } else if (base == 16 && lower.startsWith("0x")) {
                v = v.substring(2);
            }
            if (v.isEmpty()) {
                return false;
            }
            boolean prevUnderscore = true;
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '_') {
                    if (prevUnderscore) {
                        return false;
                    }
                    prevUnderscore = true;
                    continue;
                }
                if (Character.digit(c, base) < 0) {
                    return false;
                }
                prevUnderscore = false;
            }
            return !prevUnderscore;
        }

        String extractValue() {
            while (true) {
                char c = peek;
                if (c != EOF && !isSpace(c) && !inSingleTokens(c)) {
                    advance(1, true);
                } else {
                    break;
                }
            }
            return text();
        }

        boolean scanString(String startText) {
            Integer base = null;
            TokenType tokenType = TokenType.STRING;
            String endText;

            if (cfg.quotes.containsKey(startText)) {
                endText = cfg.quotes.get(startText);
            } else if (cfg.formatStrings.containsKey(startText)) {
                TokenizerConfig.FormatString fs = cfg.formatStrings.get(startText);
                endText = fs.end();
                tokenType = fs.type();

                if (tokenType == TokenType.HEX_STRING) {
                    base = 16;
                } else if (tokenType == TokenType.BIT_STRING) {
                    base = 2;
                } else if (tokenType == TokenType.HEREDOC_STRING) {
                    advance();

                    String tag;
                    if (ch != EOF && String.valueOf(ch).equals(endText)) {
                        tag = "";
                    } else {
                        tag = extractString(endText, null, true, !cfg.heredocTagIsIdentifier, false);
                    }

                    if (!tag.isEmpty()
                            && cfg.heredocTagIsIdentifier
                            && (end || isAllDigits(tag) || hasSpace(tag))) {
                        if (!end) {
                            advance(-1, false);
                        }
                        advance(-tag.length(), false);
                        add(cfg.heredocStringAlternative);
                        return true;
                    }

                    endText = startText + tag + endText;
                }
            } else {
                return false;
            }

            advance(startText.length(), false);
            String text = extractString(
                    endText,
                    tokenType == TokenType.BYTE_STRING ? cfg.byteStringEscapes : cfg.stringEscapes,
                    tokenType == TokenType.RAW_STRING
                            || tokenType == TokenType.NATIONAL_RAW_STRING
                            || tokenType == TokenType.HEREDOC_STRING,
                    true,
                    tokenType == TokenType.BYTE_STRING && cfg.byteStringsAreBytes);

            if (base != null && !text.isEmpty() && !isValidInt(text, base)) {
                throw new TokenError("Numeric string contains invalid characters from " + line + ":" + start);
            }

            add(tokenType, text);
            return true;
        }

        static boolean isAllDigits(String s) {
            if (s.isEmpty()) {
                return false;
            }
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        static boolean hasSpace(String s) {
            for (int i = 0; i < s.length(); i++) {
                if (isSpace(s.charAt(i))) {
                    return true;
                }
            }
            return false;
        }

        void scanIdentifier(String identifierEnd) {
            advance();
            java.util.HashSet<Character> escapes = new java.util.HashSet<>(cfg.identifierEscapes);
            if (identifierEnd.length() == 1) {
                escapes.add(identifierEnd.charAt(0));
            }
            String text = extractString(identifierEnd, escapes, false, true, false);
            add(TokenType.IDENTIFIER, text);
        }

        void scanVar() {
            while (true) {
                char p = peek;
                if (p == EOF || isSpace(p)) {
                    break;
                }
                if (!cfg.varSingleTokens.contains(p) && inSingleTokens(p)) {
                    break;
                }
                advance(1, true);
            }

            TokenType type;
            if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).type() == TokenType.PARAMETER) {
                type = TokenType.VAR;
            } else {
                String upperText = sql.substring(start, Math.min(current, size)).toUpperCase(java.util.Locale.ROOT);
                type = cfg.keywords.getOrDefault(upperText, TokenType.VAR);
            }
            add(type);
        }

        /** Returns {value, end}; value -1 when fewer than minDigits were read. */
        long[] readNumericEscape(int from, int base, int minDigits, int maxDigits, int maxValue) {
            long value = 0;
            int e = from;
            int limit = Math.min(from + maxDigits, size);
            while (e < limit) {
                int d = digitValue(sql.charAt(e));
                if (d >= base || value * base + d > maxValue) {
                    break;
                }
                value = value * base + d;
                e++;
            }
            return e - from >= minDigits ? new long[] {value, e} : new long[] {-1, e};
        }

        long[] readByteEscape(int pos) {
            char p = pos + 1 < size ? sql.charAt(pos + 1) : EOF;
            boolean octal = p != EOF && isOctal(p);
            int[] spec = cfg.numericEscapes.get(octal ? '0' : p);
            if (spec == null || (spec[0] != 8 && spec[3] > 0xFF)) {
                return new long[] {-1, pos};
            }
            long[] r = readNumericEscape(octal ? pos + 1 : pos + 2, spec[0], spec[1], spec[2], spec[3]);
            long v = r[0];
            return new long[] {v >= 0 ? (v & 0xFF) : v, r[1]};
        }

        String scanNumericEscape() {
            int s = current;
            char p = peek;
            boolean octal = p != EOF && isOctal(p);
            int[] spec = cfg.numericEscapes.get(octal ? '0' : p);

            if (spec != null) {
                int base = spec[0];
                int minDigits = spec[1];
                int maxDigits = spec[2];
                int maxValue = spec[3];
                long[] r = readNumericEscape(octal ? s : s + 1, base, minDigits, maxDigits, maxValue);
                long value = r[0];
                int e = (int) r[1];

                boolean isByte = cfg.numericEscapesAreBytes && (base == 8 || maxValue <= 0xFF);
                if (isByte && value >= 0) {
                    value &= 0xFF;
                }

                if (value >= 0xD800 && value <= 0xDFFF) {
                    long low = -1;
                    int lowEnd = e;
                    if (value <= 0xDBFF && e + 2 <= size && sql.charAt(e) == '\\' && sql.charAt(e + 1) == p) {
                        long[] lr = readNumericEscape(e + 2, base, minDigits, maxDigits, maxValue);
                        low = lr[0];
                        lowEnd = (int) lr[1];
                    }

                    if (low >= 0xDC00 && low <= 0xDFFF) {
                        value = 0x10000 + ((value - 0xD800) << 10) + (low - 0xDC00);
                        e = lowEnd;
                    } else if (!cfg.loneSurrogateReplacement.isEmpty() && e < size) {
                        advance(e - s + 1, false);
                        return cfg.loneSurrogateReplacement;
                    } else {
                        value = -1;
                    }
                }

                if (isByte && value >= 0xC2 && value <= 0xF4) {
                    java.io.ByteArrayOutputStream run = new java.io.ByteArrayOutputStream();
                    run.write((int) value);
                    int runEnd = e;
                    int byteCount = value < 0xE0 ? 2 : value < 0xF0 ? 3 : 4;
                    while (run.size() < byteCount && runEnd < size && sql.charAt(runEnd) == '\\') {
                        long[] br = readByteEscape(runEnd);
                        long b = br[0];
                        if (!(b >= 0x80 && b <= 0xBF)) {
                            break;
                        }
                        run.write((int) b);
                        runEnd = (int) br[1];
                    }

                    String decoded;
                    try {
                        decoded = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(java.nio.ByteBuffer.wrap(run.toByteArray()))
                                .toString();
                    } catch (CharacterCodingException ex) {
                        decoded = "";
                    }

                    if (!decoded.isEmpty() && runEnd < size) {
                        advance(runEnd - s + 1, false);
                        return decoded;
                    }
                }

                if (value >= 0 && e < size) {
                    advance(e - s + 1, false);
                    return new String(Character.toChars((int) value));
                }
            }

            return "";
        }

        String extractString(String delimiter, Set<Character> escapes, boolean rawString, boolean raiseUnmatched,
                boolean bytesLiteral) {
            StringBuilder text = new StringBuilder();
            int delimSize = delimiter.length();
            if (escapes == null) {
                escapes = cfg.stringEscapes;
            }
            java.util.Map<String, String> unescapedSequences = cfg.unescapedSequences;
            boolean hasNumericEscapes = !cfg.numericEscapes.isEmpty();
            boolean backslashIsEscape = escapes.contains('\\');

            if (delimSize == 1) {
                char d = delimiter.charAt(0);
                int pos = current - 1;
                int e = sql.indexOf(d, pos);

                if (e != -1
                        && (e + 1 >= size || sql.charAt(e + 1) != d || !escapes.contains(d))
                        && (!(!unescapedSequences.isEmpty() || backslashIsEscape) || sql.substring(pos, e).indexOf('\\') == -1)) {
                    int newlines = 0;
                    int lastNl = -1;
                    for (int i = pos; i < e; i++) {
                        if (sql.charAt(i) == '\n') {
                            newlines++;
                            lastNl = i;
                        }
                    }
                    if (newlines > 0) {
                        line += newlines;
                        col = e - lastNl;
                    } else {
                        col += e - pos;
                    }

                    current = e + 1;
                    end = current >= size;
                    ch = sql.charAt(e);
                    peek = end ? EOF : sql.charAt(current);
                    String t = sql.substring(pos, e);
                    if (bytesLiteral && !isAscii(t)) {
                        t = utf8AsLatin1(t);
                    }
                    return t;
                }
            }

            while (true) {
                boolean backslashEscape = !rawString && ch == '\\' && backslashIsEscape;

                if (backslashEscape && hasNumericEscapes) {
                    String decoded = scanNumericEscape();
                    if (!decoded.isEmpty()) {
                        text.append(decoded);
                        continue;
                    }
                }

                if (!rawString && !unescapedSequences.isEmpty() && ch != EOF && escapes.contains(ch) && current + 1 < size) {
                    String seq = unescapedSequences.get(String.valueOf(ch) + (peek == EOF ? "" : String.valueOf(peek)));
                    if (seq != null) {
                        if (peek == '\n' || peek == '\r') {
                            advance();
                            advance();
                        } else {
                            advance(2, false);
                        }
                        text.append(seq);
                        continue;
                    }
                }

                if (backslashEscape && cfg.dropUnknownEscapes && current + 1 < size) {
                    char p = peek;
                    advance();
                    advance();
                    String ps = String.valueOf(p);
                    text.append(bytesLiteral ? utf8AsLatin1(ps) : ps);
                    continue;
                }

                boolean escapedDelimiter = (peek != EOF && delimSize == 1 && peek == delimiter.charAt(0))
                        || (delimSize > 1 && peek != EOF && peek == delimiter.charAt(0) && cfg.quotes.containsKey(String.valueOf(peek)));

                if ((cfg.stringEscapesAllowedInRawStrings || !rawString)
                        && ch != EOF && escapes.contains(ch)
                        && (escapedDelimiter || (peek != EOF && escapes.contains(peek)))
                        && (!cfg.quotes.containsKey(String.valueOf(ch)) || ch == peek)) {
                    if (escapedDelimiter) {
                        if (!rawString) {
                            text.append(peek);
                        } else {
                            text.append(ch).append(peek);
                        }
                    } else {
                        text.append(ch).append(peek);
                    }

                    if (current + 1 < size) {
                        advance(2, false);
                    } else {
                        throw new TokenError("Missing " + delimiter + " from " + line + ":" + current);
                    }
                } else {
                    if (charsEquals(delimSize, delimiter)) {
                        if (delimSize > 1) {
                            advance(delimSize - 1, false);
                        }
                        break;
                    }

                    if (end) {
                        if (!raiseUnmatched) {
                            return text.append(ch == EOF ? "" : String.valueOf(ch)).toString();
                        }
                        throw new TokenError("Missing " + delimiter + " from " + line + ":" + start);
                    }

                    int cur = current - 1;
                    advance(1, true);
                    String chunk = sql.substring(cur, current - 1);
                    if (bytesLiteral && !isAscii(chunk)) {
                        chunk = utf8AsLatin1(chunk);
                    }
                    text.append(chunk);
                }
            }

            return text.toString();
        }
    }
}
