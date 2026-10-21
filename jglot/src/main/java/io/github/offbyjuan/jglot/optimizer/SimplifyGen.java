package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code simplify.gen} / {@code simplify.Gen}: a simple pseudo sql generator for quickly generating
 * sortable and uniq strings. Sorting and deduping sql is a necessary step for optimization. Calling
 * the actual generator is expensive so we have a bare minimum sql generator here.
 *
 * <p>Deviation: sqlglot emits a function's args in {@code args} dict insertion order; jglot only
 * knows the declaration (slot) order, which is what parsers produce in the vast majority of cases.
 */
final class SimplifyGen {
    private final List<Object> stack = new ArrayList<>();
    private final StringBuilder sqls = new StringBuilder();

    private SimplifyGen() {}

    static String gen(Expression expression) {
        return new SimplifyGen().run(expression, false);
    }

    static String gen(Expression expression, boolean comments) {
        return new SimplifyGen().run(expression, comments);
    }

    /** Python {@code str} ordering (by code point). */
    static int compare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private Object pop() {
        return stack.remove(stack.size() - 1);
    }

    private void push(Object... items) {
        for (Object item : items) {
            stack.add(item);
        }
    }

    String run(Expression expression, boolean comments) {
        stack.clear();
        stack.add(expression);
        sqls.setLength(0);

        while (!stack.isEmpty()) {
            Object node = pop();

            if (node instanceof Expression e) {
                if (comments && e.comments() != null && !e.comments().isEmpty()) {
                    stack.add(" /*" + String.join(",", e.comments()) + "*/");
                }

                if (!dispatch(e)) {
                    if (e.is(ExprType.FUNC)) {
                        function(e);
                    } else {
                        String key = e.key().toUpperCase(java.util.Locale.ROOT);
                        stack.add(args(e, 0) ? key + " " : key);
                    }
                }
            } else if (node instanceof List<?> list) {
                for (int k = list.size() - 1; k >= 0; k--) {
                    Object n = list.get(k);
                    if (n != null) {
                        push(n, ",");
                    }
                }
                if (!list.isEmpty()) {
                    pop();
                }
            } else if (node != null) {
                sqls.append(pyStr(node));
            }
        }

        return sqls.toString();
    }

    private static String pyStr(Object v) {
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (v instanceof Enum<?> en) {
            return en.getDeclaringClass().getSimpleName() + "." + en.name();
        }
        return String.valueOf(v);
    }

