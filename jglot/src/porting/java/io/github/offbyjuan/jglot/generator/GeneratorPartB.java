package io.github.offbyjuan.jglot.generator;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.dialects.data.ParserData;
import io.github.offbyjuan.jglot.dialects.data.PropertiesLocation;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.util.Py;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of sqlglot's Generator, sqlglot/generator.py lines 1176-2661 (uncache_sql .. _pivot_in_value_aliases). */
abstract class GeneratorPartB extends GeneratorMethods {
    GeneratorPartB(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }

    private static final Pattern ESCAPED_UNICODE_RE = Pattern.compile("\\\\(\\d+)", Pattern.UNICODE_CHARACTER_CLASS);

    /** sqlglot's {@code exp.Properties.PROPERTY_TO_NAME} (inverse of NAME_TO_PROPERTY, expressions/properties.py). */
    private static final Map<ExprType, String> PROPERTY_TO_NAME = propertyToName();

    private static Map<ExprType, String> propertyToName() {
        EnumMap<ExprType, String> m = new EnumMap<>(ExprType.class);
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
        return Collections.unmodifiableMap(m);
    }

    // ------------------------------------------------------------------------------------------
    // Private helpers

    /** Python's {@code str(value)} as used inside f-strings on raw arg values. */
    private static String pyStr(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (v instanceof Expression e) {
            // Expression.__str__ renders with the default dialect
            return e.sql(Dialects.get(""));
        }
        return v.toString();
    }

    /** {@code DType.value}. */
    private static String dtypeValue(DType t) {
        return t == DType.USERDEFINED ? "USER-DEFINED" : t.name();
    }

    private String dialectName() {
        return dialect.getClass().getSimpleName();
    }

    /** {@code self.PROPERTIES_LOCATION[p.__class__]} (KeyError if missing). */
    private PropertiesLocation propertyLocation(Expression p) {
        PropertiesLocation loc = g.PROPERTIES_LOCATION.get(p.type());
        if (loc == null) {
            throw new IllegalArgumentException("KeyError: " + p.type().className());
        }
        return loc;
    }

    @SuppressWarnings("unchecked")
    private static List<Expression> locs(Object locations, PropertiesLocation loc) {
        if (locations instanceof Map<?, ?> m) {
            Object l = m.get(loc);
            if (l != null) {
                return (List<Expression>) l;
            }
        }
        return Collections.emptyList();
    }

    private static Expression newProperties(List<Expression> expressions) {
        return Expression.of(ExprType.PROPERTIES, Args.EXPRESSIONS, new ArrayList<>(expressions));
    }

