package io.github.offbyjuan.jglot.dialects.trino;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.DialectHelpers;
import io.github.offbyjuan.jglot.dialects.presto.PrestoGenerator;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.transforms.Transforms;
import java.util.ArrayList;
import java.util.List;

/** Trino generator: a port of sqlglot's {@code sqlglot.generators.trino}. */
public class TrinoGenerator extends PrestoGenerator {
    public TrinoGenerator(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }

    @Override
    public String transform(Expression e) {
        return switch (e.type()) {
            case ARRAY_SUM -> "REDUCE(" + sql(e, Args.THIS) + ", 0, (acc, x) -> acc + x, acc -> acc)";
            case ARRAY_UNIQUE_AGG -> "ARRAY_AGG(DISTINCT " + sql(e, Args.THIS) + ")";
            case GROUP_CONCAT -> DialectHelpers.groupconcatSql(this, e, "LISTAGG", ",", true, true);
            case LOCATION_PROPERTY -> propertySql(e);
            case MERGE -> DialectHelpers.mergeWithoutTargetSql(this, e);
            case SELECT -> applyTransforms(e,
                    Transforms::eliminateQualify,
                    Transforms::eliminateDistinctOn,
                    Transforms.explodeProjectionToUnnest(1, true),
                    Transforms::eliminateSemiAndAntiJoins,
                    PrestoGenerator::amendExplodedColumnTable);
            case STABILITY_PROPERTY -> "IMMUTABLE".equals(e.name()) ? "DETERMINISTIC" : "NOT DETERMINISTIC";
            case TIME_STR_TO_TIME -> DialectHelpers.timestrtotimeSql(this, e, true);
            case TRIM -> DialectHelpers.trimSql(this, e);
            default -> super.transform(e);
        };
    }

    @Override
    public String concatwsSql(Expression expression) {
        if (expression.has(Args.FLATTEN)) {
            List<Expression> arrays = new ArrayList<>();
            boolean hasArray = false;
            boolean hasUnknown = false;

            List<Expression> exprs = expression.getExpressions();
            for (Expression arg : exprs.subList(Math.min(1, exprs.size()), exprs.size())) {
                if (arg.is(ExprType.ARRAY) || arg.isType(DType.ARRAY)) {
                    hasArray = true;
                    arg = Exp.func("COALESCE", null, Exp.cast(arg, "ARRAY<TEXT>"), Exp.array(List.of()));
                } else {
                    if ((arg.dataType() == null || arg.isType(DType.UNKNOWN))
                            && !arg.is(ExprType.LITERAL, ExprType.BOOLEAN, ExprType.NULL)) {
                        hasUnknown = true;
                    }

                    arg = Exp.array(List.of(Exp.cast(arg, DType.TEXT)));
                }

                arrays.add(arg);
            }

            if (hasUnknown) {
                unsupported("Cannot transpile CONCAT_WS with unknown argument types to Trino.");
            }

            if (hasArray) {
                Expression array = arrays.size() > 1
                        ? Expression.of(ExprType.ARRAY_CONCAT,
                                Args.THIS, arrays.get(0), Args.EXPRESSIONS, new ArrayList<>(arrays.subList(1, arrays.size())))
                        : arrays.get(0);
                return func("CONCAT_WS", exprs.get(0), array);
            }
        }

        return super.concatwsSql(expression);
    }

    @Override
    public String functionspecificationSql(Expression expression) {
        Expression characteristics = expression.expr(Args.CHARACTERISTICS);
        String characteristicsSql = characteristics != null
                ? properties(characteristics, " ", " ", "", false)
                : "";
        Expression properties = expression.expr(Args.PROPERTIES);
        String withSql = properties != null ? " " + withProperties(properties) : "";
        String body = sql(expression, Args.EXPRESSION);
        return "FUNCTION " + sql(expression, Args.THIS) + characteristicsSql + withSql + " " + body;
    }

