package io.github.offbyjuan.jglot.generator;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.transforms.Transforms;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Generator part A: the base {@code Generator.TRANSFORMS} table (including jsonpath's
 * {@code JSON_PATH_PART_TRANSFORMS}), {@code AFTER_HAVING_MODIFIER_TRANSFORMS}, preprocessing and
 * the non-data class/module attributes of sqlglot's generator.
 */
abstract class GeneratorPartA extends GeneratorMethods {
    GeneratorPartA(Dialect dialect, GeneratorOptions options) {
        super(dialect, options);
    }

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

    /**
     * Key order of sqlglot's {@code AFTER_HAVING_MODIFIER_TRANSFORMS} dicts. The generated
     * {@code g.AFTER_HAVING_MODIFIER_TRANSFORMS} is an unordered key set; the base Generator's dict
     * is cluster, distribute, sort, windows, qualify and the module-level dict (used by MySQL,
     * ClickHouse, ...) is windows, qualify. Both are this order filtered by the key set.
     */
    protected static final List<String> AFTER_HAVING_MODIFIER_ORDER = List.of("cluster", "distribute", "sort", "windows", "qualify");

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
        List<String> out = new ArrayList<>(5);
        for (String key : AFTER_HAVING_MODIFIER_ORDER) {
            if (g.AFTER_HAVING_MODIFIER_TRANSFORMS.contains(key)) {
                out.add(afterHavingModifierTransform(key, e));
            }
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
            case JSON_PATH_FILTER -> "?" + pyStr(e.thisValue());
            case JSON_PATH_KEY -> jsonpathkeySql(e);
            case JSON_PATH_RECURSIVE -> ".." + (Expression.truthy(e.thisValue()) ? pyStr(e.thisValue()) : "");
            case JSON_PATH_ROOT -> "$";
            case JSON_PATH_SCRIPT -> "(" + pyStr(e.thisValue());
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

    /** Python's {@code str(v)} in an f-string, for the raw (string) {@code this} of JSON path parts. */
    private String pyStr(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Expression x) {
            return x.sql(dialect);
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        return v.toString();
    }
}