    /** Python's {@code node.parent = parent} without attaching (no public setter on Expression yet). */
    private static void setParentDetached(Expression node, Expression parent) {
        try {
            java.lang.reflect.Field f = Expression.class.getDeclaredField("parent");
            f.setAccessible(true);
            f.set(node, parent);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** {@code self.dialect.tokenizer_class.DROP_UNKNOWN_ESCAPES} (no public accessor yet). */
    private boolean tokenizerDropUnknownEscapes() {
        try {
            java.lang.reflect.Field f = dialect.tokenizerConfig().getClass().getDeclaredField("dropUnknownEscapes");
            f.setAccessible(true);
            return f.getBoolean(dialect.tokenizerConfig());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Hook for a callable {@code UNICODE_SUBSTITUTE} (e.g. SingleStore's): return the replacement for
     * one match, or null to use the regex template.
     */
    protected String unicodeSubstitute(MatchResult match) {
        return null;
    }

    /** Python {@code re.sub} replacement template: {@code \1} is a group, {@code \\} a backslash. */
    private static String expandTemplate(String template, MatchResult m) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '\\' && i + 1 < template.length()) {
                char n = template.charAt(i + 1);
                if (Character.isDigit(n)) {
                    String grp = m.group(n - '0');
                    sb.append(grp == null ? "" : grp);
                    i++;
                    continue;
                }
                if (n == '\\') {
                    sb.append('\\');
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private String unicodeSub(Pattern pattern, String template, String text) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rep = unicodeSubstitute(m.toMatchResult());
            if (rep == null) {
                rep = expandTemplate(template, m);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Object[] toArray(Object o) {
        if (o == null) {
            return new Object[0];
        }
        if (o instanceof Object[] a) {
            return a;
        }
        if (o instanceof List<?> l) {
            return l.toArray();
        }
        if (o instanceof int[] ints) {
            Object[] out = new Object[ints.length];
            for (int i = 0; i < ints.length; i++) {
                out[i] = ints[i];
            }
            return out;
        }
        return new Object[] {o};
    }

    private static BigDecimal toDecimal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal d) {
            return d;
        }
        if (o instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (o instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(o.toString().strip());
    }

    private static String parseIntToString(String text, int radix) {
        return new BigInteger(text.strip().replace("_", ""), radix).toString();
    }

    // ------------------------------------------------------------------------------------------
    // Cache / columns / constraints

    @Override
    public String uncacheSql(Expression expression) {
        String table = sql(expression, Args.THIS);
        String existsSql = expression.has(Args.EXISTS) ? " IF EXISTS" : "";
        return "UNCACHE TABLE" + existsSql + " " + table;
    }

    @Override
    public String cacheSql(Expression expression) {
        String lazy = expression.has(Args.LAZY) ? " LAZY" : "";
        String table = sql(expression, Args.THIS);
        List<Object> options = expression.list(Args.OPTIONS);
        String optionsSql = !options.isEmpty() ? " OPTIONS(" + sql(options.get(0)) + " = " + sql(options.get(1)) + ")" : "";
        String s = sql(expression, Args.EXPRESSION);
        s = !s.isEmpty() ? " AS" + sep() + s : "";
        s = "CACHE" + lazy + " TABLE " + table + optionsSql + s;
        return prependCtes(expression, s);
    }

    @Override
    public String charactersetSql(Expression expression) {
        String default_ = expression.has(Args.DEFAULT) ? "DEFAULT " : "";
        return default_ + "CHARACTER SET=" + sql(expression, Args.THIS);
    }

    @Override
    public String columnParts(Expression expression) {
        if (expression.has(Args.SHADOW) && dd.PROJECTION_ALIASES_SHADOW_SOURCE_NAMES) {
            // The qualifier would be captured by a colliding projection alias (see qualify_columns)
            return sql(expression, Args.THIS);
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Arg a : new Arg[] {Args.CATALOG, Args.DB, Args.TABLE, Args.THIS}) {
            Object part = expression.get(a);
            if (Expression.truthy(part)) {
                if (!first) {
                    sb.append('.');
                }
                sb.append(sql(part));
                first = false;
            }
        }
        return sb.toString();
    }

    @Override
    public String columnSql(Expression expression) {
        String joinMark = expression.has(Args.JOIN_MARK) ? " (+)" : "";

        if (!joinMark.isEmpty() && !dd.SUPPORTS_COLUMN_JOIN_MARKS) {
            joinMark = "";
            unsupported("Outer join syntax using the (+) operator is not supported.");
        }

        return columnParts(expression) + joinMark;
    }

    @Override
    public String pseudocolumnSql(Expression expression) {
        return columnSql(expression);
    }

    @Override
    public String columnpositionSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        this_ = !this_.isEmpty() ? " " + this_ : "";
        String position = sql(expression, Args.POSITION);
        return position + this_;
    }

    @Override
    public String columndefSql(Expression expression, String sep) {
        String column = sql(expression, Args.THIS);
        String kind = sql(expression, Args.KIND);
        String constraints = expressions(opts(expression).key(Args.CONSTRAINTS).sep(" ").flat());
        String exists = expression.has(Args.EXISTS) ? "IF NOT EXISTS " : "";
        kind = !kind.isEmpty() ? sep + kind : "";
        constraints = !constraints.isEmpty() ? " " + constraints : "";
        String position = sql(expression, Args.POSITION);
        position = !position.isEmpty() ? " " + position : "";

        if (expression.find(ExprType.COMPUTED_COLUMN_CONSTRAINT) != null && !g.COMPUTED_COLUMN_WITH_TYPE) {
            kind = "";
        }

        return exists + column + kind + constraints + position;
    }

    @Override
    public String columnconstraintSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String kindSql = sql(expression, Args.KIND).strip();
        return !this_.isEmpty() ? "CONSTRAINT " + this_ + " " + kindSql : kindSql;
    }

    @Override
    public String computedcolumnconstraintSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String persisted;
        if (expression.has(Args.NOT_NULL)) {
            persisted = " PERSISTED NOT NULL";
        } else if (expression.has(Args.PERSISTED)) {
            persisted = " PERSISTED";
        } else {
            persisted = "";
        }

        return "AS " + this_ + persisted;
    }

    @Override
    public String autoincrementcolumnconstraintSql(Expression unused) {
        return tokenSql(TokenType.AUTO_INCREMENT);
    }

    @Override
    public String compresscolumnconstraintSql(Expression expression) {
        String this_;
        if (expression.thisValue() instanceof List<?>) {
            this_ = wrap(expressions(opts(expression).key(Args.THIS).flat()));
        } else {
            this_ = sql(expression, Args.THIS);
        }

        return "COMPRESS " + this_;
    }

    @Override
    public String generatedasidentitycolumnconstraintSql(Expression expression) {
        String this_ = "";
        Object thisValue = expression.thisValue();
        if (thisValue != null) {
            String onNull = expression.has(Args.ON_NULL) ? " ON NULL" : "";
            this_ = Expression.truthy(thisValue) ? " ALWAYS" : " BY DEFAULT" + onNull;
        }

        Object startV = expression.get(Args.START);
        String start = Expression.truthy(startV) ? "START WITH " + pyStr(startV) : "";
        Object incrementV = expression.get(Args.INCREMENT);
        String increment = Expression.truthy(incrementV) ? " INCREMENT BY " + pyStr(incrementV) : "";
        Object minvalueV = expression.get(Args.MINVALUE);
        String minvalue = Expression.truthy(minvalueV) ? " MINVALUE " + pyStr(minvalueV) : "";
        Object maxvalueV = expression.get(Args.MAXVALUE);
        String maxvalue = Expression.truthy(maxvalueV) ? " MAXVALUE " + pyStr(maxvalueV) : "";
        Object cycle = expression.get(Args.CYCLE);
        String cycleSql = "";

        if (cycle != null) {
            cycleSql = (!Expression.truthy(cycle) ? " NO" : "") + " CYCLE";
            cycleSql = start.isEmpty() && increment.isEmpty() ? cycleSql.strip() : cycleSql;
        }

        String sequenceOpts = "";
        if (!start.isEmpty() || !increment.isEmpty() || !cycleSql.isEmpty()) {
            sequenceOpts = start + increment + minvalue + maxvalue + cycleSql;
            sequenceOpts = " (" + sequenceOpts.strip() + ")";
        }

        String expr = sql(expression, Args.EXPRESSION);
        expr = !expr.isEmpty() ? "(" + expr + ")" : "IDENTITY";

        return "GENERATED" + this_ + " AS " + expr + sequenceOpts;
    }

    @Override
    public String generatedasrowcolumnconstraintSql(Expression expression) {
        String start = expression.has(Args.START) ? "START" : "END";
        String hidden = expression.has(Args.HIDDEN) ? " HIDDEN" : "";
        return "GENERATED ALWAYS AS ROW " + start + hidden;
    }

    @Override
    public String periodforsystemtimeconstraintSql(Expression expression) {
        return "PERIOD FOR SYSTEM_TIME (" + sql(expression, Args.THIS) + ", " + sql(expression, Args.EXPRESSION) + ")";
    }

    @Override
    public String notnullcolumnconstraintSql(Expression expression) {
        return (expression.has(Args.ALLOW_NULL) ? "" : "NOT ") + "NULL";
    }

    @Override
    public String primarykeycolumnconstraintSql(Expression expression) {
        Object desc = expression.get(Args.DESC);
        if (desc != null) {
            return "PRIMARY KEY" + (Expression.truthy(desc) ? " DESC" : " ASC");
        }
        String options = expressions(opts(expression).key(Args.OPTIONS).flat().sep(" "));
        options = !options.isEmpty() ? " " + options : "";
        return "PRIMARY KEY" + options;
    }

    @Override
    public String uniquecolumnconstraintSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        this_ = !this_.isEmpty() ? " " + this_ : "";
        Object indexTypeV = expression.get(Args.INDEX_TYPE);
        String indexType = Expression.truthy(indexTypeV) ? " USING " + pyStr(indexTypeV) : "";
        String onConflict = sql(expression, Args.ON_CONFLICT);
        onConflict = !onConflict.isEmpty() ? " " + onConflict : "";
        String nullsSql = expression.has(Args.NULLS) ? " NULLS NOT DISTINCT" : "";
        String options = expressions(opts(expression).key(Args.OPTIONS).flat().sep(" "));
        options = !options.isEmpty() ? " " + options : "";
        return "UNIQUE" + nullsSql + this_ + indexType + onConflict + options;
    }

    @Override
    public String inoutcolumnconstraintSql(Expression expression) {
        boolean input = expression.has(Args.INPUT_);
        boolean output = expression.has(Args.OUTPUT);
        boolean variadic = expression.has(Args.VARIADIC);

        // VARIADIC is mutually exclusive with IN/OUT/INOUT
        if (variadic) {
            return "VARIADIC";
        }

        if (input && output) {
            return "IN" + g.INOUT_SEPARATOR + "OUT";
        }
        if (input) {
            return "IN";
        }
        if (output) {
            return "OUT";
        }

        return "";
    }

    // ------------------------------------------------------------------------------------------
    // CREATE

    @Override
    public String createableSql(Expression expression, Object locations) {
        return sql(expression, Args.THIS);
    }

    @Override
    public String createSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        String mappedKind = dd.INVERSE_CREATABLE_KIND_MAPPING.get(kind);
        kind = mappedKind != null && !mappedKind.isEmpty() ? mappedKind : kind;

        Expression properties = expression.expr(Args.PROPERTIES);

        if ("TRIGGER".equals(kind) && properties != null) {
            List<Expression> pexprs = properties.getExpressions();
            if (!pexprs.isEmpty() && pexprs.get(0).is(ExprType.TRIGGER_PROPERTIES) && pexprs.get(0).has(Args.CONSTRAINT)) {
                kind = "CONSTRAINT " + kind;
            }
        }

        Object propertiesLocs = properties != null ? locateProperties(properties) : new EnumMap<PropertiesLocation, List<Expression>>(PropertiesLocation.class);

        String this_ = createableSql(expression, propertiesLocs);

        String propertiesSql = "";
        if (!locs(propertiesLocs, PropertiesLocation.POST_SCHEMA).isEmpty()
                || !locs(propertiesLocs, PropertiesLocation.POST_WITH).isEmpty()) {
            List<Expression> all = new ArrayList<>(locs(propertiesLocs, PropertiesLocation.POST_SCHEMA));
            all.addAll(locs(propertiesLocs, PropertiesLocation.POST_WITH));
            Expression propsAst = newProperties(all);
            setParentDetached(propsAst, expression);
            propertiesSql = sql(propsAst);

            if (!locs(propertiesLocs, PropertiesLocation.POST_SCHEMA).isEmpty()) {
                propertiesSql = sep() + propertiesSql;
            } else if (!pretty) {
                // Standalone POST_WITH properties need a leading whitespace in non-pretty mode
                propertiesSql = " " + propertiesSql;
            }
        }

        String begin = expression.has(Args.BEGIN) ? " BEGIN" : "";

        String expressionSql = sql(expression, Args.EXPRESSION);
        if (!expressionSql.isEmpty()) {
            expressionSql = begin + sep() + expressionSql;

            Expression inner = expression.getExpression();
            boolean isMacroOverloads = inner != null && inner.is(ExprType.MACRO_OVERLOADS);
            boolean isReturn = inner != null && inner.is(ExprType.RETURN);
            if (!isMacroOverloads && (g.CREATE_FUNCTION_RETURN_AS || !isReturn)) {
                String postaliasPropsSql = "";
                if (!locs(propertiesLocs, PropertiesLocation.POST_ALIAS).isEmpty()) {
                    postaliasPropsSql = properties(newProperties(locs(propertiesLocs, PropertiesLocation.POST_ALIAS)),
                            "", ", ", "", false);
                }
                postaliasPropsSql = !postaliasPropsSql.isEmpty() ? " " + postaliasPropsSql : "";
                expressionSql = " AS" + postaliasPropsSql + expressionSql;
            }
        }

        String postindexPropsSql = "";
        if (!locs(propertiesLocs, PropertiesLocation.POST_INDEX).isEmpty()) {
            postindexPropsSql = properties(newProperties(locs(propertiesLocs, PropertiesLocation.POST_INDEX)),
                    " ", ", ", "", false);
        }

        String indexes = expressions(opts(expression).key(Args.INDEXES).indent(false).sep(" "));
        indexes = !indexes.isEmpty() ? " " + indexes : "";
        String indexSql = indexes + postindexPropsSql;

        String replace = expression.has(Args.REPLACE) ? " OR REPLACE" : "";
        String refresh = expression.has(Args.REFRESH) ? " OR REFRESH" : "";
        String unique = expression.has(Args.UNIQUE) ? " UNIQUE" : "";

        Object clustered = expression.get(Args.CLUSTERED);
        String clusteredSql;
        if (clustered == null) {
            clusteredSql = "";
        } else if (Expression.truthy(clustered)) {
            clusteredSql = " CLUSTERED COLUMNSTORE";
        } else {
            clusteredSql = " NONCLUSTERED COLUMNSTORE";
        }

        String postcreatePropsSql = "";
        if (!locs(propertiesLocs, PropertiesLocation.POST_CREATE).isEmpty()) {
            postcreatePropsSql = properties(newProperties(locs(propertiesLocs, PropertiesLocation.POST_CREATE)),
                    " ", " ", "", false);
        }

        String modifiers = clusteredSql + replace + refresh + unique + postcreatePropsSql;

        String postexpressionPropsSql = "";
        if (!locs(propertiesLocs, PropertiesLocation.POST_EXPRESSION).isEmpty()) {
            postexpressionPropsSql = properties(newProperties(locs(propertiesLocs, PropertiesLocation.POST_EXPRESSION)),
                    " ", " ", "", false);
        }

        String concurrently = expression.has(Args.CONCURRENTLY) ? " CONCURRENTLY" : "";
        String existsSql = expression.has(Args.EXISTS) ? " IF NOT EXISTS" : "";
        String noSchemaBinding = expression.has(Args.NO_SCHEMA_BINDING) ? " WITH NO SCHEMA BINDING" : "";

        String clone = sql(expression, Args.CLONE);
        clone = !clone.isEmpty() ? " " + clone : "";

        String propertiesExpression;
        if (g.EXPRESSION_PRECEDES_PROPERTIES_CREATABLES.contains(kind)) {
            propertiesExpression = expressionSql + propertiesSql;
        } else {
            propertiesExpression = propertiesSql + expressionSql;
        }

        expressionSql = "CREATE" + modifiers + " " + kind + concurrently + existsSql + " " + this_ + propertiesExpression
                + postexpressionPropsSql + indexSql + noSchemaBinding + clone;
        return prependCtes(expression, expressionSql);
    }

    @Override
    public String sequencepropertiesSql(Expression expression) {
        String start = sql(expression, Args.START);
        start = !start.isEmpty() ? "START WITH " + start : "";
        String increment = sql(expression, Args.INCREMENT);
        increment = !increment.isEmpty() ? " INCREMENT BY " + increment : "";
        String minvalue = sql(expression, Args.MINVALUE);
        minvalue = !minvalue.isEmpty() ? " MINVALUE " + minvalue : "";
        String maxvalue = sql(expression, Args.MAXVALUE);
        maxvalue = !maxvalue.isEmpty() ? " MAXVALUE " + maxvalue : "";
        String owned = sql(expression, Args.OWNED);
        owned = !owned.isEmpty() ? " OWNED BY " + owned : "";

        Object cache = expression.get(Args.CACHE);
        String cacheStr;
        if (cache == null) {
            cacheStr = "";
        } else if (Boolean.TRUE.equals(cache)) {
            cacheStr = " CACHE";
        } else {
            cacheStr = " CACHE " + pyStr(cache);
        }

        String options = expressions(opts(expression).key(Args.OPTIONS).flat().sep(" "));
        options = !options.isEmpty() ? " " + options : "";

        return lstrip(start + increment + minvalue + maxvalue + cacheStr + options + owned);
    }

    @Override
    public String triggerpropertiesSql(Expression expression) {
        Object timingV = expression.get(Args.TIMING);
        String timing = timingV == null ? "" : pyStr(timingV);
        List<String> eventSqls = new ArrayList<>();
        for (Object event : expression.list(Args.EVENTS)) {
            eventSqls.add(sql(event));
        }
        String events = String.join(" OR ", eventSqls);
        String timingEvents = Expression.truthy(timingV) || !events.isEmpty() ? (timing + " " + events).strip() : "";

        List<String> parts = new ArrayList<>(List.of(timingEvents, "ON", sql(expression, Args.TABLE)));

        Object referencedTable = expression.get(Args.REFERENCED_TABLE);
        if (Expression.truthy(referencedTable)) {
            parts.add("FROM");
            parts.add(sql(referencedTable));
        }

        Object deferrable = expression.get(Args.DEFERRABLE);
        if (Expression.truthy(deferrable)) {
            parts.add(pyStr(deferrable));
        }

        Object initially = expression.get(Args.INITIALLY);
        if (Expression.truthy(initially)) {
            parts.add("INITIALLY " + pyStr(initially));
        }

        Object referencing = expression.get(Args.REFERENCING);
        if (Expression.truthy(referencing)) {
            parts.add(sql(referencing));
        }

        Object forEach = expression.get(Args.FOR_EACH);
        if (Expression.truthy(forEach)) {
            parts.add("FOR EACH " + pyStr(forEach));
        }

        Object when = expression.get(Args.WHEN);
        if (Expression.truthy(when)) {
            parts.add("WHEN (" + sql(when) + ")");
        }

        parts.add(sql(expression, Args.EXECUTE));

        return String.join(sep(), parts);
    }

    @Override
    public String triggerreferencingSql(Expression expression) {
        List<String> parts = new ArrayList<>();

        Object oldAlias = expression.get(Args.OLD);
        if (Expression.truthy(oldAlias)) {
            parts.add("OLD TABLE AS " + sql(oldAlias));
        }

        Object newAlias = expression.get(Args.NEW);
        if (Expression.truthy(newAlias)) {
            parts.add("NEW TABLE AS " + sql(newAlias));
        }

        return "REFERENCING " + String.join(" ", parts);
    }

    @Override
    public String triggereventSql(Expression expression) {
        if (expression.has(Args.COLUMNS)) {
            return pyStr(expression.thisValue()) + " OF " + expressions(opts(expression).key(Args.COLUMNS).flat());
        }

        return sql(expression, Args.THIS);
    }

    @Override
    public String cloneSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String shallow = expression.has(Args.SHALLOW) ? "SHALLOW " : "";
        String keyword = expression.has(Args.COPY) && g.SUPPORTS_TABLE_COPY ? "COPY" : "CLONE";
        return shallow + keyword + " " + this_;
    }

    @Override
    public String describeSql(Expression expression) {
        Object styleV = expression.get(Args.STYLE);
        String style = Expression.truthy(styleV) ? " " + pyStr(styleV) : "";
        String partition = sql(expression, Args.PARTITION);
        partition = !partition.isEmpty() ? " " + partition : "";
        String format = sql(expression, Args.FORMAT);
        format = !format.isEmpty() ? " " + format : "";
        String asJson = expression.has(Args.AS_JSON) ? " AS JSON" : "";

        return "DESCRIBE" + style + format + " " + sql(expression, Args.THIS) + partition + asJson;
    }

    @Override
    public String heredocSql(Expression expression) {
        String tag = sql(expression, Args.TAG);
        return "$" + tag + "$" + sql(expression, Args.THIS) + "$" + tag + "$";
    }

    @Override
    public String prependCtes(Expression expression, String sql) {
        String with = sql(expression, Args.WITH_);
        if (!with.isEmpty()) {
            sql = with + sep() + sql;
        }
        return sql;
    }

    @Override
    public String withSql(Expression expression) {
        String udfs = expressions(opts(expression).key(Args.UDFS).flat());
        udfs = !udfs.isEmpty() ? "WITH " + udfs : "";

        String s = expressions(opts(expression).flat());

        String recursive = g.CTE_RECURSIVE_KEYWORD_REQUIRED && expression.has(Args.RECURSIVE) ? "RECURSIVE " : "";
        String search = sql(expression, Args.SEARCH);
        search = !search.isEmpty() ? " " + search : "";
        String cycle = sql(expression, Args.CYCLE);
        cycle = !cycle.isEmpty() ? " " + cycle : "";

        s = !s.isEmpty() ? "WITH " + recursive + s + search + cycle : "";
        return !udfs.isEmpty() && !s.isEmpty() ? udfs + " " + s : udfs + s;
    }

    @Override
    public String cteSql(Expression expression) {
        Expression alias = expression.expr(Args.ALIAS);
        if (alias != null) {
            alias.addComments(expression.popComments());
        }

        String aliasSql = sql(expression, Args.ALIAS);

        Object materializedV = expression.get(Args.MATERIALIZED);
        String materialized = "";
        if (Boolean.FALSE.equals(materializedV)) {
            materialized = "NOT MATERIALIZED ";
        } else if (Expression.truthy(materializedV)) {
            materialized = "MATERIALIZED ";
        }

        String keyExpressions = expressions(opts(expression).key(Args.KEY_EXPRESSIONS).flat());
        keyExpressions = !keyExpressions.isEmpty() ? " USING KEY (" + keyExpressions + ")" : "";

        return aliasSql + keyExpressions + " AS " + materialized + wrap(expression);
    }

    @Override
    public String tablealiasSql(Expression expression) {
        String alias = sql(expression, Args.THIS);
        String columns = expressions(opts(expression).key(Args.COLUMNS).flat());
        columns = !columns.isEmpty() ? "(" + columns + ")" : "";

        Expression parent = expression.parent();
        if (!columns.isEmpty()
                && !g.SUPPORTS_TABLE_ALIAS_COLUMNS
                && !(g.SUPPORTS_NAMED_CTE_COLUMNS && parent != null && parent.is(ExprType.CTE))) {
            columns = "";
            unsupported("Named columns are not supported in table alias.");
        }

        if (alias.isEmpty() && !dd.UNNEST_COLUMN_ONLY) {
            alias = nextName();
        }

        return alias + columns;
    }

    // ------------------------------------------------------------------------------------------
    // Strings

    @Override
    public String bitstringSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        if (dd.BIT_START != null && !dd.BIT_START.isEmpty()) {
            return dd.BIT_START + this_ + dd.BIT_END;
        }
        return parseIntToString(this_, 2);
    }

