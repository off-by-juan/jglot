package io.github.offbyjuan.jglot.generator;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.optimizer.TypeAnnotator;
import io.github.offbyjuan.jglot.util.Py;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Port of sqlglot's Generator methods from {@code pivot_sql} through {@code timeserieskey_sql}. */
abstract class GeneratorPartC extends GeneratorMethods {
    GeneratorPartC(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }

    // ------------------------------------------------------------------------------------------
    // Private helpers

    /** {@code self.sql(expression, key, comment=False)}. */
    private String sqlNoComment(Expression expression, Arg key) {
        if (expression == null) {
            return "";
        }
        Object value = expression.get(key);
        return Expression.truthy(value) ? sql(value, false) : "";
    }

    /** {@code exp.maybe_copy(e)} (copy=True). */
    private static Expression maybeCopy(Expression e) {
        return e == null ? null : e.copy();
    }

    /** {@code Limit.is_limit_all}. */
    private static boolean isLimitAll(Expression limit) {
        Expression limitExpression = limit.expr(Args.EXPRESSION);
        return limitExpression != null && limitExpression.is(ExprType.VAR)
                && "ALL".equals(Py.upper(limitExpression.name()));
    }

    /** The upper-cased text of a Join arg ({@code Join.kind}, {@code .side}, ...). */
    private static String upperText(Expression e, Arg arg) {
        return Py.upper(e.text(arg));
    }

    /** Canonical (sqlglot dict) order of AFTER_HAVING_MODIFIER_TRANSFORMS keys. */
    private static final List<String> AFTER_HAVING_MODIFIER_ORDER =
            List.of("cluster", "distribute", "sort", "windows", "qualify", "sample");

    /**
     * The keys of {@code AFTER_HAVING_MODIFIER_TRANSFORMS} in the dialect's dict order. The
     * generated data is an unordered set, so this uses sqlglot's base order; BigQuery's generator
     * declares {@code qualify} before {@code windows} and must override this.
     */
    protected List<String> afterHavingModifierKeys() {
        List<String> out = new ArrayList<>(AFTER_HAVING_MODIFIER_ORDER.size());
        for (String k : AFTER_HAVING_MODIFIER_ORDER) {
            if (g.AFTER_HAVING_MODIFIER_TRANSFORMS.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }

    /** One value of {@code AFTER_HAVING_MODIFIER_TRANSFORMS}, applied to {@code e}. */
    protected String afterHavingModifier(String key, Expression e) {
        if ("windows".equals(key)) {
            return e.has(Args.WINDOWS)
                    ? seg("WINDOW ") + expressions(opts(e).key(Args.WINDOWS).flat())
                    : "";
        }
        Arg arg = Arg.forName(key);
        return arg == null ? "" : sql(e, arg);
    }

    /** {@code sqlglot.dialects.dialect.map_date_part(part, self.dialect)}. */
    private Expression mapDatePart(Expression part) {
        String mapped = part != null && !(part.is(ExprType.COLUMN) && part.parts().size() != 1)
                ? dd.DATE_PART_MAPPING.get(Py.upper(part.name()))
                : null;
        if (mapped != null && !mapped.isEmpty()) {
            return part.isString() ? Exp.string(mapped) : Exp.var(mapped);
        }
        return part;
    }

    /** {@code sqlglot.dialects.dialect.concat_to_dpipe_sql(self, expression)}. */
    private String concatToDpipeSql(Expression expression) {
        Expression acc = null;
        for (Expression e : convertConcatArgs(expression)) {
            acc = acc == null ? e : Expression.of(ExprType.D_PIPE, Args.THIS, acc, Args.EXPRESSION, e);
        }
        if (acc == null) {
            throw new IllegalArgumentException("reduce() of empty iterable with no initial value");
        }
        return sql(acc);
    }

    // ------------------------------------------------------------------------------------------
    // Pivot / version / tuple

    @Override
    public String pivotSql(Expression expression) {
        String expressions = expressions(opts(expression).flat());
        String direction = expression.has(Args.UNPIVOT) ? "UNPIVOT" : "PIVOT";

        String group = sql(expression, Args.GROUP);

        if (expression.getThis() != null) {
            String this_ = sql(expression, Args.THIS);
            String sqlText;
            if (expressions.isEmpty()) {
                sqlText = "UNPIVOT " + this_;
            } else {
                String on = seg("ON") + " " + expressions;
                String into = sql(expression, Args.INTO);
                into = !into.isEmpty() ? seg("INTO") + " " + into : "";
                String using = expressions(opts(expression).key(Args.USING).flat());
                using = !using.isEmpty() ? seg("USING") + " " + using : "";
                sqlText = direction + " " + this_ + on + into + using + group;
            }
            return prependCtes(expression, sqlText);
        }

        if (!expression.has(Args.UNPIVOT)) {
            // Wrap IN-list values with explicit aliases where the target dialect would differ
            List<Expression> newFieldExprs = pivotInValueAliases(expression);
            if (newFieldExprs != null) {
                expression.exprs(Args.FIELDS).get(0).set(Args.EXPRESSIONS, newFieldExprs);
            }
        }

        String alias = sql(expression, Args.ALIAS);
        if (!alias.isEmpty()) {
            alias = g.PIVOT_ALIAS_WITH_AS ? " AS " + alias : " " + alias;
        }

        String fields = expressions(opts(expression).key(Args.FIELDS).sep(" ").dynamic().newLine().skipFirst().skipLast());

        Object includeNulls = expression.get(Args.INCLUDE_NULLS);
        String nulls;
        if (includeNulls != null) {
            nulls = Expression.truthy(includeNulls) ? " INCLUDE NULLS " : " EXCLUDE NULLS ";
        } else {
            nulls = "";
        }

        String defaultOnNull = sql(expression, Args.DEFAULT_ON_NULL);
        defaultOnNull = !defaultOnNull.isEmpty() ? " DEFAULT ON NULL (" + defaultOnNull + ")" : "";
        String sqlText = seg(direction) + nulls + "(" + expressions + " FOR " + fields + defaultOnNull + group + ")" + alias;
        return prependCtes(expression, sqlText);
    }

    @Override
    public String versionSql(Expression expression) {
        String this_ = "FOR " + expression.name();
        String kind = expression.text(Args.KIND);
        String expr = sql(expression, Args.EXPRESSION);
        return this_ + " " + kind + " " + expr;
    }

    @Override
    public String tupleSql(Expression expression) {
        return "(" + expressions(opts(expression).dynamic().newLine().skipFirst().skipLast()) + ")";
    }

    // ------------------------------------------------------------------------------------------
    // UPDATE / VALUES

    /**
     * Returns (join_sql, from_sql) for UPDATE statements.
     * - join_sql: placed after UPDATE table, before SET
     * - from_sql: placed after SET clause (standard position)
     * Dialects like MySQL need to convert FROM to JOIN syntax.
     */
    @Override
    public Object[] updateFromJoinsSql(Expression expression) {
        Expression fromExpr = expression.expr(Args.FROM_);
        if (g.UPDATE_STATEMENT_SUPPORTS_FROM || fromExpr == null) {
            return new Object[] {"", sql(expression, Args.FROM_)};
        }

        // Qualify unqualified columns in SET clause with the target table
        // MySQL requires qualified column names in multi-table UPDATE to avoid ambiguity
        Expression targetTable = expression.getThis();
        if (targetTable != null && targetTable.is(ExprType.TABLE)) {
            Expression targetName = Exp.toIdentifier(targetTable.aliasOrName());
            for (Expression eq : expression.getExpressions()) {
                Expression col = eq.getThis();
                if (col != null && col.is(ExprType.COLUMN) && col.text(Args.TABLE).isEmpty()) {
                    col.set(Args.TABLE, targetName);
                }
            }
        }

        Expression table = fromExpr.getThis();
        List<Expression> nestedJoins = new ArrayList<>(table.exprs(Args.JOINS));
        if (!nestedJoins.isEmpty()) {
            table.set(Args.JOINS, null);
        }

        StringBuilder joinSql = new StringBuilder(sql(Expression.of(ExprType.JOIN, Args.THIS, table, Args.ON, Exp.bool(true))));
        for (Expression nested : nestedJoins) {
            if (!nested.has(Args.ON) && !nested.has(Args.USING)) {
                nested.set(Args.ON, Exp.bool(true));
            }
            joinSql.append(sql(nested));
        }

        return new Object[] {joinSql.toString(), ""};
    }

    @Override
    public String updateSql(Expression expression) {
        String hint = sql(expression, Args.HINT);
        String this_ = sql(expression, Args.THIS);
        Object[] joinFrom = updateFromJoinsSql(expression);
        String joinSql = (String) joinFrom[0];
        String fromSql = (String) joinFrom[1];
        String setSql = expressions(opts(expression).flat());
        String whereSql = sql(expression, Args.WHERE);
        String returning = sql(expression, Args.RETURNING);
        String order = sql(expression, Args.ORDER);
        String limit = sql(expression, Args.LIMIT);
        String expressionSql;
        if (g.RETURNING_END) {
            expressionSql = fromSql + whereSql + returning;
        } else {
            expressionSql = returning + fromSql + whereSql;
        }
        String options = expressions(expression, Args.OPTIONS);
        options = !options.isEmpty() ? " OPTION(" + options + ")" : "";
        String sqlText = "UPDATE" + hint + " " + this_ + joinSql + " SET " + setSql + expressionSql + order + limit + options;
        return prependCtes(expression, sqlText);
    }

    @Override
    public String valuesSql(Expression expression, boolean valuesAsTable) {
        valuesAsTable = valuesAsTable && g.VALUES_AS_TABLE;

        // The VALUES clause is still valid in an `INSERT INTO ..` statement, for example
        if (valuesAsTable || expression.findAncestor(ExprType.FROM, ExprType.JOIN) == null) {
            String args = expressions(expression);
            String alias = sql(expression, Args.ALIAS);
            String values = "VALUES" + seg("") + args;
            Expression parent = expression.parent();
            values = g.WRAP_DERIVED_VALUES
                    && (!alias.isEmpty() || (parent != null && parent.is(ExprType.FROM, ExprType.TABLE)))
                    ? "(" + values + ")"
                    : values;
            values = queryModifiers(expression, values);
            return !alias.isEmpty() ? values + " AS " + alias : values;
        }

        // Converts `VALUES...` expression into a series of select unions.
        Expression aliasNode = expression.expr(Args.ALIAS);
        List<Expression> columnNames = aliasNode == null ? List.of() : aliasNode.exprs(Args.COLUMNS);

        List<Expression> selects = new ArrayList<>();

        List<Expression> tuples = expression.getExpressions();
        for (int i = 0; i < tuples.size(); i++) {
            List<Expression> row = new ArrayList<>(tuples.get(i).getExpressions());

            if (i == 0 && !columnNames.isEmpty()) {
                List<Expression> aliased = new ArrayList<>();
                int n = Math.min(row.size(), columnNames.size());
                for (int j = 0; j < n; j++) {
                    aliased.add(Exp.alias(row.get(j).copy(), columnNames.get(j)));
                }
                row = aliased;
            }

            selects.add(Expression.of(ExprType.SELECT, Args.EXPRESSIONS, row));
        }

        if (pretty) {
            // This may result in poor performance for large-cardinality `VALUES` tables, due to
            // the deep nesting of the resulting exp.Unions.
            Expression query = null;
            for (Expression s : selects) {
                query = query == null ? s : Exp.union(query, s, false);
            }
            return subquerySql(query.subquery(aliasNode == null ? null : aliasNode.getThis(), false));
        }

        String alias = aliasNode != null ? " AS " + sql(aliasNode, Args.THIS) : "";
        StringBuilder unions = new StringBuilder();
        for (int i = 0; i < selects.size(); i++) {
            if (i > 0) {
                unions.append(" UNION ALL ");
            }
            unions.append(sql(selects.get(i)));
        }
        return "(" + unions + ")" + alias;
    }

    @Override
    public String varSql(Expression expression) {
        return sql(expression, Args.THIS);
    }

    @Override
    public String intoSql(Expression expression) {
        unsupportedArgs(expression, Args.EXPRESSIONS);
        String temporary = expression.has(Args.TEMPORARY) ? " TEMPORARY" : "";
        String unlogged = expression.has(Args.UNLOGGED) ? " UNLOGGED" : "";
        return seg("INTO") + (!temporary.isEmpty() ? temporary : unlogged) + " " + sql(expression, Args.THIS);
    }

    @Override
    public String fromSql(Expression expression) {
        return seg("FROM") + " " + sql(expression, Args.THIS);
    }

    // ------------------------------------------------------------------------------------------
    // GROUP BY and friends

    @Override
    public String groupingsetsSql(Expression expression) {
        String groupingSets = expressions(opts(expression).indent(false));
        return "GROUPING SETS " + wrap(groupingSets);
    }

    @Override
    public String rollupSql(Expression expression) {
        String expressions = expressions(opts(expression).indent(false));
        return !expressions.isEmpty() ? "ROLLUP " + wrap(expressions) : "WITH ROLLUP";
    }

    @Override
    public String rollupindexSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);

        String columns = expressions(opts(expression).flat());

        String fromSql = sql(expression, Args.FROM_INDEX);
        fromSql = !fromSql.isEmpty() ? " FROM " + fromSql : "";

        Expression properties = expression.expr(Args.PROPERTIES);
        String propertiesSql = properties != null ? " " + properties(properties, "PROPERTIES") : "";

        return this_ + "(" + columns + ")" + fromSql + propertiesSql;
    }

