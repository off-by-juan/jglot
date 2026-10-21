package io.github.offbyjuan.jglot.expressions;

import static io.github.offbyjuan.jglot.expressions.ExprType.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A node in a SQL syntax tree, equivalent to sqlglot's {@code Expression}.
 *
 * <p>A node has an {@link ExprType} and a fixed set of argument slots defined by that type. Slot
 * values are {@code null} (absent), another {@link Expression}, a {@code List} (of expressions or
 * plain values), or a plain value such as a {@link String}, {@link Boolean} or {@link DType}.
 *
 * <p><b>Thread safety:</b> trees are mutable and not thread-safe. A tree produced by a parse is
 * owned by the calling thread; use {@link #copy()} before handing it to another thread. All shared
 * state (dialects, tokenizer tables, function registries) is immutable, so parsing and generating
 * concurrently on independent trees is safe.
 */
public final class Expression {
    private final ExprType type;
    private final Object[] values;
    private Expression parent;
    private Arg argKey;
    private int index = -1;
    private List<String> comments;
    private Expression dataType;
    private Map<String, Object> meta;
    private int hash;

    public static final String SQLGLOT_META = "sqlglot.meta";

    private static final Object[] NO_VALUES = {};

    private Expression(ExprType type) {
        if (type.isTrait()) {
            throw new IllegalArgumentException(type + " is a trait and cannot be instantiated");
        }
        this.type = type;
        int n = type.argCount();
        this.values = n == 0 ? NO_VALUES : new Object[n];
    }

    /** Creates an empty node of the given type. */
    public static Expression of(ExprType type) {
        return new Expression(type);
    }

    /**
     * Creates a node from alternating {@code (Arg, value)} pairs, applying the same construction-time
     * normalizations sqlglot does in its constructors (e.g. time units become upper-case vars).
     */
    public static Expression of(ExprType type, Object... argsAndValues) {
        Expression e = new Expression(type);
        if ((argsAndValues.length & 1) != 0) {
            throw new IllegalArgumentException("expected (Arg, value) pairs");
        }
        for (int i = 0; i < argsAndValues.length; i += 2) {
            Object v = argsAndValues[i + 1];
            if (v != null) {
                e.set((Arg) argsAndValues[i], v);
            }
        }
        ExprInit.afterConstruct(e);
        return e;
    }

    /** Creates a node from a map of arg values (in iteration order), applying constructor normalizations. */
    public static Expression of(ExprType type, Map<Arg, ?> args) {
        Expression e = new Expression(type);
        for (Map.Entry<Arg, ?> entry : args.entrySet()) {
            if (entry.getValue() != null) {
                e.set(entry.getKey(), entry.getValue());
            }
        }
        ExprInit.afterConstruct(e);
        return e;
    }

    // ------------------------------------------------------------------------------------------
    // Type

    public ExprType type() {
        return type;
    }

    /** sqlglot {@code isinstance}: whether this node's type is, or inherits from, {@code t}. */
    public boolean is(ExprType t) {
        return type.isA(t);
    }

    public boolean is(ExprType a, ExprType b) {
        return type.isA(a) || type.isA(b);
    }

    public boolean is(ExprType a, ExprType b, ExprType c) {
        return type.isA(a) || type.isA(b) || type.isA(c);
    }

    public boolean is(ExprType... types) {
        for (ExprType t : types) {
            if (type.isA(t)) {
                return true;
            }
        }
        return false;
    }

    /** sqlglot {@code type(x) is T}: exact type match, ignoring inheritance. */
    public boolean isExactly(ExprType t) {
        return type == t;
    }

    public String key() {
        return type.key();
    }

    public static boolean isInstance(Object o, ExprType t) {
        return o instanceof Expression e && e.type.isA(t);
    }

    // ------------------------------------------------------------------------------------------
    // Arguments

    private int slotOrThrow(Arg arg) {
        int slot = type.slot(arg);
        if (slot < 0) {
            throw new IllegalArgumentException("Unexpected keyword: '" + arg + "' for " + type.className());
        }
        return slot;
    }

    /** Whether this node's type declares {@code arg}. */
    public boolean accepts(Arg arg) {
        return type.slot(arg) >= 0;
    }

    /** The raw value of {@code arg}, or null. Returns null for args the type doesn't declare. */
    public Object get(Arg arg) {
        int slot = type.slot(arg);
        return slot < 0 ? null : values[slot];
    }

    /** The value of {@code arg} if it is an expression, else null. */
    public Expression expr(Arg arg) {
        return get(arg) instanceof Expression e ? e : null;
    }

    /** The value of {@code arg} as a list (empty if absent); a single expression becomes a singleton. */
    @SuppressWarnings("unchecked")
    public List<Object> list(Arg arg) {
        Object v = get(arg);
        if (v == null) {
            return Collections.emptyList();
        }
        if (v instanceof List<?> l) {
            return (List<Object>) l;
        }
        return List.of(v);
    }

    /** The expressions in list arg {@code arg} (non-expression entries are skipped). */
    public List<Expression> exprs(Arg arg) {
        Object v = get(arg);
        if (v == null) {
            return Collections.emptyList();
        }
        if (v instanceof Expression e) {
            return List.of(e);
        }
        if (!(v instanceof List<?> l)) {
            return Collections.emptyList();
        }
        boolean allExprs = true;
        for (Object o : l) {
            if (!(o instanceof Expression)) {
                allExprs = false;
                break;
            }
        }
        if (allExprs) {
            @SuppressWarnings("unchecked")
            List<Expression> cast = (List<Expression>) l;
            return cast;
        }
        List<Expression> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (o instanceof Expression e) {
                out.add(e);
            }
        }
        return out;
    }

    /** The value of {@code arg} if it is a string, else null. */
    public String str(Arg arg) {
        return get(arg) instanceof String s ? s : null;
    }

    /** Python truthiness of {@code arg}: present, not false, not an empty string or list. */
    public boolean has(Arg arg) {
        return truthy(get(arg));
    }

    /** Python truthiness of {@code arg}, alias of {@link #has(Arg)} for boolean flags. */
    public boolean flag(Arg arg) {
        return truthy(get(arg));
    }

    public static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof List<?> l) {
            return !l.isEmpty();
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return true;
    }

    public Object thisValue() {
        return values.length > 0 ? get(Args.THIS) : null;
    }

    /** {@code self.this} when it is an expression. */
    public Expression getThis() {
        return expr(Args.THIS);
    }

    /** {@code self.expression} when it is an expression. */
    public Expression getExpression() {
        return expr(Args.EXPRESSION);
    }

    /** {@code self.expressions}: the {@code expressions} list arg, empty if absent. */
    public List<Expression> getExpressions() {
        return exprs(Args.EXPRESSIONS);
    }

    /** The {@code (Arg, value)} pairs that are set, in slot order. */
    public List<Map.Entry<Arg, Object>> argEntries() {
        List<Map.Entry<Arg, Object>> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                out.add(Map.entry(type.argAt(i), values[i]));
            }
        }
        return out;
    }

    /** Returns true if no arg is set. */
    public boolean hasNoArgs() {
        for (Object v : values) {
            if (v != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sets {@code arg} to {@code value}, re-parenting any child expressions. A null value removes the
     * arg. Lists are copied into a fresh mutable list.
     */
    public Expression set(Arg arg, Object value) {
        int slot = slotOrThrow(arg);
        invalidateHash();
        if (value == null) {
            values[slot] = null;
            return this;
        }
        if (value instanceof List<?> l) {
            ArrayList<Object> copy = new ArrayList<>(l);
            value = copy;
        }
        values[slot] = value;
        setParent(arg, value, -1);
        return this;
    }

    /**
     * sqlglot's {@code set(arg_key, value, index, overwrite)}: modifies one entry of a list arg. A
     * null value removes the entry; a list value splices its items in place of the entry.
     */
    @SuppressWarnings("unchecked")
    public Expression set(Arg arg, Object value, int index, boolean overwrite) {
        int slot = slotOrThrow(arg);
        invalidateHash();
        Object cur = values[slot];
        if (!(cur instanceof List<?>)) {
            return this;
        }
        List<Object> list = (List<Object>) cur;
        if (index < 0 || index >= list.size() || list.get(index) == null) {
            return this;
        }
        if (value == null) {
            list.remove(index);
            reindex(list, index);
            return this;
        }
        if (value instanceof List<?> l) {
            list.remove(index);
            list.addAll(index, l);
        } else if (overwrite) {
            list.set(index, value);
        } else {
            list.add(index, value);
        }
        setParent(arg, list, -1);
        return this;
    }

    /** Appends {@code value} to list arg {@code arg}, creating the list if needed. */
    @SuppressWarnings("unchecked")
    public Expression append(Arg arg, Object value) {
        int slot = slotOrThrow(arg);
        invalidateHash();
        List<Object> list;
        if (values[slot] instanceof List<?> l) {
            list = (List<Object>) l;
        } else {
            list = new ArrayList<>();
            values[slot] = list;
        }
        if (value instanceof Expression e) {
            e.parent = this;
            e.argKey = arg;
            e.index = list.size();
        }
        list.add(value);
        return this;
    }

    private static void reindex(List<Object> list, int from) {
        for (int i = from; i < list.size(); i++) {
            if (list.get(i) instanceof Expression e) {
                e.index = i;
            }
        }
    }

    private void setParent(Arg arg, Object value, int idx) {
        if (value instanceof Expression e) {
            e.parent = this;
            e.argKey = arg;
            e.index = idx;
        } else if (value instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i) instanceof Expression e) {
                    e.parent = this;
                    e.argKey = arg;
                    e.index = i;
                }
            }
        }
    }

    private void invalidateHash() {
        Expression node = this;
        while (node != null && node.hash != 0) {
            node.hash = 0;
            node = node.parent;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Tree position

    public Expression parent() {
        return parent;
    }

    public Arg argKey() {
        return argKey;
    }

    /** Assigns this node's parent link directly (sqlglot allows setting {@code .parent}/{@code .arg_key}/{@code .index}). */
    public void setParent(Expression parent, Arg argKey, int index) {
        this.parent = parent;
        this.argKey = argKey;
        this.index = index;
    }

    /** Index within the parent's list arg, or -1 if not in a list. */
    public int index() {
        return index;
    }

    public int depth() {
        int d = 0;
        for (Expression p = parent; p != null; p = p.parent) {
            d++;
        }
        return d;
    }

    public Expression root() {
        Expression e = this;
        while (e.parent != null) {
            e = e.parent;
        }
        return e;
    }

    /** Whether the parent has exactly the same type as this node. */
    public boolean sameParent() {
        return parent != null && parent.type == type;
    }

    public Expression findAncestor(ExprType... types) {
        Expression a = parent;
        while (a != null && !a.is(types)) {
            a = a.parent;
        }
        return a;
    }

    public Expression parentSelect() {
        return findAncestor(SELECT);
    }

    // ------------------------------------------------------------------------------------------
    // Comments, metadata and type annotation

    public List<String> comments() {
        return comments;
    }

    public void setComments(List<String> comments) {
        this.comments = comments == null ? null : new ArrayList<>(comments);
    }

    public void addComments(List<String> newComments) {
        addComments(newComments, false);
    }

    public void addComments(List<String> newComments, boolean prepend) {
        if (comments == null) {
            comments = new ArrayList<>();
        }
        if (newComments == null || newComments.isEmpty()) {
            return;
        }
        for (String comment : newComments) {
            int at = comment.indexOf(SQLGLOT_META);
            if (at >= 0) {
                String rest = String.join("", comment.substring(at + SQLGLOT_META.length()).split(java.util.regex.Pattern.quote(SQLGLOT_META)));
                for (String kv : rest.split(",")) {
                    String[] parts = kv.split("=");
                    String k = parts[0].strip();
                    Object v = parts.length > 1 ? toBool(parts[1].strip()) : Boolean.TRUE;
                    meta().put(k, v);
                }
            }
            if (!prepend) {
                comments.add(comment);
            }
        }
        if (prepend) {
            List<String> merged = new ArrayList<>(newComments);
            merged.addAll(comments);
            comments = merged;
        }
    }

    private static Object toBool(String v) {
        String u = v.toUpperCase(Locale.ROOT);
        if (u.equals("TRUE") || u.equals("1")) {
            return Boolean.TRUE;
        }
        if (u.equals("FALSE") || u.equals("0")) {
            return Boolean.FALSE;
        }
        return v;
    }

    public List<String> popComments() {
        List<String> c = comments == null ? new ArrayList<>() : comments;
        comments = null;
        return c;
    }

    public Map<String, Object> meta() {
        if (meta == null) {
            meta = new HashMap<>();
        }
        return meta;
    }

    public Object metaGet(String key) {
        return meta == null ? null : meta.get(key);
    }

    public boolean hasMeta() {
        return meta != null && !meta.isEmpty();
    }

    private static final String[] POSITION_META_KEYS = {"line", "col", "start", "end"};

    /** sqlglot's {@code update_positions(token)}: copies the token's line/col/start/end into meta. */
    public Expression updatePositions(io.github.offbyjuan.jglot.tokens.Token token) {
        if (token != null) {
            Map<String, Object> m = meta();
            m.put("line", token.line());
            m.put("col", token.col());
            m.put("start", token.start());
            m.put("end", token.end());
        }
        return this;
    }

    /** sqlglot's {@code update_positions(other)}: copies the position meta keys of another node. */
    public Expression updatePositions(Expression other) {
        if (other != null && other.meta != null && !other.meta.isEmpty()) {
            Map<String, Object> m = meta();
            for (String k : POSITION_META_KEYS) {
                if (other.meta.containsKey(k)) {
                    m.put(k, other.meta.get(k));
                }
            }
        }
        return this;
    }

    /** The data type annotation (a DATA_TYPE expression), as set by the parser or type inference. */
    public Expression dataType() {
        if (type.isDataTypeType()) {
            return this;
        }
        if (type.isCastType()) {
            return dataType != null ? dataType : expr(Args.TO);
        }
        return dataType;
    }

    public void setDataType(Expression dt) {
        this.dataType = dt;
    }

    // ------------------------------------------------------------------------------------------
    // Derived properties (sqlglot's @property accessors)

    /** Text of {@code arg} if it is a string or a leaf identifier/literal/var/star/null. */
    public String text(Arg arg) {
        Object field = get(arg);
        if (field instanceof String s) {
            return s;
        }
        if (field instanceof Expression e) {
            if (e.is(IDENTIFIER, LITERAL, VAR)) {
                Object t = e.thisValue();
                return t instanceof String s ? s : String.valueOf(t);
            }
            if (e.is(STAR, NULL)) {
                return e.name();
            }
        }
        return "";
    }

    public String name() {
        switch (type) {
            case STAR:
                return "*";
            case NULL:
                return "NULL";
            case PLACEHOLDER: {
                String t = text(Args.THIS);
                return t.isEmpty() ? "?" : t;
            }
            case DOT:
                return nameOf(getExpression());
            case ANONYMOUS: {
                Object t = thisValue();
                return t instanceof String s ? s : nameOf((Expression) t);
            }
            case DYNAMIC_IDENTIFIER:
                return getThis() != null ? getThis().name() : "";
            case TABLE: {
                Expression t = getThis();
                if (t == null || (t.is(FUNC) && !t.is(DYNAMIC_IDENTIFIER))) {
                    return "";
                }
                return t.name();
            }
            default:
                break;
        }
        if (type.isCastType() || is(ORDERED) || is(FROM) || is(EXECUTE) || is(DATA_TYPE_PARAM)) {
            return nameOf(getThis());
        }
        return text(Args.THIS);
    }

    private static String nameOf(Expression e) {
        return e == null ? "" : e.name();
    }

    public String alias() {
        Object a = get(Args.ALIAS);
        if (a instanceof Expression e) {
            return e.name();
        }
        return text(Args.ALIAS);
    }

    public List<String> aliasColumnNames() {
        Expression tableAlias = expr(Args.ALIAS);
        if (tableAlias == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Expression c : tableAlias.exprs(Args.COLUMNS)) {
            out.add(c.name());
        }
        return out;
    }

    public String aliasOrName() {
        if (is(FROM)) {
            Expression t = getThis();
            return t == null ? "" : t.aliasOrName();
        }
        String a = alias();
        return a.isEmpty() ? name() : a;
    }

    public String outputName() {
        if (type.isCastType()) {
            return name();
        }
        switch (type) {
            case COLUMN, LITERAL, IDENTIFIER, STAR, DOT, TABLE_COLUMN, PSEUDOCOLUMN:
                return name();
            case ALIAS, PIVOT_ALIAS, SUBQUERY:
                return alias();
            case PAREN:
                return getThis() == null ? "" : getThis().outputName();
            case BRACKET: {
                List<Expression> ex = getExpressions();
                return ex.size() == 1 ? ex.get(0).outputName() : "";
            }
            case JSON_EXTRACT:
                return getExpressions().isEmpty() && getExpression() != null ? getExpression().outputName() : "";
            case JSON_EXTRACT_SCALAR:
                return getExpression() == null ? "" : getExpression().outputName();
            case JSON_PATH: {
                List<Expression> ex = getExpressions();
                if (ex.isEmpty()) {
                    return "";
                }
                Object last = ex.get(ex.size() - 1).thisValue();
                return last instanceof String s ? s : "";
            }
            default:
                break;
        }
        if (is(AGG)) {
            return getThis() == null ? "" : getThis().name();
        }
        return "";
    }

    public boolean isString() {
        return type == LITERAL && truthy(get(Args.IS_STRING));
    }

    public boolean isNumber() {
        return (type == LITERAL && !truthy(get(Args.IS_STRING)))
                || (type == NEG && getThis() != null && getThis().isNumber());
    }

    /** Whether this is a numeric literal holding an integer. */
    public boolean isInt() {
        if (!isNumber()) {
            return false;
        }
        Object py = toPy();
        return py instanceof java.math.BigInteger;
    }

    public boolean isStar() {
        switch (type) {
            case STAR:
                return true;
            case COLUMN:
                return getThis() != null && getThis().type == STAR;
            case DOT:
                return getExpression() != null && getExpression().isStar();
            case SELECT:
                for (Expression e : getExpressions()) {
                    if (e.isStar()) {
                        return true;
                    }
                }
                return false;
            default:
                break;
        }
        if (is(SET_OPERATION) || type == SUBQUERY) {
            ArrayDeque<Expression> stack = new ArrayDeque<>();
            stack.push(this);
            while (!stack.isEmpty()) {
                Expression node = stack.pop();
                if (node.is(SET_OPERATION)) {
                    if (node.getThis() != null) {
                        stack.push(node.getThis());
                    }
                    if (node.getExpression() != null) {
                        stack.push(node.getExpression());
                    }
                } else if (node.type == SUBQUERY) {
                    if (node.getThis() != null) {
                        stack.push(node.getThis());
                    }
                } else if (node.isStar()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * sqlglot's {@code to_py}: literal value as a Java object. Numbers become {@link
     * java.math.BigInteger} or {@link java.math.BigDecimal}; strings stay strings; NULL is null.
     */
    public Object toPy() {
        switch (type) {
            case LITERAL: {
                String t = (String) thisValue();
                if (isNumber()) {
                    try {
                        return new java.math.BigInteger(t);
                    } catch (NumberFormatException e) {
                        try {
                            return new java.math.BigDecimal(t);
                        } catch (NumberFormatException e2) {
                            throw new IllegalArgumentException("Invalid numeric literal: '" + t + "'");
                        }
                    }
                }
                return t;
            }
            case NULL:
                return null;
            case BOOLEAN:
                return thisValue();
            case NEG: {
                Object v = getThis().toPy();
                if (v instanceof java.math.BigInteger bi) {
                    return bi.negate();
                }
                if (v instanceof java.math.BigDecimal bd) {
                    return bd.negate();
                }
                throw new IllegalArgumentException(this + " cannot be converted to a value");
            }
            default:
                throw new IllegalArgumentException(type.className() + " cannot be converted to a value");
        }
    }

    /** Column/table parts as identifiers, ordered catalog, db, table, name. */
    public List<Expression> parts() {
        if (type == COLUMN || type == PSEUDOCOLUMN) {
            List<Expression> out = new ArrayList<>(4);
            for (Arg a : new Arg[] {Args.CATALOG, Args.DB, Args.TABLE, Args.THIS}) {
                Object v = get(a);
                if (v instanceof Expression e && truthy(v)) {
                    out.add(e);
                }
            }
            return out;
        }
        if (type == TABLE) {
            List<Expression> out = new ArrayList<>(4);
            for (Arg a : new Arg[] {Args.CATALOG, Args.DB, Args.THIS}) {
                Expression part = expr(a);
                if (part == null) {
                    continue;
                }
                if (part.type == DOT) {
                    out.addAll(part.flattenList());
                } else {
                    out.add(part);
                }
            }
            return out;
        }
        if (type == DOT) {
            List<Expression> flat = flattenList();
            Expression head = flat.get(0);
            List<Expression> rest = new ArrayList<>(flat.subList(1, flat.size()));
            Collections.reverse(rest);
            for (Arg a : new Arg[] {Args.THIS, Args.TABLE, Args.DB, Args.CATALOG}) {
                Object part = head.get(a);
                if (part instanceof Expression pe) {
                    rest.add(pe);
                }
            }
            Collections.reverse(rest);
            return rest;
        }
        throw new UnsupportedOperationException("parts() is not defined for " + type.className());
    }

    /** {@code self.unit} for time-unit nodes. */
    public Expression unit() {
        return expr(Args.UNIT);
    }

    /** Whether this is a parenthesized subquery wrapping another expression with no other args. */
    public boolean isWrapper() {
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null && type.argAt(i) != Args.THIS) {
                return false;
            }
        }
        return true;
    }

    /** The projections of a query-like node. */
    public List<Expression> selects() {
        if (type == SELECT) {
            return getExpressions();
        }
        if (is(SET_OPERATION)) {
            Expression e = this;
            while (e != null && e.is(SET_OPERATION)) {
                e = e.getThis() == null ? null : e.getThis().unnest();
            }
            return e == null ? List.of() : e.selects();
        }
        if (is(UDTF)) {
            Expression alias = expr(Args.ALIAS);
            return alias == null ? List.of() : alias.exprs(Args.COLUMNS);
        }
        if (is(DERIVED_TABLE)) {
            Expression t = getThis();
            return t != null && t.is(QUERY) ? t.selects() : List.of();
        }
        if (is(DDL)) {
            Expression t = expr(Args.EXPRESSION);
            return t != null && t.is(QUERY) ? t.selects() : List.of();
        }
        return List.of();
    }

    public List<String> namedSelects() {
        if (type == SELECT) {
            List<String> out = new ArrayList<>();
            for (Expression e : getExpressions()) {
                if (!e.aliasOrName().isEmpty()) {
                    out.add(e.outputName());
                } else if (e.type == ALIASES) {
                    for (Expression a : e.getExpressions()) {
                        out.add(a.name());
                    }
                }
            }
            return out;
        }
        if (is(SET_OPERATION)) {
            Expression e = this;
            while (e != null && e.is(SET_OPERATION)) {
                if (e.has(Args.BY_NAME)) {
                    java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>(e.getThis().unnest().namedSelects());
                    s.addAll(e.getExpression().unnest().namedSelects());
                    return new ArrayList<>(s);
                }
                e = e.getThis().unnest();
            }
            return e == null ? List.of() : e.namedSelects();
        }
        List<String> out = new ArrayList<>();
        for (Expression s : selects()) {
            out.add(s.outputName());
        }
        return out;
    }

    /** The CTEs of a query's WITH clause. */
    public List<Expression> ctes() {
        Expression w = expr(Args.WITH_);
        return w == null ? List.of() : w.getExpressions();
    }

    // ------------------------------------------------------------------------------------------
    // Traversal

    /** Child expressions in slot order, exploding list args. */
    public List<Expression> iterExpressions() {
        List<Expression> out = new ArrayList<>();
        for (Object v : values) {
            if (v instanceof Expression e) {
                out.add(e);
            } else if (v instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Expression e) {
                        out.add(e);
                    }
                }
            }
        }
        return out;
    }

    private void pushChildrenReversed(ArrayDeque<Expression> stack) {
        for (int i = values.length - 1; i >= 0; i--) {
            Object v = values[i];
            if (v instanceof Expression e) {
                stack.push(e);
            } else if (v instanceof List<?> l) {
                for (int j = l.size() - 1; j >= 0; j--) {
                    if (l.get(j) instanceof Expression e) {
                        stack.push(e);
                    }
                }
            }
        }
    }

    /**
     * Depth-first pre-order traversal; {@code prune} stops descent below matching nodes. Children are
     * expanded lazily, after the caller has seen a node, matching sqlglot's generator-based dfs.
     */
    public Iterable<Expression> dfs(Predicate<Expression> prune) {
        return () -> new Iterator<>() {
            final ArrayDeque<Expression> stack = new ArrayDeque<>(List.of(Expression.this));
            Expression pending;

            @Override
            public boolean hasNext() {
                if (pending != null) {
                    pending.pushChildrenReversed(stack);
                    pending = null;
                }
                return !stack.isEmpty();
            }

            @Override
            public Expression next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Expression node = stack.pop();
                if (prune == null || !prune.test(node)) {
                    pending = node;
                }
                return node;
            }
        };
    }

    /** Depth-first traversal without pruning. */
    public Iterable<Expression> dfs() {
        return dfs(null);
    }

    /** Breadth-first traversal; {@code prune} stops descent below matching nodes. */
    public Iterable<Expression> bfs(Predicate<Expression> prune) {
        return () -> new Iterator<>() {
            final ArrayDeque<Expression> queue = new ArrayDeque<>(List.of(Expression.this));
            Expression pending;

            @Override
            public boolean hasNext() {
                if (pending != null) {
                    queue.addAll(pending.iterExpressions());
                    pending = null;
                }
                return !queue.isEmpty();
            }

            @Override
            public Expression next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Expression node = queue.poll();
                if (prune == null || !prune.test(node)) {
                    pending = node;
                }
                return node;
            }
        };
    }

    public Iterable<Expression> bfs() {
        return bfs(null);
    }

    public Iterable<Expression> walk(boolean bfs) {
        return bfs ? bfs(null) : dfs(null);
    }

    public Iterable<Expression> walk() {
        return bfs(null);
    }

    /** First node (BFS order, including this node) whose type matches any of {@code types}. */
    public Expression find(ExprType... types) {
        for (Expression e : bfs()) {
            if (e.is(types)) {
                return e;
            }
        }
        return null;
    }

    /** All nodes (BFS order, including this node) whose type matches any of {@code types}. */
    public List<Expression> findAll(ExprType... types) {
        List<Expression> out = new ArrayList<>();
        for (Expression e : bfs()) {
            if (e.is(types)) {
                out.add(e);
            }
        }
        return out;
    }

    /** All nodes in DFS order whose type matches any of {@code types}. */
    public List<Expression> findAllDfs(ExprType... types) {
        List<Expression> out = new ArrayList<>();
        for (Expression e : dfs()) {
            if (e.is(types)) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * sqlglot's {@code unnest}: strips enclosing {@code PAREN} nodes; for a {@code SUBQUERY} it returns
     * the first non-subquery node (sqlglot's {@code Subquery.unnest}).
     */
    public Expression unnest() {
        Expression e = this;
        if (type == SUBQUERY) {
            while (e != null && e.type == SUBQUERY) {
                e = e.getThis();
            }
            return e;
        }
        while (e.type == PAREN && e.getThis() != null) {
            e = e.getThis();
        }
        return e;
    }

    public Expression unalias() {
        return is(ALIAS) ? getThis() : this;
    }

    public List<Expression> unnestOperands() {
        List<Expression> out = new ArrayList<>();
        for (Expression e : iterExpressions()) {
            out.add(e.unnest());
        }
        return out;
    }

    /** sqlglot's {@code flatten}: operands of a chain of same-typed nodes, e.g. a AND b AND c. */
    public List<Expression> flattenList() {
        return flatten(true);
    }

    public List<Expression> flatten(boolean unnest) {
        List<Expression> out = new ArrayList<>();
        ExprType t = type;
        for (Expression node : dfs(n -> n.parent != null && n.type != t)) {
            if (node.type != t) {
                out.add(unnest && !node.type.isSubqueryType() ? node.unnest() : node);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Mutation helpers

    /** Replaces this node in its parent with {@code expression} (which may be null to remove it). */
    public Expression replace(Expression expression) {
        Expression p = parent;
        if (p == null || p == expression) {
            return expression;
        }
        Arg key = argKey;
        if (key != null) {
            if (index >= 0 && p.get(key) instanceof List<?>) {
                p.set(key, expression, index, true);
            } else {
                p.set(key, expression);
            }
        }
        if (expression != this) {
            parent = null;
            argKey = null;
            index = -1;
        }
        return expression;
    }

    /** Replaces this node with a list of nodes (spliced into the parent's list arg). */
    public void replaceWithList(List<Expression> expressions) {
        Expression p = parent;
        if (p == null || argKey == null) {
            return;
        }
        if (index >= 0 && p.get(argKey) instanceof List<?>) {
            p.set(argKey, expressions, index, true);
        } else {
            p.set(argKey, expressions);
        }
        parent = null;
        argKey = null;
        index = -1;
    }

    /** Removes this node from its parent. */
    public Expression pop() {
        replace(null);
        return this;
    }

    /**
     * sqlglot's {@code transform}: visits nodes depth-first, replacing each with {@code fun(node)}.
     * Children of a replaced node are not visited.
     */
    public Expression transform(Function<Expression, Expression> fun, boolean copy) {
        Expression start = copy ? copy() : this;
        Expression root = null;
        ArrayDeque<Expression> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            Expression node = stack.pop();
            Expression p = node.parent;
            Arg key = node.argKey;
            int idx = node.index;
            Expression newNode = fun.apply(node);
            if (root == null) {
                root = newNode;
            } else if (p != null && key != null && newNode != node) {
                if (idx >= 0 && p.get(key) instanceof List<?>) {
                    p.set(key, newNode, idx, true);
                } else {
                    p.set(key, newNode);
                }
            }
            if (newNode == node) {
                node.pushChildrenReversed(stack);
            }
        }
        return root;
    }

    public Expression transform(Function<Expression, Expression> fun) {
        return transform(fun, true);
    }

    // ------------------------------------------------------------------------------------------
    // Copying, equality, hashing

    /** Deep copy of this subtree (comments, metadata and type annotations included). */
    public Expression copy() {
        Expression root = new Expression(type);
        ArrayDeque<Expression[]> stack = new ArrayDeque<>();
        stack.push(new Expression[] {this, root});
        while (!stack.isEmpty()) {
            Expression[] pair = stack.pop();
            Expression node = pair[0];
            Expression cp = pair[1];
            if (node.comments != null) {
                cp.comments = new ArrayList<>(node.comments);
            }
            if (node.dataType != null) {
                cp.dataType = node.dataType.copy();
            }
            if (node.meta != null) {
                cp.meta = new HashMap<>(node.meta);
            }
            cp.hash = node.hash;
            for (int i = 0; i < node.values.length; i++) {
                Object v = node.values[i];
                Arg a = node.type.argAt(i);
                if (v instanceof Expression e) {
                    Expression child = new Expression(e.type);
                    cp.values[i] = child;
                    child.parent = cp;
                    child.argKey = a;
                    child.index = -1;
                    stack.push(new Expression[] {e, child});
                } else if (v instanceof List<?> l) {
                    ArrayList<Object> nl = new ArrayList<>(l.size());
                    for (int j = 0; j < l.size(); j++) {
                        Object o = l.get(j);
                        if (o instanceof Expression e) {
                            Expression child = new Expression(e.type);
                            child.parent = cp;
                            child.argKey = a;
                            child.index = j;
                            nl.add(child);
                            stack.push(new Expression[] {e, child});
                        } else {
                            nl.add(o);
                        }
                    }
                    cp.values[i] = nl;
                } else {
                    cp.values[i] = v;
                }
            }
        }
        return root;
    }

    /** Structural equality, matching sqlglot: strings compare case-insensitively except raw-hashed types. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Expression other) || other.type != type) {
            return false;
        }
        return hashCode() == other.hashCode() && deepEquals(this, other);
    }

    private static boolean deepEquals(Expression a, Expression b) {
        if (a.type != b.type) {
            return false;
        }
        boolean raw = a.type.hashRawArgs();
        for (int i = 0; i < a.values.length; i++) {
            Object x = a.values[i];
            Object y = b.values[i];
            if (!valueEquals(x, y, raw)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNullish(Object v, boolean raw) {
        if (raw) {
            return !truthy(v);
        }
        return v == null || Boolean.FALSE.equals(v);
    }

    private static boolean valueEquals(Object x, Object y, boolean raw) {
        if (isNullish(x, raw) && isNullish(y, raw)) {
            return true;
        }
        if (isNullish(x, raw) || isNullish(y, raw)) {
            return false;
        }
        if (x instanceof Expression ex && y instanceof Expression ey) {
            return ex.hashCode() == ey.hashCode() && deepEquals(ex, ey);
        }
        if (x instanceof List<?> lx && y instanceof List<?> ly) {
            if (lx.size() != ly.size()) {
                return false;
            }
            for (int i = 0; i < lx.size(); i++) {
                Object a = lx.get(i);
                Object b = ly.get(i);
                if (raw) {
                    if (!Objects.equals(a, b)) {
                        return false;
                    }
                } else if (!valueEquals(a, b, false)) {
                    return false;
                }
            }
            return true;
        }
        if (!raw && x instanceof String sx && y instanceof String sy) {
            return sx.equalsIgnoreCase(sy);
        }
        return Objects.equals(x, y);
    }

    @Override
    public int hashCode() {
        if (hash != 0) {
            return hash;
        }
        int h = type.hashCode() * 31 + type.ordinal();
        boolean raw = type.hashRawArgs();
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            if (isNullish(v, raw)) {
                continue;
            }
            h = h * 31 + i;
            h = h * 31 + valueHash(v, raw);
        }
        if (h == 0) {
            h = 1;
        }
        hash = h;
        return h;
    }

    private static int valueHash(Object v, boolean raw) {
        if (v instanceof String s) {
            return raw ? s.hashCode() : s.toLowerCase(Locale.ROOT).hashCode();
        }
        if (v instanceof List<?> l) {
            int h = 1;
            for (Object o : l) {
                h = h * 31 + (isNullish(o, false) ? 0 : valueHash(o, raw));
            }
            return h;
        }
        return v.hashCode();
    }

    // ------------------------------------------------------------------------------------------
    // Builder methods (sqlglot's Expression/Query/Select builder API). Their signatures are the
    // contract other code uses.
    // Unless noted, these follow sqlglot's copy=True default and return a modified copy.

    private static List<Object> argList(Object[] expressions) {
        List<Object> out = new ArrayList<>();
        if (expressions != null) {
            Collections.addAll(out, expressions);
        }
        return out;
    }

    private static List<Object> withSelf(Expression self, Object[] expressions) {
        List<Object> out = new ArrayList<>();
        out.add(self);
        if (expressions != null) {
            Collections.addAll(out, expressions);
        }
        return out;
    }

    private UnsupportedOperationException noBuilder(String what) {
        return new UnsupportedOperationException("'" + type.className() + "' object has no builder '" + what + "'");
    }

    /** {@code query.select(*expressions, append=..., copy=...)}. */
    public Expression select(boolean append, boolean copy, Object... expressions) {
        if (type == SELECT) {
            return Exp.applyListBuilder(argList(expressions), this, Args.EXPRESSIONS, append, copy, null, EXPRESSION,
                    null);
        }
        if (is(SET_OPERATION)) {
            Expression thisExpr = Exp.maybeCopy(this, copy);
            thisExpr.getThis().unnest().select(append, false, expressions);
            thisExpr.getExpression().unnest().select(append, false, expressions);
            return thisExpr;
        }
        if (type == SUBQUERY) {
            Expression thisExpr = Exp.maybeCopy(this, copy);
            Expression inner = thisExpr.unnest();
            if (inner != null && inner.is(QUERY)) {
                inner.select(append, false, expressions);
            }
            return thisExpr;
        }
        if (is(QUERY)) {
            throw new UnsupportedOperationException("Query objects must implement `select`");
        }
        throw noBuilder("select");
    }

    /** {@code select.from_(expression, copy=...)}. */
    public Expression from(Object expression, boolean copy) {
        if (type == UPDATE) {
            if (!truthy(expression)) {
                return Exp.maybeCopy(this, copy);
            }
        } else if (type != SELECT) {
            throw noBuilder("from_");
        }
        return Exp.applyBuilder(expression, this, Args.FROM_, copy, "FROM", FROM, null, Args.THIS);
    }

    /** {@code query.where(*expressions, append=..., copy=...)}: AND-combines with any existing WHERE. */
    public Expression where(boolean append, boolean copy, Object... expressions) {
        List<Object> exprs;
        if (is(QUERY)) {
            exprs = new ArrayList<>();
            for (Object e : argList(expressions)) {
                exprs.add(e instanceof Expression w && w.is(WHERE) ? w.getThis() : e);
            }
        } else if (type == DELETE || type == UPDATE) {
            exprs = argList(expressions);
        } else {
            throw noBuilder("where");
        }
        return Exp.applyConjunctionBuilder(exprs, this, Args.WHERE, WHERE, append, copy, null);
    }

    /** {@code select.join(expression, on=..., using=..., join_type=..., join_alias=..., copy=...)}. */
    public Expression join(Object expression, Object on, List<Object> using, String joinType, String joinAlias, boolean copy) {
        if (type != SELECT) {
            throw noBuilder("join");
        }
        boolean append = true;

        Expression parsed;
        try {
            parsed = Exp.maybeParse(expression, JOIN, null, "JOIN", false);
        } catch (io.github.offbyjuan.jglot.errors.ParseError e) {
            parsed = Exp.maybeParseAny(expression, new ExprType[] {JOIN, EXPRESSION}, null, null, false);
        }

        Expression join = parsed.is(JOIN) ? parsed : Expression.of(JOIN, Args.THIS, parsed);
        Expression joinThis = join.getThis();
        if (joinThis != null && joinThis.is(SELECT)) {
            joinThis.replace(joinThis.subquery(null, true));
        }

        if (joinType != null && !joinType.isEmpty()) {
            Expression newJoin = Exp.maybeParse("FROM _ " + joinType + " JOIN _", null, null, null, false).find(JOIN);
            String method = newJoin.text(Args.METHOD).toUpperCase(Locale.ROOT);
            String side = newJoin.text(Args.SIDE).toUpperCase(Locale.ROOT);
            String kind = newJoin.text(Args.KIND).toUpperCase(Locale.ROOT);

            if (!method.isEmpty()) {
                join.set(Args.METHOD, method);
            }
            if (!side.isEmpty()) {
                join.set(Args.SIDE, side);
            }
            if (!kind.isEmpty()) {
                join.set(Args.KIND, kind);
            }
        }

        if (truthy(on)) {
            List<Object> onExprs = io.github.offbyjuan.jglot.util.Py.ensureList(on);
            Expression onNode = Exp.combineConditions(onExprs, AND, null, copy, true);
            join.set(Args.ON, onNode);
        }

        if (truthy(using)) {
            List<Object> usingExprs = io.github.offbyjuan.jglot.util.Py.ensureList(using);
            join = Exp.applyListBuilder(usingExprs, join, Args.USING, append, copy, null, IDENTIFIER, null);
        }

        if (joinAlias != null && !joinAlias.isEmpty()) {
            join.set(Args.THIS, Exp.tableAlias(join.getThis(), joinAlias, null, true));
        }

        List<Object> joins = new ArrayList<>();
        joins.add(join);
        return Exp.applyListBuilder(joins, this, Args.JOINS, append, copy, null, null, null);
    }

    /** {@code select.group_by(*expressions, append=..., copy=...)}. */
    public Expression groupBy(boolean append, boolean copy, Object... expressions) {
        if (type != SELECT) {
            throw noBuilder("group_by");
        }
        if (expressions == null || expressions.length == 0) {
            return copy ? copy() : this;
        }
        return Exp.applyChildListBuilder(argList(expressions), this, Args.GROUP, append, copy, "GROUP BY", GROUP, null,
                null);
    }

    /** {@code query.order_by(*expressions, append=..., copy=...)}. */
    public Expression orderBy(boolean append, boolean copy, Object... expressions) {
        if (!is(QUERY)) {
            throw noBuilder("order_by");
        }
        return Exp.applyChildListBuilder(argList(expressions), this, Args.ORDER, append, copy, "ORDER BY", ORDER, null,
                null);
    }

    /** {@code query.limit(expression, copy=...)}. */
    public Expression limit(Object expression, boolean copy) {
        if (!is(QUERY)) {
            throw noBuilder("limit");
        }
        return Exp.applyBuilder(expression, this, Args.LIMIT, copy, "LIMIT", LIMIT, null, Args.EXPRESSION);
    }

    /** {@code query.offset(expression, copy=...)}. */
    public Expression offset(Object expression, boolean copy) {
        if (!is(QUERY)) {
            throw noBuilder("offset");
        }
        return Exp.applyBuilder(expression, this, Args.OFFSET, copy, "OFFSET", OFFSET, null, Args.EXPRESSION);
    }

    /** {@code select.distinct(*ons, distinct=..., copy=...)}. */
    public Expression distinct(boolean distinct, boolean copy, Object... ons) {
        if (type != SELECT) {
            throw noBuilder("distinct");
        }
        Expression instance = Exp.maybeCopy(this, copy);
        Expression on = null;
        if (ons != null && ons.length > 0) {
            List<Expression> items = new ArrayList<>();
            for (Object o : ons) {
                if (truthy(o)) {
                    items.add(Exp.maybeParse(o, null, null, null, copy));
                }
            }
            on = Expression.of(TUPLE, Args.EXPRESSIONS, items);
        }
        instance.set(Args.DISTINCT, distinct ? Expression.of(DISTINCT, Args.ON, on) : null);
        return instance;
    }

    /** {@code query.with_(alias, as_, recursive=..., append=..., copy=...)}. */
    public Expression with(Object alias, Object as, boolean recursive, boolean append, boolean copy) {
        if (!is(QUERY) && type != INSERT && type != UPDATE) {
            throw noBuilder("with_");
        }
        // sqlglot's _apply_cte_builder (materialized=None, scalar=None)
        Expression aliasExpression = Exp.maybeParse(alias, TABLE_ALIAS, null, null, false);
        Expression asExpression = Exp.maybeParse(as, null, null, null, copy);
        Expression cte = Expression.of(CTE, Args.THIS, asExpression, Args.ALIAS, aliasExpression);
        Map<Arg, Object> properties = new java.util.LinkedHashMap<>();
        if (recursive) {
            properties.put(Args.RECURSIVE, Boolean.TRUE);
        }
        List<Object> ctes = new ArrayList<>();
        ctes.add(cte);
        return Exp.applyChildListBuilder(ctes, this, Args.WITH_, append, copy, null, WITH, null, properties);
    }

    /** {@code query.subquery(alias=..., copy=...)}: wraps the query in a SUBQUERY node. */
    public Expression subquery(Object alias, boolean copy) {
        Expression instance = Exp.maybeCopy(this, copy);
        Object aliasNode = alias;
        if (!(alias instanceof Expression)) {
            aliasNode = truthy(alias) ? Expression.of(TABLE_ALIAS, Args.THIS, Exp.toIdentifier(alias)) : null;
        }
        return Expression.of(SUBQUERY, Args.THIS, instance, Args.ALIAS, aliasNode);
    }

    /** {@code expression.as_(alias, quoted=..., copy=..., table=...)}. */
    public Expression as(Object alias, Boolean quoted, boolean copy) {
        return Exp.alias(this, alias, quoted, copy);
    }

    private Expression setOperation(ExprType kind, String name, boolean distinct, boolean copy, Object[] expressions) {
        List<Object> all = withSelf(this, expressions);
        if (all.size() < 2) {
            throw new AssertionError("At least two expressions are required by `" + name + "`.");
        }
        return Exp.applySetOperation(all, kind, distinct, null, copy);
    }

    /** {@code query.union(*expressions, distinct=..., copy=...)}. */
    public Expression union(boolean distinct, boolean copy, Object... expressions) {
        return setOperation(UNION, "union", distinct, copy, expressions);
    }

    /** {@code query.intersect(*expressions, distinct=..., copy=...)}. */
    public Expression intersect(boolean distinct, boolean copy, Object... expressions) {
        return setOperation(INTERSECT, "intersect", distinct, copy, expressions);
    }

    /** {@code query.except_(*expressions, distinct=..., copy=...)}. */
    public Expression except(boolean distinct, boolean copy, Object... expressions) {
        return setOperation(EXCEPT, "except_", distinct, copy, expressions);
    }

    /** {@code expression.and_(*expressions, copy=...)}. */
    public Expression and(boolean copy, Object... expressions) {
        return Exp.combineConditions(withSelf(this, expressions), AND, null, copy, true);
    }

    /** {@code expression.or_(*expressions, copy=...)}. */
    public Expression or(boolean copy, Object... expressions) {
        return Exp.combineConditions(withSelf(this, expressions), OR, null, copy, true);
    }

    /** {@code expression.not_(copy=...)}. */
    public Expression not(boolean copy) {
        Expression thisExpr = Exp.condition(this, null, copy);
        return Expression.of(NOT, Args.THIS, Exp.wrap(thisExpr, CONNECTOR));
    }

    /** sqlglot's {@code Expr._binop(klass, other)}: {@code klass(this=copy of self, expression=convert(other))}. */
    public Expression binop(ExprType klass, Object other) {
        return Exp.binop(klass, this, other, false);
    }

    /** sqlglot's {@code Expr._binop(klass, other, reverse=True)} (the {@code __radd__} family). */
    public Expression binop(ExprType klass, Object other, boolean reverse) {
        return Exp.binop(klass, this, other, reverse);
    }

    /** {@code expression.eq(other)}: a copy of this = other. */
    public Expression eq(Object other) {
        return Exp.binop(EQ, this, other, false);
    }

    /** {@code expression.neq(other)}. */
    public Expression neq(Object other) {
        return Exp.binop(NEQ, this, other, false);
    }

    /** {@code expression.is_(other)}: a copy of this IS other. */
    public Expression is_(Object other) {
        return Exp.binop(IS, this, other, false);
    }

    /** {@code expression.isin(*expressions, query=..., unnest=..., copy=...)}. */
    public Expression isin(Object query, Object unnest, boolean copy, Object... expressions) {
        Expression subquery = null;
        if (truthy(query)) {
            subquery = Exp.maybeParse(query, null, null, null, copy);
            if (subquery.is(QUERY)) {
                subquery = subquery.subquery(null, false);
            }
        }
        List<Object> unnestList = io.github.offbyjuan.jglot.util.Py.ensureList(unnest);

        Expression thisExpr = Exp.maybeCopy(this, copy);
        List<Expression> converted = new ArrayList<>();
        for (Object e : argList(expressions)) {
            converted.add(Exp.convert(e, copy));
        }
        Expression unnestNode = null;
        if (truthy(unnest)) {
            List<Expression> parsed = new ArrayList<>();
            for (Object e : unnestList) {
                parsed.add(Exp.maybeParse(e, null, null, null, copy));
            }
            unnestNode = Expression.of(UNNEST, Args.EXPRESSIONS, parsed);
        }
        return Expression.of(IN, Args.THIS, thisExpr, Args.EXPRESSIONS, converted, Args.QUERY, subquery, Args.UNNEST,
                unnestNode);
    }

    /** {@code expression.between(low, high, copy=...)}. */
    public Expression between(Object low, Object high, boolean copy) {
        return Expression.of(BETWEEN,
                Args.THIS, Exp.maybeCopy(this, copy),
                Args.LOW, Exp.convert(low, copy),
                Args.HIGH, Exp.convert(high, copy));
    }

    /**
     * {@code expression.is_type(*dtypes)}: for DATA_TYPE nodes (and casts/annotated nodes, via their
     * type) whether the type matches any of {@code dtypes} (DType values, type strings or DATA_TYPE
     * nodes). See {@link DataTypes#isType}.
     */
    public boolean isType(Object... dtypes) {
        // sqlglot: DataType.is_type compares the node itself; Expression.is_type only consults the
        // explicitly annotated type (never a cast's `to`).
        if (type.isCastType()) {
            // Cast.is_type checks the target type
            Expression to = expr(Args.TO);
            return to != null && DataTypes.isType(to, false, dtypes);
        }
        Expression dt = type.isDataTypeType() ? this : dataType;
        return dt != null && DataTypes.isType(dt, false, dtypes);
    }

    /** The explicitly annotated type (sqlglot's {@code _type}), without the cast {@code to} fallback. */
    public Expression rawDataType() {
        return dataType;
    }

    /** {@code expression.assert_is(type)}. */
    public Expression assertIs(ExprType t) {
        if (!is(t)) {
            throw new AssertionError(this + " is not " + t.className() + ".");
        }
        return this;
    }

    /** {@code column.to_dot(include_dots=...)}: converts a column into a Dot chain. */
    public Expression toDot(boolean includeDots) {
        if (!is(COLUMN)) {
            throw noBuilder("to_dot");
        }
        List<Expression> parts = new ArrayList<>(parts());
        Expression p = parent;

        if (includeDots) {
            while (p != null && p.is(DOT)) {
                parts.add(p.getExpression());
                p = p.parent;
            }
        }

        if (parts.size() > 1) {
            List<Expression> copies = new ArrayList<>(parts.size());
            for (Expression part : parts) {
                copies.add(part == null ? null : part.copy());
            }
            return Exp.dot(copies);
        }
        return parts.get(0);
    }

    /** {@code case.when(condition, then, copy=...)}. */
    public Expression when(Object condition, Object then, boolean copy) {
        if (type != CASE) {
            throw noBuilder("when");
        }
        Expression instance = Exp.maybeCopy(this, copy);
        instance.append(Args.IFS, Expression.of(IF,
                Args.THIS, Exp.maybeParse(condition, null, null, null, copy),
                Args.TRUE, Exp.maybeParse(then, null, null, null, copy)));
        return instance;
    }

    /** {@code case.else_(condition, copy=...)}. */
    public Expression elseCase(Object condition, boolean copy) {
        if (type != CASE) {
            throw noBuilder("else_");
        }
        Expression instance = Exp.maybeCopy(this, copy);
        instance.set(Args.DEFAULT, Exp.maybeParse(condition, null, null, null, copy));
        return instance;
    }

    /** {@code expression.sql(dialect)}: renders this node in {@code dialect}. */
    public String sql(io.github.offbyjuan.jglot.dialects.Dialect dialect) {
        return dialect.generate(this);
    }

    // ------------------------------------------------------------------------------------------
    // Debug output

    /** sqlglot-style tree dump, e.g. {@code Select(expressions=[Column(this=Identifier(...))])}. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        dump(sb, this, 0);
        return sb.toString();
    }

    private static void dump(StringBuilder sb, Object node, int level) {
        if (node instanceof Expression e) {
            sb.append(e.type.className()).append('(');
            boolean first = true;
            for (int i = 0; i < e.values.length; i++) {
                Object v = e.values[i];
                if (v == null || (v instanceof List<?> l && l.isEmpty())) {
                    continue;
                }
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(e.type.argAt(i).name()).append('=');
                dump(sb, v, level + 1);
            }
            sb.append(')');
        } else if (node instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                dump(sb, l.get(i), level + 1);
            }
            sb.append(']');
        } else if (node instanceof String s) {
            sb.append(s);
        } else {
            sb.append(node);
        }
    }
}
