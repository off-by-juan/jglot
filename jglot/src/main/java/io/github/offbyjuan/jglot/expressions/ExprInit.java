package io.github.offbyjuan.jglot.expressions;

import static io.github.offbyjuan.jglot.expressions.ExprType.*;

import java.util.Locale;
import java.util.Map;

/** Construction-time normalizations that sqlglot performs in expression {@code __init__} methods. */
final class ExprInit {
    private ExprInit() {}

    static final Map<String, String> UNABBREVIATED_UNIT_NAME = Map.of(
            "D", "DAY",
            "H", "HOUR",
            "M", "MINUTE",
            "MS", "MILLISECOND",
            "NS", "NANOSECOND",
            "Q", "QUARTER",
            "S", "SECOND",
            "US", "MICROSECOND",
            "W", "WEEK",
            "Y", "YEAR");

    private static boolean isVarLike(Expression unit) {
        ExprType t = unit.type();
        return t == COLUMN || t == LITERAL || t == VAR;
    }

    static void afterConstruct(Expression e) {
        ExprType t = e.type();
        if (t == DATE_TRUNC) {
            Expression unit = e.expr(Args.UNIT);
            if (unit != null && unit.is(COLUMN, LITERAL, VAR) && !(unit.is(COLUMN) && unit.parts().size() != 1)) {
                String name = unit.name().toUpperCase(Locale.ROOT);
                String full = UNABBREVIATED_UNIT_NAME.get(name);
                if (full != null) {
                    name = full;
                }
                e.set(Args.UNIT, Exp.string(name));
            }
            return;
        }
        if (t.isA(TIME_UNIT)) {
            Expression unit = e.expr(Args.UNIT);
            if (unit == null) {
                return;
            }
            if (isVarLike(unit) && !(unit.type() == COLUMN && unit.parts().size() != 1)) {
                String name = unit.name();
                String full = UNABBREVIATED_UNIT_NAME.get(name);
                e.set(Args.UNIT, Exp.var((full != null ? full : name).toUpperCase(Locale.ROOT)));
            } else if (unit.type() == WEEK) {
                Expression inner = unit.getThis();
                unit.set(Args.THIS, Exp.var(inner.name().toUpperCase(Locale.ROOT)));
            }
        }
    }

    /** sqlglot's DateTrunc(unabbreviate=False) variant. */
    static Expression dateTrunc(Expression unit, Expression thisExpr, boolean unabbreviate) {
        Expression e = Expression.of(DATE_TRUNC);
        e.set(Args.THIS, thisExpr);
        if (unit != null && unit.is(COLUMN, LITERAL, VAR) && !(unit.is(COLUMN) && unit.parts().size() != 1)) {
            String name = unit.name().toUpperCase(Locale.ROOT);
            if (unabbreviate) {
                name = UNABBREVIATED_UNIT_NAME.getOrDefault(name, name);
            }
            unit = Exp.string(name);
        }
        e.set(Args.UNIT, unit);
        return e;
    }
}