    @Override
    public String rolluppropertySql(Expression expression) {
        return "ROLLUP (" + expressions(opts(expression).flat()) + ")";
    }

    @Override
    public String cubeSql(Expression expression) {
        String expressions = expressions(opts(expression).indent(false));
        return !expressions.isEmpty() ? "CUBE " + wrap(expressions) : "WITH CUBE";
    }

    @Override
    public String groupSql(Expression expression) {
        Object groupByAll = expression.get(Args.ALL);
        String modifier;
        if (Boolean.TRUE.equals(groupByAll)) {
            modifier = " ALL";
        } else if (Boolean.FALSE.equals(groupByAll)) {
            modifier = " DISTINCT";
        } else {
            modifier = "";
        }

        String groupBy = opExpressions("GROUP BY" + modifier, expression);

        String groupingSets = expressions(expression, Args.GROUPING_SETS);
        String cube = expressions(expression, Args.CUBE);
        String rollup = expressions(expression, Args.ROLLUP);

        String groupings = Py.csv(g.GROUPINGS_SEP, new String[] {
            !groupingSets.isEmpty() ? seg(groupingSets) : "",
            !cube.isEmpty() ? seg(cube) : "",
            !rollup.isEmpty() ? seg(rollup) : "",
            expression.has(Args.TOTALS) ? seg("WITH TOTALS") : "",
        });

        if (!expression.getExpressions().isEmpty()
                && !groupings.isEmpty()
                && !groupings.strip().equals("WITH CUBE")
                && !groupings.strip().equals("WITH ROLLUP")) {
            boolean addSeparator = true;

            if (!groupingSets.isEmpty()) {
                if (g.SUPPORTS_GROUPING_SETS_AS_SUFFIX) {
                    addSeparator = false;
                } else {
                    unsupported("GROUPING SETS without a comma after GROUP BY expressions is not supported");
                }
            }

            if (addSeparator) {
                groupBy = groupBy + g.GROUPINGS_SEP;
            }
        }

        return groupBy + groupings;
    }

    @Override
    public String havingSql(Expression expression) {
        String this_ = indent(sql(expression, Args.THIS));
        return seg("HAVING") + sep() + this_;
    }

    @Override
    public String connectSql(Expression expression) {
        String start = sql(expression, Args.START);
        start = !start.isEmpty() ? seg("START WITH " + start) : "";
        String nocycle = expression.has(Args.NOCYCLE) ? " NOCYCLE" : "";
        String connect = sql(expression, Args.CONNECT);
        connect = seg("CONNECT BY" + nocycle + " " + connect);
        return start + connect;
    }

