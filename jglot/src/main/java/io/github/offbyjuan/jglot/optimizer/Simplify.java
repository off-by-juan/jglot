package io.github.offbyjuan.jglot.optimizer;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.dialects.data.DialectData;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.optimizer.PyDates.Delta;
import io.github.offbyjuan.jglot.optimizer.PyDates.PyDateTime;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * sqlglot's {@code optimizer.simplify}: rewrites a sqlglot AST to simplify expressions.
 *
 * <p>Python's {@code datetime}/{@code relativedelta} arithmetic is done with {@link PyDates}
 * ({@code java.time}); Python's {@code int}/{@code Decimal} literal arithmetic with
 * {@link BigInteger}/{@link BigDecimal} (Decimal's default context: 28 digits, ROUND_HALF_EVEN).
 *
 * <p>An instance holds the per-run type annotator and is not thread-safe; {@link #simplify} creates
 * one per call.
 */
public final class Simplify {
    // Final means that an expression should not be simplified
    static final String FINAL = "final";

    // Value ranges for byte-sized signed/unsigned integers
    private static final BigInteger TINYINT_MIN = BigInteger.valueOf(-128);
    private static final BigInteger TINYINT_MAX = BigInteger.valueOf(127);
    private static final BigInteger UTINYINT_MIN = BigInteger.ZERO;
    private static final BigInteger UTINYINT_MAX = BigInteger.valueOf(255);

    private static final MathContext DECIMAL_CONTEXT = new MathContext(28, RoundingMode.HALF_EVEN);

    private final Dialect dialect;
    private final DialectData dd;
    private final boolean annotateNewExpressions;
    private final TypeAnnotator annotator;

    private Simplify(Dialect dialect, boolean annotateNewExpressions) {
        this.dialect = dialect == null ? Dialects.base() : dialect;
        this.dd = this.dialect.data();
        this.annotateNewExpressions = annotateNewExpressions;
        this.annotator = new TypeAnnotator(this.dialect, false);
    }

    /** {@code simplify(expression, dialect=dialect)}. */
    public static Expression simplify(Expression expression, Dialect dialect) {
        return simplify(expression, false, false, dialect);
    }

    /** {@code simplify(expression, constant_propagation, coalesce_simplification, dialect)}. */
    public static Expression simplify(Expression expression, boolean constantPropagation,
            boolean coalesceSimplification, Dialect dialect) {
        return new Simplify(dialect, true).run(expression, constantPropagation, coalesceSimplification);
    }

    /** sqlglot's {@code UnsupportedUnit}. */
    static final class UnsupportedUnit extends RuntimeException {
        UnsupportedUnit(String message) {
            super(message, null, false, false);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Node builders (sqlglot's exp.and_/or_/paren/... with copy=False)

    /** sqlglot's {@code _combine(expressions, operator, copy=False, wrap=True)}. */
    private static Expression combine(ExprType operator, List<Expression> expressions) {
        List<Expression> conditions = new ArrayList<>();
        for (Expression e : expressions) {
            if (e != null) {
                conditions.add(e);
            }
        }
        Expression thisExpr = conditions.get(0);
        if (conditions.size() > 1) {
            thisExpr = Exp.wrap(thisExpr, ExprType.CONNECTOR);
        }
        for (int i = 1; i < conditions.size(); i++) {
            thisExpr = Expression.of(operator, Args.THIS, thisExpr,
                    Args.EXPRESSION, Exp.wrap(conditions.get(i), ExprType.CONNECTOR));
        }
        return thisExpr;
    }

    private static Expression and(Expression... expressions) {
        return combine(ExprType.AND, List.of(expressions));
    }

    private static Expression or(Expression... expressions) {
        return combine(ExprType.OR, List.of(expressions));
    }

    private static Expression not(Expression e) {
        return Expression.of(ExprType.NOT, Args.THIS, Exp.wrap(e, ExprType.CONNECTOR));
    }

    private static Expression paren(Expression e) {
        return Expression.of(ExprType.PAREN, Args.THIS, e);
    }

    private static Expression binary(ExprType type, Expression thisExpr, Expression expression) {
        return Expression.of(type, Args.THIS, thisExpr, Args.EXPRESSION, expression);
    }

    private static Expression trueExpr() {
        return Exp.bool(true);
    }

    private static Expression falseExpr() {
        return Exp.bool(false);
    }

    private static boolean isFinal(Expression e) {
        return Expression.truthy(e.metaGet(FINAL));
    }

    // ------------------------------------------------------------------------------------------
    // annotate_types_on_change

    /**
     * sqlglot's {@code annotate_types_on_change} decorator: annotates a changed result so that new
     * children are typed too, and keeps the original expression's type.
     */
    private Expression onChange(Expression expression, Expression newExpression) {
        if (newExpression == null) {
            return null;
        }

        if (annotateNewExpressions && !expression.equals(newExpression)) {
            annotator.clear();

            // We annotate this to ensure new children nodes are also annotated
            newExpression = annotator.annotate(newExpression, false);

            // Whatever expression the original expression is transformed into needs to preserve
            // the original type, otherwise the simplification could result in a different schema
            newExpression.setDataType(expression.dataType());
        }

        return newExpression;
    }

    // ------------------------------------------------------------------------------------------
    // Module-level helpers

    /** A AND (B AND C) -> A AND B AND C; A OR (B OR C) -> A OR B OR C. */
    static Expression flatten(Expression expression) {
        if (expression.is(ExprType.CONNECTOR)) {
            for (Expression node : expression.iterExpressions()) {
                Expression child = node.unnest();
                if (child.is(expression.type())) {
                    node.replace(child);
                }
            }
        }
        return expression;
    }

    static Expression simplifyParens(Expression expression, Dialect dialect) {
        if (!expression.is(ExprType.PAREN)) {
            return expression;
        }

        Expression thisExpr = expression.getThis();
        Expression parent = expression.parent();
        boolean parentIsPredicate = parent != null && parent.is(ExprType.PREDICATE);

        if (thisExpr != null && thisExpr.is(ExprType.SELECT)) {
            return expression;
        }

        if (parent != null && parent.is(ExprType.SUBQUERY_PREDICATE, ExprType.BRACKET)) {
            return expression;
        }

        if (dialect.data().REQUIRES_PARENTHESIZED_STRUCT_ACCESS
                && parent != null && parent.is(ExprType.DOT)
                && parent.getExpression() != null
                && parent.getExpression().is(ExprType.IDENTIFIER, ExprType.STAR)) {
            return expression;
        }

        if (thisExpr != null && thisExpr.is(ExprType.PREDICATE, ExprType.NOT)) {
            if (parentIsPredicate
                    // unary operators that bind tighter than the predicate, unlike NOT
                    || (parent != null && parent.is(ExprType.NEG, ExprType.BITWISE_NOT))
                    || (parent != null && parent.is(ExprType.BINARY) && !parent.is(ExprType.CONNECTOR))) {
                return expression;
            }
            return thisExpr;
        }

        if (parent == null
                || !parent.is(ExprType.CONDITION, ExprType.BINARY)
                || parent.is(ExprType.PAREN)
                || thisExpr == null || !thisExpr.is(ExprType.BINARY)
                || (thisExpr.is(ExprType.ADD) && parent.is(ExprType.ADD))
                || (thisExpr.is(ExprType.MUL) && parent.is(ExprType.MUL))
                || (thisExpr.is(ExprType.MUL) && parent.is(ExprType.ADD, ExprType.SUB))) {
            return thisExpr;
        }

        return expression;
    }

    /**
     * Propagate constants for conjunctions in DNF:
     *
     * <p>SELECT * FROM t WHERE a = b AND b = 5 becomes SELECT * FROM t WHERE a = 5 AND b = 5
     */
    static Expression propagateConstants(Expression expression, boolean root) {
        if (expression.is(ExprType.AND)
                && (root || !expression.sameParent())
                && Normalize.normalized(expression, true)) {
            Map<Expression, Expression[]> constantMapping = new HashMap<>();
            for (Expression expr : Scopes.walkInScope(expression, node -> node.is(ExprType.IF))) {
                if (expr.is(ExprType.EQ)) {
                    Expression l = expr.getThis();
                    Expression r = expr.getExpression();

                    // Substituting the constant is only an identity when the column can't be NULL:
                    // for a NULL x, `x = 1 AND x + 1 = 0` is NULL, not FALSE
                    if (l != null && l.is(ExprType.COLUMN) && r != null && r.is(ExprType.LITERAL)
                            && TypeAnnotator.nonnull(l)) {
                        constantMapping.put(l, new Expression[] {l, r});
                    }
                }
            }

            if (!constantMapping.isEmpty()) {
                for (Expression column : Scopes.findAllInScope(expression, ExprType.COLUMN)) {
                    Expression parent = column.parent();
                    Expression[] mapped = constantMapping.get(column);
                    if (mapped != null
                            && column != mapped[0]
                            && !(parent != null && parent.is(ExprType.IS)
                                    && parent.getExpression() != null && parent.getExpression().is(ExprType.NULL))) {
                        column.replace(mapped[1].copy());
                    }
                }
            }
        }

        return expression;
    }

    private static boolean isNumber(Expression expression) {
        return expression.isNumber();
    }

    private static boolean isInterval(Expression expression) {
        return expression.is(ExprType.INTERVAL) && extractInterval(expression) != null;
    }

    private static boolean isNonnullConstant(Expression expression) {
        return expression.is(ExprType.LITERAL, ExprType.BOOLEAN) || isDateLiteral(expression);
    }

    private static boolean isConstant(Expression expression) {
        Expression expr = expression.is(ExprType.NEG) ? expression.getThis() : expression;
        return expr != null && (expr.is(ExprType.LITERAL, ExprType.BOOLEAN, ExprType.NULL) || isDateLiteral(expr));
    }

    /**
     * Get the date range for a DATE_TRUNC equality comparison, e.g. (2021-01-01, 'year') ->
     * [2021-01-01, 2022-01-01). Returns null if a value can never be equal to {@code date} for {@code unit}.
     */
    private static PyDateTime[] datetruncRange(PyDateTime date, String unit, Dialect dialect) {
        PyDateTime floor = datetimeFloor(date, unit, dialect);

        if (!date.equals(floor)) {
            // This will always be False, except for NULL values.
            return null;
        }

        return new PyDateTime[] {floor, floor.plus(interval(unit, 1))};
    }

    /** Get the logical expression for a date range. */
    private static Expression datetruncEqExpression(Expression left, PyDateTime[] drange, Expression targetType) {
        return and(
                left.binop(ExprType.GTE, dateLiteral(drange[0], targetType)),
                left.binop(ExprType.LT, dateLiteral(drange[1], targetType)));
    }

    private static Expression datetruncEq(Expression left, PyDateTime date, String unit, Dialect dialect,
            Expression targetType) {
        PyDateTime[] drange = datetruncRange(date, unit, dialect);
        if (drange == null) {
            return null;
        }

        return datetruncEqExpression(left, drange, targetType);
    }

    private static Expression datetruncNeq(Expression left, PyDateTime date, String unit, Dialect dialect,
            Expression targetType) {
        PyDateTime[] drange = datetruncRange(date, unit, dialect);
        if (drange == null) {
            return null;
        }

        return or(
                left.binop(ExprType.LT, dateLiteral(drange[0], targetType)),
                left.binop(ExprType.GTE, dateLiteral(drange[1], targetType)));
    }

    /**
     * The generator flattens nested connectors and relies on Paren nodes for grouping. Operator
     * precedence varies across dialects, so wrap unless the parent is the same connector type, in
     * which case flattening is safe by associativity.
     */
    private static Expression parenthesizeNestedConnector(Expression expression, Expression parent) {
        if (expression.is(ExprType.CONNECTOR) && parent != null
                && (parent.is(ExprType.NOT) || (parent.is(ExprType.CONNECTOR) && parent.type() != expression.type()))) {
            return paren(expression);
        }

        return expression;
    }

    static boolean alwaysTrue(Expression expression) {
        return expression != null
                && ((expression.is(ExprType.BOOLEAN) && Expression.truthy(expression.thisValue()))
                        || (expression.is(ExprType.LITERAL) && expression.isNumber() && !isZero(expression)));
    }

    static boolean alwaysFalse(Expression expression) {
        return isFalse(expression) || isNull(expression) || isZero(expression);
    }

    static boolean isZero(Expression expression) {
        if (expression == null || !expression.is(ExprType.LITERAL)) {
            return false;
        }
        Object v = expression.toPy();
        return (v instanceof BigInteger bi && bi.signum() == 0) || (v instanceof BigDecimal bd && bd.signum() == 0);
    }

    static boolean isFalse(Expression a) {
        return a != null && a.type() == ExprType.BOOLEAN && !Expression.truthy(a.thisValue());
    }

    static boolean isNull(Expression a) {
        return a != null && a.type() == ExprType.NULL;
    }

    // ------------------------------------------------------------------------------------------
    // Python value semantics (int / Decimal / str / date / datetime)

    private static BigDecimal toDecimal(Object n) {
        return n instanceof BigInteger bi ? new BigDecimal(bi) : (BigDecimal) n;
    }

    private static Object numAdd(Object a, Object b) {
        if (a instanceof BigInteger x && b instanceof BigInteger y) {
            return x.add(y);
        }
        return toDecimal(a).add(toDecimal(b), DECIMAL_CONTEXT);
    }

    private static Object numSub(Object a, Object b) {
        if (a instanceof BigInteger x && b instanceof BigInteger y) {
            return x.subtract(y);
        }
        return toDecimal(a).subtract(toDecimal(b), DECIMAL_CONTEXT);
    }

    private static Object numMul(Object a, Object b) {
        if (a instanceof BigInteger x && b instanceof BigInteger y) {
            return x.multiply(y);
        }
        return toDecimal(a).multiply(toDecimal(b), DECIMAL_CONTEXT);
    }

    private static Object numDiv(Object a, Object b) {
        return toDecimal(a).divide(toDecimal(b), DECIMAL_CONTEXT);
    }

    /** Python {@code a == b} for the values compared by the simplifier. */
    private static boolean pyEq(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return toDecimal(a).compareTo(toDecimal(b)) == 0;
        }
        return a == null ? b == null : a.equals(b);
    }

    /** Python ordering for the values compared by the simplifier. */
    private static int pyCmp(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return toDecimal(a).compareTo(toDecimal(b));
        }
        if (a instanceof String x && b instanceof String y) {
            return SimplifyGen.compare(x, y);
        }
        if (a instanceof PyDateTime x && b instanceof PyDateTime y) {
            return x.compareTo(y);
        }
        throw new IllegalArgumentException("'<' not supported between these values");
    }

    private static Expression evalBoolean(Expression expression, Object a, Object b) {
        if (expression.is(ExprType.EQ, ExprType.IS)) {
            if (expression.is(ExprType.IS) && expression.has(Args.NEGATE)) {
                return booleanLiteral(!pyEq(a, b));
            }
            return booleanLiteral(pyEq(a, b));
        }
        if (expression.is(ExprType.NEQ)) {
            return booleanLiteral(!pyEq(a, b));
        }
        if (expression.is(ExprType.GT)) {
            return booleanLiteral(pyCmp(a, b) > 0);
        }
        if (expression.is(ExprType.GTE)) {
            return booleanLiteral(pyCmp(a, b) >= 0);
        }
        if (expression.is(ExprType.LT)) {
            return booleanLiteral(pyCmp(a, b) < 0);
        }
        if (expression.is(ExprType.LTE)) {
            return booleanLiteral(pyCmp(a, b) <= 0);
        }
        return null;
    }

    /** {@code cast_as_date}: a PyDateTime or an ISO string. */
    static PyDateTime castAsDate(Object value) {
        if (value instanceof PyDateTime d) {
            return d.toDate();
        }
        PyDateTime dt = PyDates.datetimeFromIsoformat((String) value);
        return dt == null ? null : dt.toDate();
    }

    static PyDateTime castAsDatetime(Object value) {
        if (value instanceof PyDateTime d) {
            return d.toDatetime();
        }
        return PyDates.datetimeFromIsoformat((String) value);
    }

    static PyDateTime castValue(Object value, Expression to) {
        if (value == null || (value instanceof String s && s.isEmpty())) {
            return null;
        }
        if (DataTypes.isType(to, false, DType.DATE)) {
            return castAsDate(value);
        }
        if (TypeAnnotator.dataTypeIn(to, DataTypes.TEMPORAL_TYPES)) {
            return castAsDatetime(value);
        }
        return null;
    }

    static PyDateTime extractDate(Expression cast) {
        Expression to;
        if (cast.is(ExprType.CAST)) {
            to = cast.expr(Args.TO);
        } else if (cast.is(ExprType.TS_OR_DS_TO_DATE) && !cast.has(Args.FORMAT)) {
            to = DataTypes.of(DType.DATE);
        } else {
            return null;
        }
        if (to == null) {
            return null;
        }

        Expression thisExpr = cast.getThis();
        Object value;
        if (thisExpr != null && thisExpr.is(ExprType.LITERAL)) {
            value = thisExpr.name();
        } else if (thisExpr != null && thisExpr.is(ExprType.CAST, ExprType.TS_OR_DS_TO_DATE)) {
            value = extractDate(thisExpr);
        } else {
            return null;
        }
        return castValue(value, to);
    }

    private static boolean isDateLiteral(Expression expression) {
        return extractDate(expression) != null;
    }

    /** Python's {@code int(value)} for the value of {@code to_py()}; null where Python raises ValueError. */
    private static BigInteger pyInt(Object value) {
        if (value instanceof BigInteger bi) {
            return bi;
        }
        if (value instanceof BigDecimal bd) {
            return bd.toBigInteger();
        }
        if (value instanceof Boolean b) {
            return b ? BigInteger.ONE : BigInteger.ZERO;
        }
        if (value instanceof String s) {
            String t = s.strip();
            if (t.isEmpty()) {
                return null;
            }
            int i = 0;
            StringBuilder digits = new StringBuilder(t.length());
            if (t.charAt(0) == '+' || t.charAt(0) == '-') {
                digits.append(t.charAt(0));
                i = 1;
            }
            boolean prevDigit = false;
            for (; i < t.length(); i++) {
                char c = t.charAt(i);
                if (Character.isDigit(c)) {
                    digits.append(Character.digit(c, 10));
                    prevDigit = true;
                } else if (c == '_' && prevDigit && i + 1 < t.length() && Character.isDigit(t.charAt(i + 1))) {
                    prevDigit = false;
                } else {
                    return null;
                }
            }
            if (!prevDigit) {
                return null;
            }
            return new BigInteger(digits.toString());
        }
        if (value == null) {
            throw new IllegalArgumentException("int() argument must be a string, a bytes-like object or a real number, not 'NoneType'");
        }
        return null;
    }

    static Delta extractInterval(Expression expression) {
        try {
            Expression thisExpr = expression.getThis();
            if (thisExpr == null) {
                return null;
            }
            BigInteger n = pyInt(thisExpr.toPy());
            if (n == null) {
                return null;
            }
            String unit = expression.text(Args.UNIT).toLowerCase(Locale.ROOT);
            return interval(unit, n.longValueExact());
        } catch (UnsupportedUnit | IllegalArgumentException | ArithmeticException e) {
            return null;
        }
    }

    private static boolean isExactIntervalMove(Expression op, Expression literal, Expression interval) {
        Delta delta = extractInterval(interval);
        PyDateTime value = extractDate(literal);

        if (delta == null || value == null) {
            return false;
        }

        if (delta.months == 0 && delta.years == 0) {
            return true;
        }

        if (op.is(ExprType.SUB, ExprType.DATE_SUB, ExprType.DATETIME_SUB)) {
            delta = delta.negate();
        }

        PyDateTime moved = value.minus(delta);

        // Exact iff `moved` is the only x with x + delta = value: it must map back onto `value`, and the
        // next day must not, since clamping folds the last days of a longer month onto the same date
        return moved.plus(delta).equals(value) && !moved.plusDays(1).plus(delta).equals(value);
    }

    /** {@code extract_type(*expressions)}. */
    private static Expression extractType(Expression... expressions) {
        Expression targetType = null;
        for (Expression expression : expressions) {
            targetType = expression.is(ExprType.CAST) ? expression.expr(Args.TO) : expression.dataType();
            if (targetType != null) {
                break;
            }
        }

        return targetType;
    }

    /** {@code date_literal(date, target_type)}: CAST('<date>' AS <type>). */
    static Expression dateLiteral(PyDateTime date, Expression targetType) {
        Expression dataType;
        if (targetType == null || !TypeAnnotator.dataTypeIn(targetType, DataTypes.TEMPORAL_TYPES)) {
            dataType = DataTypes.of(date.isDate ? DType.DATE : DType.DATETIME);
        } else {
            dataType = targetType.copy();
        }

        Expression cast = Expression.of(ExprType.CAST, Args.THIS, Exp.string(date.toString()), Args.TO, dataType);
        // sqlglot's exp.cast sets the cast's type to `to`; a copy keeps TypeAnnotator.rawType exact
        cast.setDataType(dataType.copy());
        return cast;
    }

    static Delta interval(String unit, long n) {
        return switch (unit) {
            case "year" -> new Delta(n, 0, 0, 0, 0, 0, 0, 0);
            case "quarter" -> new Delta(0, 3 * n, 0, 0, 0, 0, 0, 0);
            case "month" -> new Delta(0, n, 0, 0, 0, 0, 0, 0);
            case "week" -> new Delta(0, 0, n, 0, 0, 0, 0, 0);
            case "day" -> new Delta(0, 0, 0, n, 0, 0, 0, 0);
            case "hour" -> new Delta(0, 0, 0, 0, n, 0, 0, 0);
            case "minute" -> new Delta(0, 0, 0, 0, 0, n, 0, 0);
            case "second" -> new Delta(0, 0, 0, 0, 0, 0, n, 0);
            case "millisecond" -> new Delta(0, 0, 0, 0, 0, 0, 0, 1000 * n);
            case "microsecond" -> new Delta(0, 0, 0, 0, 0, 0, 0, n);
            default -> throw new UnsupportedUnit("Unsupported unit: " + unit);
        };
    }

    private static boolean isZeroDelta(Delta d) {
        return d.years == 0 && d.months == 0 && d.days == 0 && !d.hasTime();
    }

    static PyDateTime datetimeFloor(PyDateTime d, String unit, Dialect dialect) {
        // Truncate sub-day units — only valid for datetime inputs
        if (!d.isDate) {
            switch (unit) {
                case "hour":
                    return d.with(d.value.withMinute(0).withSecond(0).withNano(0));
                case "minute":
                    return d.with(d.value.withSecond(0).withNano(0));
                case "second":
                    return d.with(d.value.withNano(0));
                case "millisecond":
                    return d.with(d.value.withNano((d.microsecond() / 1000) * 1000 * 1000));
                case "microsecond":
                    return d;
                default:
                    break;
            }
        }

        // Truncate date-level units, shared for both date and datetime
        PyDateTime result;
        switch (unit) {
            case "year" -> result = d.with(d.value.withMonth(1).withDayOfMonth(1));
            case "quarter" -> {
                int month = d.month() <= 3 ? 1 : d.month() <= 6 ? 4 : d.month() <= 9 ? 7 : 10;
                result = d.with(d.value.withDayOfMonth(1).withMonth(month));
            }
            case "month" -> result = d.with(d.value.withDayOfMonth(1));
            // Week truncation respects dialect.WEEK_OFFSET (0=Monday, -1=Sunday)
            case "week" -> result = d.plusDays(-Math.floorMod(d.weekday() - dialect.data().WEEK_OFFSET, 7));
            case "day" -> result = d;
            default -> throw new UnsupportedUnit("Unsupported unit: " + unit);
        }

        // For datetime inputs, zero out the time component after date-level truncation
        if (!result.isDate) {
            return result.with(result.value.toLocalDate().atStartOfDay());
        }
        return result;
    }

    private static final Map<String, Integer> WEEK_START_DAY_TO_DOW = Map.of(
            "MONDAY", 1, "TUESDAY", 2, "WEDNESDAY", 3, "THURSDAY", 4, "FRIDAY", 5, "SATURDAY", 6, "SUNDAY", 7);

    private static String truncUnit(Expression unit, Dialect dialect) {
        if (unit.is(ExprType.WEEK_START)) {
            Integer dow = WEEK_START_DAY_TO_DOW.get(unit.name().toUpperCase(Locale.ROOT));
            if (dow == null || dow != Math.floorMod(dialect.data().WEEK_OFFSET, 7) + 1) {
                throw new UnsupportedUnit("Unsupported unit: " + unit);
            }
            return "week";
        }

        return unit.name().toLowerCase(Locale.ROOT);
    }

    static PyDateTime dateCeil(PyDateTime d, String unit, Dialect dialect) {
        PyDateTime floor = datetimeFloor(d, unit, dialect);

        if (floor.equals(d)) {
            return d;
        }

        return floor.plus(interval(unit, 1));
    }

    private static Expression booleanLiteral(boolean condition) {
        return condition ? trueExpr() : falseExpr();
    }

    // ------------------------------------------------------------------------------------------
    // Class-level tables

    private static ExprType complementComparison(ExprType t) {
        return switch (t) {
            case LT -> ExprType.GTE;
            case GT -> ExprType.LTE;
            case LTE -> ExprType.GT;
            case GTE -> ExprType.LT;
            case EQ -> ExprType.NEQ;
            case NEQ -> ExprType.EQ;
            default -> null;
        };
    }

    private static ExprType inverseComparison(ExprType t) {
        return switch (t) {
            case LT -> ExprType.GT;
            case GT -> ExprType.LT;
            case LTE -> ExprType.GTE;
            case GTE -> ExprType.LTE;
            default -> t;
        };
    }

    private static boolean isComparison(Expression e) {
        return e != null && e.is(ExprType.LT, ExprType.LTE, ExprType.GT, ExprType.GTE, ExprType.EQ, ExprType.NEQ, ExprType.IS);
    }

    private static boolean isLtLte(Expression e) {
        return e.is(ExprType.LT, ExprType.LTE);
    }

    private static boolean isGtGte(Expression e) {
        return e.is(ExprType.GT, ExprType.GTE);
    }

    /** {@code INVERSE_DATE_OPS} (exact types). */
    private static ExprType inverseDateOp(ExprType t) {
        return switch (t) {
            case DATE_ADD, DATETIME_ADD -> ExprType.SUB;
            case DATE_SUB, DATETIME_SUB -> ExprType.ADD;
            default -> null;
        };
    }

    /** {@code INVERSE_OPS} (exact types). */
    private static ExprType inverseOp(ExprType t) {
        return switch (t) {
            case ADD -> ExprType.SUB;
            case SUB -> ExprType.ADD;
            default -> inverseDateOp(t);
        };
    }

    private static boolean isDatetruncComparison(ExprType t) {
        return switch (t) {
            case IN, LT, GT, LTE, GTE, EQ, NEQ -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------------------------------
    // Simplifier

    private static boolean isSimplifiable(Expression e) {
        return e.is(ExprType.BINARY, ExprType.FUNC, ExprType.LAMBDA, ExprType.PREDICATE, ExprType.UNARY);
    }

    /** sqlglot's {@code helper.while_changing}. */
    private Expression whileChanging(Expression expression, boolean constantPropagation, boolean coalesceSimplification) {
        while (true) {
            int startHash = expression.hashCode();
            expression = simplifyNode(expression, constantPropagation, coalesceSimplification);
            int endHash = expression.hashCode();

            if (startHash == endHash) {
                break;
            }
        }

        return expression;
    }

    private Expression run(Expression expression, boolean constantPropagation, boolean coalesceSimplification) {
        List<Expression> wheres = new ArrayList<>();
        List<Expression> joins = new ArrayList<>();

        for (Expression node : expression.bfs(n -> n.is(ExprType.CONDITION) || isFinal(n))) {
            if (isFinal(node)) {
                continue;
            }

            // group by expressions cannot be simplified, for example
            // select x + 1 + 1 FROM y GROUP BY x + 1 + 1
            // the projection must exactly match the group by key
            Expression group = node.expr(Args.GROUP);

            if (group != null && node.is(ExprType.QUERY)) {
                Set<Expression> groups = new HashSet<>(group.getExpressions());
                group.meta().put(FINAL, Boolean.TRUE);

                for (Expression s : node.selects()) {
                    for (Expression n : s.walk()) {
                        if (groups.contains(n)) {
                            s.meta().put(FINAL, Boolean.TRUE);
                            break;
                        }
                    }
                }

                Expression having = node.expr(Args.HAVING);

                if (having != null) {
                    for (Expression n : having.walk()) {
                        if (groups.contains(n)) {
                            having.meta().put(FINAL, Boolean.TRUE);
                            break;
                        }
                    }
                }
            }

            if (node.is(ExprType.CONDITION)) {
                Expression simplified = whileChanging(node, constantPropagation, coalesceSimplification);

                if (node == expression) {
                    expression = simplified;
                }
            } else if (node.is(ExprType.WHERE)) {
                wheres.add(node);
            } else if (node.is(ExprType.JOIN)) {
                // snowflake match_conditions have very strict ordering rules
                Expression match = node.expr(Args.MATCH_CONDITION);
                if (match != null) {
                    match.meta().put(FINAL, Boolean.TRUE);
                }

                joins.add(node);
            }
        }

        for (Expression where : wheres) {
            if (alwaysTrue(where.getThis()) && !(where.parent() != null && where.parent().is(ExprType.FILTER))) {
                where.pop();
            }
        }
        for (Expression join : joins) {
            // Only an inner join can become a CROSS JOIN: a cross join is empty as soon as either
            // side is empty, whereas an outer join keeps the rows of its outer side and pads them
            // with NULLs (`x LEFT JOIN y ON TRUE` returns x's rows when y is empty, `x RIGHT JOIN y
            // ON TRUE` returns y's rows when x is empty).
            String kind = join.text(Args.KIND).toUpperCase(Locale.ROOT);
            if (alwaysTrue(join.expr(Args.ON))
                    && !join.has(Args.USING)
                    && !join.has(Args.METHOD)
                    && join.text(Args.SIDE).isEmpty()
                    && (kind.isEmpty() || kind.equals("INNER"))) {
                join.expr(Args.ON).pop();
                join.set(Args.SIDE, null);
                join.set(Args.KIND, "CROSS");
            }
        }

        return expression;
    }

    private record PostItem(Expression node, Expression parent) {}

    private Expression simplifyNode(Expression expression, boolean constantPropagation, boolean coalesceSimplification) {
        ArrayList<Expression> preTransformationStack = new ArrayList<>();
        preTransformationStack.add(expression);
        ArrayList<PostItem> postTransformationStack = new ArrayList<>();

        while (!preTransformationStack.isEmpty()) {
            Expression original = preTransformationStack.remove(preTransformationStack.size() - 1);
            Expression node = original;

            if (!isSimplifiable(node)) {
                if (node.is(ExprType.QUERY)) {
                    run(node, constantPropagation, coalesceSimplification);
                }
                continue;
            }

            Expression parent = node.parent();
            boolean root = node == expression;

            node = rewriteBetween(node);
            node = uniqSort(node, root);
            node = absorbAndEliminate(node, root);
            node = simplifyConcat(node);
            node = simplifyConditionals(node);

            if (constantPropagation) {
                node = propagateConstants(node, root);
            }

            if (node != original) {
                original.replace(node);
            }

            List<Expression> children = node.iterExpressions();
            for (int i = children.size() - 1; i >= 0; i--) {
                Expression n = children.get(i);
                if (!isFinal(n)) {
                    preTransformationStack.add(n);
                }
            }
            postTransformationStack.add(new PostItem(node, parent));
        }

        Expression node = expression;
        while (!postTransformationStack.isEmpty()) {
            PostItem item = postTransformationStack.remove(postTransformationStack.size() - 1);
            Expression original = item.node();
            Expression parent = item.parent();
            boolean root = original == expression;

            // Resets parent, arg_key, index pointers– this is needed because some of the
            // previous transformations mutate the AST, leading to an inconsistent state.
            for (Map.Entry<Arg, Object> entry : original.argEntries()) {
                original.set(entry.getKey(), entry.getValue());
            }

            // Post-order transformations
            node = simplifyNot(original);
            node = flatten(node);
            node = simplifyConnectors(node, root);
            node = removeComplements(node, root);

            if (coalesceSimplification) {
                node = simplifyCoalesce(node);
            }
            // sqlglot sets `node.parent = parent` here so that the remaining rules see the node in
            // its final position. jglot has no bare parent setter, so the node is put in place now
            // (for the root, a reused child is detached from the discarded original instead).
            Expression placed = original;
            if (node != original) {
                if (parent != null && original.parent() == parent) {
                    original.replace(node);
                    placed = node;
                } else if (parent == null && node.parent() != null) {
                    node.pop();
                }
            }

            node = simplifyLiterals(node, root);
            node = simplifyEquality(node);
            node = simplifyParens(node, dialect);
            node = simplifyDatetrunc(node);
            node = sortComparison(node);
            node = simplifyStartswith(node);

            if (node != placed) {
                placed.replace(node);
            }
        }

        return node;
    }

    /**
     * Rewrite x between y and z to x >= y AND x <= z. This is done because comparison simplification
     * is only done on lt/lte/gt/gte.
     */
    Expression rewriteBetween(Expression expression) {
        Expression result = expression;
        if (expression.is(ExprType.BETWEEN)) {
            boolean negate = expression.parent() != null && expression.parent().is(ExprType.NOT);

            result = and(
                    binary(ExprType.GTE, expression.getThis().copy(), expression.expr(Args.LOW)),
                    binary(ExprType.LTE, expression.getThis().copy(), expression.expr(Args.HIGH)));

            if (negate) {
                result = paren(result);
            }
        }

        return onChange(expression, result);
    }

    /** Demorgan's Law: NOT (x OR y) -> NOT x AND NOT y; NOT (x AND y) -> NOT x OR NOT y. */
    Expression simplifyNot(Expression expression) {
        return onChange(expression, simplifyNotImpl(expression));
    }

    private Expression simplifyNotImpl(Expression expression) {
        if (expression.is(ExprType.NOT)) {
            Expression thisExpr = expression.getThis();
            if (isNull(thisExpr)) {
                return parenthesizeNestedConnector(and(Exp.nul(), trueExpr()), expression.parent());
            }
            ExprType complement = thisExpr != null ? complementComparison(thisExpr.type()) : null;
            if (complement != null) {
                Expression right = thisExpr.getExpression();
                if (right != null && right.type() == ExprType.ALL) {
                    right = Expression.of(ExprType.ANY, Args.THIS, right.getThis());
                } else if (right != null && right.type() == ExprType.ANY) {
                    right = Expression.of(ExprType.ALL, Args.THIS, right.getThis());
                }

                return paren(binary(complement, thisExpr.getThis(), right));
            }
            if (thisExpr != null && thisExpr.is(ExprType.PAREN)) {
                Expression condition = thisExpr.unnest();
                if (condition.is(ExprType.AND)) {
                    return paren(or(not(condition.getThis()), not(condition.getExpression())));
                }
                if (condition.is(ExprType.OR)) {
                    return paren(and(not(condition.getThis()), not(condition.getExpression())));
                }
                if (isNull(condition)) {
                    return parenthesizeNestedConnector(and(Exp.nul(), trueExpr()), expression.parent());
                }
            }
            if (alwaysTrue(thisExpr)) {
                return falseExpr();
            }
            if (isFalse(thisExpr)) {
                return trueExpr();
            }
            if (thisExpr != null && thisExpr.is(ExprType.NOT) && dd.SAFE_TO_ELIMINATE_DOUBLE_NEGATION) {
                Expression inner = thisExpr.getThis();
                if (TypeAnnotator.isType(inner, DType.BOOLEAN)) {
                    // double negation
                    // NOT NOT x -> x, if x is BOOLEAN type
                    return inner;
                }
            }
        }
        return expression;
    }

    @FunctionalInterface
    private interface PairSimplifier {
        Expression apply(Expression expression, Expression a, Expression b);
    }

    private Expression simplifyConnectorsPair(Expression expression, Expression left, Expression right) {
        if (expression.is(ExprType.AND)) {
            if (isFalse(left) || isFalse(right)) {
                return falseExpr();
            }
            if (isZero(left) || isZero(right)) {
                return falseExpr();
            }
            if ((isNull(left) && isNull(right))
                    || (isNull(left) && alwaysTrue(right))
                    || (alwaysTrue(left) && isNull(right))) {
                return Exp.nul();
            }
            if (alwaysTrue(left) && alwaysTrue(right)) {
                return trueExpr();
            }
            if (alwaysTrue(left)) {
                return right;
            }
            if (alwaysTrue(right)) {
                return left;
            }
            return simplifyComparison(expression, left, right, false);
        } else if (expression.is(ExprType.OR)) {
            if (alwaysTrue(left) || alwaysTrue(right)) {
                return trueExpr();
            }
            if ((isNull(left) && isNull(right))
                    || (isNull(left) && alwaysFalse(right))
                    || (alwaysFalse(left) && isNull(right))) {
                return Exp.nul();
            }
            if (isFalse(left)) {
                return right;
            }
            if (isFalse(right)) {
                return left;
            }
            return simplifyComparison(expression, left, right, true);
        }
        return null;
    }

    Expression simplifyConnectors(Expression expression, boolean root) {
        Expression result = expression;
        if (expression.is(ExprType.CONNECTOR)) {
            Expression originalParent = expression.parent();
            result = flatSimplify(expression, this::simplifyConnectorsPair, root);

            // If we reduced a connector to, e.g., a column (t1 AND ... AND tn -> Tk), then we need
            // to ensure that the resulting type is boolean. We know this is true only for connectors,
            // boolean values and columns that are essentially operands to a connector:
            //
            // A AND (((B)))
            //          ~ this is safe to keep because it will eventually be part of another connector
            if (!result.is(ExprType.CONNECTOR, ExprType.BOOLEAN) && !TypeAnnotator.isType(result, DType.BOOLEAN)) {
                while (true) {
                    if (originalParent != null && originalParent.is(ExprType.CONNECTOR)) {
                        break;
                    }
                    if (originalParent == null || !originalParent.is(ExprType.PAREN)) {
                        result = and(result, trueExpr());
                        break;
                    }

                    originalParent = originalParent.parent();
                }
            }
        }

        return onChange(expression, result);
    }

    private Expression simplifyComparison(Expression expression, Expression left, Expression right, boolean or) {
        return onChange(expression, simplifyComparisonImpl(expression, left, right, or));
    }

    private Expression simplifyComparisonImpl(Expression expression, Expression left, Expression right, boolean or) {
        if (isComparison(left) && isComparison(right)) {
            for (Expression e : List.of(left, right)) {
                if (e.is(ExprType.IS) && e.has(Args.NEGATE)) {
                    return null;
                }
            }

            Expression ll = left.getThis();
            Expression lr = left.getExpression();
            Expression rl = right.getThis();
            Expression rr = right.getExpression();

            Set<Expression> largs = new java.util.LinkedHashSet<>(java.util.Arrays.asList(ll, lr));
            Set<Expression> rargs = new java.util.LinkedHashSet<>(java.util.Arrays.asList(rl, rr));

            Set<Expression> matching = new java.util.LinkedHashSet<>(largs);
            matching.retainAll(rargs);
            Set<Expression> columns = new java.util.LinkedHashSet<>();
            for (Expression m : matching) {
                if (m != null && !isConstant(m) && m.find(ExprType.RAND, ExprType.RANDN) == null) {
                    columns.add(m);
                }
            }

            if (!matching.isEmpty() && !columns.isEmpty()) {
                Expression lExpr = firstNotIn(largs, columns);
                Expression rExpr = firstNotIn(rargs, columns);
                if (lExpr == null || rExpr == null) {
                    return expression;
                }

                Object l;
                Object r;
                if (lExpr.isNumber() && rExpr.isNumber()) {
                    l = lExpr.toPy();
                    r = rExpr.toPy();
                } else if (lExpr.isString() && rExpr.isString()) {
                    l = lExpr.name();
                    r = rExpr.name();
                } else {
                    PyDateTime ld = extractDate(lExpr);
                    if (ld == null) {
                        return null;
                    }
                    PyDateTime rd = extractDate(rExpr);
                    if (rd == null) {
                        return null;
                    }
                    // python won't compare date and datetime, but many engines will upcast
                    l = castAsDatetime(ld);
                    r = castAsDatetime(rd);
                }

                Expression false_ = TypeAnnotator.nonnull(left) && TypeAnnotator.nonnull(right) ? falseExpr() : null;

                Expression[][] perms = {{left, right}, {right, left}};
                Object[][] permValues = {{l, r}, {r, l}};
                for (int p = 0; p < 2; p++) {
                    Expression a = perms[p][0];
                    Expression b = perms[p][1];
                    Object av = permValues[p][0];
                    Object bv = permValues[p][1];

                    if (isLtLte(a) && isLtLte(b)) {
                        return (or ? pyCmp(av, bv) > 0 : pyCmp(av, bv) <= 0) ? left : right;
                    }
                    if (isGtGte(a) && isGtGte(b)) {
                        return (or ? pyCmp(av, bv) < 0 : pyCmp(av, bv) >= 0) ? left : right;
                    }

                    if (!or) {
                        if (a.is(ExprType.LT) && isGtGte(b)) {
                            if (pyCmp(av, bv) <= 0) {
                                return false_;
                            }
                        } else if (a.is(ExprType.GT) && isLtLte(b)) {
                            if (pyCmp(av, bv) >= 0) {
                                return false_;
                            }
                        } else if (a.is(ExprType.EQ)) {
                            if (b.is(ExprType.LT)) {
                                return pyCmp(av, bv) >= 0 ? false_ : a;
                            }
                            if (b.is(ExprType.LTE)) {
                                return pyCmp(av, bv) > 0 ? false_ : a;
                            }
                            if (b.is(ExprType.GT)) {
                                return pyCmp(av, bv) <= 0 ? false_ : a;
                            }
                            if (b.is(ExprType.GTE)) {
                                return pyCmp(av, bv) < 0 ? false_ : a;
                            }
                            if (b.is(ExprType.NEQ)) {
                                return pyEq(av, bv) ? false_ : a;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static Expression firstNotIn(Set<Expression> set, Set<Expression> exclude) {
        for (Expression e : set) {
            if (!exclude.contains(e)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Removing complements. A AND NOT A -> FALSE (only for non-NULL A); A OR NOT A -> TRUE (only for
     * non-NULL A).
     */
    Expression removeComplements(Expression expression, boolean root) {
        Expression result = expression;
        if (expression.is(ExprType.AND, ExprType.OR) && (root || !expression.sameParent())) {
            Set<Expression> ops = new HashSet<>(expression.flattenList());
            for (Expression op : ops) {
                if (op.is(ExprType.NOT) && ops.contains(op.getThis())) {
                    if (TypeAnnotator.nonnull(expression)) {
                        result = expression.is(ExprType.AND) ? falseExpr() : trueExpr();
                        break;
                    }
                }
            }
        }

        return onChange(expression, result);
    }

    /** Uniq and sort a connector. C AND A AND B AND B -> A AND B AND C. */
    Expression uniqSort(Expression expression, boolean root) {
        Expression result = expression;
        if (expression.is(ExprType.CONNECTOR) && (root || !expression.sameParent())) {
            List<Expression> flattened = expression.flattenList();

            ExprType resultFunc;
            LinkedHashMap<String, Expression> deduped;
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
                    sortedArr.sort((x, y) -> SimplifyGen.compare(x.getKey(), y.getKey()));
                    List<Expression> operands = new ArrayList<>();
                    for (Map.Entry<String, Expression> entry : sortedArr) {
                        operands.add(entry.getValue());
                    }
                    result = combine(resultFunc, operands);
                    sorted = true;
                    break;
                }
            }
            if (!sorted) {
                // we didn't have to sort but maybe we need to dedup
                if (deduped != null && !deduped.isEmpty() && deduped.size() < flattened.size()) {
                    Expression uniqueOperand = flattened.get(0);
                    if (deduped.size() == 1) {
                        result = and(uniqueOperand, trueExpr());
                    } else {
                        result = combine(resultFunc, new ArrayList<>(deduped.values()));
                    }
                }
            }
        }

        return onChange(expression, result);
    }

    /**
     * absorption: A AND (A OR B) -> A; A OR (A AND B) -> A; A AND (NOT A OR B) -> A AND B (only for
     * non-NULL A); A OR (NOT A AND B) -> A OR B (only for non-NULL A).
     *
     * <p>elimination: (A AND B) OR (A AND NOT B) -> A (only for non-NULL B); (A OR B) AND (A OR NOT B)
     * -> A (only for non-NULL B).
     */
    Expression absorbAndEliminate(Expression expression, boolean root) {
        if (expression.is(ExprType.AND, ExprType.OR) && (root || !expression.sameParent())) {
            ExprType kind = expression.is(ExprType.AND) ? ExprType.OR : ExprType.AND;

            List<Expression> ops = expression.flattenList();

            // Set of all operands, used to find complements for absorption.
            Set<Expression> opSet = new HashSet<>();
            // Sub-operands, used to find subsets for absorption.
            Map<Expression, List<Set<Expression>>> subops = new HashMap<>();
            // Pairs of complements, used for elimination.
            Map<Set<Expression>, List<Expression[]>> pairs = new HashMap<>();

            // Populate the lookup tables
            for (Expression op : ops) {
                opSet.add(op);

                if (!op.is(kind)) {
                    // In cases like: A OR (A AND B)
                    // Subop will be: ^
                    Set<Expression> single = new HashSet<>();
                    single.add(op);
                    subops.computeIfAbsent(op, k -> new ArrayList<>()).add(single);
                    continue;
                }

                // In cases like: (A AND B) OR (A AND B AND C)
                // Subops will be: ^     ^
                Set<Expression> subset = new HashSet<>(op.flattenList());
                for (Expression i : subset) {
                    subops.computeIfAbsent(i, k -> new ArrayList<>()).add(subset);
                }

                List<Expression> operands = op.unnestOperands();
                Expression a = operands.get(0);
                Expression b = operands.get(1);

                if (a.is(ExprType.NOT) && TypeAnnotator.nonnull(a.getThis())) {
                    pairs.computeIfAbsent(setOf(a.getThis(), b), k -> new ArrayList<>()).add(new Expression[] {op, b});
                }
                if (b.is(ExprType.NOT) && TypeAnnotator.nonnull(b.getThis())) {
                    pairs.computeIfAbsent(setOf(a, b.getThis()), k -> new ArrayList<>()).add(new Expression[] {op, a});
                }
            }

            for (Expression op : ops) {
                if (!op.is(kind)) {
                    continue;
                }

                List<Expression> operands = op.unnestOperands();
                Expression a = operands.get(0);
                Expression b = operands.get(1);

                // Absorb
                if (a.is(ExprType.NOT) && opSet.contains(a.getThis()) && TypeAnnotator.nonnull(a.getThis())) {
                    a.replace(kind == ExprType.AND ? trueExpr() : falseExpr());
                    continue;
                }
                if (b.is(ExprType.NOT) && opSet.contains(b.getThis()) && TypeAnnotator.nonnull(b.getThis())) {
                    b.replace(kind == ExprType.AND ? trueExpr() : falseExpr());
                    continue;
                }
                Set<Expression> superset = new HashSet<>(op.flattenList());
                boolean absorbed = false;
                for (Expression i : superset) {
                    for (Set<Expression> subset : subops.getOrDefault(i, List.of())) {
                        if (subset.size() < superset.size() && superset.containsAll(subset)) {
                            absorbed = true;
                            break;
                        }
                    }
                    if (absorbed) {
                        break;
                    }
                }
                if (absorbed) {
                    op.replace(kind == ExprType.AND ? falseExpr() : trueExpr());
                    continue;
                }

                // Eliminate
                for (Expression[] pair : pairs.getOrDefault(setOf(a, b), List.of())) {
                    Expression other = pair[0];
                    Expression complement = pair[1];
                    op.replace(complement);
                    other.replace(complement);
                }
            }
        }

        return onChange(expression, expression);
    }

    private static Set<Expression> setOf(Expression a, Expression b) {
        Set<Expression> s = new HashSet<>();
        s.add(a);
        s.add(b);
        return s;
    }

    /**
     * Use the subtraction and addition properties of equality to simplify expressions: x + 1 = 3
     * becomes x = 2. When the variable is the subtrahend the comparison is inverted instead: 5 - x = 2
     * becomes x = 3, 5 - x < 2 becomes x > 3.
     */
    Expression simplifyEquality(Expression expression) {
        Expression result;
        try {
            result = simplifyEqualityImpl(expression);
        } catch (UnsupportedUnit e) {
            result = expression;
        }
        return onChange(expression, result);
    }

    @FunctionalInterface
    private interface ExprPredicate {
        boolean test(Expression e);
    }

    private Expression simplifyEqualityImpl(Expression expression) {
        if (isComparison(expression)) {
            Expression l = expression.getThis();
            Expression r = expression.getExpression();

            if (l == null || r == null || inverseOp(l.type()) == null) {
                return expression;
            }

            ExprPredicate aPredicate;
            ExprPredicate bPredicate;
            if (r.isNumber()) {
                aPredicate = Simplify::isNumber;
                bPredicate = Simplify::isNumber;
            } else if (isDateLiteral(r)) {
                aPredicate = Simplify::isDateLiteral;
                bPredicate = Simplify::isInterval;
            } else {
                return expression;
            }

            Expression a;
            Expression b;
            if (inverseDateOp(l.type()) != null) {
                a = l.getThis();
                b = intervalOf(l);
            } else {
                a = l.getThis();
                b = l.getExpression();
            }

            if (!aPredicate.test(a) && bPredicate.test(b)) {
                // pass
            } else if (!aPredicate.test(b) && bPredicate.test(a)) {
                if (l.is(ExprType.SUB)) {
                    return binary(inverseComparison(expression.type()), b, binary(ExprType.SUB, a, r));
                }
                Expression tmp = a;
                a = b;
                b = tmp;
            } else {
                return expression;
            }

            if (b.is(ExprType.INTERVAL) && !isExactIntervalMove(l, r, b)) {
                return expression;
            }

            return binary(expression.type(), a, binary(inverseOp(l.type()), r, b));
        }
        return expression;
    }

    /** {@code IntervalOp.interval()}. */
    private static Expression intervalOf(Expression op) {
        Expression expr = op.getExpression();
        Expression unit = op.unit();
        return Expression.of(ExprType.INTERVAL,
                Args.THIS, expr != null ? expr.copy() : null,
                Args.UNIT, unit != null ? unit.copy() : null);
    }

    Expression simplifyLiterals(Expression expression, boolean root) {
        Expression result = expression;
        if (expression.is(ExprType.BINARY) && !expression.is(ExprType.CONNECTOR)) {
            result = flatSimplify(expression, this::simplifyBinary, root);
        } else if (expression.is(ExprType.NEG) && expression.getThis() != null && expression.getThis().is(ExprType.NEG)) {
            result = expression.getThis().getThis();
        } else if (inverseDateOp(expression.type()) != null) {
            Expression simplified = simplifyBinary(expression, expression.getThis(), intervalOf(expression));
            result = simplified != null ? simplified : expression;
        }

        return onChange(expression, result);
    }

    private Expression simplifyIntegerCast(Expression expr) {
        Expression thisExpr;
        if (expr.is(ExprType.CAST) && expr.getThis() != null && expr.getThis().is(ExprType.CAST)) {
            thisExpr = simplifyIntegerCast(expr.getThis());
        } else {
            thisExpr = expr.getThis();
        }

        if (expr.is(ExprType.CAST) && thisExpr != null && thisExpr.isInt()) {
            BigInteger num = (BigInteger) thisExpr.toPy();
            Object to = expr.expr(Args.TO) != null ? expr.expr(Args.TO).thisValue() : null;

            // Remove the (up)cast from small (byte-sized) integers in predicates which is side-effect free. Downcasts on any
            // integer type might cause overflow, thus the cast cannot be eliminated and the behavior is
            // engine-dependent
            if ((TINYINT_MIN.compareTo(num) <= 0 && num.compareTo(TINYINT_MAX) <= 0
                    && DataTypes.SIGNED_INTEGER_TYPES.contains(to))
                    || (UTINYINT_MIN.compareTo(num) <= 0 && num.compareTo(UTINYINT_MAX) <= 0
                            && DataTypes.UNSIGNED_INTEGER_TYPES.contains(to))) {
                return thisExpr;
            }
        }

        return expr;
    }

    private Expression simplifyBinary(Expression expression, Expression a, Expression b) {
        if (isComparison(expression)) {
            a = simplifyIntegerCast(a);
            b = simplifyIntegerCast(b);
        }

        if (expression.is(ExprType.IS)) {
            Expression c;
            boolean not_;
            if (b.is(ExprType.NOT)) {
                c = b.getThis();
                not_ = true;
            } else {
                c = b;
                not_ = false;
            }

            if (expression.has(Args.NEGATE)) {
                not_ = !not_;
            }

            if (isNull(c)) {
                if (a.is(ExprType.LITERAL)) {
                    return not_ ? trueExpr() : falseExpr();
                }
                if (isNull(a)) {
                    return not_ ? falseExpr() : trueExpr();
                }
            }
        } else if (expression.is(ExprType.NULL_SAFE_EQ, ExprType.NULL_SAFE_NEQ, ExprType.PROPERTY_EQ)) {
            return null;
        } else if ((isNull(a) || isNull(b)) && expression.parent() != null && expression.parent().is(ExprType.IF)) {
            return Exp.nul();
        }

        if (a.isNumber() && b.isNumber()) {
            Object numA = a.toPy();
            Object numB = b.toPy();

            if (expression.is(ExprType.ADD)) {
                return Exp.number(numAdd(numA, numB));
            }
            if (expression.is(ExprType.MUL)) {
                return Exp.number(numMul(numA, numB));
            }

            // We only simplify Sub, Div if a and b have the same parent because they're not associative
            if (expression.is(ExprType.SUB)) {
                return a.parent() == b.parent() ? Exp.number(numSub(numA, numB)) : null;
            }
            if (expression.is(ExprType.DIV)) {
                // engines have differing int div behavior so intdiv is not safe
                if ((numA instanceof BigInteger && numB instanceof BigInteger) || a.parent() != b.parent()) {
                    return null;
                }
                return Exp.number(numDiv(numA, numB));
            }

            Expression bool = evalBoolean(expression, numA, numB);

            if (bool != null) {
                return bool;
            }
        } else if (a.isString() && b.isString()) {
            Expression bool = evalBoolean(expression, a.thisValue(), b.thisValue());

            if (bool != null) {
                return bool;
            }
        } else if (isDateLiteral(a) && b.is(ExprType.INTERVAL)) {
            PyDateTime date = extractDate(a);
            Delta delta = extractInterval(b);
            if (date != null && delta != null && !isZeroDelta(delta)) {
                if (expression.is(ExprType.ADD, ExprType.DATE_ADD, ExprType.DATETIME_ADD)) {
                    return dateLiteral(date.plus(delta), extractType(a));
                }
                if (expression.is(ExprType.SUB, ExprType.DATE_SUB, ExprType.DATETIME_SUB)) {
                    return dateLiteral(date.minus(delta), extractType(a));
                }
            }
        } else if (a.is(ExprType.INTERVAL) && isDateLiteral(b)) {
            Delta delta = extractInterval(a);
            PyDateTime date = extractDate(b);
            // you cannot subtract a date from an interval
            if (delta != null && !isZeroDelta(delta) && expression.is(ExprType.ADD)) {
                return dateLiteral(date.plus(delta), extractType(b));
            }
        } else if (isDateLiteral(a) && isDateLiteral(b)) {
            if (expression.is(ExprType.PREDICATE)) {
                Expression bool = evalBoolean(expression, extractDate(a), extractDate(b));
                if (bool != null) {
                    return bool;
                }
            }
        }

        return null;
    }

    Expression simplifyCoalesce(Expression expression) {
        return onChange(expression, simplifyCoalesceImpl(expression));
    }

    private Expression simplifyCoalesceImpl(Expression expression) {
        // COALESCE(x) -> x
        if (expression.is(ExprType.COALESCE)
                && (expression.getExpressions().isEmpty() || isNonnullConstant(expression.getThis()))
                // COALESCE is also used as a Spark partitioning hint
                && !(expression.parent() != null && expression.parent().is(ExprType.HINT))) {
            return expression.getThis();
        }

        if (dd.COALESCE_COMPARISON_NON_STANDARD) {
            return expression;
        }

        if (!isComparison(expression)) {
            return expression;
        }

        Expression coalesce;
        Expression other;
        if (expression.getThis() != null && expression.getThis().is(ExprType.COALESCE)) {
            coalesce = expression.getThis();
            other = expression.getExpression();
        } else if (expression.getExpression() != null && expression.getExpression().is(ExprType.COALESCE)) {
            coalesce = expression.getExpression();
            other = expression.getThis();
        } else {
            return expression;
        }

        // This transformation is valid for non-constants,
        // but it really only does anything if they are both constants.
        if (!isConstant(other)) {
            return expression;
        }

        // Find the first non-NULL constant arg
        List<Expression> coalesceArgs = coalesce.getExpressions();
        int argIndex = -1;
        for (int i = 0; i < coalesceArgs.size(); i++) {
            if (isNonnullConstant(coalesceArgs.get(i))) {
                argIndex = i;
                break;
            }
        }
        if (argIndex < 0) {
            return expression;
        }
        Expression arg = coalesceArgs.get(argIndex);

        coalesce.set(Args.EXPRESSIONS, new ArrayList<>(coalesceArgs.subList(0, argIndex)));

        // Remove the COALESCE function. This is an optimization, skipping a simplify iteration,
        // since we already remove COALESCE at the top of this function.
        Expression thisExpr = !coalesce.getExpressions().isEmpty() ? coalesce : coalesce.getThis();

        // The constant takes the COALESCE's side of the comparison
        Expression substituted = expression.copy();
        substituted.set(coalesce == expression.getThis() ? Args.THIS : Args.EXPRESSION, arg.copy());

        // This expression is more complex than when we started, but it will get simplified further
        return paren(or(
                and(not(thisExpr.binop(ExprType.IS, Exp.nul())), expression.copy()),
                and(thisExpr.binop(ExprType.IS, Exp.nul()), substituted)));
    }

    /** Reduces all groups that contain string literals by concatenating them. */
    Expression simplifyConcat(Expression expression) {
        return onChange(expression, simplifyConcatImpl(expression));
    }

    private Expression simplifyConcatImpl(Expression expression) {
        if (!expression.is(ExprType.CONCAT, ExprType.D_PIPE)
                // We can't reduce a CONCAT_WS call if we don't statically know the separator
                || (expression.is(ExprType.CONCAT_WS) && !expression.getExpressions().get(0).isString())) {
            return expression;
        }

        List<Expression> expressions;
        String sep;
        ExprType concatType;
        Expression sepExpr = null;
        if (expression.is(ExprType.CONCAT_WS)) {
            List<Expression> all = expression.getExpressions();
            sepExpr = all.get(0);
            expressions = all.subList(1, all.size());
            sep = sepExpr.name();
            concatType = ExprType.CONCAT_WS;
        } else {
            expressions = expression.getExpressions();
            sep = "";
            concatType = ExprType.CONCAT;
        }

        Object safe = expression.get(Args.SAFE);
        Object coalesce = expression.get(Args.COALESCE);

        List<Expression> source = !expressions.isEmpty() ? expressions : expression.flatten(false);
        List<Expression> newArgs = new ArrayList<>();
        int i = 0;
        while (i < source.size()) {
            boolean isStringGroup = source.get(i).isString();
            int j = i;
            while (j < source.size() && source.get(j).isString() == isStringGroup) {
                j++;
            }
            if (isStringGroup) {
                StringBuilder sb = new StringBuilder();
                for (int k = i; k < j; k++) {
                    if (k > i) {
                        sb.append(sep);
                    }
                    sb.append(source.get(k).name());
                }
                newArgs.add(Exp.string(sb.toString()));
            } else {
                newArgs.addAll(source.subList(i, j));
            }
            i = j;
        }

        if (newArgs.size() == 1 && newArgs.get(0).isString()) {
            return newArgs.get(0);
        }

        if (concatType == ExprType.CONCAT_WS) {
            List<Expression> withSep = new ArrayList<>();
            withSep.add(sepExpr);
            withSep.addAll(newArgs);
            newArgs = withSep;
        } else if (expression.is(ExprType.D_PIPE)) {
            Expression acc = newArgs.get(0);
            for (int k = 1; k < newArgs.size(); k++) {
                acc = Expression.of(ExprType.D_PIPE, Args.THIS, acc, Args.EXPRESSION, newArgs.get(k), Args.SAFE, safe);
            }
            return acc;
        }

        return Expression.of(concatType, Args.EXPRESSIONS, newArgs, Args.SAFE, safe, Args.COALESCE, coalesce);
    }

    /** Simplifies expressions like IF, CASE if their condition is statically known. */
    Expression simplifyConditionals(Expression expression) {
        return onChange(expression, simplifyConditionalsImpl(expression));
    }

    @SuppressWarnings("unchecked")
    private Expression simplifyConditionalsImpl(Expression expression) {
        if (expression.is(ExprType.CASE)) {
            Expression thisExpr = expression.getThis();
            // Python iterates the live `ifs` list, so popping an entry skips the next one
            Object ifsValue = expression.get(Args.IFS);
            List<Object> ifs = ifsValue instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
            for (int idx = 0; idx < ifs.size(); idx++) {
                Expression caseExpr = (Expression) ifs.get(idx);
                Expression cond = caseExpr.getThis();
                if (thisExpr != null) {
                    // Convert CASE x WHEN matching_value ... to CASE WHEN x = matching_value ...
                    cond = cond.replace(thisExpr.pop().eq(cond));
                }

                if (alwaysTrue(cond)) {
                    if (caseExpr.index() == 0) {
                        return paren(caseExpr.expr(Args.TRUE));
                    }

                    expression.set(Args.DEFAULT, caseExpr.get(Args.TRUE));
                    expression.set(Args.IFS, new ArrayList<>(expression.exprs(Args.IFS).subList(0, caseExpr.index())));
                    break;
                }

                if (alwaysFalse(cond)) {
                    caseExpr.pop();
                    if (expression.exprs(Args.IFS).isEmpty()) {
                        Expression dflt = expression.expr(Args.DEFAULT);
                        return paren(dflt != null ? dflt : Exp.nul());
                    }
                }
                Object live = expression.get(Args.IFS);
                if (live != ifs && live instanceof List<?> l) {
                    ifs = (List<Object>) l;
                }
            }
        } else if (expression.is(ExprType.IF) && !(expression.parent() != null && expression.parent().is(ExprType.CASE))) {
            if (alwaysTrue(expression.getThis())) {
                return paren(expression.expr(Args.TRUE));
            }
            if (alwaysFalse(expression.getThis())) {
                Expression f = expression.expr(Args.FALSE);
                return paren(f != null ? f : Exp.nul());
            }
        }

        return expression;
    }

    /**
     * Reduces a prefix check to either TRUE or FALSE if both the string and the prefix are statically
     * known.
     */
    Expression simplifyStartswith(Expression expression) {
        Expression result = expression;
        if (expression.is(ExprType.STARTS_WITH)
                && expression.getThis() != null && expression.getThis().isString()
                && expression.getExpression() != null && expression.getExpression().isString()) {
            result = Exp.bool(expression.name().startsWith(expression.getExpression().name()));
        }

        return onChange(expression, result);
    }

    private boolean isDatetruncPredicate(Expression left, Expression right) {
        return left.is(ExprType.DATE_TRUNC, ExprType.TIMESTAMP_TRUNC) && isDateLiteral(right);
    }

    /** Simplify expressions like {@code DATE_TRUNC('year', x) >= CAST('2021-01-01' AS DATE)}. */
    Expression simplifyDatetrunc(Expression expression) {
        Expression result;
        try {
            result = simplifyDatetruncImpl(expression);
        } catch (UnsupportedUnit e) {
            result = expression;
        }
        return onChange(expression, result);
    }

    private Expression datetruncBinaryComparison(ExprType comparison, Expression l, PyDateTime dt, String u,
            Dialect d, Expression t) {
        return switch (comparison) {
            case LT -> {
                PyDateTime floor = datetimeFloor(dt, u, d);
                yield l.binop(ExprType.LT, dateLiteral(dt.equals(floor) ? dt : floor.plus(interval(u, 1)), t));
            }
            case GT -> l.binop(ExprType.GTE, dateLiteral(datetimeFloor(dt, u, d).plus(interval(u, 1)), t));
            case LTE -> l.binop(ExprType.LT, dateLiteral(datetimeFloor(dt, u, d).plus(interval(u, 1)), t));
            case GTE -> l.binop(ExprType.GTE, dateLiteral(dateCeil(dt, u, d), t));
            case EQ -> datetruncEq(l, dt, u, d, t);
            case NEQ -> datetruncNeq(l, dt, u, d, t);
            default -> throw new IllegalStateException(comparison.toString());
        };
    }

    private Expression simplifyDatetruncImpl(Expression expression) {
        ExprType comparison = expression.type();

        if (expression.is(ExprType.DATE_TRUNC, ExprType.TIMESTAMP_TRUNC)) {
            Expression thisExpr = expression.getThis();
            Expression truncType = TypeAnnotator.isType(expression, DataTypes.TEMPORAL_TYPES)
                    ? expression.dataType()
                    : extractType(thisExpr);
            PyDateTime date = thisExpr != null ? extractDate(thisExpr) : null;
            if (date != null && expression.unit() != null) {
                return dateLiteral(datetimeFloor(date, truncUnit(expression.unit(), dialect), dialect), truncType);
            }
        } else if (!isDatetruncComparison(comparison)) {
            return expression;
        }

        if (expression.is(ExprType.BINARY)) {
            Expression l = expression.getThis();
            Expression r = expression.getExpression();

            if (l == null || r == null || !isDatetruncPredicate(l, r)
                    || !l.is(ExprType.DATE_TRUNC, ExprType.TIMESTAMP_TRUNC)) {
                return expression;
            }

            Expression truncArg = l.getThis();
            String unit = truncUnit(l.expr(Args.UNIT), dialect);
            PyDateTime date = extractDate(r);

            if (date == null) {
                return expression;
            }

            Expression simplified = datetruncBinaryComparison(comparison, truncArg, date, unit, dialect, extractType(r));
            if (simplified == null) {
                return expression;
            }

            return parenthesizeNestedConnector(simplified, expression.parent());
        }

        if (expression.is(ExprType.IN)) {
            Expression l = expression.getThis();
            List<Expression> rs = expression.getExpressions();

            boolean all = true;
            for (Expression r : rs) {
                if (!isDatetruncPredicate(l, r)) {
                    all = false;
                    break;
                }
            }
            if (!rs.isEmpty() && all && l.is(ExprType.DATE_TRUNC, ExprType.TIMESTAMP_TRUNC)) {
                String unit = truncUnit(l.expr(Args.UNIT), dialect);

                List<PyDateTime[]> ranges = new ArrayList<>();
                for (Expression r : rs) {
                    PyDateTime date = extractDate(r);
                    if (date == null) {
                        return expression;
                    }
                    PyDateTime[] drange = datetruncRange(date, unit, dialect);
                    if (drange != null) {
                        ranges.add(drange);
                    }
                }

                if (ranges.isEmpty()) {
                    return expression;
                }

                ranges = mergeRanges(ranges);
                Expression targetType = extractType(rs.toArray(new Expression[0]));

                List<Expression> eqs = new ArrayList<>();
                for (PyDateTime[] drange : ranges) {
                    eqs.add(datetruncEqExpression(l, drange, targetType));
                }
                Expression simplified = combine(ExprType.OR, eqs);
                return parenthesizeNestedConnector(simplified, expression.parent());
            }
        }

        return expression;
    }

    /** sqlglot's {@code helper.merge_ranges}. */
    private static List<PyDateTime[]> mergeRanges(List<PyDateTime[]> ranges) {
        if (ranges.isEmpty()) {
            return new ArrayList<>();
        }

        List<PyDateTime[]> sorted = new ArrayList<>(ranges);
        sorted.sort((x, y) -> {
            int c = x[0].compareTo(y[0]);
            return c != 0 ? c : x[1].compareTo(y[1]);
        });

        List<PyDateTime[]> merged = new ArrayList<>();
        merged.add(sorted.get(0));

        for (PyDateTime[] range : sorted.subList(1, sorted.size())) {
            PyDateTime[] last = merged.get(merged.size() - 1);

            if (range[0].compareTo(last[1]) <= 0) {
                merged.set(merged.size() - 1, new PyDateTime[] {last[0], last[1].compareTo(range[1]) >= 0 ? last[1] : range[1]});
            } else {
                merged.add(range);
            }
        }

        return merged;
    }

    Expression sortComparison(Expression expression) {
        Expression result = expression;
        if (complementComparison(expression.type()) != null) {
            Expression l = expression.getThis();
            Expression r = expression.getExpression();
            boolean lColumn = l.is(ExprType.COLUMN);
            boolean rColumn = r.is(ExprType.COLUMN);
            boolean lConst = isConstant(l);
            boolean rConst = isConstant(r);

            if ((lColumn && !rColumn) || (rConst && !lConst) || r.is(ExprType.SUBQUERY_PREDICATE)) {
                return onChange(expression, expression);
            }
            if ((rColumn && !lColumn) || (lConst && !rConst)
                    || SimplifyGen.compare(SimplifyGen.gen(l), SimplifyGen.gen(r)) > 0) {
                result = binary(inverseComparison(expression.type()), r, l);
            }
        }
        return onChange(expression, result);
    }

    private Expression flatSimplify(Expression expression, PairSimplifier simplifier, boolean root) {
        if (root || !expression.sameParent()) {
            List<Expression> operands = new ArrayList<>();
            ArrayDeque<Expression> queue = new ArrayDeque<>(expression.flatten(false));
            int size = queue.size();

            // The pairwise scan below is O(n^2). For connectors, a pair only combines if one side
            // is a constant or both are comparisons (see simplifyConnectorsPair / simplifyComparison);
            // if no operand is combinable the scan is a guaranteed no-op, so return early.
            if (expression.is(ExprType.CONNECTOR)) {
                boolean combinable = false;
                for (Expression op : queue) {
                    if (op.is(ExprType.BOOLEAN, ExprType.LITERAL, ExprType.NULL) || isComparison(op)) {
                        combinable = true;
                        break;
                    }
                }
                if (!combinable) {
                    return expression;
                }
            }

            while (!queue.isEmpty()) {
                Expression a = queue.pollFirst();

                boolean combined = false;
                for (Iterator<Expression> it = queue.iterator(); it.hasNext(); ) {
                    Expression b = it.next();
                    Expression result = simplifier.apply(expression, a, b);

                    if (result != null && result != expression) {
                        // deque.remove(b) removes the first element equal to b
                        queue.remove(b);
                        queue.addFirst(result);
                        combined = true;
                        break;
                    }
                }
                if (!combined) {
                    operands.add(a);
                }
            }

            if (operands.size() < size) {
                Expression acc = operands.get(0);
                for (int i = 1; i < operands.size(); i++) {
                    acc = binary(expression.type(), acc, operands.get(i));
                }
                return acc;
            }
        }
        return expression;
    }
}
