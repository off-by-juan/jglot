package io.github.offbyjuan.jglot.generator;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.util.TimeFormat;
import io.github.offbyjuan.jglot.util.Trie;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Port of sqlglot's Generator, part D: {@code if_sql} through {@code comprehension_sql}. */
abstract class GeneratorPartD extends GeneratorMethods {
    GeneratorPartD(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }

    // ------------------------------------------------------------------------------------------
    // Private helpers

    /** sqlglot's {@code exp.Properties.PROPERTY_TO_NAME} (inverse of NAME_TO_PROPERTY). */
    private static final Map<ExprType, String> PROPERTY_TO_NAME;

    static {
        Map<ExprType, String> m = new EnumMap<>(ExprType.class);
        m.put(ExprType.ALGORITHM_PROPERTY, "ALGORITHM");
        m.put(ExprType.AUTO_INCREMENT_PROPERTY, "AUTO_INCREMENT");
        m.put(ExprType.CHARACTER_SET_PROPERTY, "CHARACTER SET");
        m.put(ExprType.CLUSTERED_BY_PROPERTY, "CLUSTERED_BY");
        m.put(ExprType.COLLATE_PROPERTY, "COLLATE");
        m.put(ExprType.SCHEMA_COMMENT_PROPERTY, "COMMENT");
        m.put(ExprType.CREDENTIALS_PROPERTY, "CREDENTIALS");
        m.put(ExprType.DEFINER_PROPERTY, "DEFINER");
        m.put(ExprType.DIST_KEY_PROPERTY, "DISTKEY");
        m.put(ExprType.DISTRIBUTED_BY_PROPERTY, "DISTRIBUTED_BY");
        m.put(ExprType.DIST_STYLE_PROPERTY, "DISTSTYLE");
        m.put(ExprType.ENGINE_PROPERTY, "ENGINE");
        m.put(ExprType.EXECUTE_AS_PROPERTY, "EXECUTE AS");
        m.put(ExprType.FILE_FORMAT_PROPERTY, "FORMAT");
        m.put(ExprType.LANGUAGE_PROPERTY, "LANGUAGE");
        m.put(ExprType.LOCATION_PROPERTY, "LOCATION");
        m.put(ExprType.LOCK_PROPERTY, "LOCK");
        m.put(ExprType.PARTITIONED_BY_PROPERTY, "PARTITIONED_BY");
        m.put(ExprType.RETURNS_PROPERTY, "RETURNS");
        m.put(ExprType.ROW_FORMAT_PROPERTY, "ROW_FORMAT");
        m.put(ExprType.SORT_KEY_PROPERTY, "SORTKEY");
        m.put(ExprType.ENCODE_PROPERTY, "ENCODE");
        m.put(ExprType.INCLUDE_PROPERTY, "INCLUDE");
        PROPERTY_TO_NAME = java.util.Collections.unmodifiableMap(m);
    }

    /** Python's {@code f"{value}"} for a raw arg value (expressions render via {@code str()}). */
    private static String pyStr(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (v instanceof Expression e) {
            return e.sql(Dialects.base());
        }
        return v.toString();
    }

    /** Python's {@code str.lstrip(chars)}. */
    private static String lstripChars(String s, String chars) {
        int i = 0;
        while (i < s.length() && chars.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return s.substring(i);
    }

    /** sqlglot's {@code Expr._binop(klass, other)}. */
    private static Expression binop(ExprType klass, Expression self, Object other) {
        Expression thisExpr = self.copy();
        Expression otherExpr = other instanceof Expression o ? o.copy() : Exp.convert(other);
        if (!thisExpr.is(klass) && !otherExpr.is(klass)) {
            thisExpr = Exp.wrap(thisExpr, ExprType.BINARY);
            otherExpr = Exp.wrap(otherExpr, ExprType.BINARY);
        }
        return Expression.of(klass, Args.THIS, thisExpr, Args.EXPRESSION, otherExpr);
    }

    /** {@code case.when(condition, then, copy=False)}. */
    private static Expression caseWhen(Expression caseExpr, Expression condition, Expression then) {
        caseExpr.append(Args.IFS, Expression.of(ExprType.IF, Args.THIS, condition, Args.TRUE, then));
        return caseExpr;
    }

    private static String prefixed(String prefix, String s) {
        return s.isEmpty() ? "" : prefix + s;
    }

    // ------------------------------------------------------------------------------------------

    @Override
    public String ifSql(Expression expression) {
        List<Object> ifs = new ArrayList<>();
        ifs.add(expression);
        return caseSql(Expression.of(ExprType.CASE, Args.IFS, ifs, Args.DEFAULT, expression.get(Args.FALSE)));
    }

    @Override
    public String matchagainstSql(Expression expression) {
        List<Object> expressions = new ArrayList<>();
        if (Expression.truthy(g.MATCH_AGAINST_TABLE_PREFIX)) {
            for (Expression expr : expression.getExpressions()) {
                if (expr.is(ExprType.TABLE)) {
                    expressions.add("TABLE " + sql(expr));
                } else {
                    expressions.add(expr);
                }
            }
        } else {
            expressions.addAll(expression.getExpressions());
        }

        Object modifier = expression.get(Args.MODIFIER);
        String modifierSql = Expression.truthy(modifier) ? " " + pyStr(modifier) : "";
        return func("MATCH", expressions.toArray()) + " AGAINST(" + sql(expression, Args.THIS) + modifierSql + ")";
    }

    @Override
    public String jsonkeyvalueSql(Expression expression) {
        return sql(expression, Args.THIS) + g.JSON_KEY_VALUE_PAIR_SEP + " " + sql(expression, Args.EXPRESSION);
    }

    @Override
    public String jsonpathSql(Expression expression) {
        String path = lstripChars(expressions(opts(expression).sep("").flat()), ".");

        if (g.QUOTE_JSON_PATH) {
            path = escapeStr(path);
            path = dd.QUOTE_START + path + dd.QUOTE_END;
        }

        return path;
    }

    @Override
    public String jsonPathPart(Object expression) {
        if (expression instanceof Expression e && e.is(ExprType.JSON_PATH_PART)) {
            String rename = g.TRANSFORMS_RENAME.get(e.type());
            if (rename != null) {
                return renameFunc(rename, e);
            }
            if (!g.TRANSFORMS_CUSTOM.contains(e.type())) {
                unsupported("Unsupported JSONPathPart type " + e.type().className());
                return "";
            }
            return transform(e);
        }

        if (expression instanceof Integer || expression instanceof Long) {
            return expression.toString();
        }

        String s = String.valueOf(expression);
        String escaped;
        if (quoteJsonPathKeyUsingBrackets && g.JSON_PATH_SINGLE_QUOTE_ESCAPE) {
            escaped = s.replace("'", "\\'");
            escaped = "'" + escaped + "'";
        } else {
            escaped = s.replace("\"", "\\\"");
            escaped = "\"" + escaped + "\"";
        }

        return escaped;
    }

    @Override
    public String formatjsonSql(Expression expression) {
        return sql(expression, Args.THIS) + " FORMAT JSON";
    }

    @Override
    public String formatphraseSql(Expression expression) {
        // Output the Teradata column FORMAT override.
        // https://docs.teradata.com/r/Enterprise_IntelliFlex_VMware/SQL-Data-Types-and-Literals/Data-Type-Formats-and-Format-Phrases/FORMAT
        String thisSql = sql(expression, Args.THIS);
        String fmt = sql(expression, Args.FORMAT);
        return thisSql + " (FORMAT " + fmt + ")";
    }

    @Override
    public String jsonobjectSql(Expression expression, String name) {
        Object nullHandlingV = expression.get(Args.NULL_HANDLING);
        String nullHandling = Expression.truthy(nullHandlingV) ? " " + pyStr(nullHandlingV) : "";

        Object uniqueKeysV = expression.get(Args.UNIQUE_KEYS);
        String uniqueKeys;
        if (uniqueKeysV != null) {
            uniqueKeys = " " + (Expression.truthy(uniqueKeysV) ? "WITH" : "WITHOUT") + " UNIQUE KEYS";
        } else {
            uniqueKeys = "";
        }

        String returnType = prefixed(" RETURNING ", sql(expression, Args.RETURN_TYPE));
        String encoding = prefixed(" ENCODING ", sql(expression, Args.ENCODING));

        if (name == null || name.isEmpty()) {
            name = expression.is(ExprType.JSON_OBJECT) ? "JSON_OBJECT" : "JSON_OBJECTAGG";
        }

        return funcWith(name, "(", nullHandling + uniqueKeys + returnType + encoding + ")", true,
                expression.getExpressions().toArray());
    }

    @Override
    public String jsonarraySql(Expression expression) {
        Object nullHandlingV = expression.get(Args.NULL_HANDLING);
        String nullHandling = Expression.truthy(nullHandlingV) ? " " + pyStr(nullHandlingV) : "";
        String returnType = prefixed(" RETURNING ", sql(expression, Args.RETURN_TYPE));
        String strict = expression.has(Args.STRICT) ? " STRICT" : "";
        return funcWith("JSON_ARRAY", "(", nullHandling + returnType + strict + ")", true,
                expression.getExpressions().toArray());
    }

    @Override
    public String jsonarrayaggSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String order = sql(expression, Args.ORDER);
        Object nullHandlingV = expression.get(Args.NULL_HANDLING);
        String nullHandling = Expression.truthy(nullHandlingV) ? " " + pyStr(nullHandlingV) : "";
        String returnType = prefixed(" RETURNING ", sql(expression, Args.RETURN_TYPE));
        String strict = expression.has(Args.STRICT) ? " STRICT" : "";
        return funcWith("JSON_ARRAYAGG", "(", order + nullHandling + returnType + strict + ")", true, thisSql);
    }