    @Override
    public String priorSql(Expression expression) {
        return "PRIOR " + sql(expression, Args.THIS);
    }

    // ------------------------------------------------------------------------------------------
    // Joins / lambda / lateral

    @Override
    public String joinSql(Expression expression) {
        String kind = upperText(expression, Args.KIND);
        String side;
        if (!g.SEMI_ANTI_JOIN_WITH_SIDE && (kind.equals("SEMI") || kind.equals("ANTI"))) {
            side = null;
        } else {
            side = upperText(expression, Args.SIDE);
        }

        StringBuilder opBuilder = new StringBuilder();
        for (String op : new String[] {
            upperText(expression, Args.METHOD),
            expression.has(Args.GLOBAL_) ? "GLOBAL" : null,
            side,
            kind,
            g.JOIN_HINTS ? upperText(expression, Args.HINT) : null,
            expression.has(Args.DIRECTED) && g.DIRECTED_JOINS ? "DIRECTED" : null,
        }) {
            if (op != null && !op.isEmpty()) {
                if (opBuilder.length() > 0) {
                    opBuilder.append(' ');
                }
                opBuilder.append(op);
            }
        }
        String opSql = opBuilder.toString();
        String matchCond = sql(expression, Args.MATCH_CONDITION);
        matchCond = !matchCond.isEmpty() ? " MATCH_CONDITION (" + matchCond + ")" : "";
        String onSql = sql(expression, Args.ON);
        List<Object> using = expression.list(Args.USING);

        if (onSql.isEmpty() && !using.isEmpty()) {
            String[] cols = new String[using.size()];
            for (int i = 0; i < cols.length; i++) {
                cols[i] = sql(using.get(i));
            }
            onSql = Py.csv(cols);
        }

        Expression this_ = expression.getThis();
        String thisSql = sql(this_);

        String exprs = expressions(expression);
        if (!exprs.isEmpty()) {
            thisSql = thisSql + "," + seg(exprs);
        }

        if (!onSql.isEmpty()) {
            onSql = indent(onSql, 0, null, true, false);
            String space = pretty ? seg(" ".repeat(pad)) : " ";
            if (!using.isEmpty()) {
                onSql = space + "USING (" + onSql + ")";
            } else {
                onSql = space + "ON " + onSql;
            }
        } else if (opSql.isEmpty()) {
            if (this_ != null && this_.is(ExprType.LATERAL) && this_.get(Args.CROSS_APPLY) != null) {
                return " " + thisSql;
            }

            return ", " + thisSql;
        }

        if (!opSql.equals("STRAIGHT_JOIN")) {
            opSql = !opSql.isEmpty() ? opSql + " JOIN" : "JOIN";
        }

        String pivots = expressions(opts(expression).key(Args.PIVOTS).sep("").flat());
        return seg(opSql) + " " + thisSql + matchCond + onSql + pivots;
    }

    @Override
    public String lambdaSql(Expression expression, String arrowSep, boolean wrap) {
        String args = expressions(opts(expression).flat());
        args = wrap && args.indexOf(',') >= 0 ? "(" + args + ")" : args;
        return args + " " + arrowSep + " " + sql(expression, Args.THIS);
    }

    @Override
    public String lateralOp(Expression expression) {
        Object crossApply = expression.get(Args.CROSS_APPLY);

        // https://www.mssqltips.com/sqlservertip/1958/sql-server-cross-apply-and-outer-apply/
        String op;
        if (Boolean.TRUE.equals(crossApply)) {
            op = "INNER JOIN ";
        } else if (Boolean.FALSE.equals(crossApply)) {
            op = "LEFT JOIN ";
        } else {
            op = "";
        }

        return op + "LATERAL";
    }

