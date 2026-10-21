package io.github.offbyjuan.jglot.generator;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.dialects.data.ParserData;
import io.github.offbyjuan.jglot.dialects.data.PropertiesLocation;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.optimizer.Scopes;
import io.github.offbyjuan.jglot.optimizer.Simplify;
import io.github.offbyjuan.jglot.optimizer.TypeAnnotator;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.transforms.Transforms;
import io.github.offbyjuan.jglot.util.Py;
import io.github.offbyjuan.jglot.util.TimeFormat;
import io.github.offbyjuan.jglot.util.Trie;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SQL generator: a port of sqlglot's {@code Generator}. Dialects subclass it to override
 * individual {@code <key>Sql} methods and the {@link #transform} hook.
 *
 * <p>Create generators through {@link Dialect#newGenerator}; an instance renders one tree and is
 * not thread-safe, but creating one is cheap since all tables are shared, immutable dialect data.
 */
public class Generator extends GeneratorMethods {
    public Generator(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }

    // ---------------------------------------------------------------------------------------
    // From GeneratorPartA

    // ------------------------------------------------------------------------------------------
    // Module-level / class-level non-data attributes

    /** generator.py {@code _HIGH_BYTE_RE = re.compile(r"[\x80-\xff]")}, used by {@code escape_str}. */
    protected static final Pattern HIGH_BYTE_RE = Pattern.compile("[\\x{80}-\\x{ff}]");

    /** generator.py {@code ESCAPED_UNICODE_RE = re.compile(r"\\(\d+)")}, used by {@code unicodestring_sql}. */
    protected static final Pattern ESCAPED_UNICODE_RE = Pattern.compile("\\\\(\\d+)", Pattern.UNICODE_CHARACTER_CLASS);

    /** Compiled {@code g.SAFE_JSON_PATH_KEY_RE} (per instance, lazily; generators are per call). */
    private Pattern safeJsonPathKeyRe;

    /**
     * {@code self.SAFE_JSON_PATH_KEY_RE} as a compiled pattern (used by {@code _jsonpathkey_sql}).
     * Python's {@code \w} is Unicode-aware, hence {@code UNICODE_CHARACTER_CLASS}. Use
     * {@code safeJsonPathKeyRe().matcher(s).find()} for Python's {@code .match(s)} (the patterns
     * are all {@code ^}-anchored).
     */
    protected Pattern safeJsonPathKeyRe() {
        Pattern p = safeJsonPathKeyRe;
        if (p == null) {
            p = Pattern.compile(g.SAFE_JSON_PATH_KEY_RE, Pattern.UNICODE_CHARACTER_CLASS);
            safeJsonPathKeyRe = p;
        }
        return p;
    }


    /** One entry of {@code AFTER_HAVING_MODIFIER_TRANSFORMS} (module-level and class-level). */
    public String afterHavingModifierTransform(String key, Expression e) {
        return switch (key) {
            case "cluster" -> sql(e, Args.CLUSTER);
            case "distribute" -> sql(e, Args.DISTRIBUTE);
            case "sort" -> sql(e, Args.SORT);
            case "windows" -> e.has(Args.WINDOWS)
                    ? seg("WINDOW ") + expressions(opts(e).key(Args.WINDOWS).flat())
                    : "";
            case "qualify" -> sql(e, Args.QUALIFY);
            default -> throw new IllegalArgumentException("Unknown AFTER_HAVING_MODIFIER_TRANSFORMS key " + key);
        };
    }

    /**
     * {@code [gen(self, expression) for gen in self.AFTER_HAVING_MODIFIER_TRANSFORMS.values()]}
     * (query_modifiers), in sqlglot's dict order.
     */
    public List<String> afterHavingModifierSqls(Expression e) {
        List<String> out = new ArrayList<>(g.AFTER_HAVING_MODIFIER_TRANSFORMS.size());
        for (String key : g.AFTER_HAVING_MODIFIER_TRANSFORMS) {
            out.add(afterHavingModifierTransform(key, e));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Preprocessing

    @Override
    public Expression preprocess(Expression expression) {
        expression = moveCtesToTopLevel(expression);

        if (g.ENSURE_BOOLS) {
            expression = Transforms.ensureBools(expression);
        }

        return expression;
    }

    @Override
    public Expression moveCtesToTopLevel(Expression expression) {
        if (expression.parent() == null
                && g.EXPRESSIONS_WITHOUT_NESTED_CTES.contains(expression.type())
                && anyWithNotDirectChild(expression)) {
            expression = Transforms.moveCtesToTopLevel(expression);
        }
        return expression;
    }

    private static boolean anyWithNotDirectChild(Expression expression) {
        for (Expression node : expression.findAll(ExprType.WITH)) {
            if (node.parent() != expression) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // TRANSFORMS

    @Override
    public String transform(Expression e) {
        return switch (e.type()) {
            // JSON_PATH_PART_TRANSFORMS (sqlglot/jsonpath.py)
            case JSON_PATH_FILTER -> "?" + Py.str(e.thisValue());
            case JSON_PATH_KEY -> jsonpathkeySql(e);
            case JSON_PATH_RECURSIVE -> ".." + (Expression.truthy(e.thisValue()) ? Py.str(e.thisValue()) : "");
            case JSON_PATH_ROOT -> "$";
            case JSON_PATH_SCRIPT -> "(" + Py.str(e.thisValue());
            case JSON_PATH_SELECTOR -> "[" + jsonPathPart(e.thisValue()) + "]";
            case JSON_PATH_SLICE -> jsonPathSlice(e);
            case JSON_PATH_SUBSCRIPT -> jsonpathsubscriptSql(e);
            case JSON_PATH_UNION -> jsonPathUnion(e);
            case JSON_PATH_WILDCARD -> "*";

            // Generator.TRANSFORMS
            case ADJACENT -> binary(e, "-|-");
            case ALLOWED_VALUES_PROPERTY -> "ALLOWED_VALUES " + expressions(opts(e).flat());
            case ANALYZE_COLUMNS -> sql(e, Args.THIS);
            case ANALYZE_WITH -> expressions(opts(e).prefix("WITH ").sep(" "));
            case ARRAY_CONTAINED_BY -> binary(e, "<@");
            case ARRAY_CONTAINS_ALL -> binary(e, "@>");
            case ARRAY_OVERLAPS -> binary(e, "&&");
            case ASSUME_COLUMN_CONSTRAINT -> "ASSUME (" + sql(e, Args.THIS) + ")";
            case AUTO_REFRESH_PROPERTY -> "AUTO REFRESH " + sql(e, Args.THIS);
            case BACKUP_PROPERTY -> "BACKUP " + sql(e, Args.THIS);
            case BINARY_COLUMN_CONSTRAINT -> "BINARY";
            case CASE_SPECIFIC_COLUMN_CONSTRAINT -> (e.has(Args.NOT_) ? "NOT " : "") + "CASESPECIFIC";
            case CALLED_ON_NULL_INPUT_PROPERTY -> "CALLED ON NULL INPUT";
            case CEIL -> ceilFloor(e);
            case CHARACTER_SET_COLUMN_CONSTRAINT -> "CHARACTER SET " + sql(e, Args.THIS);
            case CHARACTER_SET_PROPERTY -> (e.has(Args.DEFAULT) ? "DEFAULT " : "") + "CHARACTER SET=" + sql(e, Args.THIS);
            case CLUSTERED_COLUMN_CONSTRAINT -> "CLUSTERED (" + expressions(opts(e).key(Args.THIS).indent(false)) + ")";
            case COLLATE_COLUMN_CONSTRAINT -> "COLLATE " + sql(e, Args.THIS);
            case COMMENT_COLUMN_CONSTRAINT -> "COMMENT " + sql(e, Args.THIS);
            case CONNECT_BY_ROOT -> "CONNECT_BY_ROOT " + sql(e, Args.THIS);
            case CONVERT_TO_CHARSET -> func("CONVERT", e.thisValue(), e.get(Args.DEST), e.get(Args.SOURCE));
            case COPY_GRANTS_PROPERTY -> "COPY GRANTS";
            case CREDENTIALS_PROPERTY -> "CREDENTIALS=(" + expressions(opts(e).key(Args.EXPRESSIONS).sep(" ")) + ")";
            case CURRENT_CATALOG -> "CURRENT_CATALOG";
            case SESSION_USER -> "SESSION_USER";
            case DATE_FORMAT_COLUMN_CONSTRAINT -> "FORMAT " + sql(e, Args.THIS);
            case DEFAULT_COLUMN_CONSTRAINT -> "DEFAULT " + sql(e, Args.THIS);
            case API_PROPERTY -> "API";
            case APPLICATION_PROPERTY -> "APPLICATION";
            case CATALOG_PROPERTY -> "CATALOG";
            case COMPUTE_PROPERTY -> "COMPUTE";
            case DATABASE_PROPERTY -> "DATABASE";
            case DYNAMIC_PROPERTY -> "DYNAMIC";
            case EMPTY_PROPERTY -> "EMPTY";
            case ENCODE_COLUMN_CONSTRAINT -> "ENCODE " + sql(e, Args.THIS);
            case END_STATEMENT -> "END";
            case ENVIROMENT_PROPERTY -> "ENVIRONMENT (" + expressions(opts(e).flat()) + ")";
            case HANDLER_PROPERTY -> "HANDLER " + sql(e, Args.THIS);
            case PARAMETER_STYLE_PROPERTY -> "PARAMETER STYLE " + sql(e, Args.THIS);
            case EPHEMERAL_COLUMN_CONSTRAINT -> "EPHEMERAL" + (Expression.truthy(e.thisValue()) ? " " + sql(e, Args.THIS) : "");
            case EXCLUDE_COLUMN_CONSTRAINT -> "EXCLUDE " + lstrip(sql(e, Args.THIS));
            case EXECUTE_AS_PROPERTY -> nakedProperty(e);
            case EXCEPT -> setOperations(e);
            case EXTERNAL_PROPERTY -> "EXTERNAL";
            case FLOOR -> ceilFloor(e);
            case GET -> getPutSql(e);
            case GLOBAL_PROPERTY -> "GLOBAL";
            case HEAP_PROPERTY -> "HEAP";
            case HYBRID_PROPERTY -> "HYBRID";
            case ICEBERG_PROPERTY -> "ICEBERG";
            case INHERITS_PROPERTY -> "INHERITS (" + expressions(opts(e).flat()) + ")";
            case INLINE_LENGTH_COLUMN_CONSTRAINT -> "INLINE LENGTH " + sql(e, Args.THIS);
            case INPUT_MODEL_PROPERTY -> "INPUT" + sql(e, Args.THIS);
            case INTERSECT -> setOperations(e);
            case INTERVAL_SPAN -> sql(e, Args.THIS) + " TO " + sql(e, Args.EXPRESSION);
            case INT64 -> sql(Exp.cast(e.getThis(), DType.BIGINT));
            case JSONB_CONTAINS_ANY_TOP_KEYS -> binary(e, "?|");
            case JSONB_CONTAINS_ALL_TOP_KEYS -> binary(e, "?&");
            case JSONB_CONTAINS_TOP_KEY -> binary(e, "?");
            case JSONB_DELETE_AT_PATH -> binary(e, "#-");
            case JSONB_PATH_EXISTS -> binary(e, "@?");
            case JSON_OBJECT -> jsonobjectSql(e);
            case JSON_OBJECT_AGG -> jsonobjectSql(e);
            case LANGUAGE_PROPERTY -> nakedProperty(e);
            case LOCATION_PROPERTY -> nakedProperty(e);
            case LOG_PROPERTY -> (e.has(Args.NO) ? "NO " : "") + "LOG";
            case MASKING_PROPERTY -> "MASKING";
            case MATERIALIZED_PROPERTY -> "MATERIALIZED";
            case NET_FUNC -> "NET." + sql(e, Args.THIS);
            case NETWORK_PROPERTY -> "NETWORK";
            case NON_CLUSTERED_COLUMN_CONSTRAINT -> "NONCLUSTERED (" + expressions(opts(e).key(Args.THIS).indent(false)) + ")";
            case NO_PRIMARY_INDEX_PROPERTY -> "NO PRIMARY INDEX";
            case NOT_FOR_REPLICATION_COLUMN_CONSTRAINT -> "NOT FOR REPLICATION";
            case ON_COMMIT_PROPERTY -> "ON COMMIT " + (e.has(Args.DELETE) ? "DELETE" : "PRESERVE") + " ROWS";
            case ON_PROPERTY -> "ON " + sql(e, Args.THIS);
            case ON_UPDATE_COLUMN_CONSTRAINT -> "ON UPDATE " + sql(e, Args.THIS);
            case OPERATOR -> binary(e, ""); // The operator is produced in `binary`
            case OUTPUT_MODEL_PROPERTY -> "OUTPUT" + sql(e, Args.THIS);
            case EXTENDS_LEFT -> binary(e, "&<");
            case EXTENDS_RIGHT -> binary(e, "&>");
            case PATH_COLUMN_CONSTRAINT -> "PATH " + sql(e, Args.THIS);
            case PARTITIONED_BY_BUCKET -> func("BUCKET", e.thisValue(), e.get(Args.EXPRESSION));
            case PARTITION_BY_TRUNCATE -> func("TRUNCATE", e.thisValue(), e.get(Args.EXPRESSION));
            case PIVOT_ANY -> "ANY" + sql(e, Args.THIS);
            case POSITIONAL_COLUMN -> "#" + sql(e, Args.THIS);
            case PROJECTION_POLICY_COLUMN_CONSTRAINT -> "PROJECTION POLICY " + sql(e, Args.THIS);
            case INVISIBLE_COLUMN_CONSTRAINT -> "INVISIBLE";
            case ZERO_FILL_COLUMN_CONSTRAINT -> "ZEROFILL";
            case PUT -> getPutSql(e);
            case REMOTE_WITH_CONNECTION_MODEL_PROPERTY -> "REMOTE WITH CONNECTION " + sql(e, Args.THIS);
            case RETURNS_PROPERTY -> e.has(Args.NULL) ? "RETURNS NULL ON NULL INPUT" : nakedProperty(e);
            case ROW_ACCESS_PROPERTY -> "ROW ACCESS";
            case SAFE_FUNC -> "SAFE." + sql(e, Args.THIS);
            case SAMPLE_PROPERTY -> "SAMPLE BY " + sql(e, Args.THIS);
            case SECURE_PROPERTY -> "SECURE";
            case SECURITY_INTEGRATION_PROPERTY -> "SECURITY";
            case SET_CONFIG_PROPERTY -> sql(e, Args.THIS);
            case SET_PROPERTY -> (e.has(Args.MULTI) ? "MULTI" : "") + "SET";
            case SETTINGS_PROPERTY -> "SETTINGS" + seg("") + expressions(e);
            case SHARING_PROPERTY -> "SHARING=" + sql(e, Args.THIS);
            case SQL_READ_WRITE_PROPERTY -> e.name();
            case SQL_SECURITY_PROPERTY -> "SQL SECURITY " + sql(e, Args.THIS);
            case STABILITY_PROPERTY -> e.name();
            case STREAM -> "STREAM " + sql(e, Args.THIS);
            case STREAMING_TABLE_PROPERTY -> "STREAMING";
            case STRICT_PROPERTY -> "STRICT";
            case SWAP_TABLE -> "SWAP WITH " + sql(e, Args.THIS);
            case TABLE_COLUMN -> sql(e.thisValue());
            case TAGS -> "TAG (" + expressions(opts(e).flat()) + ")";
            case TEMPORARY_PROPERTY -> "TEMPORARY";
            case TITLE_COLUMN_CONSTRAINT -> "TITLE " + sql(e, Args.THIS);
            case TO_MAP -> "MAP " + sql(e, Args.THIS);
            case TO_TABLE_PROPERTY -> "TO " + sql(e.thisValue());
            case TRANSFORM_MODEL_PROPERTY -> func("TRANSFORM", e.getExpressions().toArray());
            case TRANSIENT_PROPERTY -> "TRANSIENT";
            case VIRTUAL_PROPERTY -> "VIRTUAL";
            case TRIGGER_EXECUTE -> "EXECUTE FUNCTION " + sql(e, Args.THIS);
            case UNION -> setOperations(e);
            case UNLOGGED_PROPERTY -> "UNLOGGED";
            case USING_TEMPLATE_PROPERTY -> "USING TEMPLATE " + sql(e, Args.THIS);
            case USING_DATA -> "USING DATA " + sql(e, Args.THIS);
            case UPPERCASE_COLUMN_CONSTRAINT -> "UPPERCASE";
            case UTC_DATE -> sql(Expression.of(ExprType.CURRENT_DATE, Args.THIS, Exp.string("UTC")));
            case UTC_TIME -> sql(Expression.of(ExprType.CURRENT_TIME, Args.THIS, Exp.string("UTC")));
            case UTC_TIMESTAMP -> sql(Expression.of(ExprType.CURRENT_TIMESTAMP, Args.THIS, Exp.string("UTC")));
            case VARIADIC -> "VARIADIC " + sql(e, Args.THIS);
            case VAR_MAP -> func("MAP", e.get(Args.KEYS), e.get(Args.VALUES));
            case VIEW_ATTRIBUTE_PROPERTY -> "WITH " + sql(e, Args.THIS);
            case VOLATILE_PROPERTY -> "VOLATILE";
            case WITH_JOURNAL_TABLE_PROPERTY -> "WITH JOURNAL TABLE=" + sql(e, Args.THIS);
            case WITH_PROCEDURE_OPTIONS -> "WITH " + expressions(opts(e).flat());
            case WITH_SCHEMA_BINDING_PROPERTY -> "WITH SCHEMA " + sql(e, Args.THIS);
            case WITH_OPERATOR -> sql(e, Args.THIS) + " WITH " + sql(e, Args.OP);
            case FORCE_PROPERTY -> "FORCE";
            default -> super.transform(e);
        };
    }

    // ------------------------------------------------------------------------------------------
    // JSON_PATH_PART_TRANSFORMS helpers

    /** JSONPathSlice: {@code ":".join("" if p is False else json_path_part(p) for p in [start, end, step] if p is not None)}. */
    private String jsonPathSlice(Expression e) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object p : new Object[] {e.get(Args.START), e.get(Args.END), e.get(Args.STEP)}) {
            if (p == null) {
                continue;
            }
            if (!first) {
                sb.append(':');
            }
            first = false;
            sb.append(Boolean.FALSE.equals(p) ? "" : jsonPathPart(p));
        }
        return sb.toString();
    }

    /** JSONPathUnion: {@code [part,part,...]}; parts may be raw ints/strings, so the list is read raw. */
    private String jsonPathUnion(Expression e) {
        StringBuilder sb = new StringBuilder("[");
        Object v = e.get(Args.EXPRESSIONS);
        if (v instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(jsonPathPart(l.get(i)));
            }
        }
        return sb.append(']').toString();
    }



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

    /** Python's {@code node.parent = parent} (sets the parent pointer without attaching). */
    private static void setParentDetached(Expression node, Expression parent) {
        node.setParent(parent, node.argKey(), node.index());
    }

    /** {@code self.dialect.tokenizer_class.DROP_UNKNOWN_ESCAPES}. */
    private boolean tokenizerDropUnknownEscapes() {
        return dialect.tokenizerConfig().dropUnknownEscapes();
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
        String start = Expression.truthy(startV) ? "START WITH " + Py.str(startV) : "";
        Object incrementV = expression.get(Args.INCREMENT);
        String increment = Expression.truthy(incrementV) ? " INCREMENT BY " + Py.str(incrementV) : "";
        Object minvalueV = expression.get(Args.MINVALUE);
        String minvalue = Expression.truthy(minvalueV) ? " MINVALUE " + Py.str(minvalueV) : "";
        Object maxvalueV = expression.get(Args.MAXVALUE);
        String maxvalue = Expression.truthy(maxvalueV) ? " MAXVALUE " + Py.str(maxvalueV) : "";
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
        String indexType = Expression.truthy(indexTypeV) ? " USING " + Py.str(indexTypeV) : "";
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
            cacheStr = " CACHE " + Py.str(cache);
        }

        String options = expressions(opts(expression).key(Args.OPTIONS).flat().sep(" "));
        options = !options.isEmpty() ? " " + options : "";

        return lstrip(start + increment + minvalue + maxvalue + cacheStr + options + owned);
    }

    @Override
    public String triggerpropertiesSql(Expression expression) {
        Object timingV = expression.get(Args.TIMING);
        String timing = timingV == null ? "" : Py.str(timingV);
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
            parts.add(Py.str(deferrable));
        }

        Object initially = expression.get(Args.INITIALLY);
        if (Expression.truthy(initially)) {
            parts.add("INITIALLY " + Py.str(initially));
        }

        Object referencing = expression.get(Args.REFERENCING);
        if (Expression.truthy(referencing)) {
            parts.add(sql(referencing));
        }

        Object forEach = expression.get(Args.FOR_EACH);
        if (Expression.truthy(forEach)) {
            parts.add("FOR EACH " + Py.str(forEach));
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
            return Py.str(expression.thisValue()) + " OF " + expressions(opts(expression).key(Args.COLUMNS).flat());
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
        String style = Expression.truthy(styleV) ? " " + Py.str(styleV) : "";
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
        String string = Py.str(expression.thisValue());
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
                String typeName = tv instanceof DType dt ? dtypeValue(dt) : Py.str(tv);
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
            unsupported("Data type " + (typeValue instanceof DType dt ? dtypeValue(dt) : Py.str(typeValue))
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
            typeSql = Py.str(typeValue);
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
        String kind = Py.str(kindV);
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
        String direction = Expression.truthy(directionV) ? " " + Py.str(directionV) : "";
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

        return Py.str(propertyName) + "=" + sql(expression, Args.THIS);
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
        String local = Expression.truthy(localV) ? Py.str(localV) + " " : "";
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
        String units = Expression.truthy(unitsV) ? " " + Py.str(unitsV) : "";
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
        String forOrIn = Expression.truthy(forOrInV) ? " " + Py.str(forOrInV) : "";
        Object lockType = expression.get(Args.LOCK_TYPE);
        String override = expression.has(Args.OVERRIDE) ? " OVERRIDE" : "";
        return "LOCKING " + Py.str(kind) + this_ + forOrIn + " " + Py.str(lockType) + override;
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
        String alternative = Expression.truthy(alternativeV) ? " OR " + Py.str(alternativeV) : "";
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

    // ---------------------------------------------------------------------------------------
    // From GeneratorPartC

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
        for (String s : afterHavingModifierSqls(expression)) {
            sb.append(s);
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
        String modifierSql = Expression.truthy(modifier) ? " " + Py.str(modifier) : "";
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
        String nullHandling = Expression.truthy(nullHandlingV) ? " " + Py.str(nullHandlingV) : "";

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
        String nullHandling = Expression.truthy(nullHandlingV) ? " " + Py.str(nullHandlingV) : "";
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
        String nullHandling = Expression.truthy(nullHandlingV) ? " " + Py.str(nullHandlingV) : "";
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
        String errorHandling = Expression.truthy(errorHandlingV) ? " " + Py.str(errorHandlingV) : "";
        Object emptyHandlingV = expression.get(Args.EMPTY_HANDLING);
        String emptyHandling = Expression.truthy(emptyHandlingV) ? " " + Py.str(emptyHandlingV) : "";
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

        return funcWith(sql(expression, Args.THIS), "(", ")", !isQualified, expression.list(Args.EXPRESSIONS).toArray());
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
        return "COMMENT" + existsSql + "ON" + materialized + " " + Py.str(kind) + " " + thisSql + " IS " + expressionSql;
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
        String savepointSql = Expression.truthy(savepoint) ? " TO " + Py.str(savepoint) : "";
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
            return "ALTER COLUMN" + exists + " " + thisSql + " SET " + Py.str(visible);
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
        return Py.str(propertyName) + " " + sql(expression, Args.THIS);
    }

    @Override
    public String tagSql(Expression expression) {
        return Py.str(expression.get(Args.PREFIX)) + sql(expression.thisValue()) + Py.str(expression.get(Args.POSTFIX));
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

    // ---------------------------------------------------------------------------------------
    // From GeneratorPartE

    // ------------------------------------------------------------------------------------------
    // Private helpers (module-level sqlglot functions / data not available as shared APIs)

    /** sqlglot.dialects.tsql.TSQL.CONVERT_FORMAT_MAPPING. */
    private static final Map<String, String> TSQL_CONVERT_FORMAT_MAPPING = Map.ofEntries(
            Map.entry("0", "%b %d %Y %-I:%M%p"),
            Map.entry("1", "%m/%d/%y"),
            Map.entry("2", "%y.%m.%d"),
            Map.entry("3", "%d/%m/%y"),
            Map.entry("4", "%d.%m.%y"),
            Map.entry("5", "%d-%m-%y"),
            Map.entry("6", "%d %b %y"),
            Map.entry("7", "%b %d, %y"),
            Map.entry("8", "%H:%M:%S"),
            Map.entry("9", "%b %d %Y %-I:%M:%S:%f%p"),
            Map.entry("10", "mm-dd-yy"),
            Map.entry("11", "yy/mm/dd"),
            Map.entry("12", "yymmdd"),
            Map.entry("13", "%d %b %Y %H:%M:ss:%f"),
            Map.entry("14", "%H:%M:%S:%f"),
            Map.entry("20", "%Y-%m-%d %H:%M:%S"),
            Map.entry("21", "%Y-%m-%d %H:%M:%S.%f"),
            Map.entry("22", "%m/%d/%y %-I:%M:%S %p"),
            Map.entry("23", "%Y-%m-%d"),
            Map.entry("24", "%H:%M:%S"),
            Map.entry("25", "%Y-%m-%d %H:%M:%S.%f"),
            Map.entry("100", "%b %d %Y %-I:%M%p"),
            Map.entry("101", "%m/%d/%Y"),
            Map.entry("102", "%Y.%m.%d"),
            Map.entry("103", "%d/%m/%Y"),
            Map.entry("104", "%d.%m.%Y"),
            Map.entry("105", "%d-%m-%Y"),
            Map.entry("106", "%d %b %Y"),
            Map.entry("107", "%b %d, %Y"),
            Map.entry("108", "%H:%M:%S"),
            Map.entry("109", "%b %d %Y %-I:%M:%S:%f%p"),
            Map.entry("110", "%m-%d-%Y"),
            Map.entry("111", "%Y/%m/%d"),
            Map.entry("112", "%Y%m%d"),
            Map.entry("113", "%d %b %Y %H:%M:%S:%f"),
            Map.entry("114", "%H:%M:%S:%f"),
            Map.entry("120", "%Y-%m-%d %H:%M:%S"),
            Map.entry("121", "%Y-%m-%d %H:%M:%S.%f"),
            Map.entry("126", "%Y-%m-%dT%H:%M:%S.%f"));

    /** sqlglot.dialects.dialect.WEEK_START_DAY_TO_DOW. */
    private static final Map<String, Integer> WEEK_START_DAY_TO_DOW = Map.of(
            "MONDAY", 1,
            "TUESDAY", 2,
            "WEDNESDAY", 3,
            "THURSDAY", 4,
            "FRIDAY", 5,
            "SATURDAY", 6,
            "SUNDAY", 7);

    /** sqlglot.dialects.dialect.week_offset_to_dow. */
    private static int weekOffsetToDow(int offset) {
        return Math.floorMod(offset, 7) + 1;
    }

    /** sqlglot.dialects.dialect.unit_to_str(expression) with default="DAY". */
    private static Expression unitToStr(Expression expression) {
        String default_ = "DAY";
        Expression unit = expression.expr(Args.UNIT);
        if (unit == null) {
            return Exp.string(default_);
        }
        if (unit.is(ExprType.WEEK_START)) {
            return Exp.string("WEEK");
        }
        if (unit.is(ExprType.PLACEHOLDER) || (unit.type() != ExprType.VAR && unit.type() != ExprType.LITERAL)) {
            return unit;
        }
        return Exp.string(unit.name());
    }

    /** {@code expr.is_(other)}: sqlglot's {@code _binop(Is, other)}. */
    private static Expression isOp(Expression self, Expression other) {
        Expression thisExpr = self.copy();
        Expression otherExpr = other.copy();
        if (!thisExpr.is(ExprType.IS) && !otherExpr.is(ExprType.IS)) {
            thisExpr = Exp.wrap(thisExpr, ExprType.BINARY);
            otherExpr = Exp.wrap(otherExpr, ExprType.BINARY);
        }
        return Expression.of(ExprType.IS, Args.THIS, thisExpr, Args.EXPRESSION, otherExpr);
    }




    // ------------------------------------------------------------------------------------------

    @Override
    public String columnprefixSql(Expression expression) {
        return sql(expression, Args.THIS) + "(" + sql(expression, Args.EXPRESSION) + ")";
    }

    @Override
    public String opclassSql(Expression expression) {
        return sql(expression, Args.THIS) + " " + sql(expression, Args.EXPRESSION);
    }

    @Override
    public String mlSql(Expression expression, String name) {
        String model = sql(expression, Args.THIS);
        model = "MODEL " + model;
        Expression expr = expression.getExpression();
        String exprSql;
        if (expr != null) {
            exprSql = sql(expression, Args.EXPRESSION);
            exprSql = expr.is(ExprType.TABLE) ? "TABLE " + exprSql : exprSql;
        } else {
            exprSql = null;
        }

        String parameters = sql(expression, Args.PARAMS_STRUCT);
        if (parameters.isEmpty()) {
            parameters = null;
        }

        return func(name, model, exprSql, parameters);
    }

    @Override
    public String predictSql(Expression expression) {
        return mlSql(expression, "PREDICT");
    }

    @Override
    public String generateembeddingSql(Expression expression) {
        String name = expression.has(Args.IS_TEXT) ? "GENERATE_TEXT_EMBEDDING" : "GENERATE_EMBEDDING";
        return mlSql(expression, name);
    }

    @Override
    public String generatetextSql(Expression expression) {
        return mlSql(expression, "GENERATE_TEXT");
    }

    @Override
    public String generatetableSql(Expression expression) {
        return mlSql(expression, "GENERATE_TABLE");
    }

    @Override
    public String generateboolSql(Expression expression) {
        return mlSql(expression, "GENERATE_BOOL");
    }

    @Override
    public String generateintSql(Expression expression) {
        return mlSql(expression, "GENERATE_INT");
    }

    @Override
    public String generatedoubleSql(Expression expression) {
        return mlSql(expression, "GENERATE_DOUBLE");
    }

    @Override
    public String mltranslateSql(Expression expression) {
        return mlSql(expression, "TRANSLATE");
    }

    @Override
    public String mlforecastSql(Expression expression) {
        return mlSql(expression, "FORECAST");
    }

    @Override
    public String aiforecastSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        if (expression.getThis() != null && expression.getThis().is(ExprType.TABLE)) {
            thisSql = "TABLE " + thisSql;
        }

        return func(
                "FORECAST",
                thisSql,
                expression.get(Args.DATA_COL),
                expression.get(Args.TIMESTAMP_COL),
                expression.get(Args.MODEL),
                expression.get(Args.ID_COLS),
                expression.get(Args.HORIZON),
                expression.get(Args.FORECAST_END_TIMESTAMP),
                expression.get(Args.CONFIDENCE_LEVEL),
                expression.get(Args.OUTPUT_HISTORICAL_TIME_SERIES),
                expression.get(Args.CONTEXT_WINDOW));
    }

    @Override
    public String featuresattimeSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        if (expression.getThis() != null && expression.getThis().is(ExprType.TABLE)) {
            thisSql = "TABLE " + thisSql;
        }

        return func(
                "FEATURES_AT_TIME",
                thisSql,
                expression.get(Args.TIME),
                expression.get(Args.NUM_ROWS),
                expression.get(Args.IGNORE_FEATURE_NULLS));
    }

    @Override
    public String vectorsearchSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        if (expression.getThis() != null && expression.getThis().is(ExprType.TABLE)) {
            thisSql = "TABLE " + thisSql;
        }

        String queryTable = sql(expression, Args.QUERY_TABLE);
        if (Expression.isInstance(expression.get(Args.QUERY_TABLE), ExprType.TABLE)) {
            queryTable = "TABLE " + queryTable;
        }

        return func(
                "VECTOR_SEARCH",
                thisSql,
                expression.get(Args.COLUMN_TO_SEARCH),
                queryTable,
                expression.get(Args.QUERY_COLUMN_TO_SEARCH),
                expression.get(Args.TOP_K),
                expression.get(Args.DISTANCE_TYPE),
                expression.get(Args.OPTIONS));
    }

    @Override
    public String forinSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expressionSql = sql(expression, Args.EXPRESSION);
        return "FOR " + thisSql + " DO " + expressionSql;
    }

    @Override
    public String refreshSql(Expression expression) {
        unsupportedArgs(expression, Args.PARTITION_START, Args.PARTITION_END, Args.FORCE, Args.MODE);
        String thisSql = sql(expression, Args.THIS);
        String kind = expression.getThis() != null && expression.getThis().is(ExprType.LITERAL)
                ? ""
                : expression.text(Args.KIND) + " ";
        return "REFRESH " + kind + thisSql;
    }

    @Override
    public String toarraySql(Expression expression) {
        Expression arg = expression.getThis();
        if (arg.dataType() == null) {
            arg = TypeAnnotator.annotateTypes(arg, dialect);
        }

        if (arg.isType(DType.ARRAY)) {
            return sql(arg);
        }

        Expression condForNull = isOp(arg, Exp.nul());
        return sql(Exp.func("IF", null, condForNull, Exp.nul(), Exp.array(List.of(arg))));
    }

    @Override
    public String tsordstotimeSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        String timeFormat = formatTime(expression);

        if (timeFormat != null && !timeFormat.isEmpty()) {
            return sql(
                    Exp.cast(
                            Expression.of(ExprType.STR_TO_TIME, Args.THIS, thisExpr, Args.FORMAT, expression.get(Args.FORMAT)),
                            DType.TIME));
        }

        if (thisExpr.is(ExprType.TS_OR_DS_TO_TIME) || thisExpr.isType(DType.TIME)) {
            return sql(thisExpr);
        }

        return sql(Exp.cast(thisExpr, DType.TIME));
    }

    @Override
    public String tsordstotimestampSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        if (thisExpr.is(ExprType.TS_OR_DS_TO_TIMESTAMP) || thisExpr.isType(DType.TIMESTAMP)) {
            return sql(thisExpr);
        }

        return sql(Exp.cast(thisExpr, DType.TIMESTAMP, true, dialect));
    }

    @Override
    public String tsordstodatetimeSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        if (thisExpr.is(ExprType.TS_OR_DS_TO_DATETIME) || thisExpr.isType(DType.DATETIME)) {
            return sql(thisExpr);
        }

        return sql(Exp.cast(thisExpr, DType.DATETIME, true, dialect));
    }

    @Override
    public String tsordstodateSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        String timeFormat = formatTime(expression);
        Object safe = expression.get(Args.SAFE);
        if (timeFormat != null && !timeFormat.isEmpty()
                && !timeFormat.equals(dd.TIME_FORMAT) && !timeFormat.equals(dd.DATE_FORMAT)) {
            return sql(
                    Exp.cast(
                            Expression.of(ExprType.STR_TO_TIME, Args.THIS, thisExpr, Args.FORMAT, expression.get(Args.FORMAT),
                                    Args.SAFE, safe),
                            DType.DATE));
        }

        if (thisExpr.is(ExprType.TS_OR_DS_TO_DATE) || thisExpr.isType(DType.DATE)) {
            return sql(thisExpr);
        }

        if (Expression.truthy(safe)) {
            return sql(Expression.of(ExprType.TRY_CAST, Args.THIS, thisExpr, Args.TO, DataTypes.of(DType.DATE)));
        }

        return sql(Exp.cast(thisExpr, DType.DATE));
    }

    @Override
    public String unixdateSql(Expression expression) {
        return sql(
                Exp.func(
                        "DATEDIFF",
                        null,
                        expression.getThis(),
                        Exp.cast(Exp.string("1970-01-01"), DType.DATE),
                        "day"));
    }

    @Override
    public String lastdaySql(Expression expression) {
        if (g.LAST_DAY_SUPPORTS_DATE_PART) {
            return functionFallbackSql(expression);
        }

        Expression unit = expression.expr(Args.UNIT);
        if (unit != null && !Py.upper(unit.name()).equals("MONTH")) {
            unsupported("Date parts are not supported in LAST_DAY.");
        }

        return func("LAST_DAY", expression.getThis());
    }

    @Override
    public String dateaddSql(Expression expression) {
        return func(
                "DATE_ADD",
                expression.getThis(),
                expression.getExpression(),
                unitToStr(expression));
    }

    @Override
    public String arrayinsertSql(Expression expression, int indexOffset) {
        Expression thisExpr = expression.getThis();
        Expression position = expression.expr(Args.POSITION);
        Object offsetArg = expression.get(Args.OFFSET);
        int offset = indexOffset - (Expression.truthy(offsetArg) ? ((Number) offsetArg).intValue() : 0);

        if (offset != 0) {
            if (position.isInt()) {
                java.math.BigInteger value = new java.math.BigInteger(position.toPy().toString());
                if (value.signum() >= 0) {
                    position = Exp.number(value.add(java.math.BigInteger.valueOf(offset)));
                } else if (offset < 0 && value.equals(java.math.BigInteger.valueOf(-1))) {
                    // 1-based -1 appends, which a 0-based position can only express as the size
                    position = Expression.of(ExprType.ARRAY_SIZE, Args.THIS, thisExpr.copy());
                } else {
                    // Negative positions count from the end, so they shift in the opposite
                    // direction, e.g. 0-based -1 (before the last element) is 1-based -2
                    position = Exp.number(value.subtract(java.math.BigInteger.valueOf(offset)));
                }
            } else {
                unsupported("ARRAY_INSERT position can only be converted if it's a literal");
            }
        }

        return func("ARRAY_INSERT", thisExpr, position, expression.getExpression());
    }

    @Override
    public String arrayanySql(Expression expression) {
        if (g.CAN_IMPLEMENT_ARRAY_ANY) {
            Expression filtered = Expression.of(ExprType.ARRAY_FILTER, Args.THIS, expression.getThis(),
                    Args.EXPRESSION, expression.getExpression());
            Expression filteredNotEmpty = Expression.of(ExprType.ARRAY_SIZE, Args.THIS, filtered).neq(0);
            Expression originalIsEmpty = Expression.of(ExprType.ARRAY_SIZE, Args.THIS, expression.getThis()).eq(0);
            return sql(Exp.paren(originalIsEmpty.or(true, filteredNotEmpty)));
        }

        // SQLGlot's executor supports ARRAY_ANY, so we don't wanna warn for the SQLGlot dialect
        if (dialect.getClass() != Dialect.class) {
            unsupported("ARRAY_ANY is unsupported");
        }

        return functionFallbackSql(expression);
    }

    @Override
    public String structSql(Expression expression) {
        List<Expression> newExpressions = new ArrayList<>();
        for (Expression e : expression.getExpressions()) {
            if (e.is(ExprType.PROPERTY_EQ)) {
                newExpressions.add(Exp.alias(e.getExpression(), e.getThis().isString() ? e.name() : e.getThis()));
            } else {
                newExpressions.add(e);
            }
        }
        expression.set(Args.EXPRESSIONS, newExpressions);

        return functionFallbackSql(expression);
    }

    @Override
    public String partitionrangeSql(Expression expression) {
        String low = sql(expression, Args.THIS);
        String high = sql(expression, Args.EXPRESSION);

        return low + " TO " + high;
    }

    @Override
    public String truncatetableSql(Expression expression) {
        String target = expression.has(Args.IS_DATABASE) ? "DATABASE" : "TABLE";
        String tables = " " + expressions(expression);

        String exists = expression.has(Args.EXISTS) ? " IF EXISTS" : "";

        String onCluster = sql(expression, Args.CLUSTER);
        onCluster = !onCluster.isEmpty() ? " " + onCluster : "";

        String identity = sql(expression, Args.IDENTITY);
        identity = !identity.isEmpty() ? " " + identity + " IDENTITY" : "";

        String option = sql(expression, Args.OPTION);
        option = !option.isEmpty() ? " " + option : "";

        String partition = sql(expression, Args.PARTITION);
        partition = !partition.isEmpty() ? " " + partition : "";

        return "TRUNCATE " + target + exists + tables + onCluster + identity + option + partition;
    }

    // This transpiles T-SQL's CONVERT function
    // https://learn.microsoft.com/en-us/sql/t-sql/functions/cast-and-convert-transact-sql?view=sql-server-ver16
    @Override
    public String convertSql(Expression expression) {
        Expression to = expression.getThis();
        Expression value = expression.getExpression();
        Object style = expression.get(Args.STYLE);
        Object safe = expression.get(Args.SAFE);
        Object strict = expression.get(Args.STRICT);

        if (to == null || value == null) {
            return "";
        }

        // Retrieve length of datatype and override to default if not specified
        if (Py.seqGet(to.getExpressions(), 0) == null && g.PARAMETERIZABLE_TEXT_TYPES.contains(to.thisValue())) {
            to = Expression.of(ExprType.DATA_TYPE, Args.THIS, to.thisValue(),
                    Args.EXPRESSIONS, new ArrayList<>(List.of(Exp.number(30))), Args.NESTED, Boolean.FALSE);
        }

        Expression transformed = null;
        ExprType cast = Expression.truthy(strict) ? ExprType.CAST : ExprType.TRY_CAST;

        // Check whether a conversion with format (T-SQL calls this 'style') is applicable
        if (style instanceof Expression styleExpr && styleExpr.is(ExprType.LITERAL) && styleExpr.isInt()) {
            String styleValue = styleExpr.name();
            String convertedStyle = TSQL_CONVERT_FORMAT_MAPPING.get(styleValue);
            if (convertedStyle == null || convertedStyle.isEmpty()) {
                unsupported("Unsupported T-SQL 'style' value: " + styleValue);
            }

            Expression fmt = Exp.string(convertedStyle);

            Object toThis = to.thisValue();
            if (toThis == DType.DATE) {
                transformed = Expression.of(ExprType.STR_TO_DATE, Args.THIS, value, Args.FORMAT, fmt);
            } else if (toThis == DType.DATETIME || toThis == DType.DATETIME2) {
                transformed = Expression.of(ExprType.STR_TO_TIME, Args.THIS, value, Args.FORMAT, fmt);
            } else if (g.PARAMETERIZABLE_TEXT_TYPES.contains(toThis)) {
                transformed = Expression.of(cast,
                        Args.THIS, Expression.of(ExprType.TIME_TO_STR, Args.THIS, value, Args.FORMAT, fmt),
                        Args.TO, to,
                        Args.SAFE, safe);
            } else if (toThis == DType.TEXT) {
                transformed = Expression.of(ExprType.TIME_TO_STR, Args.THIS, value, Args.FORMAT, fmt);
            }
        }

        if (transformed == null) {
            transformed = Expression.of(cast, Args.THIS, value, Args.TO, to, Args.SAFE, safe);
        }

        return sql(transformed);
    }

    @Override
    public String jsonpathkeySql(Expression expression) {
        Object thisValue = expression.thisValue();
        if (Expression.isInstance(thisValue, ExprType.JSON_PATH_WILDCARD)) {
            String part = jsonPathPart(thisValue);
            return !part.isEmpty() ? "." + part : "";
        }

        Object quoted = expression.get(Args.QUOTED);
        if (!(Expression.truthy(quoted) && g.JSON_PATH_KEY_QUOTED_FORCES_BRACKETS)
                && safeJsonPathKeyRe().matcher((String) thisValue).lookingAt()) {
            return "." + thisValue;
        }

        String part = jsonPathPart(thisValue);

        return quoteJsonPathKeyUsingBrackets && g.JSON_PATH_BRACKETED_KEY_SUPPORTED
                ? "[" + part + "]"
                : "." + part;
    }

    @Override
    public String jsonpathsubscriptSql(Expression expression) {
        String thisSql = jsonPathPart(expression.thisValue());
        return !thisSql.isEmpty() ? "[" + thisSql + "]" : "";
    }

    @Override
    public Expression simplifyUnlessLiteral(Expression expression) {
        if (!expression.is(ExprType.LITERAL)) {
            expression = Simplify.simplify(expression, dialect);
        }

        return expression;
    }

    @Override
    public String embedIgnoreNulls(Expression expression, String text) {
        Expression thisExpr = expression.getThis();
        if (thisExpr != null && isAny(thisExpr, g.RESPECT_IGNORE_NULLS_UNSUPPORTED_EXPRESSIONS)) {
            unsupported("RESPECT/IGNORE NULLS is not supported for " + thisExpr.key() + " in "
                    + dialect.getClass().getSimpleName());
            return sql(thisExpr);
        }

        if (g.IGNORE_NULLS_IN_FUNC && !Expression.truthy(expression.metaGet("inline"))) {
            if (g.IGNORE_NULLS_BEFORE_ORDER) {
                // The first modifier here will be the one closest to the AggFunc's arg
                List<Expression> mods = new ArrayList<>(
                        Scopes.findAllInScope(expression, ExprType.HAVING_MAX, ExprType.ORDER, ExprType.LIMIT));
                mods.sort((a, b) -> Integer.compare(modRank(a), modRank(b)));

                if (!mods.isEmpty()) {
                    Expression mod = mods.get(0);
                    Expression wrapped = Expression.of(expression.type(), Args.THIS, mod.getThis().copy());
                    wrapped.meta().put("inline", Boolean.TRUE);
                    mod.getThis().replace(wrapped);
                    return sql(expression.getThis());
                }
            }

            Expression aggFunc = expression.find(ExprType.AGG_FUNC);

            if (aggFunc != null) {
                String aggFuncSql = Py.slice(sql(aggFunc, false), null, -1) + " " + text + ")";
                return maybeComment(aggFuncSql, aggFunc.comments());
            }
        }

        return sql(expression, Args.THIS) + " " + text;
    }

    private static int modRank(Expression x) {
        return x.is(ExprType.HAVING_MAX) ? 0 : (x.is(ExprType.ORDER) ? 1 : 2);
    }

    @Override
    public String copyparameterSql(Expression expression) {
        String option = sql(expression, Args.THIS);

        if (!expression.getExpressions().isEmpty()) {
            String upper = Py.upper(option);

            // Snowflake FILE_FORMAT options are separated by whitespace
            String sep = upper.equals("FILE_FORMAT") ? " " : ", ";

            // Databricks copy/format options do not set their list of values with EQ
            String op = upper.equals("COPY_OPTIONS") || upper.equals("FORMAT_OPTIONS") ? " " : " = ";
            String values = expressions(opts(expression).flat().sep(sep));
            return option + op + "(" + values + ")";
        }

        String value = sql(expression, Args.EXPRESSION);

        if (value.isEmpty()) {
            return option;
        }

        String op = g.COPY_PARAMS_EQ_REQUIRED ? " = " : " ";

        return option + op + value;
    }

    @Override
    public String credentialsSql(Expression expression) {
        Object credExpr = expression.get(Args.CREDENTIALS);
        String credentials;
        if (Expression.isInstance(credExpr, ExprType.LITERAL)) {
            // Redshift case: CREDENTIALS <string>
            credentials = sql(expression, Args.CREDENTIALS);
            credentials = !credentials.isEmpty() ? "CREDENTIALS " + credentials : "";
        } else {
            // Snowflake case: CREDENTIALS = (...)
            credentials = expressions(opts(expression).key(Args.CREDENTIALS).flat().sep(" "));
            credentials = credExpr != null ? "CREDENTIALS = (" + credentials + ")" : "";
        }

        String storage = sql(expression, Args.STORAGE);
        storage = !storage.isEmpty() ? "STORAGE_INTEGRATION = " + storage : "";

        String encryption = expressions(opts(expression).key(Args.ENCRYPTION).flat().sep(" "));
        encryption = !encryption.isEmpty() ? " ENCRYPTION = (" + encryption + ")" : "";

        String iamRole = sql(expression, Args.IAM_ROLE);
        iamRole = !iamRole.isEmpty() ? "IAM_ROLE " + iamRole : "";

        String region = sql(expression, Args.REGION);
        region = !region.isEmpty() ? " REGION " + region : "";

        return credentials + storage + encryption + iamRole + region;
    }

    @Override
    public String copySql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        thisSql = g.COPY_HAS_INTO_KEYWORD ? " INTO " + thisSql : " " + thisSql;

        String credentials = sql(expression, Args.CREDENTIALS);
        credentials = !credentials.isEmpty() ? seg(credentials) : "";
        String files = expressions(opts(expression).key(Args.FILES).flat());
        String kind = !files.isEmpty() ? seg(expression.has(Args.KIND) ? "FROM" : "TO") : "";

        String sep = dd.COPY_PARAMS_ARE_CSV ? ", " : " ";
        String params = expressions(
                opts(expression)
                        .key(Args.PARAMS)
                        .sep(sep)
                        .newLine()
                        .skipLast()
                        .skipFirst()
                        .indent(g.COPY_PARAMS_ARE_WRAPPED));

        if (!params.isEmpty()) {
            if (g.COPY_PARAMS_ARE_WRAPPED) {
                params = " WITH (" + params + ")";
            } else if (!pretty && (!files.isEmpty() || !credentials.isEmpty())) {
                params = " " + params;
            }
        }

        return "COPY" + thisSql + kind + " " + files + credentials + params;
    }

    @Override
    public String semicolonSql(Expression expression) {
        return "";
    }

    @Override
    public String datadeletionpropertySql(Expression expression) {
        String onSql = expression.has(Args.ON) ? "ON" : "OFF";
        String filterCol = sql(expression, Args.FILTER_COLUMN);
        filterCol = !filterCol.isEmpty() ? "FILTER_COLUMN=" + filterCol : null;
        String retentionPeriod = sql(expression, Args.RETENTION_PERIOD);
        retentionPeriod = !retentionPeriod.isEmpty() ? "RETENTION_PERIOD=" + retentionPeriod : null;

        if (filterCol != null || retentionPeriod != null) {
            onSql = func("ON", filterCol, retentionPeriod);
        }

        return "DATA_DELETION=" + onSql;
    }

    @Override
    public String maskingpolicycolumnconstraintSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String exprs = expressions(opts(expression).flat());
        exprs = !exprs.isEmpty() ? " USING (" + exprs + ")" : "";
        return "MASKING POLICY " + thisSql + exprs;
    }

    @Override
    public String gapfillSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        thisSql = "TABLE " + thisSql;
        List<Object> args = new ArrayList<>();
        args.add(thisSql);
        for (Map.Entry<Arg, Object> entry : expression.argEntries()) {
            if (entry.getKey() != Args.THIS) {
                args.add(entry.getValue());
            }
        }
        return func("GAP_FILL", args.toArray());
    }

    @Override
    public String scopeResolution(String rhs, String scopeName) {
        return func("SCOPE_RESOLUTION", scopeName == null || scopeName.isEmpty() ? null : scopeName, rhs);
    }

    @Override
    public String scoperesolutionSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        Expression expr = expression.getExpression();

        String exprSql;
        if (expr != null && expr.is(ExprType.FUNC)) {
            // T-SQL's CLR functions are case sensitive
            exprSql = sql(expr, Args.THIS) + "(" + formatArgs(expr.getExpressions().toArray()) + ")";
        } else {
            exprSql = sql(expression, Args.EXPRESSION);
        }

        return scopeResolution(exprSql, thisSql);
    }

    @Override
    public String parsejsonSql(Expression expression) {
        if (g.PARSE_JSON_NAME == null) {
            return sql(expression.getThis());
        }

        return func(g.PARSE_JSON_NAME, expression.getThis(), expression.getExpression());
    }

    @Override
    public String randSql(Expression expression) {
        String lower = sql(expression, Args.LOWER);
        String upper = sql(expression, Args.UPPER);

        if (!lower.isEmpty() && !upper.isEmpty()) {
            return "(" + upper + " - " + lower + ") * " + func("RAND", expression.getThis()) + " + " + lower;
        }
        return func("RAND", expression.getThis());
    }

    @Override
    public String changesSql(Expression expression) {
        String information = sql(expression, Args.INFORMATION);
        information = "INFORMATION => " + information;
        String atBefore = sql(expression, Args.AT_BEFORE);
        atBefore = !atBefore.isEmpty() ? seg("") + atBefore : "";
        String end = sql(expression, Args.END);
        end = !end.isEmpty() ? seg("") + end : "";

        return "CHANGES (" + information + ")" + atBefore + end;
    }

    @Override
    public String padSql(Expression expression) {
        String prefix = expression.has(Args.IS_LEFT) ? "L" : "R";

        String fillPattern = sql(expression, Args.FILL_PATTERN);
        if (fillPattern.isEmpty()) {
            fillPattern = null;
        }
        if (fillPattern == null && g.PAD_FILL_PATTERN_IS_REQUIRED) {
            fillPattern = "' '";
        }

        return func(prefix + "PAD", expression.getThis(), expression.getExpression(), fillPattern);
    }

    @Override
    public String summarizeSql(Expression expression) {
        String table = expression.has(Args.TABLE) ? " TABLE" : "";
        return "SUMMARIZE" + table + " " + sql(expression.getThis());
    }

    @Override
    public String explodinggenerateseriesSql(Expression expression) {
        Map<Arg, Object> args = new LinkedHashMap<>();
        for (Map.Entry<Arg, Object> entry : expression.argEntries()) {
            args.put(entry.getKey(), entry.getValue());
        }
        Expression generateSeries = Expression.of(ExprType.GENERATE_SERIES, args);

        Expression parent = expression.parent();
        if (parent != null && parent.is(ExprType.ALIAS, ExprType.TABLE_ALIAS)) {
            parent = parent.parent();
        }

        if (g.SUPPORTS_EXPLODING_PROJECTIONS && !(parent != null && parent.is(ExprType.TABLE, ExprType.UNNEST))) {
            return sql(Expression.of(ExprType.UNNEST, Args.EXPRESSIONS, new ArrayList<>(List.of(generateSeries))));
        }

        if (parent != null && parent.is(ExprType.SELECT)) {
            unsupported("GenerateSeries projection unnesting is not supported.");
        }

        return sql(generateSeries);
    }

    @Override
    public String converttimezoneSql(Expression expression) {
        if (g.SUPPORTS_CONVERT_TIMEZONE) {
            return functionFallbackSql(expression);
        }

        Expression sourceTz = expression.expr(Args.SOURCE_TZ);
        Expression targetTz = expression.expr(Args.TARGET_TZ);
        Expression timestamp = expression.expr(Args.TIMESTAMP);

        if (sourceTz != null && timestamp != null) {
            timestamp = Expression.of(ExprType.AT_TIME_ZONE,
                    Args.THIS, Exp.cast(timestamp, DType.TIMESTAMPNTZ), Args.ZONE, sourceTz);
        }

        Expression expr = Expression.of(ExprType.AT_TIME_ZONE, Args.THIS, timestamp, Args.ZONE, targetTz);

        return sql(expr);
    }

    @Override
    public String jsonSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        thisSql = !thisSql.isEmpty() ? " " + thisSql : "";

        Object with = expression.get(Args.WITH_);

        String withSql;
        if (with == null) {
            withSql = "";
        } else if (!Expression.truthy(with)) {
            withSql = " WITHOUT";
        } else {
            withSql = " WITH";
        }

        String uniqueSql = expression.has(Args.UNIQUE) ? " UNIQUE KEYS" : "";

        return "JSON" + thisSql + withSql + uniqueSql;
    }

    @Override
    public String jsonvalueSql(Expression expression) {
        String path = sql(expression, Args.PATH);
        String returning = sql(expression, Args.RETURNING);
        returning = !returning.isEmpty() ? " RETURNING " + returning : "";

        String onCondition = sql(expression, Args.ON_CONDITION);
        onCondition = !onCondition.isEmpty() ? " " + onCondition : "";

        return func("JSON_VALUE", expression.getThis(), path + returning + onCondition);
    }

    @Override
    public String skipjsoncolumnSql(Expression expression) {
        String regexp = expression.has(Args.REGEXP) ? " REGEXP" : "";
        return "SKIP" + regexp + " " + sql(expression.getExpression());
    }

    @Override
    public String conditionalinsertSql(Expression expression) {
        String else_ = expression.has(Args.ELSE_) ? "ELSE " : "";
        String condition = sql(expression, Args.EXPRESSION);
        condition = !condition.isEmpty() ? "WHEN " + condition + " THEN " : else_;
        String insert = Py.slice(sql(expression, Args.THIS), "INSERT".length()).strip();
        return condition + insert;
    }

    @Override
    public String multitableinsertsSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        String source = sql(expression, Args.SOURCE);

        String sqlText;
        if (!kind.isEmpty()) {
            String exprs = seg(expressions(opts(expression).sep(" ")));
            sqlText = "INSERT " + kind + exprs + seg(source);
        } else {
            // If kind isn't present, we have Hive's FROM-first form:
            // FROM x INSERT ... SELECT ... [INSERT ... SELECT ...]
            List<String> parts = new ArrayList<>();
            for (Expression e : expression.getExpressions()) {
                parts.add(sql(e));
            }
            String inserts = String.join(sep(), parts);
            sqlText = "FROM " + source + seg(inserts);
        }

        return prependCtes(expression, sqlText);
    }

    @Override
    public String onconditionSql(Expression expression) {
        // Static options like "NULL ON ERROR" are stored as strings, in contrast to "DEFAULT <expr> ON ERROR"
        Object emptyArg = expression.get(Args.EMPTY);
        String empty = emptyArg instanceof Expression
                ? "DEFAULT " + Py.str(emptyArg) + " ON EMPTY"
                : sql(expression, Args.EMPTY);

        Object errorArg = expression.get(Args.ERROR);
        String error = errorArg instanceof Expression
                ? "DEFAULT " + Py.str(errorArg) + " ON ERROR"
                : sql(expression, Args.ERROR);

        if (!error.isEmpty() && !empty.isEmpty()) {
            error = dd.ON_CONDITION_EMPTY_BEFORE_ERROR ? empty + " " + error : error + " " + empty;
            empty = "";
        }

        String nul = sql(expression, Args.NULL);

        return empty + error + nul;
    }

    @Override
    public String jsonextractquoteSql(Expression expression) {
        String scalar = expression.has(Args.SCALAR) ? " ON SCALAR STRING" : "";
        return sql(expression, Args.OPTION) + " QUOTES" + scalar;
    }

    @Override
    public String jsonexistsSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String path = sql(expression, Args.PATH);

        String passing = expressions(expression, Args.PASSING);
        passing = !passing.isEmpty() ? " PASSING " + passing : "";

        String onCondition = sql(expression, Args.ON_CONDITION);
        onCondition = !onCondition.isEmpty() ? " " + onCondition : "";

        path = path + passing + onCondition;

        return func("JSON_EXISTS", thisSql, path);
    }

    /**
     * Add NULL filter to ARRAY_AGG if dialect requires it.
     *
     * @param arrayAggSql The generated ARRAY_AGG SQL string
     * @param arrayAggExpr The ArrayAgg expression node
     * @param columnExpr The column/expression to filter (before ORDER BY wrapping)
     * @return SQL string with FILTER clause added if needed
     */
    @Override
    public String addArrayaggNullFilter(String arrayAggSql, Expression arrayAggExpr, Expression columnExpr) {
        // Add a NULL FILTER on the column to mimic the results going from a dialect that excludes nulls
        // on ARRAY_AGG (e.g Spark) to one that doesn't (e.g. DuckDB)
        if (!(Expression.truthy(dd.ARRAY_AGG_INCLUDES_NULLS) && arrayAggExpr.has(Args.NULLS_EXCLUDED))) {
            return arrayAggSql;
        }

        Expression parent = arrayAggExpr.parent();
        if (parent != null && parent.is(ExprType.FILTER)) {
            Expression parentCond = parent.getExpression().getThis();
            parentCond.replace(parentCond.and(true, isOp(columnExpr, Exp.nul()).not(true)));
        } else if (columnExpr.find(ExprType.COLUMN) != null) {
            // Do not add the filter if the input is not a column (e.g. literal, struct etc)
            // DISTINCT is already present in the agg function, do not propagate it to FILTER as well
            String thisSql = columnExpr.is(ExprType.DISTINCT) ? expressions(columnExpr) : sql(columnExpr);
            arrayAggSql = arrayAggSql + " FILTER(WHERE " + thisSql + " IS NOT NULL)";
        }

        return arrayAggSql;
    }

    @Override
    public String arrayaggSql(Expression expression) {
        String arrayAgg = functionFallbackSql(expression);
        Expression columnExpr = expression.getThis();
        if (columnExpr != null && columnExpr.is(ExprType.ORDER)) {
            columnExpr = columnExpr.getThis();
        }

        return addArrayaggNullFilter(arrayAgg, expression, columnExpr);
    }

    @Override
    public String sliceSql(Expression expression) {
        String step = sql(expression, Args.STEP);
        String end = sql(expression.getExpression());
        String begin = sql(expression.getThis());

        String sqlText = !step.isEmpty() ? end + ":" + step : end;
        return !sqlText.isEmpty() ? begin + ":" + sqlText : begin + ":";
    }

    @Override
    public String applySql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expr = sql(expression, Args.EXPRESSION);

        return thisSql + " APPLY(" + expr + ")";
    }

    @Override
    public String grantOrRevokeSql(Expression expression, String keyword, String preposition, String grantOptionPrefix,
            String grantOptionSuffix) {
        String privilegesSql = expressions(opts(expression).key(Args.PRIVILEGES).flat());

        String kind = sql(expression, Args.KIND);
        kind = !kind.isEmpty() ? " " + kind : "";

        String securable = sql(expression, Args.SECURABLE);
        securable = !securable.isEmpty() ? " " + securable : "";

        String principals = expressions(opts(expression).key(Args.PRINCIPALS).flat());

        if (!expression.has(Args.GRANT_OPTION)) {
            grantOptionPrefix = grantOptionSuffix = "";
        }

        // cascade for revoke only
        String cascade = sql(expression, Args.CASCADE);
        cascade = !cascade.isEmpty() ? " " + cascade : "";

        return keyword + " " + grantOptionPrefix + privilegesSql + " ON" + kind + securable + " " + preposition + " "
                + principals + grantOptionSuffix + cascade;
    }

    @Override
    public String grantSql(Expression expression) {
        return grantOrRevokeSql(expression, "GRANT", "TO", "", " WITH GRANT OPTION");
    }

    @Override
    public String revokeSql(Expression expression) {
        return grantOrRevokeSql(expression, "REVOKE", "FROM", "GRANT OPTION FOR ", "");
    }

    @Override
    public String grantprivilegeSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String columns = expressions(opts(expression).flat());
        columns = !columns.isEmpty() ? "(" + columns + ")" : "";

        return thisSql + columns;
    }

    @Override
    public String grantprincipalSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);

        String kind = sql(expression, Args.KIND);
        kind = !kind.isEmpty() ? kind + " " : "";

        return kind + thisSql;
    }

    @Override
    public String columnsSql(Expression expression) {
        String fn = functionFallbackSql(expression);
        if (expression.has(Args.UNPACK)) {
            fn = "*" + fn;
        }

        return fn;
    }

    @Override
    public String overlaySql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expr = sql(expression, Args.EXPRESSION);
        String fromSql = sql(expression, Args.FROM_);
        String forSql = sql(expression, Args.FOR_);
        forSql = !forSql.isEmpty() ? " FOR " + forSql : "";

        return "OVERLAY(" + thisSql + " PLACING " + expr + " FROM " + fromSql + forSql + ")";
    }

    @Override
    public String todoubleSql(Expression expression) {
        unsupportedArgs(expression, Args.FORMAT);
        ExprType cast = expression.has(Args.SAFE) ? ExprType.TRY_CAST : ExprType.CAST;
        return sql(Expression.of(cast, Args.THIS, expression.getThis(), Args.TO, DataTypes.of(DType.DOUBLE)));
    }

    @Override
    public String stringSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        Expression zone = expression.expr(Args.ZONE);

        if (zone != null) {
            // This is a BigQuery specific argument for STRING(<timestamp_expr>, <time_zone>)
            // BigQuery stores timestamps internally as UTC, so ConvertTimezone is used with UTC
            // set for source_tz to transpile the time conversion before the STRING cast
            thisExpr = Expression.of(ExprType.CONVERT_TIMEZONE,
                    Args.SOURCE_TZ, Exp.string("UTC"), Args.TARGET_TZ, zone, Args.TIMESTAMP, thisExpr);
        }

        return sql(Exp.cast(thisExpr, DType.VARCHAR));
    }

    @Override
    public String medianSql(Expression expression) {
        if (!g.SUPPORTS_MEDIAN) {
            return sql(Expression.of(ExprType.PERCENTILE_CONT,
                    Args.THIS, expression.getThis(), Args.EXPRESSION, Exp.number(0.5)));
        }

        return functionFallbackSql(expression);
    }

    @Override
    public String overflowtruncatebehaviorSql(Expression expression) {
        String filler = sql(expression, Args.THIS);
        filler = !filler.isEmpty() ? " " + filler : "";
        String withCount = expression.has(Args.WITH_COUNT) ? "WITH COUNT" : "WITHOUT COUNT";
        return "TRUNCATE" + filler + " " + withCount;
    }

    @Override
    public String unixsecondsSql(Expression expression) {
        if (g.SUPPORTS_UNIX_SECONDS) {
            return functionFallbackSql(expression);
        }

        Expression startTs = Exp.cast(Exp.string("1970-01-01 00:00:00+00"), DType.TIMESTAMPTZ);

        return sql(Expression.of(ExprType.TIMESTAMP_DIFF,
                Args.THIS, expression.getThis(), Args.EXPRESSION, startTs, Args.UNIT, Exp.var("SECONDS")));
    }

    @Override
    public String arraysizeSql(Expression expression) {
        Expression dim = expression.getExpression();

        // For dialects that don't support the dimension arg, we can safely transpile it's default value (1st dimension)
        if (dim != null && g.ARRAY_SIZE_DIM_REQUIRED == null) {
            if (!(dim.isInt() && dim.name().equals("1"))) {
                unsupported("Cannot transpile dimension argument for ARRAY_LENGTH");
            }
            dim = null;
        }

        // If dimension is required but not specified, default initialize it
        if (Boolean.TRUE.equals(g.ARRAY_SIZE_DIM_REQUIRED) && dim == null) {
            dim = Exp.number(1);
        }

        return func(g.ARRAY_SIZE_NAME, expression.getThis(), dim);
    }

    @Override
    public String attachSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String existsSql = expression.has(Args.EXISTS) ? " IF NOT EXISTS" : "";
        String exprs = expressions(expression);
        exprs = !exprs.isEmpty() ? " (" + exprs + ")" : "";

        return "ATTACH" + existsSql + " " + thisSql + exprs;
    }

    @Override
    public String detachSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        kind = !kind.isEmpty() ? " " + kind : "";
        // the DATABASE keyword is required if IF EXISTS is set for DuckDB
        // ref: https://duckdb.org/docs/stable/sql/statements/attach.html#detach-syntax
        String exists = expression.has(Args.EXISTS) ? " IF EXISTS" : "";
        if (!exists.isEmpty()) {
            kind = !kind.isEmpty() ? kind : " DATABASE";
        }

        String thisSql = sql(expression, Args.THIS);
        thisSql = !thisSql.isEmpty() ? " " + thisSql : "";
        String cluster = sql(expression, Args.CLUSTER);
        cluster = !cluster.isEmpty() ? " " + cluster : "";
        String permanent = expression.has(Args.PERMANENT) ? " PERMANENTLY" : "";
        String sync = expression.has(Args.SYNC) ? " SYNC" : "";
        return "DETACH" + kind + exists + thisSql + cluster + permanent + sync;
    }

    @Override
    public String attachoptionSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String value = sql(expression, Args.EXPRESSION);
        value = !value.isEmpty() ? " " + value : "";
        return thisSql + value;
    }

    @Override
    public String watermarkcolumnconstraintSql(Expression expression) {
        return "WATERMARK FOR " + sql(expression, Args.THIS) + " AS " + sql(expression, Args.EXPRESSION);
    }

    @Override
    public String encodepropertySql(Expression expression) {
        String encode = expression.has(Args.KEY) ? "KEY ENCODE" : "ENCODE";
        encode = encode + " " + sql(expression, Args.THIS);

        Expression properties = expression.expr(Args.PROPERTIES);
        if (properties != null) {
            encode = encode + " " + properties(properties);
        }

        return encode;
    }

    @Override
    public String includepropertySql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String include = "INCLUDE " + thisSql;

        String columnDef = sql(expression, Args.COLUMN_DEF);
        if (!columnDef.isEmpty()) {
            include = include + " " + columnDef;
        }

        String alias = sql(expression, Args.ALIAS);
        if (!alias.isEmpty()) {
            include = include + " AS " + alias;
        }

        return include;
    }

    @Override
    public String xmlelementSql(Expression expression) {
        String prefix = expression.has(Args.EVALNAME) ? "EVALNAME" : "NAME";
        String name = prefix + " " + sql(expression, Args.THIS);
        List<Object> args = new ArrayList<>();
        args.add(name);
        args.addAll(expression.getExpressions());
        return func("XMLELEMENT", args.toArray());
    }

    @Override
    public String xmlkeyvalueoptionSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expr = sql(expression, Args.EXPRESSION);
        expr = !expr.isEmpty() ? "(" + expr + ")" : "";
        return thisSql + expr;
    }

    @Override
    public String partitionbyrangepropertySql(Expression expression) {
        String partitions = expressions(expression, Args.PARTITION_EXPRESSIONS);
        String create = expressions(expression, Args.CREATE_EXPRESSIONS);
        return "PARTITION BY RANGE " + wrap(partitions) + " " + wrap(create);
    }

    @Override
    public String partitionbyrangepropertydynamicSql(Expression expression) {
        String start = sql(expression, Args.START);
        String end = sql(expression, Args.END);

        Expression every = expression.expr(Args.EVERY);
        if (every != null && every.is(ExprType.INTERVAL) && every.getThis().isString()) {
            every.getThis().replace(Exp.number(every.name()));
        }

        return "START " + wrap(start) + " END " + wrap(end) + " EVERY " + wrap(sql(every));
    }

    @Override
    public String unpivotcolumnsSql(Expression expression) {
        String name = sql(expression, Args.THIS);
        String values = expressions(opts(expression).flat());

        return "NAME " + name + " VALUE " + values;
    }

    @Override
    public String analyzesampleSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        String sample = sql(expression, Args.SAMPLE);
        return "SAMPLE " + sample + " " + kind;
    }

    @Override
    public String analyzestatisticsSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        String option = sql(expression, Args.OPTION);
        option = !option.isEmpty() ? " " + option : "";
        String thisSql = sql(expression, Args.THIS);
        thisSql = !thisSql.isEmpty() ? " " + thisSql : "";
        String columns = expressions(expression);
        columns = !columns.isEmpty() ? " " + columns : "";
        return kind + option + " STATISTICS" + thisSql + columns;
    }

    @Override
    public String analyzehistogramSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String columns = expressions(expression);
        String innerExpression = sql(expression, Args.EXPRESSION);
        innerExpression = !innerExpression.isEmpty() ? " " + innerExpression : "";
        String updateOptions = sql(expression, Args.UPDATE_OPTIONS);
        updateOptions = !updateOptions.isEmpty() ? " " + updateOptions + " UPDATE" : "";
        return thisSql + " HISTOGRAM ON " + columns + innerExpression + updateOptions;
    }

    @Override
    public String analyzedeleteSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        kind = !kind.isEmpty() ? " " + kind : "";
        return "DELETE" + kind + " STATISTICS";
    }

    @Override
    public String analyzelistchainedrowsSql(Expression expression) {
        String innerExpression = sql(expression, Args.EXPRESSION);
        return "LIST CHAINED ROWS" + innerExpression;
    }

    @Override
    public String analyzevalidateSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        String thisSql = sql(expression, Args.THIS);
        thisSql = !thisSql.isEmpty() ? " " + thisSql : "";
        String innerExpression = sql(expression, Args.EXPRESSION);
        return "VALIDATE " + kind + thisSql + innerExpression;
    }

    @Override
    public String analyzeSql(Expression expression) {
        String options = expressions(opts(expression).key(Args.OPTIONS).sep(" "));
        options = !options.isEmpty() ? " " + options : "";
        String kind = sql(expression, Args.KIND);
        kind = !kind.isEmpty() ? " " + kind : "";
        String tables = expressions(opts(expression).key(Args.TABLES).flat());
        tables = !tables.isEmpty() ? " " + tables : "";
        String mode = sql(expression, Args.MODE);
        mode = !mode.isEmpty() ? " " + mode : "";
        String properties = sql(expression, Args.PROPERTIES);
        properties = !properties.isEmpty() ? " " + properties : "";
        String partition = sql(expression, Args.PARTITION);
        partition = !partition.isEmpty() ? " " + partition : "";
        String innerExpression = sql(expression, Args.EXPRESSION);
        innerExpression = !innerExpression.isEmpty() ? " " + innerExpression : "";
        return "ANALYZE" + options + kind + tables + partition + mode + innerExpression + properties;
    }

    @Override
    public String xmltableSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String namespaces = expressions(opts(expression).key(Args.NAMESPACES));
        namespaces = !namespaces.isEmpty() ? "XMLNAMESPACES(" + namespaces + "), " : "";
        String passing = expressions(opts(expression).key(Args.PASSING));
        passing = !passing.isEmpty() ? sep() + "PASSING" + seg(passing) : "";
        String columns = expressions(opts(expression).key(Args.COLUMNS));
        columns = !columns.isEmpty() ? sep() + "COLUMNS" + seg(columns) : "";
        String byRef = expression.has(Args.BY_REF) ? sep() + "RETURNING SEQUENCE BY REF" : "";
        return "XMLTABLE(" + sep("") + indent(namespaces + thisSql + passing + byRef + columns) + seg(")", "");
    }

    @Override
    public String xmlnamespaceSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        return expression.getThis() != null && expression.getThis().is(ExprType.ALIAS) ? thisSql : "DEFAULT " + thisSql;
    }

    @Override
    public String exportSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String connection = sql(expression, Args.CONNECTION);
        connection = !connection.isEmpty() ? "WITH CONNECTION " + connection + " " : "";
        String options = sql(expression, Args.OPTIONS);
        return "EXPORT DATA " + connection + options + " AS " + thisSql;
    }

    @Override
    public String declareSql(Expression expression) {
        String replace = expression.has(Args.REPLACE) ? "OR REPLACE " : "";
        return "DECLARE " + replace + expressions(opts(expression).flat());
    }

    @Override
    public String declareitemSql(Expression expression) {
        String variables = expressions(expression, Args.THIS);
        String default_ = sql(expression, Args.DEFAULT);
        default_ = !default_.isEmpty() ? " " + g.DECLARE_DEFAULT_ASSIGNMENT + " " + default_ : "";

        String kind = sql(expression, Args.KIND);
        if (Expression.isInstance(expression.get(Args.KIND), ExprType.SCHEMA)) {
            kind = "TABLE " + kind;
        }

        kind = !kind.isEmpty() ? " " + kind : "";

        return variables + kind + default_;
    }

    @Override
    public String recursivewithsearchSql(Expression expression) {
        String kind = sql(expression, Args.KIND);
        String thisSql = sql(expression, Args.THIS);
        String set = sql(expression, Args.EXPRESSION);
        String to = sql(expression, Args.TO);
        to = !to.isEmpty() ? " TO " + to : "";
        String default_ = sql(expression, Args.DEFAULT);
        default_ = !default_.isEmpty() ? " DEFAULT " + default_ : "";
        String using = sql(expression, Args.USING);
        using = !using.isEmpty() ? " USING " + using : "";

        String kindSql = kind.equals("CYCLE") ? kind : "SEARCH " + kind + " FIRST BY";

        return kindSql + " " + thisSql + " SET " + set + to + default_ + using;
    }

    @Override
    public String parameterizedaggSql(Expression expression) {
        String params = expressions(opts(expression).key(Args.PARAMS).flat());
        return func(expression.name(), expression.getExpressions().toArray()) + "(" + params + ")";
    }

    @Override
    public String anonymousaggfuncSql(Expression expression) {
        return func(expression.name(), expression.getExpressions().toArray());
    }

    @Override
    public String combinedaggfuncSql(Expression expression) {
        return anonymousaggfuncSql(expression);
    }

    @Override
    public String combinedparameterizedaggSql(Expression expression) {
        return parameterizedaggSql(expression);
    }

    @Override
    public String showSql(Expression expression) {
        unsupported("Unsupported SHOW statement");
        return "";
    }

    @Override
    public String installSql(Expression expression) {
        unsupported("Unsupported INSTALL statement");
        return "";
    }

    @Override
    public String liststageSql(Expression expression) {
        String pattern = sql(expression, Args.PATTERN);
        pattern = !pattern.isEmpty() ? " PATTERN = " + pattern : "";
        return "LIST " + sql(expression, Args.THIS) + pattern;
    }

    @Override
    public String getPutSql(Expression expression) {
        // Snowflake GET/PUT statements:
        //   PUT <file> <internalStage> <properties>
        //   GET <internalStage> <file> <properties>
        Expression props = expression.expr(Args.PROPERTIES);
        String propsSql = props != null ? properties(props, " ", " ", "", false) : "";
        String thisSql = sql(expression, Args.THIS);
        String target = sql(expression, Args.TARGET);

        if (expression.is(ExprType.PUT)) {
            return "PUT " + thisSql + " " + target + propsSql;
        } else {
            return "GET " + target + " " + thisSql + propsSql;
        }
    }

    @Override
    public String translatecharactersSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String expr = sql(expression, Args.EXPRESSION);
        String withError = expression.has(Args.WITH_ERROR) ? " WITH ERROR" : "";
        return "TRANSLATE(" + thisSql + " USING " + expr + withError + ")";
    }

    @Override
    public String decodecaseSql(Expression expression) {
        if (g.SUPPORTS_DECODE_CASE) {
            return func("DECODE", expression.getExpressions().toArray());
        }

        List<Expression> all = expression.getExpressions();
        Expression decodeExpr = all.get(0);
        List<Expression> exprs = all.subList(1, all.size());

        List<Expression> ifs = new ArrayList<>();
        for (int i = 0; i + 1 < exprs.size(); i += 2) {
            Expression search = exprs.get(i);
            Expression result = exprs.get(i + 1);
            if (search.is(ExprType.LITERAL)) {
                ifs.add(Expression.of(ExprType.IF, Args.THIS, decodeExpr.eq(search), Args.TRUE, result));
            } else if (search.is(ExprType.NULL)) {
                ifs.add(Expression.of(ExprType.IF, Args.THIS, isOp(decodeExpr, Expression.of(ExprType.NULL)), Args.TRUE, result));
            } else {
                if (search.is(ExprType.BINARY)) {
                    search = Exp.paren(search);
                }

                Expression cond = Exp.or(
                        decodeExpr.eq(search),
                        Exp.and(isOp(decodeExpr, Expression.of(ExprType.NULL)), isOp(search, Expression.of(ExprType.NULL))));
                ifs.add(Expression.of(ExprType.IF, Args.THIS, cond, Args.TRUE, result));
            }
        }

        Expression caseExpr = Expression.of(ExprType.CASE,
                Args.IFS, ifs,
                Args.DEFAULT, exprs.size() % 2 == 1 ? exprs.get(exprs.size() - 1) : null);
        return sql(caseExpr);
    }

    @Override
    public String semanticviewSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        thisSql = seg(thisSql, "");
        String dimensions = expressions(opts(expression).key(Args.DIMENSIONS).dynamic().skipFirst().skipLast());
        dimensions = !dimensions.isEmpty() ? seg("DIMENSIONS " + dimensions) : "";
        String metrics = expressions(opts(expression).key(Args.METRICS).dynamic().skipFirst().skipLast());
        metrics = !metrics.isEmpty() ? seg("METRICS " + metrics) : "";
        String facts = expressions(opts(expression).key(Args.FACTS).dynamic().skipFirst().skipLast());
        facts = !facts.isEmpty() ? seg("FACTS " + facts) : "";
        String where = sql(expression, Args.WHERE);
        where = !where.isEmpty() ? seg("WHERE " + where) : "";
        String body = indent(thisSql + metrics + dimensions + facts + where, 0, null, true, false);
        return "SEMANTIC_VIEW(" + body + seg(")", "");
    }

    @Override
    public String getextractSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        Expression expr = expression.getExpression();

        if (thisExpr.dataType() == null || expression.dataType() == null) {
            thisExpr = TypeAnnotator.annotateTypes(thisExpr, dialect);
        }

        if (thisExpr.isType(DType.ARRAY, DType.MAP)) {
            return sql(Expression.of(ExprType.BRACKET, Args.THIS, thisExpr, Args.EXPRESSIONS, new ArrayList<>(List.of(expr))));
        }

        return sql(Expression.of(ExprType.JSON_EXTRACT, Args.THIS, thisExpr, Args.EXPRESSION, dialect.toJsonPath(expr)));
    }

    @Override
    public String datefromunixdateSql(Expression expression) {
        return sql(
                Expression.of(ExprType.DATE_ADD,
                        Args.THIS, Exp.cast(Exp.string("1970-01-01"), DType.DATE),
                        Args.EXPRESSION, expression.getThis(),
                        Args.UNIT, Exp.var("DAY")));
    }

    @Override
    public String spaceSql(Expression expression) {
        return sql(Expression.of(ExprType.REPEAT, Args.THIS, Exp.string(" "), Args.TIMES, expression.getThis()));
    }

    @Override
    public String buildpropertySql(Expression expression) {
        return "BUILD " + sql(expression, Args.THIS);
    }

    @Override
    public String refreshtriggerpropertySql(Expression expression) {
        String method = sql(expression, Args.METHOD);
        Object kind = expression.get(Args.KIND);
        if (!Expression.truthy(kind)) {
            return "REFRESH " + method;
        }

        String every = sql(expression, Args.EVERY);
        String unit = sql(expression, Args.UNIT);
        every = !every.isEmpty() ? " EVERY " + every + " " + unit : "";
        String starts = sql(expression, Args.STARTS);
        starts = !starts.isEmpty() ? " STARTS " + starts : "";

        return "REFRESH " + method + " ON " + Py.str(kind) + every + starts;
    }

    @Override
    public String modelattributeSql(Expression expression) {
        unsupported("The model!attribute syntax is not supported");
        return "";
    }

    @Override
    public String directorystageSql(Expression expression) {
        return func("DIRECTORY", expression.getThis());
    }

    @Override
    public String uuidSql(Expression expression) {
        boolean isString = expression.has(Args.IS_STRING);
        String uuidFuncSql = func("UUID");

        if (isString && !dd.UUID_IS_STRING_TYPE) {
            return sql(Exp.cast(Exp.maybeParse(uuidFuncSql, dialect), DType.VARCHAR, true, dialect));
        }

        return uuidFuncSql;
    }

    @Override
    public String initcapSql(Expression expression) {
        Expression delimiters = expression.getExpression();

        if (delimiters != null) {
            // do not generate delimiters arg if we are round-tripping from default delimiters
            if (delimiters.isString()
                    && Objects.equals(delimiters.thisValue(), dd.INITCAP_DEFAULT_DELIMITER_CHARS)) {
                delimiters = null;
            } else if (!dd.INITCAP_SUPPORTS_CUSTOM_DELIMITERS) {
                unsupported("INITCAP does not support custom delimiters");
                delimiters = null;
            }
        }

        return func("INITCAP", expression.getThis(), delimiters);
    }

    @Override
    public String localtimeSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        return thisExpr != null ? func("LOCALTIME", thisExpr) : "LOCALTIME";
    }

    @Override
    public String localtimestampSql(Expression expression) {
        Expression thisExpr = expression.getThis();
        return thisExpr != null ? func("LOCALTIMESTAMP", thisExpr) : "LOCALTIMESTAMP";
    }

    @Override
    public String weekstartName(Expression expression) {
        // WEEK(<day>) is BigQuery-only syntax, so it degrades to the plain WEEK unit
        String thisName = Py.upper(expression.getThis().name());

        Integer dowFromWeekStartDay = WEEK_START_DAY_TO_DOW.get(thisName);
        int dowFromWeekOffset = weekOffsetToDow(dd.WEEK_OFFSET);

        if (dowFromWeekStartDay == null || dowFromWeekStartDay != dowFromWeekOffset) {
            unsupported("WEEK(" + thisName + ") is not supported; falling back to the default week start day");
        }

        return "WEEK";
    }

    @Override
    public String weekstartSql(Expression expression) {
        String name = weekstartName(expression);

        // DateTrunc stores string literal units, whereas TimeUnit expressions store keywords
        if (expression.parent() != null && expression.parent().is(ExprType.DATE_TRUNC)) {
            return sql(Exp.string(name));
        }

        return name;
    }

    @Override
    public String chrSql(Expression expression, String name) {
        String thisSql = expressions(expression);
        String charset = sql(expression, Args.CHARSET);
        String using = !charset.isEmpty() ? " USING " + charset : "";
        return func(name, thisSql + using);
    }

    @Override
    public String weightstringSql(Expression expression) {
        String to = sql(expression, Args.TO);
        to = !to.isEmpty() ? " AS " + to : "";
        return func("WEIGHT_STRING", sql(expression, Args.THIS) + to);
    }

    @Override
    public String blockSql(Expression expression) {
        String exprs = expressions(opts(expression).sep("; ").flat());
        String begin = expression.has(Args.BEGIN) ? "BEGIN " : "";
        return !exprs.isEmpty() ? begin + exprs : "";
    }

    @Override
    public String functionspecificationSql(Expression expression) {
        unsupported("Unsupported Inline UDFs syntax");
        return "";
    }

    @Override
    public String storedprocedureSql(Expression expression) {
        unsupported("Unsupported Stored Procedure syntax");
        return "";
    }

    @Override
    public String ifblockSql(Expression expression) {
        unsupported("Unsupported If block syntax");
        return "";
    }

    @Override
    public String casestatementSql(Expression expression) {
        unsupported("Unsupported Case statement syntax");
        return "";
    }

    @Override
    public String whileblockSql(Expression expression) {
        unsupported("Unsupported While block syntax");
        return "";
    }

    @Override
    public String loopblockSql(Expression expression) {
        unsupported("Unsupported Loop block syntax");
        return "";
    }

    @Override
    public String repeatblockSql(Expression expression) {
        unsupported("Unsupported Repeat block syntax");
        return "";
    }

    @Override
    public String leaveSql(Expression expression) {
        unsupported("Unsupported Leave syntax");
        return "";
    }

    @Override
    public String iterateSql(Expression expression) {
        unsupported("Unsupported Iterate syntax");
        return "";
    }

    @Override
    public String executeSql(Expression expression) {
        unsupported("Unsupported Execute syntax");
        return "";
    }

    @Override
    public String executesqlSql(Expression expression) {
        unsupported("Unsupported Execute syntax");
        return "";
    }

    @Override
    public String altermodifysqlsecuritySql(Expression expression) {
        String props = expressions(opts(expression).sep(" "));
        return "MODIFY " + props;
    }

    @Override
    public String usingpropertySql(Expression expression) {
        Object kind = expression.get(Args.KIND);
        return "USING " + Py.str(kind) + " " + sql(expression, Args.THIS);
    }

    @Override
    public String renameindexSql(Expression expression) {
        String thisSql = sql(expression, Args.THIS);
        String to = sql(expression, Args.TO);
        return "RENAME INDEX " + thisSql + " TO " + to;
    }

}
