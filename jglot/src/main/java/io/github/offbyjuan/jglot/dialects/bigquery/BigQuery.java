package io.github.offbyjuan.jglot.dialects.bigquery;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;

/** The BigQuery dialect: a port of sqlglot's {@code sqlglot.dialects.bigquery}. */
public class BigQuery extends Dialect {
    public BigQuery() {
        this("bigquery", "bigquery");
    }

    protected BigQuery(String name, String dataKey) {
        super(name, dataKey);
    }

    @Override
    public Parser newParser(ParserOptions options) {
        return new BigQueryParser(this, options);
    }

    @Override
    public Generator newGenerator(GeneratorOptions options) {
        return new BigQueryGenerator(this, options);
    }

    private static String asciiLower(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return sb.toString();
    }

    @Override
    public Expression normalizeIdentifier(Expression expression) {
        if (expression != null
                && expression.is(ExprType.IDENTIFIER)
                && normalizationStrategy() == NormalizationStrategy.CASE_INSENSITIVE) {
            Expression parent = expression.parent();
            while (parent != null && parent.is(ExprType.DOT)) {
                parent = parent.parent();
            }

            // In BigQuery, CTEs are case-insensitive, but UDF and table names are case-sensitive
            // by default. The following check uses a heuristic to detect tables based on whether
            // they are qualified. This should generally be correct, because tables in BigQuery
            // must be qualified with at least a dataset, unless @@dataset_id is set.
            boolean caseSensitive = (parent != null && parent.is(ExprType.USER_DEFINED_FUNCTION))
                    || (parent != null
                            && parent.is(ExprType.TABLE)
                            && !parent.text(Args.DB).isEmpty()
                            && (Expression.truthy(parent.metaGet("quoted_table"))
                                    || !Expression.truthy(parent.metaGet("maybe_column"))))
                    || Expression.truthy(expression.metaGet("is_table"));
            if (!caseSensitive) {
                expression.set(Args.THIS, asciiLower((String) expression.thisValue()));
            }

            return expression;
        }

        return super.normalizeIdentifier(expression);
    }
}