    @Override
    public String jsoncolumndefSql(Expression expression) {
        String path = prefixed(" PATH ", sql(expression, Args.PATH));
        String nestedSchema = sql(expression, Args.NESTED_SCHEMA);

        if (!nestedSchema.isEmpty()) {
            return "NESTED" + path + " " + nestedSchema;
        }

        String thisSql = sql(expression, Args.THIS);
        String kind = prefixed(" ", sql(expression, Args.KIND));
        String formatJson = expression.has(Args.FORMAT_JSON) ? " FORMAT JSON" : "";

        String ordinality = expression.has(Args.ORDINALITY) ? " FOR ORDINALITY" : "";
        return thisSql + kind + formatJson + path + ordinality;
    }

    @Override
    public String jsonschemaSql(Expression expression) {
        return func("COLUMNS", expression.getExpressions().toArray());
    }

    @Override
    public String jsontableSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String path = prefixed(", ", sql(expression, Args.PATH));
        Object errorHandlingV = expression.get(Args.ERROR_HANDLING);
        String errorHandling = Expression.truthy(errorHandlingV) ? " " + pyStr(errorHandlingV) : "";
        Object emptyHandlingV = expression.get(Args.EMPTY_HANDLING);
        String emptyHandling = Expression.truthy(emptyHandlingV) ? " " + pyStr(emptyHandlingV) : "";
        String schema = sql(expression, Args.SCHEMA);
        return funcWith("JSON_TABLE", "(", path + errorHandling + emptyHandling + " " + schema + ")", true, thisSql);
    }

    @Override
    public String openjsoncolumndefSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String kind = sql(expression, Args.KIND);
        String path = prefixed(" ", sql(expression, Args.PATH));
        String asJson = expression.has(Args.AS_JSON) ? " AS JSON" : "";
        return thisSql + " " + kind + path + asJson;
    }

    @Override
    public String openjsonSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String path = prefixed(", ", sql(expression, Args.PATH));
        String expressionsSql = expressions(expression);
        String with = !expressionsSql.isEmpty()
                ? " WITH (" + seg(indent(expressionsSql), "") + seg(")", "")
                : "";
        return "OPENJSON(" + thisSql + path + ")" + with;
    }

    @Override
    public String inSql(Expression expression) {
        Object query = expression.get(Args.QUERY);
        Object unnest = expression.get(Args.UNNEST);
        Object field = expression.get(Args.FIELD);
        String isGlobal = expression.has(Args.IS_GLOBAL) ? " GLOBAL" : "";

        String inSql;
        if (Expression.truthy(query)) {
            inSql = sql(query);
        } else if (Expression.truthy(unnest)) {
            inSql = inUnnestOp((Expression) unnest);
        } else if (Expression.truthy(field)) {
            inSql = sql(field);
        } else {
            inSql = "(" + expressions(opts(expression).dynamic().newLine().skipFirst().skipLast()) + ")";
        }

        return sql(expression, Args.THIS) + isGlobal + " IN " + inSql;
    }

    @Override
    public String inUnnestOp(Expression unnest) {
        return "(SELECT " + sql(unnest) + ")";
    }

    @Override
    public String intervalSql(Expression expression) {
        boolean includeKeyword = !g.AUTO_REFRESH_BARE_INTERVALS || !Expression.isInstance(
                expression.findAncestor(ExprType.AUTO_REFRESH_PROPERTY, ExprType.SELECT),
                ExprType.AUTO_REFRESH_PROPERTY);
        String intervalKeyword = includeKeyword ? "INTERVAL" : "";
        Object unitExpression = expression.get(Args.UNIT);
        String unit = Expression.truthy(unitExpression) ? sql(unitExpression) : "";
        if (!g.INTERVAL_ALLOWS_PLURAL_FORM) {
            unit = g.TIME_PART_SINGULARS.getOrDefault(unit, unit);
        }
        unit = prefixed(" ", unit);

        Expression thisExpr = expression.getThis();
        if (g.SINGLE_STRING_INTERVAL) {
            String thisSql = thisExpr != null ? thisExpr.name() : "";
            if (!thisSql.isEmpty()) {
                intervalKeyword = !intervalKeyword.isEmpty() ? intervalKeyword + " " : "";
                if (Expression.truthy(unitExpression) && Expression.isInstance(unitExpression, ExprType.INTERVAL_SPAN)) {
                    return intervalKeyword + "'" + thisSql + "'" + unit;
                }
                return intervalKeyword + "'" + thisSql + unit + "'";
            }
            return intervalKeyword + unit;
        }

        String thisSql = sql(expression, Args.THIS);
        if (!thisSql.isEmpty()) {
            if (!includeKeyword && thisExpr.isString()) {
                thisSql = thisExpr.name();
            }
            if (!isAny(thisExpr, g.UNWRAPPED_INTERVAL_VALUES)) {
                thisSql = "(" + thisSql + ")";
            }
            if (includeKeyword) {
                thisSql = " " + thisSql;
            }
        }

        return intervalKeyword + thisSql + unit;
    }

    @Override
    public String returnSql(Expression expression) {
        return "RETURN " + sql(expression, Args.THIS);
    }

    @Override
    public String referenceSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expressionsSql = expressions(opts(expression).flat());
        expressionsSql = !expressionsSql.isEmpty() ? "(" + expressionsSql + ")" : "";
        String options = prefixed(" ", expressions(opts(expression).key(Args.OPTIONS).flat().sep(" ")));
        return "REFERENCES " + thisSql + expressionsSql + options;
    }

    @Override
    public String anonymousSql(Expression expression) {
        // We don't normalize qualified functions such as a.b.foo(), because they can be case-sensitive
        Expression parent = expression.parent();
        boolean isQualified = parent != null && parent.is(ExprType.DOT) && expression == parent.getExpression();

        return funcWith(sql(expression, Args.THIS), "(", ")", !isQualified, expression.getExpressions().toArray());
    }

    @Override
    public String parenSql(Expression expression) {
        String s = seg(indent(sql(expression, Args.THIS)), "");
        return "(" + s + seg(")", "");
    }

    @Override
    public String negSql(Expression expression) {
        // This makes sure we don't convert "- - 5" to "--5", which is a comment
        String thisSql = sql(expression, Args.THIS);
        String sep = thisSql.charAt(0) == '-' ? " " : "";
        return "-" + sep + thisSql;
    }

    @Override
    public String notSql(Expression expression) {
        return "NOT " + sql(expression, Args.THIS);
    }

    @Override
    public String aliasSql(Expression expression) {
        String alias = prefixed(" AS ", sql(expression, Args.ALIAS));
        return sql(expression, Args.THIS) + alias;
    }

    @Override
    public String pivotaliasSql(Expression expression) {
        Expression alias = expression.expr(Args.ALIAS);

        Expression parent = expression.parent();
        Expression pivot = parent != null ? parent.parent() : null;

        if (pivot != null && pivot.is(ExprType.PIVOT) && pivot.has(Args.UNPIVOT)) {
            boolean identifierAlias = alias != null && alias.is(ExprType.IDENTIFIER);
            boolean literalAlias = alias != null && alias.is(ExprType.LITERAL);

            if (identifierAlias && !g.UNPIVOT_ALIASES_ARE_IDENTIFIERS) {
                alias.replace(Exp.string(alias.outputName()));
            } else if (!identifierAlias && literalAlias && g.UNPIVOT_ALIASES_ARE_IDENTIFIERS) {
                alias.replace(Exp.toIdentifier(alias.outputName()));
            }
        }

        return aliasSql(expression);
    }

    @Override
    public String aliasesSql(Expression expression) {
        return sql(expression, Args.THIS) + " AS (" + expressions(opts(expression).flat()) + ")";
    }

    @Override
    public String atindexSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String index = sql(expression, Args.EXPRESSION);
        return thisSql + " AT " + index;
    }

    @Override
    public String attimezoneSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String zone = sql(expression, Args.ZONE);
        return thisSql + " AT TIME ZONE " + zone;
    }

    @Override
    public String fromtimezoneSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String zone = sql(expression, Args.ZONE);
        return thisSql + " AT TIME ZONE " + zone + " AT TIME ZONE 'UTC'";
    }

    @Override
    public String fromiso8601dateSql(Expression expression) {
        return sql(Exp.cast(expression.getThis(), DType.DATE));
    }

    @Override
    public String fromiso8601timestampSql(Expression expression) {
        return sql(Exp.cast(expression.getThis(), DType.TIMESTAMPTZ));
    }

    @Override
    public String fromiso8601timestampnanosSql(Expression expression) {
        return sql(Exp.cast(expression.getThis(), DType.TIMESTAMPTZ));
    }

    @Override
    public String addSql(Expression expression) {
        return binary(expression, "+");
    }

    @Override
    public String andSql(Expression expression, List<Object> stack) {
        return connectorSql(expression, "AND", stack);
    }

    @Override
    public String orSql(Expression expression, List<Object> stack) {
        return connectorSql(expression, "OR", stack);
    }

    @Override
    public String xorSql(Expression expression, List<Object> stack) {
        return connectorSql(expression, "XOR", stack);
    }

    @Override
    public String connectorSql(Expression expression, String op, List<Object> stack) {
        if (stack != null) {
            stack.add(expression.get(Args.EXPRESSION));
            List<String> comments = expression.comments();
            if (comments != null && !comments.isEmpty() && preserveComments) {
                op = maybeComment(op, comments);
            }

            stack.add(op);
            stack.add(expression.get(Args.THIS));
            return op;
        }

        stack = new ArrayList<>();
        stack.add(expression);
        List<String> sqls = new ArrayList<>();
        Set<String> ops = new HashSet<>();

        while (!stack.isEmpty()) {
            Object node = stack.remove(stack.size() - 1);
            if (node instanceof Expression n && n.is(ExprType.CONNECTOR)) {
                ops.add(connectorDispatch(n, stack));
            } else {
                String s = sql(node);
                if (!sqls.isEmpty() && ops.contains(sqls.get(sqls.size() - 1))) {
                    sqls.set(sqls.size() - 1, sqls.get(sqls.size() - 1) + " " + s);
                } else {
                    sqls.add(s);
                }
            }
        }

        String sep = pretty && tooWide(sqls) ? "\n" : " ";
        return String.join(sep, sqls);
    }

    /** {@code getattr(self, f"{node.key}_sql")(node, stack)} for a Connector node. */
    private String connectorDispatch(Expression node, List<Object> stack) {
        return switch (node.type()) {
            case AND -> andSql(node, stack);
            case OR -> orSql(node, stack);
            case XOR -> xorSql(node, stack);
            default -> throw new IllegalArgumentException("Unsupported connector " + node.type().className());
        };
    }

    @Override
    public String bitwiseandSql(Expression expression) {
        return binary(expression, "&");
    }

    @Override
    public String bitwiseleftshiftSql(Expression expression) {
        return binary(expression, "<<");
    }

    @Override
    public String bitwisenotSql(Expression expression) {
        return "~" + sql(expression, Args.THIS);
    }

    @Override
    public String bitwiseorSql(Expression expression) {
        return binary(expression, "|");
    }

    @Override
    public String bitwiserightshiftSql(Expression expression) {
        return binary(expression, ">>");
    }

    @Override
    public String bitwisexorSql(Expression expression) {
        return binary(expression, "^");
    }

    @Override
    public String castSql(Expression expression, String safePrefix) {
        String formatSql = prefixed(" FORMAT ", sql(expression, Args.FORMAT));
        String toSql = prefixed(" ", sql(expression, Args.TO));
        String action = prefixed(" ", sql(expression, Args.ACTION));
        String defaultSql = sql(expression, Args.DEFAULT);
        defaultSql = !defaultSql.isEmpty() ? " DEFAULT " + defaultSql + " ON CONVERSION ERROR" : "";
        return (safePrefix != null ? safePrefix : "") + "CAST(" + sql(expression, Args.THIS) + " AS" + toSql
                + defaultSql + formatSql + action + ")";
    }

    // Base implementation that excludes safe, zone, and target_type metadata args
    @Override
    public String strtotimeSql(Expression expression) {
        return func("STR_TO_TIME", expression.get(Args.THIS), expression.get(Args.FORMAT));
    }

    // Base implementation that excludes the safe and default_year metadata args
    @Override
    public String strtodateSql(Expression expression) {
        return func("STR_TO_DATE", expression.get(Args.THIS), expression.get(Args.FORMAT));
    }

    @Override
    public String parsedatetimeSql(Expression expression) {
        return func("PARSE_DATETIME", expression.get(Args.THIS), expression.get(Args.FORMAT), expression.get(Args.ZONE));
    }

    @Override
    public String currentdateSql(Expression expression) {
        String zone = sql(expression, Args.THIS);
        return !zone.isEmpty() ? "CURRENT_DATE(" + zone + ")" : "CURRENT_DATE";
    }

    @Override
    public String collateSql(Expression expression) {
        if (g.COLLATE_IS_FUNC) {
            return functionFallbackSql(expression);
        }
        return binary(expression, "COLLATE");
    }

    @Override
    public String commandSql(Expression expression) {
        return sql(expression, Args.THIS) + " " + expression.text(Args.EXPRESSION).strip();
    }

    @Override
    public String commentSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        Object kind = expression.get(Args.KIND);
        String materialized = expression.has(Args.MATERIALIZED) ? " MATERIALIZED" : "";
        String existsSql = expression.has(Args.EXISTS) ? " IF EXISTS " : " ";
        String expressionSql = sql(expression, Args.EXPRESSION);
        return "COMMENT" + existsSql + "ON" + materialized + " " + pyStr(kind) + " " + thisSql + " IS " + expressionSql;
    }

    @Override
    public String mergetreettlactionSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String delete = expression.has(Args.DELETE) ? " DELETE" : "";
        String recompress = prefixed(" RECOMPRESS ", sql(expression, Args.RECOMPRESS));
        String toDisk = prefixed(" TO DISK ", sql(expression, Args.TO_DISK));
        String toVolume = prefixed(" TO VOLUME ", sql(expression, Args.TO_VOLUME));
        return thisSql + delete + recompress + toDisk + toVolume;
    }

    @Override
    public String mergetreettlSql(Expression expression) {
        String where = sql(expression, Args.WHERE);
        String group = sql(expression, Args.GROUP);
        String aggregates = expressions(opts(expression).key(Args.AGGREGATES));
        aggregates = !aggregates.isEmpty() ? seg("SET") + seg(aggregates) : "";

        if (where.isEmpty() && group.isEmpty() && aggregates.isEmpty() && expression.getExpressions().size() == 1) {
            return "TTL " + expressions(opts(expression).flat());
        }

        return "TTL" + seg(expressions(expression)) + where + group + aggregates;
    }

    @Override
    public String transactionSql(Expression expression) {
        String modes = prefixed(" ", expressions(opts(expression).key(Args.MODES)));
        return "BEGIN" + modes;
    }

    @Override
    public String commitSql(Expression expression) {
        Object chain = expression.get(Args.CHAIN);
        String chainSql = "";
        if (chain != null) {
            chainSql = Expression.truthy(chain) ? " AND CHAIN" : " AND NO CHAIN";
        }

        return "COMMIT" + chainSql;
    }

    @Override
    public String rollbackSql(Expression expression) {
        Object savepoint = expression.get(Args.SAVEPOINT);
        String savepointSql = Expression.truthy(savepoint) ? " TO " + pyStr(savepoint) : "";
        return "ROLLBACK" + savepointSql;
    }

    @Override
    public String altercolumnSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);

        String exists = "";
        if (expression.has(Args.EXISTS)) {
            if (g.SUPPORTS_ALTER_COLUMN_IF_EXISTS) {
                exists = " IF EXISTS";
            } else {
                unsupported("ALTER COLUMN IF EXISTS is not supported by this dialect");
            }
        }

        String dtype = sql(expression, Args.DTYPE);
        if (!dtype.isEmpty()) {
            String collate = prefixed(" COLLATE ", sql(expression, Args.COLLATE));
            String using = prefixed(" USING ", sql(expression, Args.USING));
            String alterSetType = g.ALTER_SET_TYPE != null && !g.ALTER_SET_TYPE.isEmpty() ? g.ALTER_SET_TYPE + " " : "";
            String nullConstraint = alterColumnNullConstraintSql(expression);

            return "ALTER COLUMN" + exists + " " + thisSql + " " + alterSetType + dtype
                    + collate + using + nullConstraint;
        }

        String defaultSql = sql(expression, Args.DEFAULT);
        if (!defaultSql.isEmpty()) {
            return "ALTER COLUMN" + exists + " " + thisSql + " SET DEFAULT " + defaultSql;
        }

        String comment = sql(expression, Args.COMMENT);
        if (!comment.isEmpty()) {
            return "ALTER COLUMN" + exists + " " + thisSql + " COMMENT " + comment;
        }

        Object visible = expression.get(Args.VISIBLE);
        if (Expression.truthy(visible)) {
            return "ALTER COLUMN" + exists + " " + thisSql + " SET " + pyStr(visible);
        }

        Object allowNull = expression.get(Args.ALLOW_NULL);
        Object drop = expression.get(Args.DROP);

        if (!Expression.truthy(drop) && !Expression.truthy(allowNull)) {
            unsupported("Unsupported ALTER COLUMN syntax");
        }

        if (allowNull != null) {
            String keyword = Expression.truthy(drop) ? "DROP" : "SET";
            return "ALTER COLUMN" + exists + " " + thisSql + " " + keyword + " NOT NULL";
        }

        return "ALTER COLUMN" + exists + " " + thisSql + " DROP DEFAULT";
    }

    @Override
    public String alterColumnNullConstraintSql(Expression expression) {
        Object allowNull = expression.get(Args.ALLOW_NULL);
        if (allowNull == null) {
            return "";
        }

        if (!g.SUPPORTS_ALTER_COLUMN_NULLABILITY) {
            unsupported("ALTER COLUMN cannot set nullability along with a type");
            return "";
        }

        return Expression.truthy(allowNull) ? " NULL" : " NOT NULL";
    }

    @Override
    public String modifycolumnSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String renameFrom = sql(expression, Args.RENAME_FROM);
        if (!renameFrom.isEmpty()) {
            if (!g.SUPPORTS_CHANGE_COLUMN) {
                unsupported("CHANGE COLUMN is not supported in this dialect");
            }
            return "CHANGE COLUMN " + renameFrom + " " + thisSql;
        }
        if (!g.SUPPORTS_MODIFY_COLUMN) {
            unsupported("MODIFY COLUMN is not supported in this dialect");
        }
        return "MODIFY COLUMN " + thisSql;
    }

    @Override
    public String alterindexSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);

        String visibleSql = expression.has(Args.VISIBLE) ? "VISIBLE" : "INVISIBLE";

        return "ALTER INDEX " + thisSql + " " + visibleSql;
    }

    @Override
    public String alterdiststyleSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        if (!Expression.isInstance(expression.thisValue(), ExprType.VAR)) {
            thisSql = "KEY DISTKEY " + thisSql;
        }
        return "ALTER DISTSTYLE " + thisSql;
    }

    @Override
    public String altersortkeySql(Expression expression) {
        String compound = expression.has(Args.COMPOUND) ? " COMPOUND" : "";
        String thisSql = sql(expression, Args.THIS);
        String expressionsSql = expressions(opts(expression).flat());
        expressionsSql = !expressionsSql.isEmpty() ? "(" + expressionsSql + ")" : "";
        return "ALTER" + compound + " SORTKEY " + (!thisSql.isEmpty() ? thisSql : expressionsSql);
    }

    @Override
    public String alterrenameSql(Expression expression, boolean includeTo) {
        if (!g.RENAME_TABLE_WITH_DB) {
            // Remove db from tables
            expression = expression.transform(
                    n -> n.is(ExprType.TABLE) ? Exp.table(n.thisValue(), null, null, null, null) : n)
                    .assertIs(ExprType.ALTER_RENAME);
        }
        String thisSql = sql(expression, Args.THIS);
        String toKw = includeTo ? " TO" : "";
        return "RENAME" + toKw + " " + thisSql;
    }

    @Override
    public String renamecolumnSql(Expression expression) {
        String exists = expression.has(Args.EXISTS) ? " IF EXISTS" : "";
        String oldColumn = sql(expression, Args.THIS);
        String newColumn = sql(expression, Args.TO);
        return "RENAME COLUMN" + exists + " " + oldColumn + " TO " + newColumn;
    }

    @Override
    public String altersetSql(Expression expression) {
        String exprs = expressions(opts(expression).flat());
        if (g.ALTER_SET_WRAPPED) {
            exprs = "(" + exprs + ")";
        }

        return "SET " + exprs;
    }

    @Override
    public String alterSql(Expression expression) {
        List<Expression> actions = expression.exprs(Args.ACTIONS);

        String actionsSql;
        if (!dd.ALTER_TABLE_ADD_REQUIRED_FOR_EACH_COLUMN
                && !actions.isEmpty()
                && actions.get(0).is(ExprType.COLUMN_DEF)) {
            actionsSql = expressions(opts(expression).key(Args.ACTIONS).flat());
            actionsSql = "ADD " + actionsSql;
        } else {
            List<Object> actionsList = new ArrayList<>();
            for (Expression action : actions) {
                String actionSql;
                if (action.is(ExprType.COLUMN_DEF, ExprType.SCHEMA)) {
                    actionSql = addColumnSql(action);
                } else {
                    actionSql = sql(action);
                    if (action.is(ExprType.QUERY)) {
                        actionSql = "AS " + actionSql;
                    }
                }

                actionsList.add(actionSql);
            }

            actionsSql = lstripChars(formatArgs(actionsList.toArray()), "\n");
        }

        String iceberg = expression.has(Args.ICEBERG) && g.SUPPORTS_DROP_ALTER_ICEBERG_PROPERTY ? "ICEBERG " : "";
        String exists = expression.has(Args.EXISTS) ? " IF EXISTS" : "";
        String onCluster = prefixed(" ", sql(expression, Args.CLUSTER));
        String only = expression.has(Args.ONLY) ? " ONLY" : "";
        List<Object> options = expression.list(Args.OPTIONS);
        List<Object> nonPartitionOptions = new ArrayList<>();
        List<Object> partitionOptions = new ArrayList<>();
        for (Object o : options) {
            if (o instanceof Expression oe && isAny(oe, g.ALTER_PARTITION_OPTIONS)) {
                partitionOptions.add(o);
            } else {
                nonPartitionOptions.add(o);
            }
        }
        String optionsSql = expressions(optsSqls(nonPartitionOptions));
        optionsSql = !optionsSql.isEmpty() && !actionsSql.isEmpty() ? ", " + optionsSql : optionsSql;
        String partitionsSql = expressions(optsSqls(partitionOptions).sep(" ").flat());
        if (!partitionsSql.isEmpty() && (!actionsSql.isEmpty() || !optionsSql.isEmpty())) {
            partitionsSql = " " + partitionsSql;
        }
        String kind = sql(expression, Args.KIND);
        String notValid = expression.has(Args.NOT_VALID) ? " NOT VALID" : "";
        String check = expression.has(Args.CHECK) ? " WITH CHECK" : "";
        String cascade = expression.has(Args.CASCADE) && dd.ALTER_TABLE_SUPPORTS_CASCADE ? " CASCADE" : "";
        String thisSql = prefixed(" ", sql(expression, Args.THIS));

        return "ALTER " + iceberg + kind + exists + only + thisSql + onCluster + check + sep() + actionsSql
                + notValid + optionsSql + partitionsSql + cascade;
    }

    @Override
    public String altersessionSql(Expression expression) {
        String itemsSql = expressions(opts(expression).flat());
        String keyword = expression.has(Args.UNSET) ? "UNSET" : "SET";
        return keyword + " " + itemsSql;
    }

    @Override
    public String addColumnSql(Expression expression) {
        String s = sql(expression);
        String columnText;
        if (expression.is(ExprType.SCHEMA)) {
            columnText = " COLUMNS";
        } else if (expression.is(ExprType.COLUMN_DEF) && g.ALTER_TABLE_INCLUDE_COLUMN_KEYWORD) {
            columnText = " COLUMN";
        } else {
            columnText = "";
        }

        return "ADD" + columnText + " " + s;
    }

    @Override
    public String droppartitionSql(Expression expression) {
        String expressionsSql = expressions(expression);
        String exists = expression.has(Args.EXISTS) ? " IF EXISTS " : " ";
        return "DROP" + exists + expressionsSql;
    }

    @Override
    public String dropprimarykeySql(Expression expression) {
        return "DROP PRIMARY KEY";
    }

    @Override
    public String addconstraintSql(Expression expression) {
        return "ADD " + expressions(opts(expression).indent(false));
    }

    @Override
    public String addpartitionSql(Expression expression) {
        String exists = expression.has(Args.EXISTS) ? "IF NOT EXISTS " : "";
        String location = prefixed(" ", sql(expression, Args.LOCATION));
        return "ADD " + exists + sql(expression.thisValue()) + location;
    }

    @Override
    public String distinctSql(Expression expression) {
        String thisSql = expressions(opts(expression).flat());

        if (!g.MULTI_ARG_DISTINCT && expression.getExpressions().size() > 1) {
            Expression caseExpr = Expression.of(ExprType.CASE, Args.IFS, new ArrayList<>());
            for (Expression arg : expression.getExpressions()) {
                caseExpr = caseWhen(caseExpr, binop(ExprType.IS, arg, Exp.nul()), Exp.nul());
            }
            caseExpr.set(Args.DEFAULT, Exp.maybeParse("(" + thisSql + ")", null));
            thisSql = sql(caseExpr);
        }

        thisSql = prefixed(" ", thisSql);

        String on = prefixed(" ON ", sql(expression, Args.ON));
        return "DISTINCT" + thisSql + on;
    }

    @Override
    public String ignorenullsSql(Expression expression) {
        return embedIgnoreNulls(expression, "IGNORE NULLS");
    }

    @Override
    public String respectnullsSql(Expression expression) {
        return embedIgnoreNulls(expression, "RESPECT NULLS");
    }

    @Override
    public String havingmaxSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expressionSql = sql(expression, Args.EXPRESSION);
        String kind = expression.has(Args.MAX) ? "MAX" : "MIN";
        return thisSql + " HAVING " + kind + " " + expressionSql;
    }

    @Override
    public String intdivSql(Expression expression) {
        return truncatedDivSql(expression.getThis(), expression.getExpression());
    }

    @Override
    public String truncatedDivSql(Expression this_, Expression divisor) {
        Expression div = Expression.of(ExprType.DIV, Args.THIS, this_, Args.EXPRESSION, divisor);
        if (g.CAST_TO_INT_ROUNDS) {
            div = Expression.of(ExprType.TRUNC, Args.THIS, div);
        }

        return sql(Expression.of(ExprType.CAST, Args.THIS, div, Args.TO, DataTypes.of(DType.BIGINT)));
    }

    @Override
    public String dpipeSql(Expression expression) {
        if (dd.STRICT_STRING_CONCAT && expression.has(Args.SAFE)) {
            List<Object> args = new ArrayList<>();
            for (Expression e : expression.flatten(true)) {
                args.add(Exp.cast(e, DType.TEXT));
            }
            return func("CONCAT", args.toArray());
        }
        return binary(expression, "||");
    }

    @Override
    public String divSql(Expression expression) {
        Expression l = expression.getThis();
        Expression r = expression.getExpression();

        if (!dd.SAFE_DIVISION && expression.has(Args.SAFE)) {
            r.replace(Expression.of(ExprType.NULLIF, Args.THIS, r.copy(), Args.EXPRESSION, Exp.number(0)));
        }

        if (dd.TYPED_DIVISION && !expression.has(Args.TYPED)) {
            if (!l.isType(DataTypes.REAL_TYPES.toArray()) && !r.isType(DataTypes.REAL_TYPES.toArray())) {
                l.replace(Exp.cast(l.copy(), DType.DOUBLE));
            }
        } else if (!dd.TYPED_DIVISION && expression.has(Args.TYPED)) {
            if (l.isType(DataTypes.INTEGER_TYPES.toArray()) && r.isType(DataTypes.INTEGER_TYPES.toArray())) {
                // Native integer division depends on the target's operand types, which may differ
                // from the source's (e.g. FLOOR(int) is DOUBLE in DuckDB), unless the operands are
                // integer literals or casts
                boolean allIntOrCast = (l.isInt() || l.is(ExprType.CAST)) && (r.isInt() || r.is(ExprType.CAST));
                if (allIntOrCast && (expression.has(Args.SAFE) || !g.SAFE_INT_DIVISION)) {
                    return sql(Expression.of(ExprType.INT_DIV, Args.THIS, l, Args.EXPRESSION, r));
                }
                return truncatedDivSql(l, r);
            }
        }

        return binary(expression, "/");
    }

    @Override
    public String safedivideSql(Expression expression) {
        Expression n = Exp.wrap(expression.getThis(), ExprType.BINARY);
        Expression d = Exp.wrap(expression.getExpression(), ExprType.BINARY);
        return sql(Expression.of(ExprType.IF,
                Args.THIS, binop(ExprType.NEQ, d, 0),
                Args.TRUE, binop(ExprType.DIV, n, d),
                Args.FALSE, Exp.nul()));
    }

    @Override
    public String overlapsSql(Expression expression) {
        return binary(expression, "OVERLAPS");
    }

    @Override
    public String distanceSql(Expression expression) {
        return binary(expression, "<->");
    }

    @Override
    public String distancendSql(Expression expression) {
        return binary(expression, "<<->>");
    }

    @Override
    public String dotSql(Expression expression) {
        return sql(expression, Args.THIS) + "." + sql(expression, Args.EXPRESSION);
    }

    @Override
    public String eqSql(Expression expression) {
        return binary(expression, "=");
    }

    @Override
    public String propertyeqSql(Expression expression) {
        return binary(expression, ":=");
    }

    @Override
    public String escapeSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        if (thisExpr != null
                && thisExpr.is(ExprType.LIKE, ExprType.I_LIKE)
                && thisExpr.getExpression() != null
                && thisExpr.getExpression().is(ExprType.ALL, ExprType.ANY)
                && !g.SUPPORTS_LIKE_QUANTIFIERS) {
            return likeSql(thisExpr, expression);
        }
        return binary(expression, "ESCAPE");
    }

    @Override
    public String globSql(Expression expression) {
        return binary(expression, "GLOB");
    }

    @Override
    public String gtSql(Expression expression) {
        return binary(expression, ">");
    }

    @Override
    public String gteSql(Expression expression) {
        return binary(expression, ">=");
    }

    @Override
    public String isSql(Expression expression) {
        Object negate = expression.get(Args.NEGATE);
        Expression expr = expression.getExpression();
        if (!g.IS_BOOL_ALLOWED && expr != null && expr.is(ExprType.BOOLEAN)) {
            boolean positive = Expression.truthy(expr.thisValue()) != Expression.truthy(negate);
            return sql(positive ? expression.thisValue() : Exp.not(expression.getThis().copy()));
        }
        return binary(expression, Expression.truthy(negate) ? "IS NOT" : "IS");
    }

    @Override
    public String likeSql(Expression expression, Expression escape) {
        Expression thisExpr = expression.getThis();
        Expression rhs = expression.getExpression();

        ExprType expClass;
        String op;
        if (expression.is(ExprType.LIKE)) {
            expClass = ExprType.LIKE;
            op = "LIKE";
        } else {
            expClass = ExprType.I_LIKE;
            op = "ILIKE";
        }

        if (expression.has(Args.NEGATE)) {
            op = "NOT " + op;
        }

        if (rhs != null && rhs.is(ExprType.ALL, ExprType.ANY) && !g.SUPPORTS_LIKE_QUANTIFIERS) {
            Expression unnested = rhs.getThis().unnest();

            List<Expression> exprs;
            if (unnested.is(ExprType.TUPLE)) {
                exprs = unnested.getExpressions();
            } else {
                exprs = List.of(unnested);
            }

            boolean isAny = rhs.is(ExprType.ANY);

            Expression likeExpr = makeLike(expClass, thisExpr, exprs.get(0), expression, escape);
            for (Expression expr : exprs.subList(1, exprs.size())) {
                Expression next = makeLike(expClass, thisExpr, expr, expression, escape);
                likeExpr = isAny ? Exp.or(likeExpr, next) : Exp.and(likeExpr, next);
            }

            Expression parent = escape != null ? escape.parent() : expression.parent();
            if (parent != null && !parent.is(likeExpr.type(), ExprType.PAREN) && parent.is(ExprType.CONDITION)) {
                likeExpr = Exp.paren(likeExpr);
            }

            return sql(likeExpr);
        }

        return binary(expression, op);
    }

    /** {@code _make_like} closure of {@code _like_sql}. */
    private static Expression makeLike(ExprType expClass, Expression thisExpr, Expression expr, Expression expression,
            Expression escape) {
        Expression like = Expression.of(expClass, Args.THIS, thisExpr, Args.EXPRESSION, expr,
                Args.NEGATE, expression.get(Args.NEGATE));
        if (escape != null) {
            like = Expression.of(ExprType.ESCAPE, Args.THIS, like, Args.EXPRESSION, escape.getExpression().copy());
        }
        return like;
    }

    @Override
    public String likeSqlImpl(Expression expression) {
        return likeSql(expression);
    }

    @Override
    public String ilikeSql(Expression expression) {
        return likeSql(expression);
    }

    @Override
    public String matchSql(Expression expression) {
        if (expression.has(Args.KIND) || expression.has(Args.ANALYZER)) {
            unsupported("MATCH variants and USING ANALYZER are not supported in this dialect");
        }
        return binary(expression, "MATCH");
    }

    @Override
    public String similartoSql(Expression expression) {
        return binary(expression, "SIMILAR TO");
    }

    @Override
    public String ltSql(Expression expression) {
        return binary(expression, "<");
    }

    @Override
    public String lteSql(Expression expression) {
        return binary(expression, "<=");
    }

    @Override
    public String modSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expr = sql(expression, Args.EXPRESSION);
        String s = thisSql + " " + maybeComment(g.MOD_OPERATOR, expression.comments()) + " " + expr;

        Expression parent = expression.parent();
        if (parent != null && isAny(parent, g.MOD_PAREN_PARENT_TYPES) && parent.get(Args.EXPRESSION) == expression) {
            return "(" + s + ")";
        }

        return s;
    }

    @Override
    public String mulSql(Expression expression) {
        return binary(expression, "*");
    }

    @Override
    public String neqSql(Expression expression) {
        return binary(expression, "<>");
    }

    @Override
    public String nullsafeeqSql(Expression expression) {
        return binary(expression, "IS NOT DISTINCT FROM");
    }

    @Override
    public String nullsafeneqSql(Expression expression) {
        return binary(expression, "IS DISTINCT FROM");
    }

    @Override
    public String subSql(Expression expression) {
        return binary(expression, "-");
    }

    @Override
    public String trycastSql(Expression expression) {
        return castSql(expression, "TRY_");
    }

    @Override
    public String jsoncastSql(Expression expression) {
        return castSql(expression);
    }

    @Override
    public String trySql(Expression expression) {
        if (!g.TRY_SUPPORTED) {
            unsupported("Unsupported TRY function");
            return sql(expression, Args.THIS);
        }

        return func("TRY", expression.get(Args.THIS));
    }

    @Override
    public String logSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        Expression expr = expression.getExpression();

        if (Boolean.FALSE.equals(dd.LOG_BASE_FIRST)) {
            Expression tmp = thisExpr;
            thisExpr = expr;
            expr = tmp;
        } else if (dd.LOG_BASE_FIRST == null && expr != null) {
            String name = thisExpr.name();
            if (name.equals("2") || name.equals("10")) {
                return func("LOG" + name, expr);
            }

            unsupported("Unsupported logarithm with base " + sql(thisExpr));
        }

        return func("LOG", thisExpr, expr);
    }

    @Override
    public String useSql(Expression expression) {
        String kind = prefixed(" ", sql(expression, Args.KIND));
        String thisSql = sql(expression, Args.THIS);
        if (thisSql.isEmpty()) {
            thisSql = expressions(opts(expression).flat());
        }
        thisSql = prefixed(" ", thisSql);
        return "USE" + kind + thisSql;
    }

    @Override
    public String ceilFloor(Expression expression) {
        String toClause = sql(expression, Args.TO);
        if (!toClause.isEmpty()) {
            return expression.type().sqlName() + "(" + sql(expression, Args.THIS) + " TO " + toClause + ")";
        }

        return functionFallbackSql(expression);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String formatTime(Expression expression, Map<String, Object> inverseTimeMapping, Object inverseTimeTrie) {
        Map<String, String> mapping = inverseTimeMapping != null && !inverseTimeMapping.isEmpty()
                ? (Map<String, String>) (Map<String, ?>) inverseTimeMapping
                : dd.INVERSE_TIME_MAPPING;
        Trie trie = inverseTimeTrie instanceof Trie t ? t : dialect.inverseTimeTrie();
        return TimeFormat.formatTime(sql(expression, Args.FORMAT), mapping, trie);
    }

    @Override
    public String opExpressions(String op, Expression expression, boolean flat) {
        flat = flat || Expression.isInstance(expression.parent(), ExprType.PROPERTIES);
        String expressionsSql = expressions(opts(expression).flat(flat));
        if (flat) {
            return op + " " + expressionsSql;
        }
        return seg(op) + (!expressionsSql.isEmpty() ? sep() : "") + expressionsSql;
    }

    @Override
    public String nakedProperty(Expression expression) {
        String propertyName = PROPERTY_TO_NAME.get(expression.type());
        if (propertyName == null) {
            unsupported("Unsupported property " + expression.type().className());
        }
        return pyStr(propertyName) + " " + sql(expression, Args.THIS);
    }

    @Override
    public String tagSql(Expression expression) {
        return pyStr(expression.get(Args.PREFIX)) + sql(expression.thisValue()) + pyStr(expression.get(Args.POSTFIX));
    }

    @Override
    public String tokenSql(TokenType tokenType) {
        return g.TOKEN_MAPPING.getOrDefault(tokenType, tokenType.name());
    }

    @Override
    public String userdefinedfunctionSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expressionsSql = noIdentify(() -> expressions(expression));
        expressionsSql = expression.has(Args.WRAPPED) ? wrap(expressionsSql) : " " + expressionsSql;
        return !expressionsSql.strip().isEmpty() ? thisSql + expressionsSql : thisSql;
    }

    @Override
    public String macrooverloadsSql(Expression expression) {
        return expressions(opts(expression).flat());
    }

    @Override
    public String macrooverloadSql(Expression expression) {
        String params = noIdentify(() -> expressions(opts(expression).flat()));
        String body = sql(expression, Args.THIS);
        String prefix = expression.has(Args.IS_TABLE) ? "TABLE " : "";
        return "(" + params + ") AS " + prefix + body;
    }

    @Override
    public String joinhintSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expressionsSql = expressions(opts(expression).flat());
        return thisSql + "(" + expressionsSql + ")";
    }

    @Override
    public String kwargSql(Expression expression) {
        return binary(expression, "=>");
    }

    @Override
    public String whenSql(Expression expression) {
        String matched = expression.has(Args.MATCHED) ? "MATCHED" : "NOT MATCHED";
        String source = g.MATCHED_BY_SOURCE && expression.has(Args.SOURCE) ? " BY SOURCE" : "";
        String condition = prefixed(" AND ", sql(expression, Args.CONDITION));

        Object thenValue = expression.get(Args.THEN);
        Expression thenExpression = thenValue instanceof Expression te ? te : null;
        String then;
        if (thenExpression != null && thenExpression.is(ExprType.INSERT)) {
            String thisSql = sql(thenExpression, Args.THIS);
            thisSql = !thisSql.isEmpty() ? "INSERT " + thisSql : "INSERT";
            then = sql(thenExpression, Args.EXPRESSION);
            then = !then.isEmpty() ? thisSql + " VALUES " + then : thisSql;
        } else if (thenExpression != null && thenExpression.is(ExprType.UPDATE)) {
            if (Expression.isInstance(thenExpression.get(Args.EXPRESSIONS), ExprType.STAR)) {
                then = "UPDATE " + sql(thenExpression, Args.EXPRESSIONS);
            } else {
                String expressionsSql = expressions(thenExpression);
                then = !expressionsSql.isEmpty() ? "UPDATE SET" + sep() + expressionsSql : "UPDATE";
            }
        } else {
            then = sql(thenValue);
        }

        if (thenExpression != null && thenExpression.is(ExprType.INSERT, ExprType.UPDATE)) {
            String where = sql(thenExpression, Args.WHERE);
            if (!where.isEmpty() && !g.SUPPORTS_MERGE_WHERE) {
                String kind = thenExpression.is(ExprType.INSERT) ? "INSERT" : "UPDATE";
                unsupported("WHERE clause in MERGE " + kind + " is not supported");
                where = "";
            }
            then = then + where;
        }
        return "WHEN " + matched + source + condition + " THEN " + then;
    }

    @Override
    public String whensSql(Expression expression) {
        return expressions(opts(expression).sep(" ").indent(false));
    }

    @Override
    public String mergeSql(Expression expression) {
        Expression table = expression.getThis();
        String tableAlias = "";

        List<Object> hints = table.list(Args.HINTS);
        if (!hints.isEmpty() && !table.alias().isEmpty() && Expression.isInstance(hints.get(0), ExprType.WITH_TABLE_HINT)) {
            // T-SQL syntax is MERGE ... <target_table> [WITH (<merge_hint>)] [[AS] table_alias]
            tableAlias = " AS " + sql(table.expr(Args.ALIAS).pop());
        }

        String thisSql = sql(table);
        String using = "USING " + sql(expression, Args.USING);
        String whens = sql(expression, Args.WHENS);

        String on = prefixed("ON ", sql(expression, Args.ON));

        if (on.isEmpty()) {
            on = expressions(opts(expression).key(Args.USING_COND));
            on = !on.isEmpty() ? "USING (" + on + ")" : "";
        }

        String returning = sql(expression, Args.RETURNING);
        if (!returning.isEmpty()) {
            whens = whens + returning;
        }

        String sep = sep();

        return prependCtes(expression,
                "MERGE INTO " + thisSql + tableAlias + sep + using + sep + on + sep + whens);
    }

    @Override
    public String tocharSql(Expression expression) {
        unsupportedArgs(expression, Args.FORMAT);
        return sql(Exp.cast(expression.getThis(), DType.TEXT));
    }

    @Override
    public String tonumberSql(Expression expression) {
        unsupportedArgs(expression, Args.DEFAULT);
        if (!g.SUPPORTS_TO_NUMBER) {
            unsupported("Unsupported TO_NUMBER function");
            return tonumberCastSql(expression);
        }

        Object fmt = expression.get(Args.FORMAT);
        if (!Expression.truthy(fmt)) {
            unsupported("Conversion format is required for TO_NUMBER");
            return tonumberCastSql(expression);
        }

        return func("TO_NUMBER", expression.get(Args.THIS), fmt);
    }

    @Override
    public String tonumberCastSql(Expression expression) {
        if (expression.has(Args.SAFE)) {
            return sql(Expression.of(ExprType.TRY_CAST, Args.THIS, expression.get(Args.THIS), Args.TO,
                    DataTypes.build("DOUBLE")));
        }
        return sql(Exp.cast(expression.getThis(), DType.DOUBLE));
    }

    @Override
    public String dictpropertySql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String kind = sql(expression, Args.KIND);
        String settingsSql = expressions(opts(expression).key(Args.SETTINGS).sep(" "));
        String args = !settingsSql.isEmpty() ? "(" + sep("") + settingsSql + seg(")", "") : "()";
        return thisSql + "(" + kind + args + ")";
    }

    @Override
    public String dictrangeSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String max = sql(expression, Args.MAX);
        String min = sql(expression, Args.MIN);
        return thisSql + "(MIN " + min + " MAX " + max + ")";
    }

    @Override
    public String dictsubpropertySql(Expression expression) {
        return sql(expression, Args.THIS) + " " + sql(expression, Args.VALUE);
    }

    @Override
    public String aggregatekeypropertySql(Expression expression) {
        return "AGGREGATE KEY (" + expressions(opts(expression).flat()) + ")";
    }

    @Override
    public String duplicatekeypropertySql(Expression expression) {
        return "DUPLICATE KEY (" + expressions(opts(expression).flat()) + ")";
    }

    // https://docs.starrocks.io/docs/sql-reference/sql-statements/table_bucket_part_index/CREATE_TABLE/
    @Override
    public String uniquekeypropertySql(Expression expression, String prefix) {
        return prefix + " (" + expressions(opts(expression).flat()) + ")";
    }

    // https://docs.starrocks.io/docs/sql-reference/sql-statements/data-definition/CREATE_TABLE/#distribution_desc
    @Override
    public String distributedbypropertySql(Expression expression) {
        String expressionsSql = expressions(opts(expression).flat());
        expressionsSql = !expressionsSql.isEmpty() ? " " + wrap(expressionsSql) : "";
        String buckets = sql(expression, Args.BUCKETS);
        String kind = sql(expression, Args.KIND);
        buckets = prefixed(" BUCKETS ", buckets);
        String order = sql(expression, Args.ORDER);
        return "DISTRIBUTED BY " + kind + expressionsSql + buckets + order;
    }

    @Override
    public String onclusterSql(Expression expression) {
        return "";
    }

    @Override
    public String clusteredbypropertySql(Expression expression) {
        String expressionsSql = expressions(opts(expression).key(Args.EXPRESSIONS).flat());
        String sortedBy = expressions(opts(expression).key(Args.SORTED_BY).flat());
        sortedBy = !sortedBy.isEmpty() ? " SORTED BY (" + sortedBy + ")" : "";
        String buckets = sql(expression, Args.BUCKETS);
        return "CLUSTERED BY (" + expressionsSql + ")" + sortedBy + " INTO " + buckets + " BUCKETS";
    }

    @Override
    public String anyvalueSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String having = sql(expression, Args.HAVING);

        if (!having.isEmpty()) {
            thisSql = thisSql + " HAVING " + (expression.has(Args.MAX) ? "MAX" : "MIN") + " " + having;
        }

        return func("ANY_VALUE", thisSql);
    }

    @Override
    public String querytransformSql(Expression expression) {
        String transform = func("TRANSFORM", expression.getExpressions().toArray());
        String rowFormatBefore = prefixed(" ", sql(expression, Args.ROW_FORMAT_BEFORE));
        String recordWriter = prefixed(" RECORDWRITER ", sql(expression, Args.RECORD_WRITER));
        String using = " USING " + sql(expression, Args.COMMAND_SCRIPT);
        String schema = prefixed(" AS ", sql(expression, Args.SCHEMA));
        String rowFormatAfter = prefixed(" ", sql(expression, Args.ROW_FORMAT_AFTER));
        String recordReader = prefixed(" RECORDREADER ", sql(expression, Args.RECORD_READER));
        return transform + rowFormatBefore + recordWriter + using + schema + rowFormatAfter + recordReader;
    }

    @Override
    public String indexconstraintoptionSql(Expression expression) {
        String keyBlockSize = sql(expression, Args.KEY_BLOCK_SIZE);
        if (!keyBlockSize.isEmpty()) {
            return "KEY_BLOCK_SIZE = " + keyBlockSize;
        }

        String using = sql(expression, Args.USING);
        if (!using.isEmpty()) {
            return "USING " + using;
        }

        String parser = sql(expression, Args.PARSER);
        if (!parser.isEmpty()) {
            return "WITH PARSER " + parser;
        }

        String comment = sql(expression, Args.COMMENT);
        if (!comment.isEmpty()) {
            return "COMMENT " + comment;
        }

        Object visible = expression.get(Args.VISIBLE);
        if (visible != null) {
            return Expression.truthy(visible) ? "VISIBLE" : "INVISIBLE";
        }

        String engineAttr = sql(expression, Args.ENGINE_ATTR);
        if (!engineAttr.isEmpty()) {
            return "ENGINE_ATTRIBUTE = " + engineAttr;
        }

        String secondaryEngineAttr = sql(expression, Args.SECONDARY_ENGINE_ATTR);
        if (!secondaryEngineAttr.isEmpty()) {
            return "SECONDARY_ENGINE_ATTRIBUTE = " + secondaryEngineAttr;
        }

        unsupported("Unsupported index constraint option.");
        return "";
    }

    @Override
    public String checkcolumnconstraintSql(Expression expression) {
        String enforced = expression.has(Args.ENFORCED) ? " ENFORCED" : "";
        return "CHECK (" + sql(expression, Args.THIS) + ")" + enforced;
    }

    @Override
    public String indexcolumnconstraintSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        kind = !kind.isEmpty() ? kind + " INDEX" : "INDEX";
        String thisSql = prefixed(" ", sql(expression, Args.THIS));
        String indexType = prefixed(" USING ", sql(expression, Args.INDEX_TYPE));
        String expressionsSql = expressions(opts(expression).flat());
        expressionsSql = !expressionsSql.isEmpty() ? " (" + expressionsSql + ")" : "";
        String options = prefixed(" ", expressions(opts(expression).key(Args.OPTIONS).sep(" ")));
        return kind + thisSql + indexType + expressionsSql + options;
    }

    @Override
    public String nvl2Sql(Expression expression) {
        if (g.NVL2_SUPPORTED) {
            return functionFallbackSql(expression);
        }

        Expression caseExpr = caseWhen(Expression.of(ExprType.CASE),
                Exp.not(binop(ExprType.IS, expression.getThis(), Exp.nul())),
                expression.expr(Args.TRUE));
        Object elseCond = expression.get(Args.FALSE);
        if (Expression.truthy(elseCond)) {
            caseExpr.set(Args.DEFAULT, elseCond);
        }

        return sql(caseExpr);
    }

    @Override
    public String nthvalueSql(Expression expression) {
        if (Boolean.FALSE.equals(expression.get(Args.FROM_FIRST))) {
            unsupported("NTH_VALUE FROM LAST is not supported");
        }

        return functionFallbackSql(expression);
    }

    @Override
    public String comprehensionSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expr = sql(expression, Args.EXPRESSION);
        String position = prefixed(", ", sql(expression, Args.POSITION));
        String iterator = sql(expression, Args.ITERATOR);
        String condition = prefixed(" IF ", sql(expression, Args.CONDITION));
        return thisSql + " FOR " + expr + position + " IN " + iterator + condition;
    }
}