    @Override
    public String hexstringSql(Expression expression, String binaryFunctionRepr) {
        String this_ = sql(expression, Args.THIS);
        boolean isIntegerType = expression.has(Args.IS_INTEGER);
        boolean hasRepr = binaryFunctionRepr != null && !binaryFunctionRepr.isEmpty();
        boolean hasHexStart = dd.HEX_START != null && !dd.HEX_START.isEmpty();

        if ((isIntegerType && !dd.HEX_STRING_IS_INTEGER_TYPE) || (!hasHexStart && !hasRepr)) {
            // Integer representation will be returned if:
            // - The read dialect treats the hex value as integer literal but not the write
            // - The transpilation is not supported (write dialect hasn't set HEX_START or the param flag)
            return parseIntToString(this_, 16);
        }

        if (!isIntegerType) {
            // Read dialect treats the hex value as BINARY/BLOB
            if (hasRepr) {
                // The write dialect supports the transpilation to its equivalent BINARY/BLOB
                return func(binaryFunctionRepr, Exp.string(this_));
            }
            if (dd.HEX_STRING_IS_INTEGER_TYPE) {
                // The write dialect does not support the transpilation, it'll treat the hex value as INTEGER
                unsupported("Unsupported transpilation from BINARY/BLOB hex string");
            }
        }

        return dd.HEX_START + this_ + dd.HEX_END;
    }

