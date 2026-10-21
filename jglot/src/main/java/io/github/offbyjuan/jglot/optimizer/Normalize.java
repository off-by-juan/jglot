package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.expressions.Exp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.logging.Logger;

/** sqlglot's {@code optimizer.normalize}. */
public final class Normalize {
    private static final Logger LOGGER = Logger.getLogger("jglot");

    private Normalize() {}

    /** {@code normalize(expression, dnf=..., max_distance=128)}. */
    public static Expression normalize(Expression expression, boolean dnf) {
        return normalize(expression, dnf, 128);
    }

    /**
     * Rewrite sqlglot AST into conjunctive normal form or disjunctive normal form.
     *
     * <p>Example: {@code (x AND y) OR z} becomes {@code (x OR z) AND (y OR z)}.
     *
     * @param expression expression to normalize
     * @param dnf rewrite in disjunctive normal form instead
     * @param maxDistance the maximal estimated distance from cnf/dnf to attempt conversion
     */
    public static Expression normalize(Expression expression, boolean dnf, int maxDistance) {
        List<Expression> nodes = new ArrayList<>();
        for (Expression n : expression.bfs(e -> e.is(ExprType.CONNECTOR))) {
            nodes.add(n);
        }

        for (Expression node : nodes) {
            if (node.is(ExprType.CONNECTOR)) {
                if (normalized(node, dnf)) {
                    continue;
                }
                boolean root = node == expression;
                Expression original = node.copy();

                node.transform(Normalize::rewriteBetween, false);
                long distance = normalizationDistance(node, dnf, maxDistance);

                if (distance > maxDistance) {
                    LOGGER.fine("Skipping normalization because distance " + distance + " exceeds max " + maxDistance);
                    return expression;
                }

                try {
                    final Expression current = node;
                    node = node.replace(whileChanging(current, e -> distributiveLaw(e, dnf, maxDistance)));
                } catch (OptimizeError e) {
                    LOGGER.fine(e.getMessage());
                    node.replace(original);
                    if (root) {
                        return original;
                    }
                    return expression;
                }

                if (root) {
                    expression = node;
                }
            }
        }

        return expression;
    }

    /**
     * Checks whether a given expression is in a normal form of interest: DNF if {@code dnf},
     * otherwise CNF.
     */
    public static boolean normalized(Expression expression, boolean dnf) {
        ExprType ancestor = dnf ? ExprType.AND : ExprType.OR;
        ExprType root = dnf ? ExprType.OR : ExprType.AND;
        for (Expression connector : Scopes.findAllInScope(expression, root)) {
            if (connector.findAncestor(ancestor) != null) {
                return false;
            }
        }
        return true;
    }

    /** {@code normalized(expression)} (CNF). */
    public static boolean normalized(Expression expression) {
        return normalized(expression, false);
    }

    /**
     * The difference in the number of predicates between a given expression and its normalized
     * form. This is used as an estimate of the cost of the conversion which is exponential in
     * complexity. E.g. {@code (a AND b) OR (c AND d)} gives 4.
     *
     * @param max stop early if count exceeds this
     */
    public static long normalizationDistance(Expression expression, boolean dnf, double max) {
        long[] total = {-(expression.findAll(ExprType.CONNECTOR).size() + 1)};

        try {
            predicateLengths(expression, dnf, max, 0, length -> {
                total[0] += length;
                if (total[0] > max) {
                    throw new StopIteration();
                }
            });
        } catch (StopIteration e) {
            return total[0];
        }

        return total[0];
    }

    public static long normalizationDistance(Expression expression, boolean dnf) {
        return normalizationDistance(expression, dnf, Double.POSITIVE_INFINITY);
    }

    /** Signals early termination of the {@code _predicate_lengths} generator. */
    private static final class StopIteration extends RuntimeException {
        StopIteration() {
            super(null, null, false, false);
        }
    }

    /**
     * Yields the predicate lengths when expanded to normalized form (a push-style port of the
     * Python generator). {@code (A AND B) OR C -> [2, 2]} because len(A OR C), len(B OR C).
     */
    private static void predicateLengths(Expression expression, boolean dnf, double max, int depth, IntConsumer yield) {
        if (depth > max) {
            yield.accept(depth);
            return;
        }

        expression = Scopes.unnest(expression);

        if (expression == null || !expression.is(ExprType.CONNECTOR)) {
            yield.accept(1);
            return;
        }

        int nextDepth = depth + 1;
        Expression left = expression.getThis();
        Expression right = expression.getExpression();

        if (expression.is(dnf ? ExprType.AND : ExprType.OR)) {
            predicateLengths(left, dnf, max, nextDepth, a -> {
                predicateLengths(right, dnf, max, nextDepth, b -> yield.accept(a + b));
            });
        } else {
            predicateLengths(left, dnf, max, nextDepth, yield);
            predicateLengths(right, dnf, max, nextDepth, yield);
        }
    }