    @Override
    public String lateralSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);

        if (expression.has(Args.VIEW)) {
            Expression alias = expression.expr(Args.ALIAS);
            String columns = expressions(opts(alias).key(Args.COLUMNS).flat());
            String table = !alias.name().isEmpty() ? " " + alias.name() : "";
            columns = !columns.isEmpty() ? " AS " + columns : "";
            String opSql = seg("LATERAL VIEW" + (expression.has(Args.OUTER) ? " OUTER" : ""));
            return opSql + sep() + this_ + table + columns;
        }

        Expression tableAlias = expression.expr(Args.ALIAS);
        Expression thisExpr = expression.getThis();
        Object offset = thisExpr == null ? null : thisExpr.get(Args.OFFSET);

        if (g.UNNEST_WITH_ORDINALITY
                && tableAlias != null
                && thisExpr != null && thisExpr.is(ExprType.UNNEST)
                && Expression.isInstance(offset, ExprType.IDENTIFIER)) {
            // UNNEST ... WITH ORDINALITY stores the ordinality column's name in Unnest.offset
            tableAlias = tableAlias.copy();
            tableAlias.append(Args.COLUMNS, ((Expression) offset).copy());
        }

        String alias = sql(tableAlias);
        alias = !alias.isEmpty() ? " AS " + alias : "";

        String ordinality = "";
        if (expression.has(Args.ORDINALITY)) {
            ordinality = " WITH ORDINALITY" + alias;
            alias = "";
        }

        return lateralOp(expression) + " " + this_ + alias + ordinality;
    }

    // ------------------------------------------------------------------------------------------
    // LIMIT / OFFSET / SET / LOCK

    @Override
    public String limitSql(Expression expression, boolean top) {
        String this_ = sql(expression, Args.THIS);

        if (isLimitAll(expression) && !dd.SUPPORTS_LIMIT_ALL) {
            return this_;
        }

        List<Expression> args = new ArrayList<>(2);
        for (Arg k : new Arg[] {Args.OFFSET, Args.EXPRESSION}) {
            Object v = expression.get(k);
            if (Expression.truthy(v)) {
                Expression e = (Expression) v;
                args.add(g.LIMIT_ONLY_LITERALS ? simplifyUnlessLiteral(e) : e);
            }
        }

        StringBuilder argsBuilder = new StringBuilder();
        boolean anyNonNumber = false;
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                argsBuilder.append(", ");
            }
            argsBuilder.append(sql(args.get(i)));
            if (!args.get(i).isNumber()) {
                anyNonNumber = true;
            }
        }
        String argsSql = argsBuilder.toString();
        argsSql = top && anyNonNumber ? "(" + argsSql + ")" : argsSql;
        String expressions = expressions(opts(expression).flat());
        String limitOptions = sql(expression, Args.LIMIT_OPTIONS);
        expressions = !expressions.isEmpty() ? " BY " + expressions : "";

        return this_ + seg(top ? "TOP" : "LIMIT") + " " + argsSql + limitOptions + expressions;
    }

    @Override
    public String offsetSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        Expression value = expression.getExpression();
        value = g.LIMIT_ONLY_LITERALS ? simplifyUnlessLiteral(value) : value;
        String expressions = expressions(opts(expression).flat());
        expressions = !expressions.isEmpty() ? " BY " + expressions : "";
        return this_ + seg("OFFSET") + " " + sql(value) + expressions;
    }

    @Override
    public String setitemSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        if (!g.SET_ASSIGNMENT_REQUIRES_VARIABLE_KEYWORD && kind.equals("VARIABLE")) {
            kind = "";
        } else {
            kind = !kind.isEmpty() ? kind + " " : "";
        }
        String this_ = sql(expression, Args.THIS);
        String expressions = expressions(expression);
        String collate = sql(expression, Args.COLLATE);
        collate = !collate.isEmpty() ? " COLLATE " + collate : "";
        String global = expression.has(Args.GLOBAL_) ? "GLOBAL " : "";
        return global + kind + this_ + expressions + collate;
    }

    @Override
    public String setSql(Expression expression) {
        String expressions = " " + expressions(opts(expression).flat());
        String tag = expression.has(Args.TAG) ? " TAG" : "";
        return (expression.has(Args.UNSET) ? "UNSET" : "SET") + tag + expressions;
    }

    @Override
    public String querybandSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String update = expression.has(Args.UPDATE) ? " UPDATE" : "";
        String scope = sql(expression, Args.SCOPE);
        scope = !scope.isEmpty() ? " FOR " + scope : "";

        return "QUERY_BAND = " + this_ + update + scope;
    }

    @Override
    public String pragmaSql(Expression expression) {
        return "PRAGMA " + sql(expression, Args.THIS);
    }

    @Override
    public String lockSql(Expression expression) {
        if (!g.LOCKING_READS_SUPPORTED) {
            unsupported("Locking reads using 'FOR UPDATE/SHARE' are not supported");
            return "";
        }

        boolean update = expression.has(Args.UPDATE);
        boolean key = expression.has(Args.KEY);
        String lockType;
        if (update) {
            lockType = key ? "FOR NO KEY UPDATE" : "FOR UPDATE";
        } else {
            lockType = key ? "FOR KEY SHARE" : "FOR SHARE";
        }
        String expressions = expressions(opts(expression).flat());
        expressions = !expressions.isEmpty() ? " OF " + expressions : "";
        Object wait = expression.get(Args.WAIT);
        String waitSql = "";

        if (wait != null) {
            if (Expression.isInstance(wait, ExprType.LITERAL)) {
                waitSql = " WAIT " + sql(wait);
            } else {
                waitSql = Expression.truthy(wait) ? " NOWAIT" : " SKIP LOCKED";
            }
        }

        return lockType + expressions + waitSql;
    }

    // ------------------------------------------------------------------------------------------
    // Literals and string escaping

    @Override
    public String literalSql(Expression expression) {
        Object thisValue = expression.thisValue();
        String text = Expression.truthy(thisValue) ? thisValue.toString() : "";
        if (expression.isString()) {
            text = dd.QUOTE_START + escapeStr(text) + dd.QUOTE_END;
        }
        return text;
    }

    @Override
    public String escapeStr(String text, boolean escapeBackslash, String delimiter, String escapedDelimiter,
            boolean isByteString, boolean isBytes) {
        boolean supportsEscapeSequences = isByteString
                ? dd.BYTE_STRINGS_SUPPORT_ESCAPED_SEQUENCES
                : dd.STRINGS_SUPPORT_ESCAPED_SEQUENCES;

        if (supportsEscapeSequences) {
            Map<String, String> escaped = dd.ESCAPED_SEQUENCES;
            if (!escaped.isEmpty()) {
                StringBuilder sb = null;
                int n = text.length();
                for (int i = 0; i < n; ) {
                    int cp = text.codePointAt(i);
                    int len = Character.charCount(cp);
                    String ch = text.substring(i, i + len);
                    String rep = escapeBackslash || cp != '\\' ? escaped.get(ch) : null;
                    if (rep != null) {
                        if (sb == null) {
                            sb = new StringBuilder(n + 16);
                            sb.append(text, 0, i);
                        }
                        sb.append(rep);
                    } else if (sb != null) {
                        sb.append(ch);
                    }
                    i += len;
                }
                if (sb != null) {
                    text = sb.toString();
                }
            }

            if (isBytes && dialect.tokenizerConfig().numericEscapes().containsKey('x')) {
                // Escape high bytes so they aren't re-encoded as UTF-8 characters.
                StringBuilder sb = null;
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c >= 0x80 && c <= 0xff) {
                        if (sb == null) {
                            sb = new StringBuilder(text.length() + 16);
                            sb.append(text, 0, i);
                        }
                        sb.append("\\x").append(String.format(java.util.Locale.ROOT, "%02x", (int) c));
                    } else if (sb != null) {
                        sb.append(c);
                    }
                }
                if (sb != null) {
                    text = sb.toString();
                }
            }
        }

        delimiter = delimiter == null || delimiter.isEmpty() ? dd.QUOTE_END : delimiter;
        escapedDelimiter = escapedDelimiter == null || escapedDelimiter.isEmpty() ? escapedQuoteEnd : escapedDelimiter;

        return replaceLineBreaks(text).replace(delimiter, escapedDelimiter);
    }

    @Override
    public String loaddataSql(Expression expression) {
        boolean isOverwrite = expression.has(Args.OVERWRITE);
        String overwrite = isOverwrite ? " OVERWRITE" : "";
        String this_ = sql(expression, Args.THIS);

        Expression files = expression.expr(Args.FILES);
        if (files != null) {
            String filesSql = expressions(opts(files).flat());
            filesSql = "FILES" + wrap(filesSql);
            if (isOverwrite) {
                this_ = " " + this_;
            } else if (expression.has(Args.TEMP)) {
                this_ = " INTO TEMP TABLE " + this_;
            } else {
                this_ = " INTO TABLE " + this_;
            }
            return "LOAD DATA" + overwrite + this_ + " FROM " + filesSql;
        }

        String local = expression.has(Args.LOCAL) ? " LOCAL" : "";
        String inpath = " INPATH " + sql(expression, Args.INPATH);
        this_ = " INTO TABLE " + this_;
        String partition = sql(expression, Args.PARTITION);
        partition = !partition.isEmpty() ? " " + partition : "";
        String inputFormat = sql(expression, Args.INPUT_FORMAT);
        inputFormat = !inputFormat.isEmpty() ? " INPUTFORMAT " + inputFormat : "";
        String serde = sql(expression, Args.SERDE);
        serde = !serde.isEmpty() ? " SERDE " + serde : "";
        return "LOAD DATA" + local + inpath + overwrite + this_ + partition + inputFormat + serde;
    }

    @Override
    public String nullSql(Object... unused) {
        return "NULL";
    }

    @Override
    public String booleanSql(Expression expression) {
        return Expression.truthy(expression.thisValue()) ? "TRUE" : "FALSE";
    }

    @Override
    public String boolandSql(Expression expression) {
        return "((" + sql(expression, Args.THIS) + ") AND (" + sql(expression, Args.EXPRESSION) + "))";
    }

    @Override
    public String boolorSql(Expression expression) {
        return "((" + sql(expression, Args.THIS) + ") OR (" + sql(expression, Args.EXPRESSION) + "))";
    }

    // ------------------------------------------------------------------------------------------
    // ORDER BY and friends

    @Override
    public String orderSql(Expression expression, boolean flat) {
        String this_ = sql(expression, Args.THIS);
        this_ = !this_.isEmpty() ? this_ + " " : this_;
        String siblings = expression.has(Args.SIBLINGS) ? "SIBLINGS " : "";
        return opExpressions(this_ + "ORDER " + siblings + "BY", expression, !this_.isEmpty() || flat);
    }

    @Override
    public String withfillSql(Expression expression) {
        String fromSql = sql(expression, Args.FROM_);
        fromSql = !fromSql.isEmpty() ? " FROM " + fromSql : "";
        String toSql = sql(expression, Args.TO);
        toSql = !toSql.isEmpty() ? " TO " + toSql : "";
        String stepSql = sql(expression, Args.STEP);
        stepSql = !stepSql.isEmpty() ? " STEP " + stepSql : "";
        List<String> interpolatedValues = new ArrayList<>();
        for (Expression e : expression.exprs(Args.INTERPOLATE)) {
            interpolatedValues.add(e.is(ExprType.ALIAS)
                    ? sql(e, Args.ALIAS) + " AS " + sql(e, Args.THIS)
                    : sql(e, Args.THIS));
        }
        String interpolate = !interpolatedValues.isEmpty()
                ? " INTERPOLATE (" + String.join(", ", interpolatedValues) + ")"
                : "";
        return "WITH FILL" + fromSql + toSql + stepSql + interpolate;
    }

    @Override
    public String clusterSql(Expression expression) {
        return opExpressions("CLUSTER BY", expression);
    }

    @Override
    public String clusterpropertySql(Expression expression) {
        if (expression.getThis() != null) {
            unsupported("Unsupported CLUSTER BY " + sql(expression, Args.THIS));
            return "";
        }
        String expressions = expressions(opts(expression).flat());
        return "CLUSTER BY (" + expressions + ")";
    }

    @Override
    public String distributeSql(Expression expression) {
        return opExpressions("DISTRIBUTE BY", expression);
    }

    @Override
    public String sortSql(Expression expression) {
        return opExpressions("SORT BY", expression);
    }

    /**
     * Resolve a bare ORDER BY name against the enclosing SELECT projection.
     *
     * <p>Returns the underlying expression of the uniquely-matching projection (Alias-stripped)
     * for substitution into the NULLS FIRST/LAST CASE simulation, since the CASE is evaluated in
     * FROM-clause scope rather than alias scope (MySQL error 1052). Returns null if no safe
     * substitution applies, leaving the original behaviour unchanged.
     */
    @Override
    public Expression resolveOrderedForNullOrderingSimulation(Expression expression) {
        Expression this_ = expression.getThis();
        if (!(this_ != null && this_.is(ExprType.COLUMN) && this_.text(Args.TABLE).isEmpty())) {
            return null;
        }

        // Only a query's own ORDER BY can reference its projection aliases
        Expression order = expression.parent();
        Expression ancestor = order != null && order.is(ExprType.ORDER) ? order.parent() : null;
        if (ancestor == null || !ancestor.is(ExprType.SELECT)) {
            return null;
        }

        String columnName = this_.name();
        List<Expression> matched = new ArrayList<>();
        for (Expression p : ancestor.selects()) {
            if (p.outputName().equals(columnName)) {
                matched.add(p.is(ExprType.ALIAS) ? p.getThis() : p);
            }
        }
        Expression match = matched.size() == 1 ? matched.get(0) : null;

        // Skip the substitution when it would be identical to the existing
        // reference (e.g. ``SELECT col FROM t ORDER BY col``).
        if (match != null && match.is(ExprType.COLUMN) && match.text(Args.TABLE).isEmpty()
                && match.name().equals(columnName)) {
            return null;
        }

        return match;
    }

    @Override
    public String orderedSql(Expression expression) {
        Object descValue = expression.get(Args.DESC);
        boolean desc = Expression.truthy(descValue);
        boolean asc = !desc;

        boolean nullsFirst = expression.has(Args.NULLS_FIRST);
        boolean nullsLast = !nullsFirst;
        boolean nullsAreLarge = "nulls_are_large".equals(dd.NULL_ORDERING);
        boolean nullsAreSmall = "nulls_are_small".equals(dd.NULL_ORDERING);
        boolean nullsAreLast = "nulls_are_last".equals(dd.NULL_ORDERING);

        String this_ = sql(expression, Args.THIS);

        String sortOrder = desc ? " DESC" : (Boolean.FALSE.equals(descValue) ? " ASC" : "");
        String nullsSortChange = "";
        if (nullsFirst && ((asc && nullsAreLarge) || (desc && nullsAreSmall) || nullsAreLast)) {
            nullsSortChange = " NULLS FIRST";
        } else if (nullsLast
                && ((asc && nullsAreSmall) || (desc && nullsAreLarge))
                && !nullsAreLast) {
            nullsSortChange = " NULLS LAST";
        }

        // If the NULLS FIRST/LAST clause is unsupported, we add another sort key to simulate it
        if (!nullsSortChange.isEmpty() && !Boolean.TRUE.equals(g.NULL_ORDERING_SUPPORTED)) {
            Expression window = expression.findAncestor(ExprType.WINDOW, ExprType.SELECT);

            Expression windowThis;
            Expression spec;
            if (window != null && window.is(ExprType.WINDOW)) {
                windowThis = window.getThis();
                if (windowThis != null && windowThis.is(ExprType.IGNORE_NULLS, ExprType.RESPECT_NULLS)) {
                    windowThis = windowThis.getThis();
                }
                spec = window.expr(Args.SPEC);
            } else {
                windowThis = null;
                spec = null;
            }

            // Some window functions (e.g. LAST_VALUE, RANK) support NULLS FIRST/LAST
            // without a spec or with a ROWS spec, but not with RANGE
            if (!(windowThis != null
                    && isAny(windowThis, g.WINDOW_FUNCS_WITH_NULL_ORDERING)
                    && (spec == null || Py.upper(spec.text(Args.KIND)).equals("ROWS")))) {
                if (windowThis != null && spec != null) {
                    unsupported("'" + nullsSortChange.strip() + "' translation not supported in window function "
                            + windowThis.type().sqlName());
                    nullsSortChange = "";
                } else if (Boolean.FALSE.equals(g.NULL_ORDERING_SUPPORTED)
                        && ((asc && nullsSortChange.equals(" NULLS LAST"))
                                || (desc && nullsSortChange.equals(" NULLS FIRST")))) {
                    // BigQuery does not allow these ordering/nulls combinations when used under
                    // an aggregation func or under a window containing one
                    Expression ancestor = expression.findAncestor(ExprType.AGG_FUNC, ExprType.WINDOW, ExprType.SELECT);

                    if (ancestor != null && ancestor.is(ExprType.WINDOW)) {
                        ancestor = ancestor.getThis();
                    }
                    if (ancestor != null && ancestor.is(ExprType.AGG_FUNC)) {
                        unsupported("'" + nullsSortChange.strip() + "' translation not supported for aggregate function "
                                + ancestor.type().sqlName() + " with " + sortOrder + " sort order");
                        nullsSortChange = "";
                    }
                } else if (g.NULL_ORDERING_SUPPORTED == null) {
                    Expression orderedThis = expression.getThis();
                    if (orderedThis.isInt()) {
                        unsupported("'" + nullsSortChange.strip() + "' translation not supported with positional ordering");
                    } else if (!orderedThis.is(ExprType.RAND)) {
                        Expression resolved = resolveOrderedForNullOrderingSimulation(expression);
                        String target = resolved != null ? sql(resolved) : this_;
                        String nullSortOrder = nullsSortChange.equals(" NULLS FIRST") ? " DESC" : "";
                        this_ = "CASE WHEN " + target + " IS NULL THEN 1 ELSE 0 END" + nullSortOrder + ", " + target;
                    }
                    nullsSortChange = "";
                }
            }
        }

        String withFill = sql(expression, Args.WITH_FILL);
        withFill = !withFill.isEmpty() ? " " + withFill : "";

        return this_ + sortOrder + nullsSortChange + withFill;
    }

    // ------------------------------------------------------------------------------------------
    // MATCH_RECOGNIZE

    @Override
    public String matchrecognizemeasureSql(Expression expression) {
        String windowFrame = sql(expression, Args.WINDOW_FRAME);
        windowFrame = !windowFrame.isEmpty() ? windowFrame + " " : "";

        String this_ = sql(expression, Args.THIS);

        return windowFrame + this_;
    }

    @Override
    public String matchrecognizeSql(Expression expression) {
        String partition = partitionBySql(expression);
        String order = sql(expression, Args.ORDER);
        String measures = expressions(expression, Args.MEASURES);
        measures = !measures.isEmpty() ? seg("MEASURES" + seg(measures)) : "";
        String rows = sql(expression, Args.ROWS);
        rows = !rows.isEmpty() ? seg(rows) : "";
        String after = sql(expression, Args.AFTER);
        after = !after.isEmpty() ? seg(after) : "";
        String pattern = sql(expression, Args.PATTERN);
        pattern = !pattern.isEmpty() ? seg("PATTERN (" + pattern + ")") : "";
        List<String> definitionSqls = new ArrayList<>();
        for (Expression definition : expression.exprs(Args.DEFINE)) {
            definitionSqls.add(sql(definition, Args.ALIAS) + " AS " + sql(definition, Args.THIS));
        }
        String definitions = expressions(optsSqls(definitionSqls));
        String define = !definitions.isEmpty() ? seg("DEFINE" + seg(definitions)) : "";
        String body = partition + order + measures + rows + after + pattern + define;
        String alias = sql(expression, Args.ALIAS);
        alias = !alias.isEmpty() ? " " + alias : "";
        return seg("MATCH_RECOGNIZE") + " " + wrap(body) + alias;
    }

    // ------------------------------------------------------------------------------------------
    // Query modifiers

    @Override
    public String queryModifiers(Expression expression, String... sqls) {
        Expression limit = expression.expr(Args.LIMIT);

        if ("LIMIT".equals(g.LIMIT_FETCH) && limit != null && limit.is(ExprType.FETCH)) {
            Expression count = limit.expr(Args.COUNT);
            // "FETCH FIRST ROWS ONLY" without a count means one row per the SQL
            // standard; emitting a bare "LIMIT" here would produce invalid SQL.
            limit = Expression.of(ExprType.LIMIT, Args.EXPRESSION,
                    count != null ? maybeCopy(count) : Exp.number(1));
        } else if ("FETCH".equals(g.LIMIT_FETCH) && limit != null && limit.is(ExprType.LIMIT) && !isLimitAll(limit)) {
            limit = Expression.of(ExprType.FETCH, Args.DIRECTION, "FIRST", Args.COUNT, maybeCopy(limit.getExpression()));
        }

        StringBuilder sb = new StringBuilder();
        for (String s : sqls) {
            if (s != null) {
                sb.append(s);
            }
        }
        for (Object join : expression.list(Args.JOINS)) {
            sb.append(sql(join));
        }
        sb.append(sql(expression, Args.MATCH));
        for (Object lateral : expression.list(Args.LATERALS)) {
            sb.append(sql(lateral));
        }
        sb.append(sql(expression, Args.PREWHERE));
        sb.append(sql(expression, Args.WHERE));
        sb.append(sql(expression, Args.CONNECT));
        sb.append(sql(expression, Args.GROUP));
        sb.append(sql(expression, Args.HAVING));
        for (String key : afterHavingModifierKeys()) {
            sb.append(afterHavingModifier(key, expression));
        }
        sb.append(sql(expression, Args.ORDER));
        for (String s : offsetLimitModifiers(expression, limit != null && limit.is(ExprType.FETCH), limit)) {
            sb.append(s);
        }
        for (String s : afterLimitModifiers(expression)) {
            sb.append(s);
        }
        sb.append(sql(expression, Args.FOR_));
        sb.append(optionsModifier(expression));
        return sb.toString();
    }

    @Override
    public String optionsModifier(Expression expression) {
        String options = expressions(expression, Args.OPTIONS);
        return !options.isEmpty() ? " " + options : "";
    }

    @Override
    public String forclauseSql(Expression expression) {
        Object kindValue = expression.get(Args.KIND);
        String kind = kindValue instanceof String s ? s : sql(kindValue);
        if (kind.equals("BROWSE")) {
            return sep() + "FOR BROWSE";
        }
        // FOR XML/JSON always carry at least AUTO/PATH. An empty rendering means
        // the target dialect doesn't support QueryOption, so we drop the clause.
        String options = expressions(expression, Args.EXPRESSIONS);
        if (options.isEmpty()) {
            return "";
        }
        return sep() + "FOR " + kind + seg(options);
    }

    @Override
    public String queryoptionSql(Expression expression) {
        unsupported("Unsupported query option.");
        return "";
    }

    @Override
    public Collection<String> offsetLimitModifiers(Expression expression, boolean fetch, Expression limit) {
        return List.of(
                fetch ? sql(expression, Args.OFFSET) : sql(limit),
                fetch ? sql(limit) : sql(expression, Args.OFFSET));
    }

    @Override
    public Collection<String> afterLimitModifiers(Expression expression) {
        String locks = expressions(opts(expression).key(Args.LOCKS).sep(" "));
        locks = !locks.isEmpty() ? " " + locks : "";
        return List.of(locks, sql(expression, Args.SAMPLE));
    }

    // ------------------------------------------------------------------------------------------
    // SELECT

    @Override
    public String selectSql(Expression expression) {
        Expression into = expression.expr(Args.INTO);
        if (!g.SUPPORTS_SELECT_INTO && into != null) {
            into.pop();
        }

        String hint = sql(expression, Args.HINT);
        String distinct = sql(expression, Args.DISTINCT);
        distinct = !distinct.isEmpty() ? " " + distinct : "";
        String kind = sql(expression, Args.KIND);

        Expression limit = expression.expr(Args.LIMIT);
        String top;
        if (limit != null && limit.is(ExprType.LIMIT) && g.LIMIT_IS_TOP) {
            top = limitSql(limit, true);
            limit.pop();
        } else {
            top = "";
        }

        String expressions = expressions(expression);

        if (!kind.isEmpty()) {
            if (g.SELECT_KINDS.contains(kind)) {
                kind = " AS " + kind;
            } else {
                if (kind.equals("STRUCT")) {
                    List<Expression> structArgs = new ArrayList<>();
                    for (Expression e : expression.getExpressions()) {
                        structArgs.add(e.is(ExprType.ALIAS)
                                ? Expression.of(ExprType.PROPERTY_EQ, Args.THIS, e.get(Args.ALIAS), Args.EXPRESSION, e.getThis())
                                : e);
                    }
                    expressions = expressions(optsSqls(List.of(
                            sql(Expression.of(ExprType.STRUCT, Args.EXPRESSIONS, structArgs)))));
                }
                kind = "";
            }
        }

        String operationModifiers = expressions(opts(expression).key(Args.OPERATION_MODIFIERS).sep(" "));
        operationModifiers = !operationModifiers.isEmpty() ? sep() + operationModifiers : "";

        List<Object> exclude = expression.list(Args.EXCLUDE);

        if (!g.STAR_EXCLUDE_REQUIRES_DERIVED_TABLE && !exclude.isEmpty()) {
            String excludeSql = expressions(optsSqls(exclude).flat());
            expressions = expressions + seg("EXCLUDE") + " (" + excludeSql + ")";
        }

        // We use LIMIT_IS_TOP as a proxy for whether DISTINCT should go first because tsql and Teradata
        // are the only dialects that use LIMIT_IS_TOP and both place DISTINCT first.
        String topDistinct = g.LIMIT_IS_TOP ? distinct + hint + top : top + hint + distinct;
        expressions = !expressions.isEmpty() ? sep() + expressions : expressions;
        String sqlText = queryModifiers(
                expression,
                "SELECT" + topDistinct + operationModifiers + kind + expressions,
                sqlNoComment(expression, Args.INTO),
                sqlNoComment(expression, Args.FROM_));

        // If both the CTE and SELECT clauses have comments, generate the latter earlier
        if (expression.has(Args.WITH_)) {
            sqlText = maybeComment(sqlText, expression);
            expression.popComments();
        }

        sqlText = prependCtes(expression, sqlText);

        if (g.STAR_EXCLUDE_REQUIRES_DERIVED_TABLE && !exclude.isEmpty()) {
            List<Object> excludeList = new ArrayList<>(exclude);
            expression.set(Args.EXCLUDE, null);
            Expression subquery = expression.subquery(null, false);
            Expression star = Expression.of(ExprType.STAR, Args.EXCEPT_, excludeList);
            sqlText = sql(Exp.select(star).from(subquery, false));
        }

        if (!g.SUPPORTS_SELECT_INTO && into != null) {
            String tableKind;
            if (into.has(Args.TEMPORARY)) {
                tableKind = " TEMPORARY";
            } else if (g.SUPPORTS_UNLOGGED_TABLES && into.has(Args.UNLOGGED)) {
                tableKind = " UNLOGGED";
            } else {
                tableKind = "";
            }
            sqlText = "CREATE" + tableKind + " TABLE " + sql(into.getThis()) + " AS " + sqlText;
        }

        return sqlText;
    }

    @Override
    public String schemaSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String sqlText = schemaColumnsSql(expression);
        return !this_.isEmpty() && !sqlText.isEmpty() ? this_ + " " + sqlText : (!this_.isEmpty() ? this_ : sqlText);
    }

    @Override
    public String schemaColumnsSql(Expression expression) {
        if (!expression.getExpressions().isEmpty()) {
            return "(" + sep("") + expressions(expression) + seg(")", "");
        }
        return "";
    }

    @Override
    public String starSql(Expression expression) {
        String except = expressions(opts(expression).key(Args.EXCEPT_).flat());
        except = !except.isEmpty() ? seg(g.STAR_EXCEPT) + " (" + except + ")" : "";
        String replace = expressions(opts(expression).key(Args.REPLACE).flat());
        replace = !replace.isEmpty() ? seg("REPLACE") + " (" + replace + ")" : "";
        String rename = expressions(opts(expression).key(Args.RENAME).flat());
        rename = !rename.isEmpty() ? seg("RENAME") + " (" + rename + ")" : "";
        String ilike = sql(expression, Args.ILIKE);
        ilike = !ilike.isEmpty() ? seg("ILIKE") + " " + ilike : "";
        return "*" + ilike + except + replace + rename;
    }

    @Override
    public String parameterSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        return g.PARAMETER_TOKEN + this_;
    }

    @Override
    public String sessionparameterSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String kind = expression.text(Args.KIND);
        if (!kind.isEmpty()) {
            kind = kind + ".";
        }
        return "@@" + kind + this_;
    }

    @Override
    public String placeholderSql(Expression expression) {
        return Expression.truthy(expression.thisValue()) ? g.NAMED_PLACEHOLDER_TOKEN + expression.name() : "?";
    }

    @Override
    public String subquerySql(Expression expression, String sep) {
        String alias = sql(expression, Args.ALIAS);
        alias = !alias.isEmpty() ? sep + alias : "";
        String sample = sql(expression, Args.SAMPLE);
        if (dd.ALIAS_POST_TABLESAMPLE && !sample.isEmpty()) {
            alias = sample + alias;

            // Set to None so it's not generated again by self.query_modifiers()
            expression.set(Args.SAMPLE, null);
        }

        String pivots = expressions(opts(expression).key(Args.PIVOTS).sep("").flat());
        String sqlText = queryModifiers(expression, wrap(expression), alias, pivots);
        return prependCtes(expression, sqlText);
    }

    @Override
    public String qualifySql(Expression expression) {
        String this_ = indent(sql(expression, Args.THIS));
        return seg("QUALIFY") + sep() + this_;
    }

    @Override
    public String unnestSql(Expression expression) {
        String args = expressions(opts(expression).flat());

        Expression aliasNode = expression.expr(Args.ALIAS);
        Object offset = expression.get(Args.OFFSET);

        if (g.UNNEST_WITH_ORDINALITY) {
            if (aliasNode != null && offset instanceof Expression offsetExpr) {
                aliasNode.append(Args.COLUMNS, offsetExpr);
                expression.set(Args.OFFSET, null);
            }
        }

        String alias;
        if (aliasNode != null && dd.UNNEST_COLUMN_ONLY) {
            List<Expression> columns = aliasNode.exprs(Args.COLUMNS);
            alias = !columns.isEmpty() ? sql(columns.get(0)) : "";
        } else {
            alias = sql(aliasNode);
        }

        alias = !alias.isEmpty() ? " AS " + alias : alias;
        String suffix;
        if (g.UNNEST_WITH_ORDINALITY) {
            suffix = Expression.truthy(offset) ? " WITH ORDINALITY" + alias : alias;
        } else {
            if (offset instanceof Expression) {
                suffix = alias + " WITH OFFSET AS " + sql(offset);
            } else if (Expression.truthy(offset)) {
                suffix = alias + " WITH OFFSET";
            } else {
                suffix = alias;
            }
        }

        return "UNNEST(" + args + ")" + suffix;
    }

    @Override
    public String prewhereSql(Expression expression) {
        return "";
    }

    @Override
    public String whereSql(Expression expression) {
        String this_ = indent(sql(expression, Args.THIS));
        return seg("WHERE") + sep() + this_;
    }

    // ------------------------------------------------------------------------------------------
    // Windows

    @Override
    public String windowSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String partition = partitionBySql(expression);
        Expression orderExpr = expression.expr(Args.ORDER);
        String order = orderExpr != null ? orderSql(orderExpr, true) : "";
        String spec = sql(expression, Args.SPEC);
        String alias = sql(expression, Args.ALIAS);
        String over = sql(expression, Args.OVER);
        if (over.isEmpty()) {
            over = "OVER";
        }

        this_ = this_ + " " + (expression.argKey() == Args.WINDOWS ? "AS" : over);

        Object firstValue = expression.get(Args.FIRST);
        String first;
        if (firstValue == null) {
            first = "";
        } else {
            first = Expression.truthy(firstValue) ? "FIRST" : "LAST";
        }

        if (partition.isEmpty() && order.isEmpty() && spec.isEmpty() && !alias.isEmpty()) {
            return this_ + " " + alias;
        }

        List<Object> argList = new ArrayList<>(5);
        for (String arg : new String[] {alias, first, partition, order, spec}) {
            if (!arg.isEmpty()) {
                argList.add(arg);
            }
        }
        String args = formatArgsSep(" ", argList.toArray());
        return this_ + " (" + args + ")";
    }

    @Override
    public String partitionBySql(Expression expression) {
        String partition = expressions(opts(expression).key(Args.PARTITION_BY).flat());
        return !partition.isEmpty() ? "PARTITION BY " + partition : "";
    }

    @Override
    public String windowspecSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        String start = Py.csv(" ", new String[] {sql(expression, Args.START), sql(expression, Args.START_SIDE)});
        String end = Py.csv(" ", new String[] {sql(expression, Args.END), sql(expression, Args.END_SIDE)});
        if (end.isEmpty()) {
            end = "CURRENT ROW";
        }

        String windowSpec = kind + " BETWEEN " + start + " AND " + end;

        String exclude = sql(expression, Args.EXCLUDE);
        if (!exclude.isEmpty()) {
            if (g.SUPPORTS_WINDOW_EXCLUDE) {
                windowSpec += " EXCLUDE " + exclude;
            } else {
                unsupported("EXCLUDE clause is not supported in the WINDOW clause");
            }
        }

        return windowSpec;
    }

    @Override
    public String withingroupSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String expressionSql = Py.slice(sql(expression, Args.EXPRESSION), 1); // order has a leading space
        return this_ + " WITHIN GROUP (" + expressionSql + ")";
    }

    @Override
    public String betweenSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String low = sql(expression, Args.LOW);
        String high = sql(expression, Args.HIGH);
        Object symmetric = expression.get(Args.SYMMETRIC);

        if (Expression.truthy(symmetric) && !g.SUPPORTS_BETWEEN_FLAGS) {
            return "(" + this_ + " BETWEEN " + low + " AND " + high + " OR " + this_ + " BETWEEN " + high + " AND " + low + ")";
        }

        String flag = Expression.truthy(symmetric)
                ? " SYMMETRIC"
                : Boolean.FALSE.equals(symmetric) && g.SUPPORTS_BETWEEN_FLAGS
                        ? " ASYMMETRIC"
                        : ""; // silently drop ASYMMETRIC – semantics identical
        return this_ + " BETWEEN" + flag + " " + low + " AND " + high;
    }

    // ------------------------------------------------------------------------------------------
    // Brackets, ALL/ANY/EXISTS, CASE

    @Override
    public List<Expression> bracketOffsetExpressions(Expression expression, Integer indexOffset) {
        if (expression.has(Args.JSON_ACCESS)) {
            return expression.getExpressions();
        }

        Object offsetValue = expression.get(Args.OFFSET);
        int offset = offsetValue instanceof Number n ? n.intValue() : 0;
        int base = indexOffset != null && indexOffset != 0 ? indexOffset : dd.INDEX_OFFSET;
        return Exp.applyIndexOffset(expression.getThis(), expression.getExpressions(), base - offset, dialect);
    }

    @Override
    public String bracketSql(Expression expression) {
        List<Expression> expressions = bracketOffsetExpressions(expression);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < expressions.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(sql(expressions.get(i)));
        }
        return sql(expression, Args.THIS) + "[" + sb + "]";
    }

    @Override
    public String allSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        Expression thisExpr = expression.getThis();
        if (!(thisExpr != null && thisExpr.is(ExprType.TUPLE, ExprType.PAREN))) {
            this_ = wrap(this_);
        }
        return "ALL " + this_;
    }

    @Override
    public String anySql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        Expression thisExpr = expression.getThis();
        if (thisExpr != null && thisExpr.is(ExprType.SELECT, ExprType.SET_OPERATION, ExprType.PAREN)) {
            if (thisExpr.is(ExprType.SELECT, ExprType.SET_OPERATION)) {
                this_ = wrap(this_);
            }
            return "ANY" + this_;
        }
        return "ANY " + this_;
    }

    @Override
    public String existsSql(Expression expression) {
        return "EXISTS" + wrap(expression);
    }

    @Override
    public String caseSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        List<String> statements = new ArrayList<>();
        statements.add(!this_.isEmpty() ? "CASE " + this_ : "CASE");

        for (Expression e : expression.exprs(Args.IFS)) {
            statements.add("WHEN " + sql(e, Args.THIS));
            statements.add("THEN " + sql(e, Args.TRUE));
        }

        String default_ = sql(expression, Args.DEFAULT);

        if (!default_.isEmpty()) {
            statements.add("ELSE " + default_);
        }

        statements.add("END");

        if (pretty && tooWide(statements)) {
            return indent(String.join("\n", statements), 0, null, true, true);
        }

        return String.join(" ", statements);
    }

    @Override
    public String constraintSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String expressions = expressions(opts(expression).flat());
        return "CONSTRAINT " + this_ + " " + expressions;
    }

    @Override
    public String nextvalueforSql(Expression expression) {
        Expression orderExpr = expression.expr(Args.ORDER);
        String order = orderExpr != null ? " OVER (" + orderSql(orderExpr, true) + ")" : "";
        return "NEXT VALUE FOR " + sql(expression, Args.THIS) + order;
    }

    @Override
    public String extractSql(Expression expression) {
        Expression this_ = g.NORMALIZE_EXTRACT_DATE_PARTS ? mapDatePart(expression.getThis()) : expression.getThis();
        String thisSql;
        if (g.EXTRACT_ALLOWS_QUOTES) {
            thisSql = sql(this_);
        } else if (this_ != null && this_.is(ExprType.WEEK_START)) {
            thisSql = weekstartName(this_);
        } else {
            thisSql = this_.name();
        }
        String expressionSql = sql(expression, Args.EXPRESSION);

        return "EXTRACT(" + thisSql + " FROM " + expressionSql + ")";
    }

    @Override
    public String trimSql(Expression expression) {
        String trimType = sql(expression, Args.POSITION);

        String funcName;
        if (trimType.equals("LEADING")) {
            funcName = "LTRIM";
        } else if (trimType.equals("TRAILING")) {
            funcName = "RTRIM";
        } else {
            funcName = "TRIM";
        }

        return func(funcName, expression.getThis(), expression.getExpression());
    }

    // ------------------------------------------------------------------------------------------
    // CONCAT

    @Override
    public List<Expression> convertConcatArgs(Expression expression) {
        List<Expression> args = expression.getExpressions();
        boolean isConcatWs = expression.is(ExprType.CONCAT_WS);
        if (isConcatWs) {
            args = Py.slice(args, 1); // Skip the delimiter
        }

        if (dd.STRICT_STRING_CONCAT && expression.has(Args.SAFE)) {
            List<Expression> casted = new ArrayList<>(args.size());
            for (Expression e : args) {
                casted.add(Exp.cast(e, DType.TEXT));
            }
            args = casted;
        }

        boolean concatCoalesce = isConcatWs ? dd.CONCAT_WS_COALESCE : dd.CONCAT_COALESCE;

        if (!concatCoalesce && expression.has(Args.COALESCE)) {
            List<Expression> wrapped = new ArrayList<>(args.size());
            for (Expression e : args) {
                wrapped.add(wrapWithCoalesce(e));
            }
            args = wrapped;
        }

        return args;
    }

    private Expression wrapWithCoalesce(Expression e) {
        if (e.dataType() == null) {
            e = TypeAnnotator.annotateTypes(e, dialect);
        }

        if (e.isString() || e.isType(DType.ARRAY)) {
            return e;
        }

        return Exp.func("coalesce", Dialects.base(), e, Exp.string(""));
    }

    @Override
    public String concatSql(Expression expression) {
        if (dd.CONCAT_COALESCE && !expression.has(Args.COALESCE)) {
            // Dialect's CONCAT function coalesces NULLs to empty strings, but the expression does not.
            // Transpile to double pipe operators, which typically returns NULL if any args are NULL
            // instead of coalescing them to empty string.
            return concatToDpipeSql(expression);
        }

        List<Expression> expressions = convertConcatArgs(expression);

        // Some dialects don't allow a single-argument CONCAT call
        if (!g.SUPPORTS_SINGLE_ARG_CONCAT && expressions.size() == 1) {
            return sql(expressions.get(0));
        }

        return func("CONCAT", expressions.toArray());
    }

    @Override
    public String concatwsSql(Expression expression) {
        if (dd.CONCAT_WS_COALESCE && !expression.has(Args.COALESCE)) {
            // Dialect's CONCAT_WS function skips NULL args, but the expression does not.
            // Wrap the entire call in a CASE expression that returns NULL if any input IS NULL.
            List<Expression> allArgs = expression.getExpressions();
            expression.set(Args.COALESCE, Boolean.TRUE);
            Expression[] isNulls = new Expression[allArgs.size()];
            for (int i = 0; i < isNulls.length; i++) {
                // arg.is_(exp.null()): copies the operand and wraps binaries in parens
                isNulls[i] = Expression.of(ExprType.IS,
                        Args.THIS, Exp.wrap(allArgs.get(i).copy(), ExprType.BINARY),
                        Args.EXPRESSION, Exp.nul());
            }
            return sql(Exp.caseExpr(null)
                    .when(Exp.or(isNulls), Exp.nul(), true)
                    .elseCase(expression, true));
        }

        List<Expression> rest = convertConcatArgs(expression);
        Object[] args = new Object[rest.size() + 1];
        args[0] = Py.seqGet(expression.getExpressions(), 0);
        for (int i = 0; i < rest.size(); i++) {
            args[i + 1] = rest.get(i);
        }
        return func("CONCAT_WS", args);
    }

    // ------------------------------------------------------------------------------------------
    // Constraints

    @Override
    public String checkSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        return "CHECK (" + this_ + ")";
    }

    @Override
    public String foreignkeySql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        this_ = !this_.isEmpty() ? " " + this_ : "";
        String expressions = expressions(opts(expression).flat());
        expressions = !expressions.isEmpty() ? " (" + expressions + ")" : "";
        String reference = sql(expression, Args.REFERENCE);
        reference = !reference.isEmpty() ? " " + reference : "";
        String delete = sql(expression, Args.DELETE);
        delete = !delete.isEmpty() ? " ON DELETE " + delete : "";
        String update = sql(expression, Args.UPDATE);
        update = !update.isEmpty() ? " ON UPDATE " + update : "";
        String options = expressions(opts(expression).key(Args.OPTIONS).flat().sep(" "));
        options = !options.isEmpty() ? " " + options : "";
        return "FOREIGN KEY" + this_ + expressions + reference + delete + update + options;
    }

    @Override
    public String primarykeySql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        this_ = !this_.isEmpty() ? " " + this_ : "";
        String expressions = expressions(opts(expression).flat());
        String include = sql(expression, Args.INCLUDE);
        String options = expressions(opts(expression).key(Args.OPTIONS).flat().sep(" "));
        options = !options.isEmpty() ? " " + options : "";
        return "PRIMARY KEY" + this_ + " (" + expressions + ")" + include + options;
    }

    @Override
    public String timeserieskeySql(Expression expression) {
        unsupported("TIMESERIES primary key columns are not supported");
        return sql(expression, Args.THIS);
    }
}
