package io.github.offbyjuan.jglot.expressions;

import static io.github.offbyjuan.jglot.expressions.ExprType.*;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.errors.ParseError;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.parser.ParserOptions;
import io.github.offbyjuan.jglot.util.Py;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** sqlglot's {@code alias_} (column alias form); like sqlglot, {@code e} is copied. */
    public static Expression alias(Expression e, Object alias, Boolean quoted) {
        return alias(e, alias, quoted, true);
    }

    /** sqlglot's {@code alias_(expression, alias, quoted=..., copy=...)} (column alias form). */
    public static Expression alias(Expression e, Object alias, Boolean quoted, boolean copy) {
        e = maybeCopy(e, copy);
        Expression id = toIdentifier(alias, quoted, true);
        if (e.accepts(Args.ALIAS) && e.type() != WINDOW) {
            e.set(Args.ALIAS, id);
            return e;
        }
        return Expression.of(ALIAS, Args.THIS, e, Args.ALIAS, id);
    }

    /** sqlglot's {@code alias_(..., table=True|columns)}; like sqlglot, {@code e} is copied. */
    public static Expression tableAlias(Expression e, Object alias, List<?> columns) {
        return tableAlias(e, alias, columns, true);
    }

    /** sqlglot's {@code alias_(..., table=True|columns, copy=...)}. */
    public static Expression tableAlias(Expression e, Object alias, List<?> columns, boolean copy) {
        e = maybeCopy(e, copy);
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
        // Like sqlglot's _combine: the first operand is only wrapped when there is more than one.
        List<Expression> present = new ArrayList<>();
        for (Expression c : conditions) {
            if (c != null) {
                present.add(c);
            }
        }
        if (present.isEmpty()) {
            return null;
        }
        Expression acc = present.get(0);
        if (present.size() > 1 && wrap) {
            acc = wrap(acc, CONNECTOR);
        }
        for (int i = 1; i < present.size(); i++) {
            Expression c = present.get(i);
            acc = Expression.of(kind, Args.THIS, acc, Args.EXPRESSION, wrap ? wrap(c, CONNECTOR) : c);
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

    // ------------------------------------------------------------------------------------------
    // sqlglot builder helpers (expressions/builders.py, core.py).

    static RuntimeException notPorted(String what) {
        return new UnsupportedOperationException("not yet ported from sqlglot: " + what);
    }

    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger("jglot");

    private static Dialect dialectOrBase(Dialect dialect) {
        return dialect == null ? Dialects.base() : dialect;
    }

    /** sqlglot's {@code maybe_copy}. */
    static Expression maybeCopy(Expression instance, boolean copy) {
        return copy && instance != null ? instance.copy() : instance;
    }

    /** Python's {@code str(value)} for the scalar values accepted by the builders. */
    private static String pyStr(Object value) {
        if (value instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (value instanceof Expression e) {
            return Dialects.base().generate(e);
        }
        return String.valueOf(value);
    }

    /**
     * {@code sqlglot.parse_one(sql, read=dialect, into=into, **opts)}: parses one statement (several
     * statements become a {@code BLOCK}); {@code into} may list several types to try in order.
     */
    public static Expression parseOne(String sql, Dialect dialect, ParserOptions options, ExprType... into) {
        Dialect d = dialectOrBase(dialect);
        ParserOptions opts = options == null ? ParserOptions.DEFAULT : options;
        List<Expression> result;
        if (into != null && into.length > 0) {
            result = parseIntoAny(d, into, sql, opts);
        } else {
            result = d.parse(sql, opts);
        }
        if (result == null || result.isEmpty() || result.get(0) == null) {
            throw new ParseError("No expression was parsed from '" + sql + "'");
        }
        return result.size() > 1 ? Expression.of(BLOCK, Args.EXPRESSIONS, result) : result.get(0);
    }

    private static List<Expression> parseIntoAny(Dialect d, ExprType[] into, String sql, ParserOptions opts) {
        if (into.length == 1) {
            return d.parseInto(into[0], sql, opts);
        }
        List<ParseError> errors = new ArrayList<>();
        for (ExprType t : into) {
            try {
                return d.parseInto(t, sql, opts);
            } catch (ParseError e) {
                errors.add(e);
            }
        }
        StringBuilder types = new StringBuilder("(");
        for (int i = 0; i < into.length; i++) {
            if (i > 0) {
                types.append(", ");
            }
            types.append(into[i]);
        }
        types.append(into.length == 1 ? ",)" : ")");
        ParseError err = new ParseError("Failed to parse '" + sql + "' into " + types, ParseError.merge(errors));
        err.initCause(errors.get(errors.size() - 1));
        throw err;
    }

    /**
     * sqlglot's {@code maybe_parse(sql_or_expression, into=..., dialect=..., prefix=..., copy=...)}:
     * expressions pass through (copied if {@code copy}); anything else is stringified, prefixed and
     * parsed. {@code into} may be null.
     */
    public static Expression maybeParse(Object sqlOrExpression, ExprType into, Dialect dialect, String prefix,
            boolean copy) {
        return maybeParseAny(sqlOrExpression, into == null ? new ExprType[0] : new ExprType[] {into}, dialect,
                prefix, copy);
    }

    static Expression maybeParseAny(Object sqlOrExpression, ExprType[] into, Dialect dialect, String prefix,
            boolean copy) {
        if (sqlOrExpression instanceof Expression e) {
            return copy ? e.copy() : e;
        }
        if (sqlOrExpression == null) {
            throw new ParseError("SQL cannot be None");
        }
        String sql = pyStr(sqlOrExpression);
        if (prefix != null && !prefix.isEmpty()) {
            sql = prefix + " " + sql;
        }
        return parseOne(sql, dialect, ParserOptions.DEFAULT, into);
    }

    private static boolean isWrongExpression(Object expression, ExprType into) {
        return expression instanceof Expression e && !e.is(into);
    }

    /** sqlglot's {@code _apply_builder}. */
    static Expression applyBuilder(Object expression, Expression instance, Arg arg, boolean copy, String prefix,
            ExprType into, Dialect dialect, Arg intoArg) {
        if (into != null && isWrongExpression(expression, into)) {
            expression = Expression.of(into, intoArg, expression);
        }
        instance = maybeCopy(instance, copy);
        Expression parsed = maybeParse(expression, into, dialect, prefix, false);
        instance.set(arg, parsed);
        return instance;
    }

    /** sqlglot's {@code _apply_child_list_builder}. */
    static Expression applyChildListBuilder(List<?> expressions, Expression instance, Arg arg, boolean append,
            boolean copy, String prefix, ExprType into, Dialect dialect, Map<Arg, Object> properties) {
        instance = maybeCopy(instance, copy);
        List<Object> parsed = new ArrayList<>();
        Map<Arg, Object> props = properties == null ? new LinkedHashMap<>() : properties;

        for (Object expression : expressions) {
            if (expression != null) {
                if (into != null && isWrongExpression(expression, into)) {
                    List<Object> wrapped = new ArrayList<>();
                    wrapped.add(expression);
                    expression = Expression.of(into, Args.EXPRESSIONS, wrapped);
                }

                Expression e = maybeParse(expression, into, dialect, prefix, false);
                for (Map.Entry<Arg, Object> kv : e.argEntries()) {
                    if (kv.getKey() == Args.EXPRESSIONS) {
                        if (kv.getValue() instanceof List<?> l) {
                            parsed.addAll(l);
                        } else {
                            parsed.add(kv.getValue());
                        }
                    } else {
                        props.put(kv.getKey(), kv.getValue());
                    }
                }
            }
        }

        Expression existing = instance.expr(arg);
        if (append && existing != null) {
            List<Object> merged = new ArrayList<>(existing.getExpressions());
            merged.addAll(parsed);
            parsed = merged;
        }
        if (into == null) {
            throw new IllegalArgumentException("`into` is required to use `_apply_child_list_builder`");
        }
        Expression child = Expression.of(into, Args.EXPRESSIONS, parsed);
        for (Map.Entry<Arg, Object> kv : props.entrySet()) {
            child.set(kv.getKey(), kv.getValue());
        }
        instance.set(arg, child);

        return instance;
    }

    /** sqlglot's {@code _apply_list_builder}. */
    static Expression applyListBuilder(List<?> expressions, Expression instance, Arg arg, boolean append,
            boolean copy, String prefix, ExprType into, Dialect dialect) {
        Expression inst = maybeCopy(instance, copy);

        List<Object> parsed = new ArrayList<>();
        for (Object expression : expressions) {
            if (expression != null) {
                parsed.add(maybeParse(expression, into, dialect, prefix, false));
            }
        }

        Object existingExpressions = inst.get(arg);
        if (append && Expression.truthy(existingExpressions)) {
            List<Object> merged = new ArrayList<>(Py.ensureList(existingExpressions));
            merged.addAll(parsed);
            parsed = merged;
        }

        inst.set(arg, parsed);
        return inst;
    }

    /** sqlglot's {@code _apply_conjunction_builder}. */
    static Expression applyConjunctionBuilder(List<?> expressions, Expression instance, Arg arg, ExprType into,
            boolean append, boolean copy, Dialect dialect) {
        List<Object> filtered = new ArrayList<>();
        for (Object e : expressions) {
            if (e != null && !"".equals(e)) {
                filtered.add(e);
            }
        }
        if (filtered.isEmpty()) {
            return instance;
        }

        Expression inst = maybeCopy(instance, copy);

        Object existing = inst.get(arg);
        if (append && existing != null) {
            filtered.add(0, into != null ? ((Expression) existing).getThis() : existing);
        }

        Expression node = combineConditions(filtered, AND, dialect, copy, true);

        inst.set(arg, into != null ? Expression.of(into, Args.THIS, node) : node);
        return inst;
    }

    /** sqlglot's {@code _combine}: parses each operand as a condition and chains them with {@code operator}. */
    static Expression combineConditions(List<?> expressions, ExprType operator, Dialect dialect, boolean copy,
            boolean wrap) {
        List<Expression> conditions = new ArrayList<>();
        for (Object expression : expressions) {
            if (expression != null) {
                conditions.add(condition(expression, dialect, copy));
            }
        }
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("not enough values to unpack (expected at least 1, got 0)");
        }

        Expression acc = conditions.get(0);
        if (conditions.size() > 1 && wrap) {
            acc = wrap(acc, CONNECTOR);
        }
        for (int i = 1; i < conditions.size(); i++) {
            Expression expression = conditions.get(i);
            acc = Expression.of(operator, Args.THIS, acc, Args.EXPRESSION, wrap ? wrap(expression, CONNECTOR) : expression);
        }
        return acc;
    }

    /** sqlglot's {@code _apply_set_operation}. */
    static Expression applySetOperation(List<?> expressions, ExprType setOperation, boolean distinct,
            Dialect dialect, boolean copy) {
        Expression acc = null;
        for (Object e : expressions) {
            Expression parsed = maybeParse(e, null, dialect, null, copy);
            acc = acc == null
                    ? parsed
                    : Expression.of(setOperation, Args.THIS, acc, Args.EXPRESSION, parsed, Args.DISTINCT, distinct);
        }
        return acc;
    }

    /** sqlglot's {@code Expr._binop(klass, other, reverse)}. */
    static Expression binop(ExprType klass, Expression self, Object other, boolean reverse) {
        Expression thisExpr = self.copy();
        Expression otherExpr = convert(other, true);
        if (!thisExpr.is(klass) && !otherExpr.is(klass)) {
            thisExpr = wrap(thisExpr, BINARY);
            otherExpr = wrap(otherExpr, BINARY);
        }
        if (reverse) {
            return Expression.of(klass, Args.THIS, otherExpr, Args.EXPRESSION, thisExpr);
        }
        return Expression.of(klass, Args.THIS, thisExpr, Args.EXPRESSION, otherExpr);
    }

    /** sqlglot's {@code convert(value, copy=...)}: like {@link #convert(Object)}, optionally copying nodes. */
    public static Expression convert(Object value, boolean copy) {
        if (value instanceof Expression e) {
            return maybeCopy(e, copy);
        }
        if (value instanceof List<?> l) {
            List<Expression> items = new ArrayList<>();
            for (Object o : l) {
                items.add(convert(o, copy));
            }
            return array(items);
        }
        return convert(value);
    }

    private static String dtypeValue(DType t) {
        return t == DType.USERDEFINED ? "USER-DEFINED" : t.name();
    }

    /** {@code exp.cast(expression, to)}: CAST(expression AS to); {@code to} is a DType, type string or DATA_TYPE node. */
    public static Expression cast(Expression expression, Object to) {
        return cast(expression, to, true, null);
    }

    /** {@code exp.cast(expression, to, copy=..., dialect=...)}. */
    public static Expression cast(Expression expression, Object to, boolean copy, Dialect dialect) {
        Expression expr = maybeParse(expression, null, dialect, null, copy);
        Expression dataType = DataTypes.build(to, dialect, false, copy);

        // dont re-cast if the expression is already a cast to the correct type
        if (expr.is(CAST)) {
            Dialect targetDialect = dialectOrBase(dialect);
            Map<DType, String> typeMapping = targetDialect.generatorData().TYPE_MAPPING;

            Expression existingTo = expr.expr(Args.TO);
            Object existingCastType = existingTo == null ? null : existingTo.thisValue();
            Object newCastType = dataType.thisValue();
            // `this` is only a plain type enum for simple types; complex ones such as
            // INTERVAL nest another expression there, so the equivalence check is skipped.
            boolean typesAreEquivalent = existingCastType instanceof DType a
                    && newCastType instanceof DType b
                    && typeMapping.getOrDefault(a, dtypeValue(a)).equals(typeMapping.getOrDefault(b, dtypeValue(b)));

            if (expr.isType(dataType) || typesAreEquivalent) {
                return expr;
            }
        }

        expr = Expression.of(CAST, Args.THIS, expr, Args.TO, dataType);
        expr.setDataType(dataType);

        return expr;
    }

    /** {@code exp.to_interval("1 day")} or an existing expression. */
    public static Expression toInterval(Object interval) {
        if (interval instanceof Expression e && e.is(LITERAL)) {
            if (!e.isString()) {
                throw new IllegalArgumentException("Invalid interval string.");
            }
            interval = e.thisValue();
        }

        Expression parsed = maybeParse("INTERVAL " + pyStr(interval), null, null, null, false);
        if (!parsed.is(INTERVAL)) {
            throw new AssertionError("expected an Interval, got " + parsed.type().className());
        }
        return parsed;
    }

    /** {@code exp.to_table("db.tbl")}: parses a (possibly dotted) table name. */
    public static Expression toTable(Object sqlPath, Dialect dialect) {
        if (sqlPath instanceof Expression e && e.is(TABLE)) {
            return e.copy();
        }

        try {
            return maybeParse(sqlPath, TABLE, dialect, null, false);
        } catch (ParseError err) {
            String[] words = splitNumWords(String.valueOf(sqlPath), 3);
            if (words.length > 3) {
                throw new IllegalArgumentException("too many values to unpack (expected 3)", err);
            }
            String catalog = words[0];
            String db = words[1];
            String thisName = words[2];

            if (thisName == null || thisName.isEmpty()) {
                throw err;
            }

            return table(thisName, db == null || db.isEmpty() ? null : db,
                    catalog == null || catalog.isEmpty() ? null : catalog, null, null);
        }
    }

    /** sqlglot's {@code split_num_words(value, ".", min_num_words)}, filling from the start. */
    private static String[] splitNumWords(String value, int minNumWords) {
        String[] words = value.split(Pattern.quote("."), -1);
        if (words.length >= minNumWords) {
            return words;
        }
        String[] out = new String[minNumWords];
        System.arraycopy(words, 0, out, minNumWords - words.length, words.length);
        return out;
    }

    /** {@code exp.to_column("tbl.col")}. */
    public static Expression toColumn(Object sqlPath, Dialect dialect) {
        if (sqlPath instanceof Expression e && e.is(COLUMN)) {
            return e.copy();
        }

        try {
            return maybeParse(sqlPath, COLUMN, dialect, null, false);
        } catch (ParseError err) {
            String[] parts = String.valueOf(sqlPath).split(Pattern.quote("."), -1);
            if (parts.length > 4) {
                throw new IllegalArgumentException(
                        "column() takes from 1 to 4 positional arguments but " + parts.length + " were given", err);
            }
            Object[] reversed = new Object[4];
            for (int i = 0; i < parts.length; i++) {
                reversed[i] = parts[parts.length - 1 - i];
            }
            return column(reversed[0], reversed[1], reversed[2], reversed[3], null);
        }
    }

    /** {@code exp.subquery(expression, alias)}: {@code SELECT * FROM (expression) AS alias}. */
    public static Expression subquery(Expression expression, String alias) {
        Expression expr = maybeParse(expression, null, null, null, false).assertIs(QUERY).subquery(alias, true);
        return Expression.of(SELECT).from(expr, true);
    }

    /** {@code exp.values(rows, alias, columns)}. */
    public static Expression values(List<List<Object>> rows, String alias, List<String> columns) {
        boolean hasColumns = columns != null && !columns.isEmpty();
        boolean hasAlias = alias != null && !alias.isEmpty();
        if (hasColumns && !hasAlias) {
            throw new IllegalArgumentException("Alias is required when providing columns");
        }

        List<Expression> tuples = new ArrayList<>();
        for (List<Object> row : rows) {
            List<Expression> items = new ArrayList<>();
            for (Object v : row) {
                items.add(convert(v, false));
            }
            tuples.add(tuple(items));
        }

        Expression aliasNode = null;
        if (hasColumns) {
            List<Expression> cols = new ArrayList<>();
            for (String c : columns) {
                cols.add(toIdentifier(c));
            }
            aliasNode = Expression.of(TABLE_ALIAS, Args.THIS, toIdentifier(alias), Args.COLUMNS, cols);
        } else if (hasAlias) {
            aliasNode = Expression.of(TABLE_ALIAS, Args.THIS, toIdentifier(alias));
        }

        return Expression.of(VALUES, Args.EXPRESSIONS, tuples, Args.ALIAS, aliasNode);
    }

    /**
     * {@code exp.func(name, *args, dialect=...)}: a known function node if {@code dialect}'s parser
     * knows {@code name}, otherwise an Anonymous function.
     */
    public static Expression func(String name, Dialect dialect, Object... args) {
        Dialect d = dialectOrBase(dialect);

        List<Expression> converted = new ArrayList<>();
        for (Object arg : args) {
            converted.add(maybeParse(arg, null, d, null, true));
        }

        String upper = Py.upper(name);
        io.github.offbyjuan.jglot.dialects.data.ParserData pd = d.parserData();
        ExprType fromArgList = pd.FUNCTIONS_FROM_ARG_LIST.get(upper);
        boolean custom = fromArgList == null && pd.FUNCTIONS_CUSTOM.contains(upper);

        Expression function;
        if (fromArgList != null || custom) {
            if (!converted.isEmpty()) {
                function = fromArgList != null ? fromArgList(fromArgList, converted) : callFunctionBuilder(d, upper, converted);
            } else if (fromArgList != null) {
                function = Expression.of(fromArgList);
            } else {
                ExprType byName = functionByName(upper);
                if (byName != null) {
                    function = Expression.of(byName);
                } else {
                    throw new IllegalArgumentException("Unable to convert '" + name + "' into a Func. Either manually "
                            + "construct the Func expression of interest or parse the function call.");
                }
            }
        } else {
            function = Expression.of(ANONYMOUS, Args.THIS, name, Args.EXPRESSIONS, converted);
        }

        for (String errorMessage : io.github.offbyjuan.jglot.parser.ParserCore.errorMessages(function, converted)) {
            throw new IllegalArgumentException(errorMessage);
        }

        return function;
    }

    /** sqlglot's {@code FUNCTION_BY_NAME[name]} (later definitions win, like the Python dict). */
    private static ExprType functionByName(String upperName) {
        ExprType found = null;
        for (ExprType t : ExprType.values()) {
            if (t.isTrait() || !t.isA(FUNC)) {
                continue;
            }
            for (String n : t.sqlNames()) {
                if (n.equals(upperName)) {
                    found = t;
                }
            }
        }
        return found;
    }

    /** Calls the dialect parser's hand-written FUNCTIONS builder. */
    private static Expression callFunctionBuilder(Dialect dialect, String upperName, List<Expression> args) {
        return dialect.newParser(ParserOptions.DEFAULT).callFunctionBuilder(upperName, args);
    }

    /** {@code exp.case(expression)}: an empty CASE; add branches with {@link Expression#when}. */
    public static Expression caseExpr(Expression expression) {
        Expression thisExpr = expression != null ? maybeParse(expression, null, null, null, true) : null;
        return Expression.of(CASE, Args.THIS, thisExpr, Args.IFS, new ArrayList<>());
    }

    /** {@code exp.select(*expressions)}: a new SELECT. Strings are parsed with the base dialect. */
    public static Expression select(Object... expressions) {
        return Expression.of(SELECT).select(true, true, expressions);
    }

    /** {@code exp.union(left, right, distinct=...)}. */
    public static Expression union(Expression left, Expression right, boolean distinct) {
        return applySetOperation(java.util.Arrays.asList(left, right), UNION, distinct, null, true);
    }

    /** {@code exp.intersect(left, right, distinct=...)}. */
    public static Expression intersect(Expression left, Expression right, boolean distinct) {
        return applySetOperation(java.util.Arrays.asList(left, right), INTERSECT, distinct, null, true);
    }

    /** {@code exp.except_(left, right, distinct=...)}. */
    public static Expression except(Expression left, Expression right, boolean distinct) {
        return applySetOperation(java.util.Arrays.asList(left, right), EXCEPT, distinct, null, true);
    }

    /** {@code exp.maybe_parse(sql_or_expression, dialect=...)}: parses strings, passes nodes through. */
    public static Expression maybeParse(Object sqlOrExpression, Dialect dialect) {
        return maybeParse(sqlOrExpression, null, dialect, null, false);
    }

    /** {@code exp.condition(...)}: parses a condition string or passes a node through. */
    public static Expression condition(Object expression, Dialect dialect) {
        return condition(expression, dialect, true);
    }

    /** {@code exp.condition(expression, dialect=..., copy=...)}. */
    public static Expression condition(Object expression, Dialect dialect, boolean copy) {
        return maybeParse(expression, CONDITION, dialect, null, copy);
    }

    /** {@code exp.xor(*conditions)}. */
    public static Expression xor(Expression... conditions) {
        return combine(XOR, List.of(conditions), true);
    }

    /** {@code exp.apply_index_offset(this, expressions, offset, dialect)}. */
    public static List<Expression> applyIndexOffset(Expression thisExpr, List<Expression> expressions, int offset,
            Dialect dialect) {
        if (offset == 0 || expressions.size() != 1) {
            return expressions;
        }

        Expression expression = expressions.get(0);
        Dialect d = dialectOrBase(dialect);

        if (thisExpr.dataType() == null) {
            io.github.offbyjuan.jglot.optimizer.TypeAnnotator.annotateTypes(thisExpr, d);
        }

        Object thisType = thisExpr.dataType().thisValue();
        if (thisType != DType.UNKNOWN && thisType != DType.ARRAY) {
            return expressions;
        }

        if (expression.dataType() == null) {
            io.github.offbyjuan.jglot.optimizer.TypeAnnotator.annotateTypes(expression, d);
        }

        if (DataTypes.INTEGER_TYPES.contains(expression.dataType().thisValue())) {
            LOGGER.info("Applying array index offset (" + offset + ")");
            expression = io.github.offbyjuan.jglot.optimizer.Simplify.simplify(binop(ADD, expression, offset, false),
                    Dialects.base());
            List<Expression> out = new ArrayList<>();
            out.add(expression);
            return out;
        }

        return expressions;
    }

    /** {@code exp.table_name(table, dialect, identify)}: the dotted name of a table. */
    public static String tableName(Object table, Dialect dialect, boolean identify) {
        Expression expr = maybeParse(table, TABLE, dialect, null, false);

        if (expr == null) {
            throw new IllegalArgumentException("Cannot parse " + table);
        }

        Dialect d = dialectOrBase(dialect);
        GeneratorOptions opts = GeneratorOptions.builder().identify(Boolean.TRUE).comments(false).build();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Expression part : expr.parts()) {
            if (!first) {
                sb.append('.');
            }
            first = false;
            if (identify || !isSafeIdentifier(part.name())) {
                sb.append(d.newGenerator(opts).generate(part, false));
            } else {
                sb.append(part.name());
            }
        }
        return sb.toString();
    }

    /** {@code exp.replace_children(expression, fun)}: replaces each child with {@code fun(child)}. */
    public static void replaceChildren(Expression expression, java.util.function.Function<Expression, Object> fun) {
        for (Map.Entry<Arg, Object> kv : expression.argEntries()) {
            Arg k = kv.getKey();
            Object v = kv.getValue();
            boolean isListArg = v instanceof List<?>;

            List<?> childNodes = isListArg ? new ArrayList<>((List<?>) v) : java.util.Collections.singletonList(v);
            List<Object> newChildNodes = new ArrayList<>();

            for (Object cn : childNodes) {
                if (cn instanceof Expression ce) {
                    Object result = fun.apply(ce);
                    if (result instanceof java.util.Collection<?> c) {
                        newChildNodes.addAll(c);
                    } else if (result != null) {
                        newChildNodes.add(result);
                    }
                } else {
                    newChildNodes.add(cn);
                }
            }

            if (isListArg) {
                expression.set(k, newChildNodes);
            } else {
                expression.set(k, newChildNodes.isEmpty() ? null : newChildNodes.get(0));
            }
        }
    }

    /**
     * {@code exp.replace_tree(expression, fun, prune)}: replaces nodes in reverse DFS order (leaves
     * first); new nodes produced by {@code fun} are traversed as well.
     */
    public static Expression replaceTree(Expression expression, java.util.function.Function<Expression, Expression> fun,
            java.util.function.Predicate<Expression> prune) {
        List<Expression> stack = new ArrayList<>();
        for (Expression node : expression.dfs(prune)) {
            stack.add(node);
        }

        Expression newNode = null;
        while (!stack.isEmpty()) {
            Expression node = stack.remove(stack.size() - 1);
            newNode = fun.apply(node);

            if (newNode != node) {
                node.replace(newNode);

                if (newNode != null) {
                    stack.add(newNode);
                }
            }
        }

        return newNode;
    }
}