    /**
     * {@code x OR (y AND z) -> (x OR y) AND (x OR z)};
     * {@code (x AND y) OR (y AND z) -> (x OR y) AND (x OR z) AND (y OR y) AND (y OR z)}.
     */
    public static Expression distributiveLaw(Expression expression, boolean dnf, double maxDistance) {
        if (normalized(expression, dnf)) {
            return expression;
        }

        long distance = normalizationDistance(expression, dnf, maxDistance);

        if (distance > maxDistance) {
            throw new OptimizeError("Normalization distance " + distance + " exceeds max " + formatMax(maxDistance));
        }

        Exp.replaceChildren(expression, e -> distributiveLaw(e, dnf, maxDistance));
        ExprType toExp = dnf ? ExprType.OR : ExprType.AND;
        ExprType fromExp = dnf ? ExprType.AND : ExprType.OR;

        if (expression.is(fromExp)) {
            List<Expression> operands = unnestOperands(expression);
            if (operands.size() != 2) {
                throw new IllegalStateException("expected 2 operands, got " + operands.size());
            }
            Expression a = operands.get(0);
            Expression b = operands.get(1);

            ExprType fromFunc = fromExp;
            ExprType toFunc = toExp;

            if (a.is(toExp) && b.is(toExp)) {
                if (a.findAll(ExprType.CONNECTOR).size() > b.findAll(ExprType.CONNECTOR).size()) {
                    return distribute(a, b, fromFunc, toFunc);
                }
                return distribute(b, a, fromFunc, toFunc);
            }
            if (a.is(toExp)) {
                return distribute(b, a, fromFunc, toFunc);
            }
            if (b.is(toExp)) {
                return distribute(a, b, fromFunc, toFunc);
            }
        }

        return expression;
    }