    @Override
    public String bytestringSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        boolean isBytes = expression.has(Args.IS_BYTES);
        if (isBytes && g.BYTE_STRING_FUNCTION != null && !g.BYTE_STRING_FUNCTION.isEmpty()) {
            // Hex digits can be quoted directly without escaping.
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < this_.length(); i++) {
                char c = this_.charAt(i);
                if (c > 0xFF) {
                    throw new IllegalArgumentException("'latin-1' codec can't encode character at position " + i);
                }
                hex.append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xF, 16));
            }
            return func(g.BYTE_STRING_FUNCTION, "'" + hex + "'");
        }

        if (dd.BYTE_START != null && !dd.BYTE_START.isEmpty()) {
            String escapedByteString = escapeStr(
                    this_,
                    !dialect.tokenizerConfig().numericEscapes().isEmpty() || tokenizerDropUnknownEscapes(),
                    dd.BYTE_END,
                    escapedByteQuoteEnd,
                    true,
                    isBytes);
            String delimitedByteString = dd.BYTE_START + escapedByteString + dd.BYTE_END;
            if (isBytes && !dd.BYTE_STRING_IS_BYTES_TYPE) {
                return sql(Exp.cast(Exp.maybeParse(delimitedByteString, dialect), DType.BINARY, true, dialect));
            }
            if (!isBytes && dd.BYTE_STRING_IS_BYTES_TYPE) {
                return sql(Exp.cast(Exp.maybeParse(delimitedByteString, dialect), DType.VARCHAR, true, dialect));
            }

            return delimitedByteString;
        }

        if (dialect.tokenizerConfig().stringEscapes().contains('\\')) {
            return sql(Exp.string(this_));
        }

        unsupported("Byte strings are not supported for " + dialectName());
        return "";
    }

    @Override
    public String unicodestringSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        Expression escape = expression.expr(Args.ESCAPE);
        String unicodeStart = dd.UNICODE_START;
        boolean hasUnicodeStart = unicodeStart != null && !unicodeStart.isEmpty();

        String escapeSubstitute;
        String leftQuote;
        String rightQuote;
        if (hasUnicodeStart) {
            escapeSubstitute = "\\\\\\1";
            leftQuote = unicodeStart;
            rightQuote = dd.UNICODE_END != null && !dd.UNICODE_END.isEmpty() ? dd.UNICODE_END : "";
        } else {
            escapeSubstitute = "\\\\u\\1";
            leftQuote = dd.QUOTE_START;
            rightQuote = dd.QUOTE_END;
        }

        Pattern escapePattern;
        String escapeSql;
        if (escape != null) {
            escapePattern = Pattern.compile(escape.name() + "(\\d+)", Pattern.UNICODE_CHARACTER_CLASS);
            escapeSql = g.SUPPORTS_UESCAPE ? " UESCAPE " + sql(escape) : "";
        } else {
            escapePattern = ESCAPED_UNICODE_RE;
            escapeSql = "";
        }

        if (!hasUnicodeStart || (escape != null && !g.SUPPORTS_UESCAPE)) {
            String template = g.UNICODE_SUBSTITUTE != null && !g.UNICODE_SUBSTITUTE.isEmpty() ? g.UNICODE_SUBSTITUTE : escapeSubstitute;
            this_ = unicodeSub(escapePattern, template, this_);
        }

        if (hasUnicodeStart) {
            // A Unicode literal only escapes its delimiter by doubling it; the escape character
            // introduces a code point, so the dialect's ordinary string escapes don't apply here
            this_ = rightQuote.isEmpty() ? replaceLineBreaks(this_) : replaceLineBreaks(this_).replace(rightQuote, rightQuote + rightQuote);
        } else {
            this_ = escapeStr(this_, false);
        }

        return leftQuote + this_ + rightQuote + escapeSql;
    }

    @Override
    public String rawstringSql(Expression expression) {
        String string = pyStr(expression.thisValue());
        if (dialect.tokenizerConfig().stringEscapes().contains('\\')) {
            string = string.replace("\\", "\\\\");
        }

        string = escapeStr(string, false);
        return dd.QUOTE_START + string + dd.QUOTE_END;
    }

    // ------------------------------------------------------------------------------------------
    // Data types

    @Override
    public String datatypeparamSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String specifier = sql(expression, Args.EXPRESSION);
        specifier = !specifier.isEmpty() && g.DATA_TYPE_SPECIFIERS_ALLOWED ? " " + specifier : "";
        return this_ + specifier;
    }

    /**
     * {@code typeValue} is the DataType node whose {@code this} is the DType (the contract types the
     * Python {@code exp.DType} parameter as an Expression).
     */
    @Override
    public Expression datatypeParamBoundLimiter(Expression expression, Expression typeValue, Object[] defaults, Object[] bounds) {
        Object tv = (typeValue != null ? typeValue : expression).thisValue();
        List<Expression> params = expression.getExpressions();

        if (params.isEmpty()) {
            if (defaults != null && defaults.length > 0) {
                List<Expression> newParams = new ArrayList<>();
                for (Object d : defaults) {
                    newParams.add(Expression.of(ExprType.DATA_TYPE_PARAM, Args.THIS, Exp.number(d)));
                }
                expression.set(Args.EXPRESSIONS, newParams);
            }
            return expression;
        }

        if (bounds == null || bounds.length == 0) {
            return expression;
        }

        params = new ArrayList<>(params);
        for (int i = 0; i < params.size(); i++) {
            Object bound = i < bounds.length ? bounds[i] : null;
            if (bound == null) {
                continue;
            }

            Expression param = params.get(i);
            Expression paramValue = param.is(ExprType.DATA_TYPE_PARAM) ? param.getThis() : param;
            Object value = paramValue != null && paramValue.is(ExprType.LITERAL) && paramValue.isNumber() ? paramValue.toPy() : null;
            if ((value instanceof BigInteger || value instanceof BigDecimal) && toDecimal(value).compareTo(toDecimal(bound)) > 0) {
                String typeName = tv instanceof DType dt ? dtypeValue(dt) : pyStr(tv);
                unsupported(typeName + " parameter " + paramValue.name() + " exceeds "
                        + dialectName() + "'s maximum of " + bound + "; capping");
                expression.set(Args.EXPRESSIONS, Expression.of(ExprType.DATA_TYPE_PARAM, Args.THIS, Exp.number(bound)), i, true);
            }
        }

        return expression;
    }

    @Override
    public String datatypeSql(Expression expression) {
        String nested = "";
        String values = "";

        boolean exprNested = expression.has(Args.NESTED);
        Object typeValue = expression.thisValue();

        if (!exprNested && typeValue instanceof DType dt) {
            Object settings = g.TYPE_PARAM_SETTINGS.get(dt);
            if (Expression.truthy(settings)) {
                Object[] s = toArray(settings);
                expression = datatypeParamBoundLimiter(expression, expression, toArray(s.length > 0 ? s[0] : null),
                        toArray(s.length > 1 ? s[1] : null));
            }
        }

        String interior = exprNested && pretty
                ? expressions(opts(expression).dynamic().newLine().skipFirst().skipLast())
                : expressions(opts(expression).flat());

        if (typeValue != null && g.UNSUPPORTED_TYPES.contains(typeValue)) {
            unsupported("Data type " + (typeValue instanceof DType dt ? dtypeValue(dt) : pyStr(typeValue))
                    + " is not supported when targeting " + dialectName());
        }

        String typeSql;
        if (typeValue == DType.USERDEFINED && expression.has(Args.KIND)) {
            typeSql = sql(expression, Args.KIND);
        } else if (typeValue == DType.CHARACTER_SET) {
            String chr = !interior.isEmpty() ? "CHAR(" + interior + ")" : "CHAR";
            return chr + " CHARACTER SET " + sql(expression, Args.KIND);
        } else if (typeValue instanceof DType dt) {
            String mapped = g.TYPE_MAPPING.get(dt);
            typeSql = mapped != null ? mapped : dtypeValue(dt);
        } else {
            typeSql = pyStr(typeValue);
        }

        if (!interior.isEmpty()) {
            if (exprNested) {
                nested = g.STRUCT_DELIMITER.get(0) + interior + g.STRUCT_DELIMITER.get(1);
                if (expression.get(Args.VALUES) != null) {
                    String open = typeValue == DType.ARRAY ? "[" : "(";
                    String close = typeValue == DType.ARRAY ? "]" : ")";
                    values = expressions(opts(expression).key(Args.VALUES).flat());
                    values = open + values + close;
                }
            } else if (typeValue == DType.INTERVAL) {
                nested = " " + interior;
            } else {
                nested = "(" + interior + ")";
            }
        }

        typeSql = typeSql + nested + values;
        if (g.TZ_TO_WITH_TIME_ZONE && (typeValue == DType.TIMETZ || typeValue == DType.TIMESTAMPTZ)) {
            typeSql = typeSql + " WITH TIME ZONE";
        }

        String collate = sql(expression, Args.COLLATE);
        if (!collate.isEmpty()) {
            typeSql = typeSql + " COLLATE " + collate;
        }

        return typeSql;
    }

    @Override
    public String directorySql(Expression expression) {
        String local = expression.has(Args.LOCAL) ? "LOCAL " : "";
        String rowFormat = sql(expression, Args.ROW_FORMAT);
        rowFormat = !rowFormat.isEmpty() ? " " + rowFormat : "";
        return local + "DIRECTORY " + sql(expression, Args.THIS) + rowFormat;
    }

    // ------------------------------------------------------------------------------------------
    // DELETE / DROP / set operations

    @Override
    public String deleteSql(Expression expression) {
        String hint = sql(expression, Args.HINT);
        String this_ = sql(expression, Args.THIS);
        this_ = !this_.isEmpty() ? " FROM " + this_ : "";
        String using = expressions(expression, Args.USING);
        using = !using.isEmpty() ? " USING " + using : "";
        String cluster = sql(expression, Args.CLUSTER);
        cluster = !cluster.isEmpty() ? " " + cluster : "";
        String where = sql(expression, Args.WHERE);
        String returning = sql(expression, Args.RETURNING);
        String order = sql(expression, Args.ORDER);
        String limit = sql(expression, Args.LIMIT);
        String tables = expressions(expression, Args.TABLES);
        tables = !tables.isEmpty() ? " " + tables : "";
        String expressionSql;
        if (g.RETURNING_END) {
            expressionSql = this_ + using + cluster + where + returning + order + limit;
        } else {
            expressionSql = returning + this_ + using + cluster + where + order + limit;
        }
        return prependCtes(expression, "DELETE" + hint + tables + expressionSql);
    }

    @Override
    public String dropSql(Expression expression) {
        String tables = expressions(opts(expression).key(Args.TABLES).flat());
        String exprs = expressions(opts(expression).flat());
        exprs = !exprs.isEmpty() ? " (" + exprs + ")" : "";
        Object kindV = expression.get(Args.KIND);
        String kind = pyStr(kindV);
        String mappedKind = kindV instanceof String ks ? dd.INVERSE_CREATABLE_KIND_MAPPING.get(ks) : null;
        kind = mappedKind != null && !mappedKind.isEmpty() ? mappedKind : kind;
        String iceberg = expression.has(Args.ICEBERG) && g.SUPPORTS_DROP_ALTER_ICEBERG_PROPERTY ? " ICEBERG" : "";
        String existsSql = expression.has(Args.EXISTS) ? " IF EXISTS " : " ";
        String concurrentlySql = expression.has(Args.CONCURRENTLY) ? " CONCURRENTLY" : "";
        String onCluster = sql(expression, Args.CLUSTER);
        onCluster = !onCluster.isEmpty() ? " " + onCluster : "";
        String temporary = expression.has(Args.TEMPORARY) ? " TEMPORARY" : "";
        String materialized = expression.has(Args.MATERIALIZED) ? " MATERIALIZED" : "";
        String cascade = expression.has(Args.CASCADE) ? " CASCADE" : "";
        String restrict = expression.has(Args.RESTRICT) ? " RESTRICT" : "";
        String constraints = expression.has(Args.CONSTRAINTS) ? " CONSTRAINTS" : "";
        String purge = expression.has(Args.PURGE) ? " PURGE" : "";
        String sync = expression.has(Args.SYNC) ? " SYNC" : "";
        String force = expression.has(Args.FORCE) ? " FORCE" : "";
        return "DROP" + temporary + materialized + iceberg + " " + kind + concurrentlySql + existsSql + tables + onCluster
                + exprs + cascade + restrict + constraints + purge + sync + force;
    }

    @Override
    public String setOperation(Expression expression) {
        ExprType opType = expression.type();
        String opName = Py.upper(opType.key());

        Object distinct = expression.get(Args.DISTINCT);
        if (Boolean.FALSE.equals(distinct)
                && (opType == ExprType.EXCEPT || opType == ExprType.INTERSECT)
                && !g.EXCEPT_INTERSECT_SUPPORT_ALL_CLAUSE) {
            unsupported(opName + " ALL is not supported");
        }

        if (!dd.SET_OP_DISTINCT_BY_DEFAULT.containsKey(opType)) {
            throw new IllegalArgumentException("KeyError: " + opType.className());
        }
        Boolean defaultDistinct = dd.SET_OP_DISTINCT_BY_DEFAULT.get(opType);

        if (distinct == null) {
            distinct = defaultDistinct;
            if (distinct == null) {
                unsupported(opName + " requires DISTINCT or ALL to be specified");
            }
        }

        String distinctOrAll;
        if (Objects.equals(distinct, defaultDistinct)) {
            distinctOrAll = "";
        } else {
            distinctOrAll = Expression.truthy(distinct) ? " DISTINCT" : " ALL";
        }

        String side = Py.upper(expression.text(Args.SIDE));
        String kind = Py.upper(expression.text(Args.KIND));
        String sideKind;
        if (!side.isEmpty() && !kind.isEmpty()) {
            sideKind = side + " " + kind;
        } else {
            sideKind = side + kind;
        }
        sideKind = !sideKind.isEmpty() ? sideKind + " " : "";

        String byName = expression.has(Args.BY_NAME) ? " BY NAME" : "";
        String on = expressions(opts(expression).key(Args.ON).flat());
        on = !on.isEmpty() ? " ON (" + on + ")" : "";

        return sideKind + opName + distinctOrAll + byName + on;
    }

    @Override
    public String setOperations(Expression expression) {
        if (!g.SET_OP_MODIFIERS) {
            Object limit = expression.get(Args.LIMIT);
            Object order = expression.get(Args.ORDER);
            Object offset = expression.get(Args.OFFSET);

            if (Expression.truthy(limit) || Expression.truthy(order) || Expression.truthy(offset)) {
                // exp.subquery(expression, "_l_0", copy=False).select("*", copy=False)
                Expression sub = Expression.of(ExprType.SELECT).from(expression.subquery("_l_0", false), true);
                Expression select = moveCtesToTopLevel(sub.select(true, false, "*"));

                for (Arg arg : new Arg[] {Args.LIMIT, Args.ORDER, Args.OFFSET}) {
                    Expression value = expression.expr(arg);
                    if (value != null) {
                        select.set(arg, value.pop());
                    }
                }
                return sql(select);
            }
        }

        List<String> sqls = new ArrayList<>();
        java.util.ArrayDeque<Object> stack = new java.util.ArrayDeque<>();
        stack.push(expression);

        while (!stack.isEmpty()) {
            Object node = stack.pop();

            if (node instanceof Expression n && n.is(ExprType.SET_OPERATION)) {
                stack.push(nullToNone(n.get(Args.EXPRESSION)));
                stack.push(maybeComment(setOperation(n), null, n.comments(), true));
                stack.push(nullToNone(n.get(Args.THIS)));
            } else {
                if (node == NONE) {
                    node = null;
                }
                if (!g.SET_OP_LIMITS && node instanceof Expression n && n.is(ExprType.SELECT) && n.has(Args.LIMIT)) {
                    Expression wrapped = n.subquery(null, false);
                    if (!g.SET_OP_PARENTHESIZED_OPERANDS) {
                        wrapped = Exp.select("*").from(wrapped, false);
                    }
                    node = wrapped;
                }
                sqls.add(sql(node));
            }
        }

        String this_ = String.join(sep(), sqls);
        this_ = queryModifiers(expression, this_);
        return prependCtes(expression, this_);
    }

    private static final Object NONE = new Object();

    private static Object nullToNone(Object o) {
        return o == null ? NONE : o;
    }

    // ------------------------------------------------------------------------------------------
    // FETCH / LIMIT / FILTER / hints / indexes

    @Override
    public String fetchSql(Expression expression) {
        Object directionV = expression.get(Args.DIRECTION);
        String direction = Expression.truthy(directionV) ? " " + pyStr(directionV) : "";
        String count = sql(expression, Args.COUNT);
        count = !count.isEmpty() ? " " + count : "";
        String limitOptions = sql(expression, Args.LIMIT_OPTIONS);
        limitOptions = !limitOptions.isEmpty() ? limitOptions : " ROWS ONLY";
        return seg("FETCH") + direction + count + limitOptions;
    }

    @Override
    public String limitoptionsSql(Expression expression) {
        String percent = expression.has(Args.PERCENT) ? " PERCENT" : "";
        String rows = expression.has(Args.ROWS) ? " ROWS" : "";
        String withTies = expression.has(Args.WITH_TIES) ? " WITH TIES" : "";
        if (withTies.isEmpty() && !rows.isEmpty()) {
            withTies = " ONLY";
        }
        return percent + rows + withTies;
    }

    @Override
    public String filterSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String where = sql(expression, Args.EXPRESSION).strip();
        return this_ + " FILTER(" + where + ")";
    }

    @Override
    public String hintSql(Expression expression) {
        if (!g.QUERY_HINTS) {
            unsupported("Hints are not supported");
            return "";
        }

        return " /*+ " + expressions(opts(expression).sep(g.QUERY_HINT_SEP)).strip() + " */";
    }

    @Override
    public String indexparametersSql(Expression expression) {
        String using = sql(expression, Args.USING);
        using = !using.isEmpty() ? " USING " + using : "";
        String columns = expressions(opts(expression).key(Args.COLUMNS).flat());
        columns = !columns.isEmpty() ? "(" + columns + ")" : "";
        String partitionBy = expressions(opts(expression).key(Args.PARTITION_BY).flat());
        partitionBy = !partitionBy.isEmpty() ? " PARTITION BY " + partitionBy : "";
        String where = sql(expression, Args.WHERE);
        String include = expressions(opts(expression).key(Args.INCLUDE).flat());
        if (!include.isEmpty()) {
            include = " INCLUDE (" + include + ")";
        }
        String withStorage = expressions(opts(expression).key(Args.WITH_STORAGE).flat());
        withStorage = !withStorage.isEmpty() ? " WITH (" + withStorage + ")" : "";
        String tablespace = sql(expression, Args.TABLESPACE);
        tablespace = !tablespace.isEmpty() ? " USING INDEX TABLESPACE " + tablespace : "";
        String on = sql(expression, Args.ON);
        on = !on.isEmpty() ? " ON " + on : "";

        return using + columns + include + withStorage + tablespace + partitionBy + where + on;
    }

    @Override
    public String indexSql(Expression expression) {
        String unique = expression.has(Args.UNIQUE) ? "UNIQUE " : "";
        String primary = expression.has(Args.PRIMARY) ? "PRIMARY " : "";
        String amp = expression.has(Args.AMP) ? "AMP " : "";
        String name = sql(expression, Args.THIS);
        name = !name.isEmpty() ? name + " " : "";
        String table = sql(expression, Args.TABLE);
        table = !table.isEmpty() ? g.INDEX_ON + " " + table : "";

        String index = table.isEmpty() ? "INDEX " : "";

        String params = sql(expression, Args.PARAMS);
        return unique + primary + amp + index + name + table + params;
    }

    // ------------------------------------------------------------------------------------------
    // Identifiers and misc leaves

    @Override
    public String dynamicidentifierSql(Expression expression) {
        Expression this_ = expression.getThis();
        if (this_ != null && this_.isString()) {
            String resolved = Exp.maybeParse(this_.name(), Dialects.get("")).sql(dialect);
            if (expression.get(Args.EXPRESSIONS) != null) {
                // `IDENTIFIER(...)` invoked as a function, e.g. `IDENTIFIER('my_func')(1, 2)`
                // We can't safely emit the call to other dialects since name/arg semantics may differ
                unsupported("Transpiling dynamically-invoked IDENTIFIER() functions is unsupported");
            }
            return resolved;
        }
        unsupported("IDENTIFIER() with non-literal arguments is not supported");
        return func("IDENTIFIER", this_);
    }

    @Override
    public String identifierSql(Expression expression) {
        String text = expression.name();
        String lower = Py.lower(text);
        boolean quoted = expression.has(Args.QUOTED);
        text = normalize && !quoted ? lower : text;
        if (identifierEscapedSequences != null && !identifierEscapedSequences.isEmpty()) {
            for (Map.Entry<String, String> en : identifierEscapedSequences.entrySet()) {
                text = text.replace(en.getKey(), en.getValue());
            }
        }
        text = text.replace(identifierEnd, escapedIdentifierEnd);
        if (quoted
                || dialect.canQuote(expression, identify)
                || g.RESERVED_KEYWORDS.contains(lower)
                || (!dd.IDENTIFIERS_CAN_START_WITH_DIGIT && !text.isEmpty() && Character.isDigit(text.codePointAt(0)))) {
            text = identifierStart + replaceLineBreaks(text) + identifierEnd;
        }
        return text;
    }

    @Override
    public String hexSql(Expression expression) {
        String text = func(g.HEX_FUNC, sql(expression, Args.THIS));
        if (dd.HEX_LOWERCASE) {
            text = func("LOWER", text);
        }

        return text;
    }

    @Override
    public String lowerhexSql(Expression expression) {
        String text = func(g.HEX_FUNC, sql(expression, Args.THIS));
        if (!dd.HEX_LOWERCASE) {
            text = func("LOWER", text);
        }
        return text;
    }

    @Override
    public String inputoutputformatSql(Expression expression) {
        String inputFormat = sql(expression, Args.INPUT_FORMAT);
        inputFormat = !inputFormat.isEmpty() ? "INPUTFORMAT " + inputFormat : "";
        String outputFormat = sql(expression, Args.OUTPUT_FORMAT);
        outputFormat = !outputFormat.isEmpty() ? "OUTPUTFORMAT " + outputFormat : "";
        return inputFormat + sep() + outputFormat;
    }

    @Override
    public String nationalSql(Expression expression, String prefix) {
        String string = sql(Exp.string(expression.name()));
        return prefix + string;
    }

    @Override
    public String partitionSql(Expression expression) {
        String partitionKeyword = expression.has(Args.SUBPARTITION) ? "SUBPARTITION" : "PARTITION";
        return partitionKeyword + "(" + expressions(opts(expression).flat()) + ")";
    }

    // ------------------------------------------------------------------------------------------
    // Properties

    @Override
    public String propertiesSql(Expression expression) {
        List<Expression> rootProperties = new ArrayList<>();
        List<Expression> withProperties = new ArrayList<>();

        for (Expression p : expression.getExpressions()) {
            PropertiesLocation pLoc = propertyLocation(p);
            if (pLoc == PropertiesLocation.POST_WITH) {
                withProperties.add(p);
            } else if (pLoc == PropertiesLocation.POST_SCHEMA) {
                rootProperties.add(p);
            }
        }

        Expression rootPropsAst = newProperties(rootProperties);
        setParentDetached(rootPropsAst, expression.parent());

        Expression withPropsAst = newProperties(withProperties);
        setParentDetached(withPropsAst, expression.parent());

        String rootProps = rootProperties(rootPropsAst);
        String withProps = withProperties(withPropsAst);

        if (!rootProps.isEmpty() && !withProps.isEmpty() && !pretty) {
            withProps = " " + withProps;
        }

        return rootProps + withProps;
    }

    @Override
    public String rootProperties(Expression properties) {
        if (!properties.getExpressions().isEmpty()) {
            return expressions(opts(properties).indent(false).sep(" "));
        }
        return "";
    }

    @Override
    public String properties(Expression properties, String prefix, String sep, String suffix, boolean wrapped) {
        if (!properties.getExpressions().isEmpty()) {
            String exprs = expressions(opts(properties).sep(sep).indent(false));
            if (!exprs.isEmpty()) {
                exprs = wrapped ? wrap(exprs) : exprs;
                return prefix + (!prefix.strip().isEmpty() ? " " : "") + exprs + suffix;
            }
        }
        return "";
    }

    @Override
    public String withProperties(Expression properties) {
        return properties(properties, seg(g.WITH_PROPERTIES_PREFIX, ""));
    }

    /** Returns an {@code EnumMap<PropertiesLocation, List<Expression>>} (sqlglot's defaultdict(list)). */
    @Override
    public Object locateProperties(Expression properties) {
        EnumMap<PropertiesLocation, List<Expression>> propertiesLocs = new EnumMap<>(PropertiesLocation.class);
        for (Expression p : properties.getExpressions()) {
            PropertiesLocation pLoc = propertyLocation(p);
            if (pLoc != PropertiesLocation.UNSUPPORTED) {
                propertiesLocs.computeIfAbsent(pLoc, k -> new ArrayList<>()).add(p);
            } else {
                unsupported("Unsupported property " + p.key());
            }
        }

        return propertiesLocs;
    }

    @Override
    public String propertyName(Expression expression, boolean stringKey) {
        Expression this_ = expression.getThis();
        if (this_ != null && this_.is(ExprType.DOT)) {
            return sql(expression, Args.THIS);
        }
        return stringKey ? "'" + expression.name() + "'" : expression.name();
    }

    @Override
    public String propertySql(Expression expression) {
        ExprType propertyCls = expression.type();
        if (propertyCls == ExprType.PROPERTY) {
            return propertyName(expression) + "=" + sql(expression, Args.VALUE);
        }

        String propertyName = PROPERTY_TO_NAME.get(propertyCls);
        if (propertyName == null || propertyName.isEmpty()) {
            unsupported("Unsupported property " + expression.key());
        }

        return pyStr(propertyName) + "=" + sql(expression, Args.THIS);
    }

    @Override
    public String uuidpropertySql(Expression expression) {
        return "UUID " + sql(expression, Args.THIS);
    }

    @Override
    public String likepropertySql(Expression expression) {
        if (g.SUPPORTS_CREATE_TABLE_LIKE) {
            List<String> optionParts = new ArrayList<>();
            for (Expression e : expression.getExpressions()) {
                optionParts.add(e.name() + " " + sql(e, Args.VALUE));
            }
            String options = String.join(" ", optionParts);
            options = !options.isEmpty() ? " " + options : "";

            String like = "LIKE " + sql(expression, Args.THIS) + options;
            Expression parent = expression.parent();
            if (g.LIKE_PROPERTY_INSIDE_SCHEMA && !(parent != null && parent.is(ExprType.SCHEMA))) {
                like = "(" + like + ")";
            }

            return like;
        }

        if (!expression.getExpressions().isEmpty()) {
            unsupported("Transpilation of LIKE property options is unsupported");
        }

        Expression select = Exp.select("*").from(expression.getThis(), true).limit(0, true);
        return "AS " + sql(select);
    }

    @Override
    public String fallbackpropertySql(Expression expression) {
        String no = expression.has(Args.NO) ? "NO " : "";
        String protection = expression.has(Args.PROTECTION) ? " PROTECTION" : "";
        return no + "FALLBACK" + protection;
    }

    @Override
    public String journalpropertySql(Expression expression) {
        String no = expression.has(Args.NO) ? "NO " : "";
        Object localV = expression.get(Args.LOCAL);
        String local = Expression.truthy(localV) ? pyStr(localV) + " " : "";
        String dual = expression.has(Args.DUAL) ? "DUAL " : "";
        String before = expression.has(Args.BEFORE) ? "BEFORE " : "";
        String after = expression.has(Args.AFTER) ? "AFTER " : "";
        return no + local + dual + before + after + "JOURNAL";
    }

    @Override
    public String freespacepropertySql(Expression expression) {
        String freespace = sql(expression, Args.THIS);
        String percent = expression.has(Args.PERCENT) ? " PERCENT" : "";
        return "FREESPACE=" + freespace + percent;
    }

    @Override
    public String checksumpropertySql(Expression expression) {
        String property;
        if (expression.has(Args.DEFAULT)) {
            property = "DEFAULT";
        } else if (expression.has(Args.ON)) {
            property = "ON";
        } else {
            property = "OFF";
        }
        return "CHECKSUM=" + property;
    }

    @Override
    public String mergeblockratiopropertySql(Expression expression) {
        if (expression.has(Args.NO)) {
            return "NO MERGEBLOCKRATIO";
        }
        if (expression.has(Args.DEFAULT)) {
            return "DEFAULT MERGEBLOCKRATIO";
        }

        String percent = expression.has(Args.PERCENT) ? " PERCENT" : "";
        return "MERGEBLOCKRATIO=" + sql(expression, Args.THIS) + percent;
    }

    @Override
    public String modulepropertySql(Expression expression) {
        String exprs = expressions(opts(expression).flat());
        exprs = !exprs.isEmpty() ? "(" + exprs + ")" : "";
        return "USING " + sql(expression, Args.THIS) + exprs;
    }

    @Override
    public String datablocksizepropertySql(Expression expression) {
        boolean default_ = expression.has(Args.DEFAULT);
        boolean minimum = expression.has(Args.MINIMUM);
        boolean maximum = expression.has(Args.MAXIMUM);
        if (default_ || minimum || maximum) {
            String prop;
            if (default_) {
                prop = "DEFAULT";
            } else if (minimum) {
                prop = "MINIMUM";
            } else {
                prop = "MAXIMUM";
            }
            return prop + " DATABLOCKSIZE";
        }
        Object unitsV = expression.get(Args.UNITS);
        String units = Expression.truthy(unitsV) ? " " + pyStr(unitsV) : "";
        return "DATABLOCKSIZE=" + sql(expression, Args.SIZE) + units;
    }

    @Override
    public String blockcompressionpropertySql(Expression expression) {
        Object autotemp = expression.get(Args.AUTOTEMP);
        boolean always = expression.has(Args.ALWAYS);
        boolean default_ = expression.has(Args.DEFAULT);
        boolean manual = expression.has(Args.MANUAL);
        boolean never = expression.has(Args.NEVER);

        String prop;
        if (autotemp != null) {
            prop = "AUTOTEMP(" + expressions((Expression) autotemp) + ")";
        } else if (always) {
            prop = "ALWAYS";
        } else if (default_) {
            prop = "DEFAULT";
        } else if (manual) {
            prop = "MANUAL";
        } else if (never) {
            prop = "NEVER";
        } else {
            // Python raises UnboundLocalError here
            throw new IllegalStateException("local variable 'prop' referenced before assignment");
        }
        return "BLOCKCOMPRESSION=" + prop;
    }

    @Override
    public String isolatedloadingpropertySql(Expression expression) {
        String no = expression.has(Args.NO) ? " NO" : "";
        String concurrent = expression.has(Args.CONCURRENT) ? " CONCURRENT" : "";
        String target = sql(expression, Args.TARGET);
        target = !target.isEmpty() ? " " + target : "";
        return "WITH" + no + concurrent + " ISOLATED LOADING" + target;
    }

    @Override
    public String partitionboundspecSql(Expression expression) {
        Object thisValue = expression.thisValue();
        if (thisValue instanceof List<?>) {
            return "IN (" + expressions(opts(expression).key(Args.THIS).flat()) + ")";
        }
        if (Expression.truthy(thisValue)) {
            String modulus = sql(expression, Args.THIS);
            String remainder = sql(expression, Args.EXPRESSION);
            return "WITH (MODULUS " + modulus + ", REMAINDER " + remainder + ")";
        }

        String fromExpressions = expressions(opts(expression).key(Args.FROM_EXPRESSIONS).flat());
        String toExpressions = expressions(opts(expression).key(Args.TO_EXPRESSIONS).flat());
        return "FROM (" + fromExpressions + ") TO (" + toExpressions + ")";
    }

    @Override
    public String partitionedofpropertySql(Expression expression) {
        String this_ = sql(expression, Args.THIS);

        Expression forValuesOrDefault = expression.getExpression();
        String forValuesOrDefaultSql;
        if (forValuesOrDefault != null && forValuesOrDefault.is(ExprType.PARTITION_BOUND_SPEC)) {
            forValuesOrDefaultSql = " FOR VALUES " + sql(forValuesOrDefault);
        } else {
            forValuesOrDefaultSql = " DEFAULT";
        }

        return "PARTITION OF " + this_ + forValuesOrDefaultSql;
    }

    @Override
    public String lockingpropertySql(Expression expression) {
        Object kind = expression.get(Args.KIND);
        String this_ = Expression.truthy(expression.thisValue()) ? " " + sql(expression, Args.THIS) : "";
        Object forOrInV = expression.get(Args.FOR_OR_IN);
        String forOrIn = Expression.truthy(forOrInV) ? " " + pyStr(forOrInV) : "";
        Object lockType = expression.get(Args.LOCK_TYPE);
        String override = expression.has(Args.OVERRIDE) ? " OVERRIDE" : "";
        return "LOCKING " + pyStr(kind) + this_ + forOrIn + " " + pyStr(lockType) + override;
    }

    @Override
    public String withdatapropertySql(Expression expression) {
        String dataSql = "WITH " + (expression.has(Args.NO) ? "NO " : "") + "DATA";
        Object statistics = expression.get(Args.STATISTICS);
        String statisticsSql = "";
        if (statistics != null) {
            statisticsSql = " AND " + (!Expression.truthy(statistics) ? "NO " : "") + "STATISTICS";
        }
        return dataSql + statisticsSql;
    }

    @Override
    public String withsystemversioningpropertySql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        this_ = !this_.isEmpty() ? "HISTORY_TABLE=" + this_ : "";
        String dataConsistency = sql(expression, Args.DATA_CONSISTENCY);
        dataConsistency = !dataConsistency.isEmpty() ? "DATA_CONSISTENCY_CHECK=" + dataConsistency : null;
        String retentionPeriod = sql(expression, Args.RETENTION_PERIOD);
        retentionPeriod = !retentionPeriod.isEmpty() ? "HISTORY_RETENTION_PERIOD=" + retentionPeriod : null;

        String onSql;
        if (!this_.isEmpty()) {
            onSql = func("ON", this_, dataConsistency, retentionPeriod);
        } else {
            onSql = expression.has(Args.ON) ? "ON" : "OFF";
        }

        String s = "SYSTEM_VERSIONING=" + onSql;

        return expression.has(Args.WITH_) ? "WITH(" + s + ")" : s;
    }

    // ------------------------------------------------------------------------------------------
    // INSERT and friends

    @Override
    public String insertSql(Expression expression) {
        String hint = sql(expression, Args.HINT);
        boolean overwrite = expression.has(Args.OVERWRITE);

        Expression thisExpr = expression.getThis();
        String this_;
        if (thisExpr != null && thisExpr.is(ExprType.DIRECTORY)) {
            this_ = overwrite ? " OVERWRITE" : " INTO";
        } else {
            this_ = overwrite ? g.INSERT_OVERWRITE : " INTO";
        }

        String stored = sql(expression, Args.STORED);
        stored = !stored.isEmpty() ? " " + stored : "";
        Object alternativeV = expression.get(Args.ALTERNATIVE);
        String alternative = Expression.truthy(alternativeV) ? " OR " + pyStr(alternativeV) : "";
        String ignore = expression.has(Args.IGNORE) ? " IGNORE" : "";
        if (expression.has(Args.IS_FUNCTION)) {
            this_ = this_ + " FUNCTION";
        }
        this_ = this_ + " " + sql(expression, Args.THIS);

        String exists = expression.has(Args.EXISTS) ? " IF EXISTS" : "";
        String where = sql(expression, Args.WHERE);
        where = !where.isEmpty() ? sep() + "REPLACE WHERE " + where : "";
        String using = expressions(opts(expression).key(Args.USING).flat());
        using = !using.isEmpty() ? sep() + "REPLACE USING (" + using + ")" : "";
        String expressionSql = sep() + sql(expression, Args.EXPRESSION);
        String onConflict = sql(expression, Args.CONFLICT);
        onConflict = !onConflict.isEmpty() ? " " + onConflict : "";
        String byName = expression.has(Args.BY_NAME) ? " BY NAME" : "";
        String defaultValues = expression.has(Args.DEFAULT) ? "DEFAULT VALUES" : "";
        String returning = sql(expression, Args.RETURNING);

        if (g.RETURNING_END) {
            expressionSql = expressionSql + onConflict + defaultValues + returning;
        } else {
            expressionSql = returning + expressionSql + onConflict;
        }

        String partitionBy = sql(expression, Args.PARTITION);
        partitionBy = !partitionBy.isEmpty() ? " " + partitionBy : "";
        String settings = sql(expression, Args.SETTINGS);
        settings = !settings.isEmpty() ? " " + settings : "";

        String source = sql(expression, Args.SOURCE);
        source = !source.isEmpty() ? "TABLE " + source : "";

        String s = "INSERT" + hint + alternative + ignore + this_ + stored + byName + exists + partitionBy + settings + where
                + using + expressionSql + source;
        return prependCtes(expression, s);
    }

    @Override
    public String introducerSql(Expression expression) {
        return sql(expression, Args.THIS) + " " + sql(expression, Args.EXPRESSION);
    }

    @Override
    public String killSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        kind = !kind.isEmpty() ? " " + kind : "";
        String this_ = sql(expression, Args.THIS);
        this_ = !this_.isEmpty() ? " " + this_ : "";
        return "KILL" + kind + this_;
    }

    @Override
    public String pseudotypeSql(Expression expression) {
        return expression.name();
    }

    @Override
    public String objectidentifierSql(Expression expression) {
        return expression.name();
    }

    @Override
    public String onconflictSql(Expression expression) {
        String conflict = expression.has(Args.DUPLICATE) ? "ON DUPLICATE KEY" : "ON CONFLICT";

        String constraint = sql(expression, Args.CONSTRAINT);
        constraint = !constraint.isEmpty() ? " ON CONSTRAINT " + constraint : "";

        String conflictKeys = expressions(opts(expression).key(Args.CONFLICT_KEYS).flat());
        if (!conflictKeys.isEmpty()) {
            conflictKeys = "(" + conflictKeys + ")";
        }

        String indexPredicate = sql(expression, Args.INDEX_PREDICATE);
        conflictKeys = conflictKeys + indexPredicate + " ";

        String action = sql(expression, Args.ACTION);

        String exprs = expressions(opts(expression).flat());
        if (!exprs.isEmpty()) {
            String setKeyword = g.DUPLICATE_KEY_UPDATE_WITH_SET ? "SET " : "";
            exprs = " " + setKeyword + exprs;
        }

        String where = sql(expression, Args.WHERE);
        return conflict + constraint + conflictKeys + action + exprs + where;
    }

    @Override
    public String returningSql(Expression expression) {
        return seg("RETURNING") + " " + expressions(opts(expression).flat());
    }

    @Override
    public String rowformatdelimitedpropertySql(Expression expression) {
        String fields = sql(expression, Args.FIELDS);
        fields = !fields.isEmpty() ? " FIELDS TERMINATED BY " + fields : "";
        String escaped = sql(expression, Args.ESCAPED);
        escaped = !escaped.isEmpty() ? " ESCAPED BY " + escaped : "";
        String items = sql(expression, Args.COLLECTION_ITEMS);
        items = !items.isEmpty() ? " COLLECTION ITEMS TERMINATED BY " + items : "";
        String keys = sql(expression, Args.MAP_KEYS);
        keys = !keys.isEmpty() ? " MAP KEYS TERMINATED BY " + keys : "";
        String lines = sql(expression, Args.LINES);
        lines = !lines.isEmpty() ? " LINES TERMINATED BY " + lines : "";
        String nul = sql(expression, Args.NULL);
        nul = !nul.isEmpty() ? " NULL DEFINED AS " + nul : "";
        return "ROW FORMAT DELIMITED" + fields + escaped + items + keys + lines + nul;
    }

    @Override
    public String withtablehintSql(Expression expression) {
        return "WITH (" + expressions(opts(expression).flat()) + ")";
    }

    @Override
    public String indextablehintSql(Expression expression) {
        String this_ = sql(expression, Args.THIS) + " INDEX";
        String target = sql(expression, Args.TARGET);
        target = !target.isEmpty() ? " FOR " + target : "";
        return this_ + target + " (" + expressions(opts(expression).flat()) + ")";
    }

    @Override
    public String historicaldataSql(Expression expression) {
        String this_ = sql(expression, Args.THIS);
        String kind = sql(expression, Args.KIND);
        String expr = sql(expression, Args.EXPRESSION);
        return this_ + " (" + kind + " => " + expr + ")";
    }

    // ------------------------------------------------------------------------------------------
    // Tables

    @Override
    public String tableParts(Expression expression) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Arg a : new Arg[] {Args.CATALOG, Args.DB, Args.THIS}) {
            Object part = expression.get(a);
            if (part != null) {
                if (!first) {
                    sb.append('.');
                }
                sb.append(sql(part));
                first = false;
            }
        }
        return sb.toString();
    }

    @Override
    public String tableSql(Expression expression, String sep) {
        String table = tableParts(expression);
        String only = expression.has(Args.ONLY) ? "ONLY " : "";
        String partition = sql(expression, Args.PARTITION);
        partition = !partition.isEmpty() ? " " + partition : "";
        String version = sql(expression, Args.VERSION);
        version = !version.isEmpty() ? " " + version : "";
        String alias = sql(expression, Args.ALIAS);
        alias = !alias.isEmpty() ? sep + alias : "";

        String sample = sql(expression, Args.SAMPLE);
        String postAlias = "";
        String preAlias = "";

        if (dd.ALIAS_POST_TABLESAMPLE) {
            preAlias = sample;
        } else {
            postAlias = sample;
        }

        if (dd.ALIAS_POST_VERSION) {
            preAlias = preAlias + version;
        } else {
            postAlias = postAlias + version;
        }

        String hints = expressions(opts(expression).key(Args.HINTS).sep(" "));
        hints = !hints.isEmpty() && g.TABLE_HINTS ? " " + hints : "";
        String pivots = expressions(opts(expression).key(Args.PIVOTS).sep("").flat());
        String joins = indent(expressions(opts(expression).key(Args.JOINS).sep("").flat()), 0, null, true, false);
        String laterals = expressions(opts(expression).key(Args.LATERALS).sep(""));

        String fileFormat = sql(expression, Args.FORMAT);
        String pattern = sql(expression, Args.PATTERN);
        if (!fileFormat.isEmpty()) {
            pattern = !pattern.isEmpty() ? ", PATTERN => " + pattern : "";
            fileFormat = " (FILE_FORMAT => " + fileFormat + pattern + ")";
        } else if (!pattern.isEmpty()) {
            fileFormat = " (PATTERN => " + pattern + ")";
        }

        String ordinality = "";
        if (expression.has(Args.ORDINALITY)) {
            ordinality = " WITH ORDINALITY" + alias;
            alias = "";
        }

        String when = sql(expression, Args.WHEN);
        if (!when.isEmpty()) {
            if (g.HISTORICAL_DATA_POST_ALIAS) {
                alias = alias + " " + when;
            } else {
                table = table + " " + when;
            }
        }

        String changes = sql(expression, Args.CHANGES);
        changes = !changes.isEmpty() ? " " + changes : "";

        String rowsFrom = expressions(expression, Args.ROWS_FROM);
        if (!rowsFrom.isEmpty()) {
            table = "ROWS FROM " + wrap(rowsFrom);
        }

        Object indexedV = expression.get(Args.INDEXED);
        String indexed;
        if (indexedV != null) {
            indexed = Expression.truthy(indexedV) ? " INDEXED BY " + sql(indexedV) : " NOT INDEXED";
        } else {
            indexed = "";
        }

        return only + table + changes + partition + fileFormat + preAlias + alias + indexed + hints + pivots + postAlias
                + joins + laterals + ordinality;
    }

    @Override
    public String tablefromrowsSql(Expression expression) {
        String table = func("TABLE", expression.thisValue());
        String alias = sql(expression, Args.ALIAS);
        alias = !alias.isEmpty() ? " AS " + alias : "";
        String sample = sql(expression, Args.SAMPLE);
        String pivots = expressions(opts(expression).key(Args.PIVOTS).sep("").flat());
        String joins = indent(expressions(opts(expression).key(Args.JOINS).sep("").flat()), 0, null, true, false);
        return table + alias + pivots + sample + joins;
    }

    @Override
    public String tablesampleSql(Expression expression, String tablesampleKeyword) {
        String method = sql(expression, Args.METHOD);
        method = !method.isEmpty() && g.TABLESAMPLE_WITH_METHOD ? method + " " : "";
        String numerator = sql(expression, Args.BUCKET_NUMERATOR);
        String denominator = sql(expression, Args.BUCKET_DENOMINATOR);
        String field = sql(expression, Args.BUCKET_FIELD);
        field = !field.isEmpty() ? " ON " + field : "";
        String bucket = !numerator.isEmpty() ? "BUCKET " + numerator + " OUT OF " + denominator + field : "";
        String seed = sql(expression, Args.SEED);
        seed = !seed.isEmpty() ? " " + g.TABLESAMPLE_SEED_KEYWORD + " (" + seed + ")" : "";

        String size = sql(expression, Args.SIZE);
        if (!size.isEmpty() && g.TABLESAMPLE_SIZE_IS_ROWS) {
            size = size + " ROWS";
        }

        String percent = sql(expression, Args.PERCENT);
        if (!percent.isEmpty() && !dd.TABLESAMPLE_SIZE_IS_PERCENT) {
            percent = percent + " PERCENT";
        }

        String expr = bucket + percent + size;
        if (g.TABLESAMPLE_REQUIRES_PARENS) {
            expr = "(" + expr + ")";
        }

        String keyword = tablesampleKeyword != null && !tablesampleKeyword.isEmpty() ? tablesampleKeyword : g.TABLESAMPLE_KEYWORDS;
        return " " + keyword + " " + method + expr + seed;
    }

    @Override
    public List<Expression> pivotInValueAliases(Expression expression) {
        // Returns the rewritten field.expressions list with PivotAlias wrappers injected where
        // the stored column name differs from the target dialect's natural output.
        List<Expression> columns = expression.exprs(Args.COLUMNS);
        List<Expression> fields = expression.exprs(Args.FIELDS);
        if (columns.isEmpty() || fields.size() != 1) {
            return null;
        }

        ParserData parserCls = dialect.parserData();

        boolean tgtIdentifyPivotStrings = parserCls.IDENTIFY_PIVOT_STRINGS;
        boolean tgtPrefixedPivotColumns = parserCls.PREFIXED_PIVOT_COLUMNS;
        String tgtPivotColumnNaming = parserCls.PIVOT_COLUMN_NAMING;

        Object srcIdentifyPivotStrings = expression.get(Args.IDENTIFY_PIVOT_STRINGS);
        if (srcIdentifyPivotStrings == null) {
            srcIdentifyPivotStrings = tgtIdentifyPivotStrings;
        }
        Object srcPrefixedPivotColumns = expression.get(Args.PREFIXED_PIVOT_COLUMNS);
        if (srcPrefixedPivotColumns == null) {
            srcPrefixedPivotColumns = tgtPrefixedPivotColumns;
        }
        Object srcPivotColumnNaming = expression.get(Args.PIVOT_COLUMN_NAMING);
        if (srcPivotColumnNaming == null) {
            srcPivotColumnNaming = tgtPivotColumnNaming;
        }

        if (Objects.equals(srcIdentifyPivotStrings, tgtIdentifyPivotStrings)
                && Objects.equals(srcPrefixedPivotColumns, tgtPrefixedPivotColumns)
                && Objects.equals(srcPivotColumnNaming, tgtPivotColumnNaming)) {
            return null;
        }

        Dialect base = Dialects.get("");
        List<Expression> inExprs = fields.get(0).getExpressions();
        int step = Math.floorDiv(columns.size(), inExprs.size());

        // Derive the per-value suffix from the first stored column vs the first IN-list value.
        // This correctly handles dialects (e.g. Spark single-agg) that ignore agg aliases.
        boolean srcIdentify = Expression.truthy(srcIdentifyPivotStrings);
        String firstBase = srcIdentify ? inExprs.get(0).sql(base) : inExprs.get(0).aliasOrName();
        String firstStored = columns.get(0).name();

        // exit if only suffix matches, not prefix. (e.g. BigQuery, which cannot be fixed)
        if (!firstStored.startsWith(firstBase)) {
            return null;
        }

        String suffix = firstStored.substring(firstBase.length());

        // Whether the target dialect would append an agg-name suffix for this pivot.
        // Spark single-agg uniquely drops the agg alias entirely.
        List<Expression> aggs = expression.getExpressions();
        boolean anyAlias = false;
        for (Expression a : aggs) {
            if (!a.alias().isEmpty()) {
                anyAlias = true;
                break;
            }
        }
        boolean targetHasSuffix = (aggs.size() > 1 || !"agg_name_if_multiple".equals(tgtPivotColumnNaming)) && anyAlias;
        boolean sourceHasSuffix = !suffix.isEmpty();

        List<Expression> newExprs = new ArrayList<>();
        boolean modified = false;
        for (int valIdx = 0; valIdx < inExprs.size(); valIdx++) {
            Expression e = inExprs.get(valIdx);
            if (e.is(ExprType.PIVOT_ALIAS)) {
                newExprs.add(e);
                continue;
            }

            int i = valIdx * step;
            String storedFull = columns.get(i).name();
            String storedValue = !suffix.isEmpty() ? Py.slice(storedFull, 0, -suffix.length()) : storedFull;
            String targetValue = tgtIdentifyPivotStrings ? e.sql(base) : e.aliasOrName();

            if (sourceHasSuffix && !targetHasSuffix) {
                // Source had a suffix, but target won't apply one
                newExprs.add(Expression.of(ExprType.PIVOT_ALIAS, Args.THIS, e, Args.ALIAS, Exp.toIdentifier(storedFull, true, true)));
                modified = true;
            } else if (!storedValue.equals(targetValue)) {
                // Value-part mismatch (e.g. Snowflake's literal-style values vs others).
                newExprs.add(Expression.of(ExprType.PIVOT_ALIAS, Args.THIS, e, Args.ALIAS, Exp.toIdentifier(storedValue, true, true)));
                modified = true;
            } else {
                newExprs.add(e);
            }
        }

        return modified ? newExprs : null;
    }
}
