package io.github.offbyjuan.jglot.tokens;

import java.util.ArrayList;
import java.util.List;

/**
 * A lexical token. Tokens are created per tokenization call and are not shared between threads;
 * the comment list is mutable because the parser may move comments between tokens and nodes.
 */
public final class Token {
    private final TokenType type;
    private final String text;
    private final int line;
    private final int col;
    private final int start;
    private final int end;
    private List<String> comments;

    public Token(TokenType type, String text, int line, int col, int start, int end, List<String> comments) {
        this.type = type;
        this.text = text;
        this.line = line;
        this.col = col;
        this.start = start;
        this.end = end;
        this.comments = comments == null ? new ArrayList<>() : comments;
    }

    public Token(TokenType type, String text) {
        this(type, text, 1, 1, 0, 0, null);
    }

    public static Token number(long n) {
        return new Token(TokenType.NUMBER, Long.toString(n));
    }

    public static Token string(String s) {
        return new Token(TokenType.STRING, s);
    }

    public static Token identifier(String s) {
        return new Token(TokenType.IDENTIFIER, s);
    }

    public static Token var(String s) {
        return new Token(TokenType.VAR, s);
    }

    public TokenType type() {
        return type;
    }

    public String text() {
        return text;
    }

    public int line() {
        return line;
    }

    public int col() {
        return col;
    }

    /** Start offset (inclusive) in the SQL string. */
    public int start() {
        return start;
    }

    /** End offset (inclusive) in the SQL string. */
    public int end() {
        return end;
    }

    public List<String> comments() {
        return comments;
    }

    public void setComments(List<String> comments) {
        this.comments = comments == null ? new ArrayList<>() : comments;
    }

    @Override
    public String toString() {
        return "<Token token_type: TokenType." + type + ", text: " + text + ", line: " + line + ", col: " + col
                + ", start: " + start + ", end: " + end + ", comments: " + comments + ">";
    }
}