    /** Python formats the float/int {@code max_distance}; integral values print without a fraction. */
    private static String formatMax(double v) {
        if (Double.isInfinite(v)) {
            return v > 0 ? "inf" : "-inf";
        }
        if (v == Math.rint(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private static List<Expression> unnestOperands(Expression expression) {
        List<Expression> out = new ArrayList<>();
        for (Expression e : expression.iterExpressions()) {
            out.add(Scopes.unnest(e));
        }
        return out;
    }

    private static Expression distribute(Expression a, Expression b, ExprType fromFunc, ExprType toFunc) {
        // When `a` and `b` are connectors of the SAME polarity (e.g. both AND in
        // CNF mode), `b` is distributed across `a`'s children so the existing
        // cross-product handling can simplify them.
        if (a.is(ExprType.CONNECTOR) && a.is(b.type())) {
            Exp.replaceChildren(a, c -> combine(
                    toFunc,
                    uniqSort(flatten(combine(fromFunc, c, b.getThis()))),
                    uniqSort(flatten(combine(fromFunc, c, b.getExpression())))));
            return a;
        }

        // Otherwise apply the textbook rule
        // ``a OR (b.left AND b.right) -> (a OR b.left) AND (a OR b.right)`` (or
        // the AND/OR dual). When `a` is a connector of the OPPOSITE polarity to
        // `b` (e.g. `Or(...) ∨ And(...)` in CNF mode) the previous behaviour was
        // to push `b` into each child of `a`, leaving an intermediate
        // ``Or(And, And)`` that `while_changing` would later cross-product into
        // redundant superset and duplicate clauses. Returning the direct rewrite
        // avoids that detour.
        return combine(
                toFunc,
                uniqSort(flatten(combine(fromFunc, a, b.getThis()))),
                uniqSort(flatten(combine(fromFunc, a, b.getExpression()))));
    }

    // ------------------------------------------------------------------------------------------
    // Helpers from sqlglot.helper / sqlglot.expressions

    /** {@code helper.while_changing}: applies {@code func} until the hash stops changing. */
    private static Expression whileChanging(Expression expression, Function<Expression, Expression> func) {
        while (true) {
            int startHash = expression.hashCode();
            expression = func.apply(expression);
            int endHash = expression.hashCode();

            if (startHash == endHash) {
                break;
            }
        }

        return expression;
    }

    /**
     * {@code exp._combine(expressions, operator, copy=False, wrap=True)}: the first operand is only
     * wrapped when there is more than one.
     */
    private static Expression combine(ExprType kind, Expression... expressions) {
        List<Expression> conditions = new ArrayList<>();
        for (Expression e : expressions) {
            if (e != null) {
                conditions.add(e);
            }
        }
        Expression acc = conditions.get(0);
        if (conditions.size() > 1) {
            acc = Exp.wrap(acc, ExprType.CONNECTOR);
        }
        for (int i = 1; i < conditions.size(); i++) {
            acc = Expression.of(kind, Args.THIS, acc, Args.EXPRESSION, Exp.wrap(conditions.get(i), ExprType.CONNECTOR));
        }
        return acc;
    }

    // ------------------------------------------------------------------------------------------
    // Minimal helpers from sqlglot.optimizer.simplify (Simplifier(annotate_new_expressions=False))

    /** {@code simplify.flatten}: A AND (B AND C) -> A AND B AND C; A OR (B OR C) -> A OR B OR C. */
    private static Expression flatten(Expression expression) {
        if (expression.is(ExprType.CONNECTOR)) {
            for (Expression node : expression.iterExpressions()) {
                Expression child = Scopes.unnest(node);
                if (child != null && child.is(expression.type())) {
                    node.replace(child);
                }
            }
        }
        return expression;
    }

    /**
     * {@code Simplifier.rewrite_between}: rewrite x between y and z to x >= y AND x <= z. This is
     * done because comparison simplification is only done on lt/lte/gt/gte.
     */
    private static Expression rewriteBetween(Expression expression) {
        if (expression.is(ExprType.BETWEEN)) {
            boolean negate = expression.parent() != null && expression.parent().is(ExprType.NOT);

            expression = combine(
                    ExprType.AND,
                    Expression.of(ExprType.GTE, Args.THIS, expression.getThis().copy(), Args.EXPRESSION, expression.get(Args.LOW)),
                    Expression.of(ExprType.LTE, Args.THIS, expression.getThis().copy(), Args.EXPRESSION, expression.get(Args.HIGH)));

            if (negate) {
                expression = Exp.paren(expression);
            }
        }

        return expression;
    }

    /** {@code Simplifier.uniq_sort(expression, root=True)}: C AND A AND B AND B -> A AND B AND C. */
    private static Expression uniqSort(Expression expression) {
        if (expression.is(ExprType.CONNECTOR)) {
            List<Expression> flattened = expression.flatten(true);

            ExprType resultFunc;
            Map<String, Expression> deduped;
            List<Map.Entry<String, Expression>> arr = new ArrayList<>();
            if (expression.is(ExprType.XOR)) {
                resultFunc = ExprType.XOR;
                // Do not deduplicate XOR as A XOR A != A if A == True
                deduped = null;
                for (Expression e : flattened) {
                    arr.add(Map.entry(SimplifyGen.gen(e), e));
                }
            } else {
                resultFunc = expression.is(ExprType.AND) ? ExprType.AND : ExprType.OR;
                deduped = new LinkedHashMap<>();
                for (Expression e : flattened) {
                    deduped.put(SimplifyGen.gen(e), e);
                }
                arr.addAll(deduped.entrySet());
            }

            // check if the operands are already sorted, if not sort them
            // A AND C AND B -> A AND B AND C
            boolean sorted = false;
            for (int i = 0; i + 1 < arr.size(); i++) {
                if (SimplifyGen.compare(arr.get(i + 1).getKey(), arr.get(i).getKey()) < 0) {
                    List<Map.Entry<String, Expression>> sortedArr = new ArrayList<>(arr);
                    // Python sorts (sql, expr) tuples; ties on sql are only possible for XOR
                    sortedArr.sort((x, y) -> SimplifyGen.compare(x.getKey(), y.getKey()));
                    List<Expression> operands = new ArrayList<>();
                    for (Map.Entry<String, Expression> entry : sortedArr) {
                        operands.add(entry.getValue());
                    }
                    expression = combine(resultFunc, operands.toArray(new Expression[0]));
                    sorted = true;
                    break;
                }
            }
            if (!sorted) {
                // we didn't have to sort but maybe we need to dedup
                if (deduped != null && !deduped.isEmpty() && deduped.size() < flattened.size()) {
                    Expression uniqueOperand = flattened.get(0);
                    if (deduped.size() == 1) {
                        expression = uniqueOperand.and(false, Exp.bool(true));
                    } else {
                        expression = combine(resultFunc, deduped.values().toArray(new Expression[0]));
                    }
                }
            }
        }

        return expression;
    }

    // ------------------------------------------------------------------------------------------
    // simplify.gen / simplify.Gen

    /**
     * {@code simplify.Gen}: a simple pseudo sql generator for quickly generating sortable and uniq
     * strings. Sorting and deduping sql is a necessary step for optimization. Calling the actual
     * generator is expensive so we have a bare minimum sql generator here.
     */
    private static final class SimplifyGen {
        private final List<Object> stack = new ArrayList<>();
        private final StringBuilder sqls = new StringBuilder();

        static String gen(Expression expression) {
            return new SimplifyGen().run(expression, false);
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
}
