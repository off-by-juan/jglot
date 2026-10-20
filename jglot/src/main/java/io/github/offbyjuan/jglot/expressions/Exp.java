package io.github.offbyjuan.jglot.expressions;

import static io.github.offbyjuan.jglot.expressions.ExprType.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Static builders mirroring sqlglot's module-level expression helpers ({@code exp.column}, ...). */
public final class Exp {
    private Exp() {}

    public static final Pattern SAFE_IDENTIFIER_RE =
            Pattern.compile("^[_\\p{L}][\\w]*$", Pattern.UNICODE_CHARACTER_CLASS);

    public static boolean isSafeIdentifier(String s) {
        if (s.isEmpty()) {
            return false;
        }
        char c = s.charAt(0);
        if (c < 128) {
            if (!(c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                return false;
            }
            boolean ascii = true;
            for (int i = 1; i < s.length(); i++) {
                char d = s.charAt(i);
                if (d >= 128) {
                    ascii = false;
                    break;
                }
                if (!(d == '_' || (d >= 'a' && d <= 'z') || (d >= 'A' && d <= 'Z') || (d >= '0' && d <= '9'))) {
                    return false;
                }
            }
            if (ascii) {
                return true;
            }
        }
        // Python's re: ^[_a-zA-Z][\w]*$ (first char must be ASCII letter or underscore).
        if (!(c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
            return false;
        }
        return SAFE_IDENTIFIER_RE.matcher(s).matches();
    }

    // ------------------------------------------------------------------------------------------
    // Leaves

    public static Expression string(String value) {
        Expression e = Expression.of(LITERAL);
        e.set(Args.THIS, value);
        e.set(Args.IS_STRING, Boolean.TRUE);
        return e;
    }

    /** sqlglot's {@code Literal.number}: negative numbers become {@code Neg(Literal)}. */
    public static Expression number(Object number) {
        String text = number.toString();
        Expression lit = Expression.of(LITERAL);
        lit.set(Args.THIS, text);
        lit.set(Args.IS_STRING, Boolean.FALSE);
        try {
            Object py = lit.toPy();
            boolean negative = (py instanceof BigInteger bi && bi.signum() < 0)
                    || (py instanceof BigDecimal bd && bd.signum() < 0);
            if (negative) {
                String abs = py instanceof BigInteger bi ? bi.abs().toString() : ((BigDecimal) py).abs().toString();
                lit.set(Args.THIS, abs);
                return Expression.of(NEG, Args.THIS, lit);
            }
        } catch (RuntimeException ignored) {
            // non-numeric text stays as-is, like sqlglot
        }
        return lit;
    }

    /** A numeric literal whose text is kept verbatim (sqlglot {@code Literal(this=..., is_string=False)}). */
    public static Expression numberLiteral(String text) {
        Expression lit = Expression.of(LITERAL);
        lit.set(Args.THIS, text);
        lit.set(Args.IS_STRING, Boolean.FALSE);
        return lit;
    }

    public static Expression var(String name) {
        Expression e = Expression.of(VAR);
        e.set(Args.THIS, name);
        return e;
    }

    public static Expression identifier(String name) {
        return identifier(name, null);
    }

    /** sqlglot's {@code to_identifier}: quoted defaults to "not a safe identifier". */
    public static Expression identifier(String name, Boolean quoted) {
        if (name == null) {
            return null;
        }
        Expression e = Expression.of(IDENTIFIER);
        e.set(Args.THIS, name);
        e.set(Args.QUOTED, quoted == null ? !isSafeIdentifier(name) : quoted);
        return e;
    }

    /** sqlglot's {@code to_identifier} for an existing node: copies Identifiers. */
    public static Expression toIdentifier(Object name, Boolean quoted, boolean copy) {
        if (name == null) {
            return null;
        }
        if (name instanceof Expression e) {
            if (e.type() == IDENTIFIER) {
                return copy ? e.copy() : e;
            }
            throw new IllegalArgumentException("Name needs to be a string or an Identifier, got: " + e.type().className());
        }
        return identifier(name.toString(), quoted);
    }

    public static Expression toIdentifier(Object name) {
        return toIdentifier(name, null, true);
    }

    public static Expression bool(boolean value) {
        return Expression.of(BOOLEAN, Args.THIS, value);
    }

    public static Expression nul() {
        return Expression.of(NULL);
    }

    public static Expression star() {
        return Expression.of(STAR);
    }

    // ------------------------------------------------------------------------------------------
    // Compound builders

    public static Expression column(Object col, Object table) {
        return column(col, table, null, null, null);
    }

    public static Expression column(Object col) {
        return column(col, null, null, null, null);
    }

    public static Expression column(Object col, Object table, Object db, Object catalog, Boolean quoted) {
        Object c = col instanceof Expression e && e.type() == STAR ? e : toIdentifier(col, quoted, true);
        return Expression.of(COLUMN,
                Args.THIS, c,
                Args.TABLE, toIdentifier(table, quoted, true),
                Args.DB, toIdentifier(db, quoted, true),
                Args.CATALOG, toIdentifier(catalog, quoted, true));
    }

    public static Expression table(Object name, Object db, Object catalog, Boolean quoted, Object alias) {
        Expression t = Expression.of(TABLE,
                Args.THIS, toIdentifier(name, quoted, true),
                Args.DB, toIdentifier(db, quoted, true),
                Args.CATALOG, toIdentifier(catalog, quoted, true));
        if (alias != null) {
            t.set(Args.ALIAS, Expression.of(TABLE_ALIAS, Args.THIS, toIdentifier(alias)));
        }
        return t;
    }

    /** {@code Dot.build}: left-folds parts into nested Dot nodes. */
    public static Expression dot(List<Expression> parts) {
        if (parts.size() < 2) {
            throw new IllegalArgumentException("Dot requires >= 2 expressions.");
        }
        Expression acc = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            acc = Expression.of(DOT, Args.THIS, acc, Args.EXPRESSION, parts.get(i));
        }
        return acc;
    }

    public static Expression paren(Expression e) {
        return Expression.of(PAREN, Args.THIS, e);
    }

    /** sqlglot's {@code _wrap}: parenthesizes {@code e} if it is a {@code kind}. */
    public static Expression wrap(Expression e, ExprType kind) {
        return e != null && e.is(kind) ? paren(e) : e;
    }

    public static Expression not(Expression e) {
        return Expression.of(NOT, Args.THIS, wrap(e, CONNECTOR));
    }

    public static Expression alias(Expression e, Object alias) {
        return alias(e, alias, null);
    }

    /** sqlglot's {@code alias_} (column alias form). */
    public static Expression alias(Expression e, Object alias, Boolean quoted) {
        Expression id = toIdentifier(alias, quoted, true);
        if (e.accepts(Args.ALIAS) && e.type() != WINDOW) {
            e.set(Args.ALIAS, id);
            return e;
        }
        return Expression.of(ALIAS, Args.THIS, e, Args.ALIAS, id);
    }

    /** sqlglot's {@code alias_(..., table=True|columns)}. */
    public static Expression tableAlias(Expression e, Object alias, List<?> columns) {
        Expression ta = Expression.of(TABLE_ALIAS, Args.THIS, toIdentifier(alias));
        e.set(Args.ALIAS, ta);
        if (columns != null) {
            for (Object c : columns) {
                ta.append(Args.COLUMNS, toIdentifier(c));
            }
        }
        return e;
    }

    /** Combines conditions with {@code kind} (AND/OR/XOR), wrapping connector operands in parens. */
    public static Expression combine(ExprType kind, List<Expression> conditions, boolean wrap) {
        Expression acc = null;
        for (Expression c : conditions) {
            if (c == null) {
                continue;
            }
            Expression operand = wrap ? wrap(c, CONNECTOR) : c;
            acc = acc == null ? operand : Expression.of(kind, Args.THIS, acc, Args.EXPRESSION, operand);
        }
        return acc;
    }

    public static Expression and(Expression... conditions) {
        return combine(AND, List.of(conditions), true);
    }

    public static Expression or(Expression... conditions) {
        return combine(OR, List.of(conditions), true);
    }

    public static Expression and(List<Expression> conditions) {
        return combine(AND, conditions, true);
    }

    public static Expression array(List<Expression> items) {
        return Expression.of(ARRAY, Args.EXPRESSIONS, new ArrayList<>(items));
    }

    public static Expression tuple(List<Expression> items) {
        return Expression.of(TUPLE, Args.EXPRESSIONS, new ArrayList<>(items));
    }

    /** Builds a function node of a known type from positional args (sqlglot {@code from_arg_list}). */
    public static Expression fromArgList(ExprType type, List<?> args) {
        Expression e = Expression.of(type);
        Arg[] keys = type.args;
        if (type.isVarLenArgs()) {
            Arg varKey = type.varLenArgKey();
            int varIdx = type.slot(varKey);
            int i = 0;
            for (; i < Math.min(varIdx, args.size()); i++) {
                Object v = args.get(i);
                if (v != null) {
                    e.set(keys[i], v);
                }
            }
            List<Object> rest = new ArrayList<>();
            for (; i < args.size(); i++) {
                rest.add(args.get(i));
            }
            e.set(varKey, rest);
        } else {
            for (int i = 0; i < Math.min(keys.length, args.size()); i++) {
                Object v = args.get(i);
                if (v != null) {
                    e.set(keys[i], v);
                }
            }
        }
        ExprInit.afterConstruct(e);
        return e;
    }

    /** {@code exp.Anonymous(this=name, expressions=args)}. */
    public static Expression anonymous(String name, List<Expression> args) {
        Expression e = Expression.of(ANONYMOUS);
        e.set(Args.THIS, name);
        e.set(Args.EXPRESSIONS, new ArrayList<>(args));
        return e;
    }

    public static Expression dateTrunc(Expression unit, Expression thisExpr, boolean unabbreviate) {
        return ExprInit.dateTrunc(unit, thisExpr, unabbreviate);
    }

    /** sqlglot's {@code convert} for simple Java values. */
    public static Expression convert(Object value) {
        if (value instanceof Expression e) {
            return e;
        }
        if (value instanceof String s) {
            return string(s);
        }
        if (value instanceof Boolean b) {
            return bool(b);
        }
        if (value == null) {
            return nul();
        }
        if (value instanceof Number n) {
            return number(n);
        }
        if (value instanceof List<?> l) {
            List<Expression> items = new ArrayList<>();
            for (Object o : l) {
                items.add(convert(o));
            }
            return array(items);
        }
        throw new IllegalArgumentException("Cannot convert " + value);
    }
}