    /** Python {@code repr(str)}. */
    private static String pyRepr(String s) {
        char quote = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder sb = new StringBuilder(s.length() + 2).append(quote);
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c == quote || c == '\\') {
                sb.append('\\').appendCodePoint(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20 || c == 0x7f) {
                sb.append(String.format("\\x%02x", c));
            } else {
                sb.appendCodePoint(c);
            }
        }
        return sb.append(quote).toString();
    }

    private boolean dispatch(Expression e) {
        switch (e.type()) {
            case ADD -> binary(e, " + ");
            case ALIAS -> push(e.get(Args.ALIAS), " AS ", e.get(Args.THIS));
            case AND -> binary(e, " AND ");
            case ANONYMOUS -> anonymous(e);
            case BETWEEN -> push(e.get(Args.HIGH), " AND ", e.get(Args.LOW), " BETWEEN ", e.get(Args.THIS));
            case BOOLEAN -> stack.add(Expression.truthy(e.get(Args.THIS)) ? "TRUE" : "FALSE");
            case BRACKET -> {
                args(e, 2);
                push("]", e.getExpressions(), "[", e.get(Args.THIS));
            }
            case COLUMN -> parts(e);
            case DATA_TYPE -> {
                args(e, 1);
                Object t = e.thisValue();
                String name = t instanceof Enum<?> en ? en.name() : t instanceof Expression te ? te.name() : String.valueOf(t);
                stack.add(name + " ");
            }
            case DIV -> binary(e, " / ");
            case DOT -> binary(e, ".");
            case EQ -> binary(e, " = ");
            case FROM -> push(e.get(Args.THIS), "FROM ");
            case GT -> binary(e, " > ");
            case GTE -> binary(e, " >= ");
            case IDENTIFIER -> {
                String text = (String) e.thisValue();
                if (e.flag(Args.QUOTED)) {
                    stack.add("\"" + text.replace("\"", "\"\"") + "\"");
                } else {
                    stack.add(text);
                }
            }
            case I_LIKE -> binary(e, e.has(Args.NEGATE) ? " NOT ILIKE " : " ILIKE ");
            case IN -> {
                stack.add(")");
                args(e, 1);
                push("(", " IN ", e.get(Args.THIS));
            }
            case INT_DIV -> binary(e, " DIV ");
            case IS -> binary(e, e.has(Args.NEGATE) ? " IS NOT " : " IS ");
            case LIKE -> binary(e, e.has(Args.NEGATE) ? " NOT Like " : " Like ");
            case LITERAL -> {
                String text = (String) e.thisValue();
                if (e.isString()) {
                    stack.add("'" + text.replace("'", "''") + "'");
                } else {
                    stack.add(text);
                }
            }
            case LT -> binary(e, " < ");
            case LTE -> binary(e, " <= ");
            case MOD -> binary(e, " % ");
            case MUL -> binary(e, " * ");
            case NEG -> push(e.get(Args.THIS), "-");
            case NEQ -> binary(e, " <> ");
            case NOT -> push(e.get(Args.THIS), "NOT ");
            case NULL -> stack.add("NULL");
            case OR -> binary(e, " OR ");
            case PAREN -> push(")", e.get(Args.THIS), "(");
            case SUB -> binary(e, " - ");
            case SUBQUERY -> {
                args(e, 2);
                Object alias = e.get(Args.ALIAS);
                if (Expression.truthy(alias)) {
                    stack.add(alias);
                }
                push(")", e.get(Args.THIS), "(");
            }
            case TABLE -> {
                args(e, 4);
                Object alias = e.get(Args.ALIAS);
                if (Expression.truthy(alias)) {
                    stack.add(alias);
                }
                parts(e);
            }
            case TABLE_ALIAS -> {
                List<Expression> columns = e.exprs(Args.COLUMNS);
                if (!columns.isEmpty()) {
                    push(")", columns, "(");
                }
                push(e.get(Args.THIS), " AS ");
            }
            case VAR -> stack.add(e.get(Args.THIS));
            default -> {
                return false;
            }
        }
        return true;
    }

    private void parts(Expression e) {
        List<Expression> parts = e.parts();
        for (int k = parts.size() - 1; k >= 0; k--) {
            push(parts.get(k), ".");
        }
        pop();
    }

    private void anonymous(Expression e) {
        Object thisValue = e.thisValue();
        String name;
        if (thisValue instanceof String s) {
            name = s.toUpperCase(java.util.Locale.ROOT);
        } else if (thisValue instanceof Expression id && id.is(ExprType.IDENTIFIER)) {
            name = (String) id.thisValue();
            if (id.flag(Args.QUOTED)) {
                name = "\"" + name.replace("\"", "\"\"") + "\"";
            } else {
                name = name.toUpperCase(java.util.Locale.ROOT);
            }
        } else {
            String cls = thisValue instanceof Expression x ? x.type().className()
                    : thisValue == null ? "NoneType" : thisValue.getClass().getSimpleName();
            throw new IllegalArgumentException(
                    "Anonymous.this expects a str or an Identifier, got '" + cls + "'.");
        }

        push(")", e.getExpressions(), "(", name);
    }

    private void binary(Expression e, String op) {
        push(e.get(Args.EXPRESSION), op, e.get(Args.THIS));
    }

    private void function(Expression e) {
        List<Object> values = new ArrayList<>();
        for (Map.Entry<io.github.offbyjuan.jglot.expressions.Arg, Object> entry : e.argEntries()) {
            values.add(entry.getValue());
        }
        push(")", values, "(", sqlName(e.type()));
    }

    private static String sqlName(ExprType type) {
        String[] names = type.sqlNames();
        if (names.length > 0) {
            return names[0];
        }
        // helper.camel_to_snake_case: re.sub("(?<!^)(?=[A-Z])", "_", name).upper()
        String cls = type.className();
        StringBuilder sb = new StringBuilder(cls.length() + 8);
        for (int k = 0; k < cls.length(); k++) {
            char c = cls.charAt(k);
            if (k > 0 && c >= 'A' && c <= 'Z') {
                sb.append('_');
            }
            sb.append(c);
        }
        return sb.toString().toUpperCase(java.util.Locale.ROOT);
    }

    private boolean args(Expression node, int argIndex) {
        List<Object> kvs = new ArrayList<>();
        ExprType type = node.type();

        for (int k = argIndex; k < type.argCount(); k++) {
            io.github.offbyjuan.jglot.expressions.Arg arg = type.argAt(k);
            Object v = node.get(arg);

            if (v != null) {
                // repr() plain strings so their content can't mimic gen's structural text
                List<Object> kv = new ArrayList<>(2);
                kv.add(":" + arg.name());
                kv.add(v instanceof String s ? pyRepr(s) : v);
                kvs.add(kv);
            }
        }
        if (!kvs.isEmpty()) {
            stack.add(kvs);
            return true;
        }
        return false;
    }
}
