package io.github.offbyjuan.jglot.errors;

import java.util.ArrayList;
import java.util.List;

/** Raised when parsing fails. Carries structured details of each underlying error. */
public class ParseError extends JGlotError {
    /** Structured description of one parse error. */
    public record Detail(
            String description,
            Integer line,
            Integer col,
            String startContext,
            String highlight,
            String endContext,
            String intoExpression) {}

    private final List<Detail> errors;

    public ParseError(String message) {
        this(message, List.of());
    }

    public ParseError(String message, List<Detail> errors) {
        super(message);
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ParseError of(String message, String description, Integer line, Integer col, String startContext,
            String highlight, String endContext, String intoExpression) {
        return new ParseError(message,
                List.of(new Detail(description, line, col, startContext, highlight, endContext, intoExpression)));
    }

    public List<Detail> errors() {
        return errors;
    }

    public static List<Detail> merge(List<ParseError> errors) {
        List<Detail> out = new ArrayList<>();
        for (ParseError e : errors) {
            out.addAll(e.errors);
        }
        return out;
    }

    public static String concatMessages(List<? extends Throwable> errors, int maximum) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(errors.size(), maximum);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append(errors.get(i).getMessage());
        }
        int remaining = errors.size() - maximum;
        if (remaining > 0) {
            sb.append("\n\n... and ").append(remaining).append(" more");
        }
        return sb.toString();
    }
}