    @Override
    public String ifblockSql(Expression expression) {
        // ELSEIF chains are nested into `false` at parse time (see
        // TrinoParser._parse_routine_if), so this flattens them back out rather
        // than recursing on ifblock_sql itself, which would re-wrap each link in
        // its own IF ... END IF.
        List<String> branches = new ArrayList<>();
        Object node = expression;

        while (node instanceof Expression n && n.is(ExprType.IF_BLOCK)) {
            String keyword = branches.isEmpty() ? "IF" : "ELSEIF";
            branches.add(keyword + " " + sql(n, Args.THIS) + " THEN " + sql(n, Args.TRUE) + ";");
            node = n.get(Args.FALSE);
        }

        if (node != null) {
            branches.add("ELSE " + sql(node) + ";");
        }

        return String.join(" ", branches) + " END IF";
    }

    @Override
    public String casestatementSql(Expression expression) {
        // Mirrors case_sql, using `;`-terminated statement bodies and END CASE
        // instead of a single value expression per branch and bare END.
        String this_ = sql(expression, Args.THIS);
        List<String> branches = new ArrayList<>();
        branches.add(!this_.isEmpty() ? "CASE " + this_ : "CASE");

        for (Expression node : expression.exprs(Args.IFS)) {
            branches.add("WHEN " + sql(node, Args.THIS) + " THEN " + sql(node, Args.TRUE) + ";");
        }

        Expression default_ = expression.expr(Args.DEFAULT);
        if (default_ != null) {
            branches.add("ELSE " + sql(default_) + ";");
        }

        branches.add("END CASE");
        return String.join(" ", branches);
    }

    private String labelSql(Expression expression) {
        Expression label = expression.expr(Args.LABEL);
        return label != null ? sql(label) + ": " : "";
    }

    @Override
    public String whileblockSql(Expression expression) {
        String labelSql = labelSql(expression);
        String condition = sql(expression, Args.THIS);
        String body = sql(expression, Args.BODY);
        return labelSql + "WHILE " + condition + " DO " + body + "; END WHILE";
    }

    @Override
    public String loopblockSql(Expression expression) {
        String labelSql = labelSql(expression);
        String body = sql(expression, Args.BODY);
        return labelSql + "LOOP " + body + "; END LOOP";
    }

    @Override
    public String repeatblockSql(Expression expression) {
        String labelSql = labelSql(expression);
        String body = sql(expression, Args.BODY);
        String until = sql(expression, Args.UNTIL);
        return labelSql + "REPEAT " + body + "; UNTIL " + until + " END REPEAT";
    }

    @Override
    public String leaveSql(Expression expression) {
        return "LEAVE " + sql(expression, Args.THIS);
    }

    @Override
    public String iterateSql(Expression expression) {
        return "ITERATE " + sql(expression, Args.THIS);
    }

    @Override
    public String jsonextractSql(Expression expression) {
        if (!expression.has(Args.JSON_QUERY)) {
            return super.jsonextractSql(expression);
        }

        String jsonPath = sql(expression, Args.EXPRESSION);

        // Trino's JSON_QUERY requires the path to start with a mode specifier. Paths coming from
        // dialects that don't have one (e.g. T-SQL) are parsed into a JSONPath, so we prefix the
        // standard default mode. Paths that failed to parse stay literals and keep their own mode.
        if (Expression.isInstance(expression.getExpression(), ExprType.JSON_PATH)) {
            String quote = dd.QUOTE_START;
            jsonPath = quote + "lax " + (jsonPath.startsWith(quote) ? jsonPath.substring(quote.length()) : jsonPath);
        }

        String option = sql(expression, Args.OPTION);
        option = !option.isEmpty() ? " " + option : "";

        String quote = sql(expression, Args.QUOTE);
        quote = !quote.isEmpty() ? " " + quote : "";

        String onCondition = sql(expression, Args.ON_CONDITION);
        onCondition = !onCondition.isEmpty() ? " " + onCondition : "";

        return func("JSON_QUERY", expression.getThis(), jsonPath + option + quote + onCondition);
    }
}
