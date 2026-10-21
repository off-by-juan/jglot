package io.github.offbyjuan.jglot.dialects.clickhouse;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;
import java.util.ArrayList;
import java.util.List;

/** The ClickHouse dialect: a port of sqlglot's {@code sqlglot.dialects.clickhouse}. */
public class ClickHouse extends Dialect {
    public ClickHouse() {
        this("clickhouse", "clickhouse");
    }

    protected ClickHouse(String name, String dataKey) {
        super(name, dataKey);
    }

    @Override
    public Parser newParser(ParserOptions options) {
        return new ClickHouseParser(this, options);
    }

    @Override
    public Generator newGenerator(GeneratorOptions options) {
        return new ClickHouseGenerator(this, options);
    }

    @Override
    public List<Expression> generateValuesAliases(Expression expression) {
        // Clickhouse allows VALUES to have an embedded structure e.g:
        // VALUES('person String, place String', ('Noah', 'Paris'), ...)
        // In this case, we don't want to qualify the columns
        List<Expression> values = expression.getExpressions().get(0).getExpressions();

        Expression structure = values.size() > 1 && values.get(0).isString() && values.get(1).is(ExprType.TUPLE)
                ? values.get(0)
                : null;

        List<Expression> columnAliases = new ArrayList<>();
        if (structure != null) {
            // Split each column definition into the column name e.g:
            // 'person String, place String' -> ['person', 'place']
            for (String coldef : structure.name().split(",", -1)) {
                String trimmed = coldef.strip();
                columnAliases.add(Exp.toIdentifier(trimmed.split(" ", -1)[0]));
            }
        } else {
            // Default column aliases in CH are "c1", "c2", etc.
            int n = values.get(0).getExpressions().size();
            for (int i = 0; i < n; i++) {
                columnAliases.add(Exp.toIdentifier("c" + (i + 1)));
            }
        }

        return columnAliases;
    }
}
