package io.github.offbyjuan.jglot.dialects.bigquery;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.DialectHelpers;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.optimizer.TypeAnnotator;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;
import io.github.offbyjuan.jglot.tokens.Token;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.util.Py;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** BigQuery parser: a port of sqlglot's {@code sqlglot.parsers.bigquery}. */
public class BigQueryParser extends Parser {
    private static final List<String> MAKE_INTERVAL_KWARGS = List.of("year", "month", "day", "hour", "minute", "second");

    /** Placeholder; cannot occur in a SQL identifier. */
    private static final String DOMAIN_DOT = "\0";

    public BigQueryParser(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }

    // ------------------------------------------------------------------------------------------
    // Module-level helpers

    /** Sets an arg addressed by its sqlglot name (Python's {@code expr.set(name, value)}). */
    static void setByName(Expression expr, String name, Object value) {
        Arg arg = Arg.forName(name);
        if (arg != null && expr.accepts(arg)) {
            expr.set(arg, value);
        }
    }

    private static Expression buildContainsSubstring(List<Expression> args) {
        Expression this_ = Expression.of(ExprType.LOWER, Args.THIS, Py.seqGet(args, 0));
        Expression expr = Expression.of(ExprType.LOWER, Args.THIS, Py.seqGet(args, 1));
        return Expression.of(ExprType.CONTAINS, Args.THIS, this_, Args.EXPRESSION, expr,
                Args.JSON_SCOPE, Py.seqGet(args, 2));
    }

    private static Expression buildDate(List<Expression> args) {
        ExprType exprType = args.size() == 3 ? ExprType.DATE_FROM_PARTS : ExprType.DATE;
        return Exp.fromArgList(exprType, args);
    }

    private static Expression normalizeBareWeek(Expression expr) {
        // In BigQuery, a bare WEEK date part is equivalent to WEEK(SUNDAY)
        Object unit = expr.get(Args.UNIT);
        if (unit instanceof Expression u && u.is(ExprType.LITERAL, ExprType.VAR) && Py.upper(u.name()).equals("WEEK")) {
            expr.set(Args.UNIT, Expression.of(ExprType.WEEK_START, Args.THIS, Exp.var("SUNDAY")));
        }
        return expr;
    }

    private static Expression buildDateDiff(ExprType exprType, List<Expression> args) {
        return normalizeBareWeek(Expression.of(exprType,
                Args.THIS, Py.seqGet(args, 0),
                Args.EXPRESSION, Py.seqGet(args, 1),
                Args.UNIT, Py.seqGet(args, 2),
                Args.DATE_PART_BOUNDARY, Boolean.TRUE));
    }

    private static Expression buildDatetime(List<Expression> args) {
        if (args.size() == 1) {
            return Exp.fromArgList(ExprType.TS_OR_DS_TO_DATETIME, args);
        }
        if (args.size() == 2) {
            return Exp.fromArgList(ExprType.DATETIME, args);
        }
        return Exp.fromArgList(ExprType.TIMESTAMP_FROM_PARTS, args);
    }

    private Expression buildExtractJsonWithDefaultPath(ExprType exprType, List<Expression> args) {
        if (args.size() == 1) {
            args = new ArrayList<>(args);
            args.add(Exp.string("$"));
        }
        return buildExtractJsonWithPath(exprType, args);
    }

    private Expression buildFormatTime(ExprType exprType, List<Expression> args) {
        Expression formattedTime = DialectHelpers.buildFormattedTime(ExprType.TIME_TO_STR, null,
                Arrays.asList(Expression.of(exprType, Args.THIS, Py.seqGet(args, 1)), Py.seqGet(args, 0)), dialect);
        formattedTime.set(Args.ZONE, Py.seqGet(args, 2));
        return formattedTime;
    }

    private static Expression buildJsonStripNulls(List<Expression> args) {
        Expression expression = Expression.of(ExprType.JSON_STRIP_NULLS, Args.THIS, Py.seqGet(args, 0));
        for (Expression arg : Py.slice(args, 1)) {
            if (arg != null && arg.is(ExprType.KWARG)) {
                setByName(expression, Py.lower(arg.getThis().name()), arg);
            } else {
                expression.set(Args.EXPRESSION, arg);
            }
        }
        return expression;
    }

    private static Expression buildLevenshtein(List<Expression> args) {
        Expression maxDist = Py.seqGet(args, 2);
        return Expression.of(ExprType.LEVENSHTEIN,
                Args.THIS, Py.seqGet(args, 0),
                Args.EXPRESSION, Py.seqGet(args, 1),
                Args.MAX_DIST, maxDist != null ? maxDist.getExpression() : null);
    }

    private Expression buildParseDate(List<Expression> args) {
        Expression this_ = DialectHelpers.buildFormattedTime(ExprType.STR_TO_DATE, null,
                Arrays.asList(Py.seqGet(args, 1), Py.seqGet(args, 0)), dialect);
        this_.set(Args.DEFAULT_YEAR, Exp.number(1970));
        return this_;
    }

    private Expression buildParseTimestamp(List<Expression> args) {
        Expression this_ = DialectHelpers.buildFormattedTime(ExprType.STR_TO_TIME, null,
                Arrays.asList(Py.seqGet(args, 1), Py.seqGet(args, 0)), dialect);
        this_.set(Args.ZONE, Py.seqGet(args, 2));
        this_.set(Args.DEFAULT_YEAR, Exp.number(1970));
        return this_;
    }

    private Expression buildParseDatetime(List<Expression> args) {
        Expression this_ = DialectHelpers.buildFormattedTime(ExprType.PARSE_DATETIME, null,
                Arrays.asList(Py.seqGet(args, 1), Py.seqGet(args, 0)), dialect);
        this_.set(Args.DEFAULT_YEAR, Exp.number(1970));
        return this_;
    }

    private Expression buildRegexpExtract(ExprType exprType, Expression defaultGroup, List<Expression> args) {
        boolean group;
        try {
            group = regexGroupCount(args.get(1).name()) == 1;
        } catch (PatternSyntaxException | IndexOutOfBoundsException e) {
            group = false;
        }

        Expression result = Expression.of(exprType,
                Args.THIS, Py.seqGet(args, 0),
                Args.EXPRESSION, Py.seqGet(args, 1),
                Args.POSITION, Py.seqGet(args, 2),
                Args.OCCURRENCE, Py.seqGet(args, 3),
                Args.GROUP, group ? Exp.number(1) : defaultGroup);
        if (exprType == ExprType.REGEXP_EXTRACT) {
            result.set(Args.NULL_IF_POS_OVERFLOW, dd.REGEXP_EXTRACT_POSITION_OVERFLOW_RETURNS_NULL);
        }
        return result;
    }

    /** Python's {@code re.compile(pattern).groups}: the number of capturing groups. */
    private static int regexGroupCount(String pattern) {
        // Python's named group syntax (?P<name>...) isn't valid in Java
        String javaPattern = pattern.replace("(?P<", "(?<");
        return Pattern.compile(javaPattern).matcher("").groupCount();
    }

    private static Expression buildTime(List<Expression> args) {
        if (args.size() == 1) {
            return Expression.of(ExprType.TS_OR_DS_TO_TIME, Args.THIS, args.get(0));
        }
        if (args.size() == 2) {
            return Exp.fromArgList(ExprType.TIME, args);
        }
        return Exp.fromArgList(ExprType.TIME_FROM_PARTS, args);
    }

    private static Expression buildTimestamp(List<Expression> args) {
        Expression timestamp = Exp.fromArgList(ExprType.TIMESTAMP, args);
        timestamp.set(Args.WITH_TZ, Boolean.TRUE);
        return timestamp;
    }

    private static Expression buildToHex(List<Expression> args) {
        Expression arg = Py.seqGet(args, 0);
        return arg != null && arg.is(ExprType.MD5_DIGEST)
                ? Expression.of(ExprType.MD5, Args.THIS, arg.getThis())
                : Expression.of(ExprType.LOWER_HEX, Args.THIS, arg);
    }

    /** sqlglot's {@code split_num_words(value, sep, min_num_words)} (fill_from_start=True). */
    private static List<String> splitNumWords(String value, String sep, int minNumWords) {
        List<String> words = new ArrayList<>(Arrays.asList(value.split(Pattern.quote(sep), -1)));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < minNumWords - words.size(); i++) {
            out.add(null);
        }
        out.addAll(words);
        return out;
    }

    static List<String> splitQualifiedName(String name, int minNumWords) {
        // A dotted reference (e.g. `project.dataset.table`) is split into a fixed number of parts,
        // the first of which is the project. Domain-scoped (legacy) project IDs have the form
        // `domain.com:project-id`, where the dots belong to the domain, not the path - and a project
        // ID itself can't contain dots, so every such dot precedes the colon. Mask those, then let
        // `split_num_words` split and pad as usual, to avoid corrupting the project ID.
        int colon = name.indexOf(':');
        if (colon != -1 && name.substring(0, colon).contains(".")) {
            name = name.substring(0, colon).replace(".", DOMAIN_DOT) + name.substring(colon);
            List<String> out = new ArrayList<>();
            for (String p : splitNumWords(name, ".", minNumWords)) {
                out.add(p != null && !p.isEmpty() ? p.replace(DOMAIN_DOT, ".") : p);
            }
            return out;
        }
        return splitNumWords(name, ".", minNumWords);
    }

    private static String joinPartNames(List<Expression> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(parts.get(i).name());
        }
        return sb.toString();
    }

    private static List<Expression> quotedIdentifiers(List<String> names) {
        List<Expression> out = new ArrayList<>();
        for (String p : names) {
            out.add(p == null ? null : Exp.toIdentifier(p, Boolean.TRUE, true));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // FUNCTIONS

    @Override
    protected Expression buildFunction(String name, List<Expression> args) {
        return switch (name) {
            case "CONTAINS_SUBSTR" -> buildContainsSubstring(args);
            case "DATE" -> buildDate(args);
            case "DATE_ADD" -> DialectHelpers.buildDateDeltaWithInterval(ExprType.DATE_ADD, args, dialect);
            case "DATE_DIFF" -> buildDateDiff(ExprType.DATE_DIFF, args);
            case "DATE_SUB" -> DialectHelpers.buildDateDeltaWithInterval(ExprType.DATE_SUB, args, dialect);
            case "DATE_TRUNC" -> normalizeBareWeek(Expression.of(ExprType.DATE_TRUNC,
                    Args.UNIT, Py.seqGet(args, 1),
                    Args.THIS, Py.seqGet(args, 0),
                    Args.ZONE, Py.seqGet(args, 2)));
            case "DATETIME" -> buildDatetime(args);
            case "DATETIME_ADD" -> DialectHelpers.buildDateDeltaWithInterval(ExprType.DATETIME_ADD, args, dialect);
            case "DATETIME_DIFF" -> buildDateDiff(ExprType.DATETIME_DIFF, args);
            case "DATETIME_SUB" -> DialectHelpers.buildDateDeltaWithInterval(ExprType.DATETIME_SUB, args, dialect);
            case "DATETIME_TRUNC" -> normalizeBareWeek(Exp.fromArgList(ExprType.DATETIME_TRUNC, args));
            case "DIV" -> DialectHelpers.binaryFromFunction(ExprType.INT_DIV, args, dialect);
            case "EDIT_DISTANCE" -> buildLevenshtein(args);
            case "FORMAT_DATE" -> buildFormatTime(ExprType.TS_OR_DS_TO_DATE, args);
            case "JSON_EXTRACT_SCALAR" -> buildExtractJsonWithDefaultPath(ExprType.JSON_EXTRACT_SCALAR, args);
            case "JSON_EXTRACT_ARRAY" -> buildExtractJsonWithDefaultPath(ExprType.JSON_EXTRACT_ARRAY, args);
            case "JSON_EXTRACT_STRING_ARRAY" -> buildExtractJsonWithDefaultPath(ExprType.JSON_VALUE_ARRAY, args);
            case "JSON_QUERY" -> buildExtractJsonWithPath(ExprType.JSON_EXTRACT, args);
            case "JSON_QUERY_ARRAY" -> buildExtractJsonWithDefaultPath(ExprType.JSON_EXTRACT_ARRAY, args);
            case "JSON_STRIP_NULLS" -> buildJsonStripNulls(args);
            case "JSON_VALUE" -> buildExtractJsonWithDefaultPath(ExprType.JSON_EXTRACT_SCALAR, args);
            case "JSON_VALUE_ARRAY" -> buildExtractJsonWithDefaultPath(ExprType.JSON_VALUE_ARRAY, args);
            case "LAST_DAY" -> normalizeBareWeek(Exp.fromArgList(ExprType.LAST_DAY, args));
            case "LENGTH" -> Expression.of(ExprType.LENGTH, Args.THIS, Py.seqGet(args, 0), Args.BINARY, Boolean.TRUE);
            case "NORMALIZE_AND_CASEFOLD" -> Expression.of(ExprType.NORMALIZE,
                    Args.THIS, Py.seqGet(args, 0), Args.FORM, Py.seqGet(args, 1), Args.IS_CASEFOLD, Boolean.TRUE);
            case "TO_HEX" -> buildToHex(args);
            case "PARSE_DATE" -> buildParseDate(args);
            case "PARSE_TIME" -> DialectHelpers.buildFormattedTime(ExprType.PARSE_TIME, null,
                    Arrays.asList(Py.seqGet(args, 1), Py.seqGet(args, 0)), dialect);
            case "PARSE_TIMESTAMP" -> buildParseTimestamp(args);
            case "PARSE_DATETIME" -> buildParseDatetime(args);
            case "REGEXP_EXTRACT", "REGEXP_SUBSTR" -> buildRegexpExtract(ExprType.REGEXP_EXTRACT, null, args);
            case "REGEXP_EXTRACT_ALL" -> buildRegexpExtract(ExprType.REGEXP_EXTRACT_ALL, Exp.number(0), args);
            case "SHA256" -> Expression.of(ExprType.SHA2_DIGEST, Args.THIS, Py.seqGet(args, 0), Args.LENGTH, Exp.number(256));
            case "SHA512" -> Expression.of(ExprType.SHA2_DIGEST, Args.THIS, Py.seqGet(args, 0), Args.LENGTH, Exp.number(512));
            case "SPLIT" -> {
                // https://cloud.google.com/bigquery/docs/reference/standard-sql/string_functions#split
                Expression sep = Py.seqGet(args, 1);
                yield Expression.of(ExprType.SPLIT,
                        Args.THIS, Py.seqGet(args, 0),
                        Args.EXPRESSION, sep != null ? sep : Exp.string(","));
            }
            case "TIME" -> buildTime(args);
            case "TIME_ADD" -> DialectHelpers.buildDateDeltaWithInterval(ExprType.TIME_ADD, args, dialect);
            case "TIME_SUB" -> DialectHelpers.buildDateDeltaWithInterval(ExprType.TIME_SUB, args, dialect);
            case "TIMESTAMP" -> buildTimestamp(args);
            case "TIMESTAMP_ADD" -> DialectHelpers.buildDateDeltaWithInterval(ExprType.TIMESTAMP_ADD, args, dialect);
            case "TIMESTAMP_SUB" -> DialectHelpers.buildDateDeltaWithInterval(ExprType.TIMESTAMP_SUB, args, dialect);
            case "TIMESTAMP_MICROS" -> Expression.of(ExprType.UNIX_TO_TIME,
                    Args.THIS, Py.seqGet(args, 0), Args.SCALE, Exp.number(6));
            case "TIMESTAMP_MILLIS" -> Expression.of(ExprType.UNIX_TO_TIME,
                    Args.THIS, Py.seqGet(args, 0), Args.SCALE, Exp.number(3));
            case "TIMESTAMP_SECONDS" -> Expression.of(ExprType.UNIX_TO_TIME, Args.THIS, Py.seqGet(args, 0));
            case "TIMESTAMP_TRUNC" -> normalizeBareWeek(Exp.fromArgList(ExprType.TIMESTAMP_TRUNC, args));
            case "TO_JSON" -> Expression.of(ExprType.JSON_FORMAT,
                    Args.THIS, Py.seqGet(args, 0), Args.OPTIONS, Py.seqGet(args, 1), Args.TO_JSON, Boolean.TRUE);
            case "FORMAT_DATETIME" -> buildFormatTime(ExprType.TS_OR_DS_TO_DATETIME, args);
            case "FORMAT_TIMESTAMP" -> buildFormatTime(ExprType.TS_OR_DS_TO_TIMESTAMP, args);
            case "FORMAT_TIME" -> buildFormatTime(ExprType.TS_OR_DS_TO_TIME, args);
            case "WEEK" -> {
                Expression arg = Py.seqGet(args, 0);
                if (arg == null || arg.name().isEmpty()) {
                    throw new IllegalArgumentException("Cannot convert empty name into var.");
                }
                yield Expression.of(ExprType.WEEK_START, Args.THIS, Exp.var(arg.name()));
            }
            default -> super.buildFunction(name, args);
        };
    }

    // ------------------------------------------------------------------------------------------
    // FUNCTION_PARSERS

    @Override
    protected Expression functionParser(String key) {
        return switch (key) {
            case "ARRAY" -> {
                List<Expression> exprs = new ArrayList<>();
                exprs.add(parseStatement());
                yield expression(Expression.of(ExprType.ARRAY,
                        Args.EXPRESSIONS, exprs, Args.STRUCT_NAME_INHERITANCE, Boolean.TRUE));
            }
            case "JSON_ARRAY" -> expression(Expression.of(ExprType.JSON_ARRAY,
                    Args.EXPRESSIONS, parseCsv(this::parseBitwise)));
            case "MAKE_INTERVAL" -> parseMakeInterval();
            case "PREDICT" -> parseMl(ExprType.PREDICT, false);
            case "TRANSLATE" -> parseTranslate();
            case "FEATURES_AT_TIME" -> parseFeaturesAtTime();
            case "GENERATE_EMBEDDING" -> parseMl(ExprType.GENERATE_EMBEDDING, false);
            case "GENERATE_TEXT_EMBEDDING" -> parseMl(ExprType.GENERATE_EMBEDDING, true);
            case "GENERATE_TEXT" -> parseGenerate(ExprType.GENERATE_TEXT);
            case "GENERATE_TABLE" -> parseGenerate(ExprType.GENERATE_TABLE);
            case "GENERATE_BOOL" -> parseGenerate(ExprType.GENERATE_BOOL);
            case "GENERATE_INT" -> parseGenerate(ExprType.GENERATE_INT);
            case "GENERATE_DOUBLE" -> parseGenerate(ExprType.GENERATE_DOUBLE);
            case "VECTOR_SEARCH" -> parseVectorSearch();
            case "FORECAST" -> parseForecast();
            default -> super.functionParser(key);
        };
    }

    // ------------------------------------------------------------------------------------------
    // PROPERTY_PARSERS / CONSTRAINT_PARSERS

    @Override
    protected Object propertyParser(String key, Map<String, Object> kwargs) {
        if (key.equals("OPTIONS")) {
            return parseWithProperty();
        }
        return super.propertyParser(key, kwargs);
    }

    @Override
    protected Expression constraintParser(String key) {
        if (key.equals("OPTIONS")) {
            return Expression.of(ExprType.PROPERTIES, Args.EXPRESSIONS, parseWithProperty());
        }
        return super.constraintParser(key);
    }

    // ------------------------------------------------------------------------------------------
    // STATEMENT_PARSERS

    @Override
    protected Expression statementParser(TokenType t) {
        return switch (t) {
            case ELSE, END -> parseAsCommand(prev);
            case FOR -> parseForIn();
            case EXPORT -> parseExportData();
            default -> super.statementParser(t);
        };
    }

    protected Expression parseForIn() {
        int index = tokenIndex;
        Expression this_ = parseRange(null);
        matchTextSeq("DO");
        if (match(TokenType.COMMAND)) {
            retreat(index);
            return parseAsCommand(prev);
        }
        return expression(Expression.of(ExprType.FOR_IN, Args.THIS, this_, Args.EXPRESSION, parseStatement()));
    }

    // ------------------------------------------------------------------------------------------
    // Overridden _parse_* methods

    @Override
    protected Expression parseTablePart(boolean schema) {
        Expression this_ = super.parseTablePart(schema);
        if (this_ == null) {
            this_ = parseNumber();
        }

        // https://cloud.google.com/bigquery/docs/reference/standard-sql/lexical#table_names
        if (this_ != null && this_.is(ExprType.IDENTIFIER)) {
            String tableName = this_.name();
            while (match(TokenType.DASH, false) && present(next)) {
                Token start = curr;
                while (isConnected() && !matchSet(d.DASHED_TABLE_PART_FOLLOW_TOKENS, false)) {
                    advance();
                }

                if (start == curr) {
                    break;
                }

                tableName += findSql(start, prev);
            }

            this_ = Expression.of(ExprType.IDENTIFIER, Args.THIS, tableName, Args.QUOTED, this_.get(Args.QUOTED))
                    .updatePositions(this_);
        } else if (this_ != null && this_.is(ExprType.LITERAL)) {
            String tableName = this_.name();

            if (isConnected() && parseVar(true, null, false) != null) {
                tableName += prev.text();
            }

            this_ = Expression.of(ExprType.IDENTIFIER, Args.THIS, tableName, Args.QUOTED, Boolean.TRUE)
                    .updatePositions(this_);
        }

        return this_;
    }

    @Override
    protected Expression parseTableParts(boolean schema, boolean isDbReference, boolean wildcard, boolean fast) {
        Expression table = super.parseTableParts(schema, isDbReference, true, fast);

        if (table == null || !table.is(ExprType.TABLE)) {
            return table;
        }

        // proj-1.db.tbl -- `1.` is tokenized as a float so we need to unravel it here
        if (table.text(Args.CATALOG).isEmpty()) {
            if (!table.text(Args.DB).isEmpty()) {
                Expression previousDb = table.expr(Args.DB);
                String[] parts = table.text(Args.DB).split(Pattern.quote("."), -1);
                if (parts.length == 2 && !previousDb.flag(Args.QUOTED)) {
                    table.set(Args.CATALOG, Expression.of(ExprType.IDENTIFIER, Args.THIS, parts[0]).updatePositions(previousDb));
                    table.set(Args.DB, Expression.of(ExprType.IDENTIFIER, Args.THIS, parts[1]).updatePositions(previousDb));
                }
            } else {
                Expression previousThis = table.getThis();
                String[] parts = table.name().split(Pattern.quote("."), -1);
                if (parts.length == 2 && !previousThis.flag(Args.QUOTED)) {
                    table.set(Args.DB, Expression.of(ExprType.IDENTIFIER, Args.THIS, parts[0]).updatePositions(previousThis));
                    table.set(Args.THIS, Expression.of(ExprType.IDENTIFIER, Args.THIS, parts[1]).updatePositions(previousThis));
                }
            }
        }

        Expression alias;
        boolean anyDotted = false;
        for (Expression p : table.parts()) {
            if (p.name().contains(".")) {
                anyDotted = true;
                break;
            }
        }
        Expression tableThis = table.getThis();
        if (tableThis != null && tableThis.is(ExprType.IDENTIFIER) && anyDotted) {
            alias = tableThis;
            List<Expression> ids = quotedIdentifiers(splitQualifiedName(joinPartNames(table.parts()), 3));
            Expression catalog = ids.get(0);
            Expression db = ids.get(1);
            Expression thisId = ids.get(2);
            List<Expression> rest = ids.subList(3, ids.size());

            for (Expression part : Arrays.asList(catalog, db, thisId)) {
                if (part != null) {
                    part.updatePositions(tableThis);
                }
            }

            Expression this_ = thisId;
            if (!rest.isEmpty() && this_ != null) {
                List<Expression> dotParts = new ArrayList<>();
                dotParts.add(this_);
                dotParts.addAll(rest);
                this_ = Exp.dot(dotParts);
            }

            table = Expression.of(ExprType.TABLE, Args.THIS, this_, Args.DB, db, Args.CATALOG, catalog,
                    Args.PIVOTS, table.get(Args.PIVOTS));
            table.meta().put("quoted_table", Boolean.TRUE);
        } else {
            alias = null;
        }

        // The `INFORMATION_SCHEMA` views in BigQuery need to be qualified by a region or
        // dataset, so if the project identifier is omitted we need to fix the ast so that
        // the `INFORMATION_SCHEMA.X` bit is represented as a single (quoted) Identifier.
        // Otherwise, we wouldn't correctly qualify a `Table` node that references these
        // views, because it would seem like the "catalog" part is set, when it'd actually
        // be the region/dataset. Merging the two identifiers into a single one is done to
        // avoid producing a 4-part Table reference, which would cause issues in the schema
        // module, when there are 3-part table names mixed with information schema views.
        //
        // See: https://cloud.google.com/bigquery/docs/information-schema-intro#syntax
        List<Expression> tableParts = table.parts();
        int n = tableParts.size();
        if (n > 1 && Py.upper(tableParts.get(n - 2).name()).equals("INFORMATION_SCHEMA")) {
            // We need to alias the table here to avoid breaking existing qualified columns.
            // This is expected to be safe, because if there's an actual alias coming up in
            // the token stream, it will overwrite this one. If there isn't one, we are only
            // exposing the name that can be used to reference the view explicitly (a no-op).
            Exp.tableAlias(table, alias != null ? alias : tableParts.get(n - 1), null, false);

            String infoSchemaView = tableParts.get(n - 2).name() + "." + tableParts.get(n - 1).name();
            Expression newThis = Expression.of(ExprType.IDENTIFIER, Args.THIS, infoSchemaView, Args.QUOTED, Boolean.TRUE);
            Map<String, Object> m = newThis.meta();
            putIfNotNull(m, "line", tableParts.get(n - 2).metaGet("line"));
            putIfNotNull(m, "col", tableParts.get(n - 1).metaGet("col"));
            putIfNotNull(m, "start", tableParts.get(n - 2).metaGet("start"));
            putIfNotNull(m, "end", tableParts.get(n - 1).metaGet("end"));
            table.set(Args.THIS, newThis);
            table.set(Args.DB, Py.seqGet(tableParts, -3));
            table.set(Args.CATALOG, Py.seqGet(tableParts, -4));
        }

        return table;
    }

    private static void putIfNotNull(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    @Override
    protected Expression parseColumn() {
        Expression column = super.parseColumn();
        if (column != null && column.is(ExprType.COLUMN)) {
            List<Expression> parts = column.parts();
            boolean anyDotted = false;
            for (Expression p : parts) {
                if (p.name().contains(".")) {
                    anyDotted = true;
                    break;
                }
            }
            if (anyDotted) {
                List<Expression> ids = quotedIdentifiers(splitQualifiedName(joinPartNames(parts), 4));
                Expression catalog = ids.get(0);
                Expression db = ids.get(1);
                Expression table = ids.get(2);
                Expression thisId = ids.get(3);
                List<Expression> rest = ids.subList(4, ids.size());

                Expression this_ = thisId;
                if (!rest.isEmpty() && this_ != null) {
                    List<Expression> dotParts = new ArrayList<>();
                    dotParts.add(this_);
                    dotParts.addAll(rest);
                    this_ = Exp.dot(dotParts);
                }

                column = Expression.of(ExprType.COLUMN, Args.THIS, this_, Args.TABLE, table, Args.DB, db,
                        Args.CATALOG, catalog);
                column.meta().put("quoted_column", Boolean.TRUE);
            }
        }
        return column;
    }

    @Override
    protected Expression parseClusterProperty() {
        return expression(Expression.of(ExprType.CLUSTER_PROPERTY, Args.EXPRESSIONS, parseCsv(this::parseColumn)));
    }

    @Override
    protected Object parseJsonObject(Object agg) {
        Expression jsonObject = (Expression) super.parseJsonObject(Boolean.FALSE);
        Expression arrayKvPair = Py.seqGet(jsonObject.getExpressions(), 0);

        // Converts BQ's "signature 2" of JSON_OBJECT into SQLGlot's canonical representation
        // https://cloud.google.com/bigquery/docs/reference/standard-sql/json_functions#json_object_signature2
        if (arrayKvPair != null
                && Expression.isInstance(arrayKvPair.get(Args.THIS), ExprType.ARRAY)
                && Expression.isInstance(arrayKvPair.get(Args.EXPRESSION), ExprType.ARRAY)) {
            List<Expression> keys = arrayKvPair.getThis().getExpressions();
            List<Expression> values = arrayKvPair.getExpression().getExpressions();

            List<Expression> kvs = new ArrayList<>();
            for (int i = 0; i < Math.min(keys.size(), values.size()); i++) {
                kvs.add(Expression.of(ExprType.JSON_KEY_VALUE, Args.THIS, keys.get(i), Args.EXPRESSION, values.get(i)));
            }
            jsonObject.set(Args.EXPRESSIONS, kvs);
        }

        return jsonObject;
    }

    @Override
    protected Expression parseBracket(Expression this_) {
        Expression bracket = super.parseBracket(this_);

        if (bracket != null && bracket.is(ExprType.ARRAY)) {
            bracket.set(Args.STRUCT_NAME_INHERITANCE, Boolean.TRUE);
        }

        if (this_ == bracket) {
            return bracket;
        }

        if (bracket != null && bracket.is(ExprType.BRACKET)) {
            for (Expression expression : new ArrayList<>(bracket.getExpressions())) {
                String name = Py.upper(expression.name());

                List<Expression> expressions = expression.getExpressions();

                if (!d.BRACKET_OFFSETS.containsKey(name) || expressions.isEmpty()) {
                    break;
                }

                List<String> offsetSafe = d.BRACKET_OFFSETS.get(name);
                bracket.set(Args.OFFSET, Integer.parseInt(offsetSafe.get(0)));
                bracket.set(Args.SAFE, "True".equals(offsetSafe.get(1)));
                expression.replace(expressions.get(0));
            }
        }

        return bracket;
    }

    @Override
    protected Expression parseUnnest(boolean withAlias) {
        Expression unnest = super.parseUnnest(withAlias);

        if (unnest == null) {
            return null;
        }

        Expression unnestExpr = Py.seqGet(unnest.getExpressions(), 0);
        if (unnestExpr != null) {
            unnestExpr = TypeAnnotator.annotateTypes(unnestExpr, dialect);

            // Unnesting a nested array (i.e array of structs) explodes the top-level struct fields,
            // in contrast to other dialects such as DuckDB which flattens only the array by default
            if (unnestExpr != null && unnestExpr.isType(DType.ARRAY)) {
                Expression dt = unnestExpr.dataType();
                boolean anyStruct = false;
                if (dt != null) {
                    for (Expression arrayElem : dt.getExpressions()) {
                        if (arrayElem.isType(DType.STRUCT)) {
                            anyStruct = true;
                            break;
                        }
                    }
                }
                if (anyStruct) {
                    unnest.set(Args.EXPLODE_ARRAY, Boolean.TRUE);
                }
            }
        }

        return unnest;
    }

    @Override
    protected Expression parseColumnOps(Expression this_) {
        int funcIndex = tokenIndex + 1;
        this_ = super.parseColumnOps(this_);

        if (this_ != null && this_.is(ExprType.DOT) && Expression.isInstance(this_.get(Args.EXPRESSION), ExprType.FUNC)) {
            String prefix = Py.upper(this_.getThis().name());

            ExprType func = null;
            if (prefix.equals("NET")) {
                func = ExprType.NET_FUNC;
            } else if (prefix.equals("SAFE")) {
                func = ExprType.SAFE_FUNC;
            }

            if (func != null) {
                // Retreat to try and parse a known function instead of an anonymous one,
                // which is parsed by the base column ops parser due to anonymous_func=true
                retreat(funcIndex);
                this_ = Expression.of(func, Args.THIS, parseFunction(null, false, true, true));
            } else if (prefix.equals("AI") || prefix.equals("ML")) {
                // AI.* and ML.* function calls can use custom BigQuery signatures that rely on
                // function parsers, so re-parse the function in non-anonymous mode.
                retreat(funcIndex);
                Expression parsed = parseFunction(null, false, true, true);
                if (parsed != null) {
                    this_ = expression(Expression.of(ExprType.DOT, Args.THIS, this_.getThis(), Args.EXPRESSION, parsed));
                }
            }
        }

        return this_;
    }

    // ------------------------------------------------------------------------------------------
    // New _parse_* methods

    protected Expression parseMakeInterval() {
        Expression expr = Expression.of(ExprType.MAKE_INTERVAL);

        for (String argKey : MAKE_INTERVAL_KWARGS) {
            Expression value = parseLambda(false);

            if (value == null) {
                break;
            }

            // Non-named arguments are filled sequentially, (optionally) followed by named arguments
            // that can appear in any order e.g MAKE_INTERVAL(1, minute => 5, day => 2)
            String key = argKey;
            if (value.is(ExprType.KWARG)) {
                key = value.getThis().name();
            }

            setByName(expr, key, value);

            match(TokenType.COMMA);
        }

        return expr;
    }

    protected Expression parseMl(ExprType exprType, boolean isText) {
        matchTextSeq("MODEL");
        Expression this_ = parseTable();

        match(TokenType.COMMA);
        matchTextSeq("TABLE");

        // Certain functions like ML.FORECAST require a STRUCT argument but not a TABLE/SELECT one
        Expression expression = !match(TokenType.STRUCT, false) ? parseTable() : null;

        match(TokenType.COMMA);

        return expression(Expression.of(exprType,
                Args.THIS, this_,
                Args.EXPRESSION, expression,
                Args.PARAMS_STRUCT, parseBitwise(),
                Args.IS_TEXT, isText ? Boolean.TRUE : null));
    }

    protected Expression parseGenerate(ExprType exprType) {
        matchTextSeq("MODEL");
        Expression this_ = parseTable();

        match(TokenType.COMMA);

        Expression expression;
        if (matchTextSeq("TABLE")) {
            expression = parseTable();
        } else if (match(TokenType.L_PAREN, false)) {
            expression = parseTable();
        } else {
            expression = parseBitwise();
        }

        Object paramsStruct = match(TokenType.COMMA) ? parseBitwise() : Boolean.FALSE;

        return expression(Expression.of(exprType,
                Args.THIS, this_, Args.EXPRESSION, expression, Args.PARAMS_STRUCT, paramsStruct));
    }

    protected Expression parseTranslate() {
        // Check if this is ML.TRANSLATE by looking at previous tokens
        Token token = Py.seqGet(tokenList, tokenIndex - 4);
        if (token != null && Py.upper(token.text()).equals("ML")) {
            return parseMl(ExprType.ML_TRANSLATE, false);
        }

        return Exp.fromArgList(ExprType.TRANSLATE, parseFunctionArgs(false));
    }

    protected Expression parseForecast() {
        // Check if this is ML.FORECAST by looking at previous tokens.
        Token token = Py.seqGet(tokenList, tokenIndex - 4);
        if (token != null && Py.upper(token.text()).equals("ML")) {
            return parseMl(ExprType.ML_FORECAST, false);
        }

        // AI.FORECAST is a TVF, where the first argument is either TABLE <table>
        // or a parenthesized query statement, followed by named arguments.
        match(TokenType.TABLE);
        Expression this_ = parseTable();
        if (this_ == null) {
            raiseError("Expected table or query statement");
        }

        Expression expr = expression(Expression.of(ExprType.AI_FORECAST, Args.THIS, this_));
        while (match(TokenType.COMMA)) {
            Expression arg = parseLambda(false);
            if (arg != null && arg.is(ExprType.KWARG)) {
                setByName(expr, arg.getThis().name(), arg);
            } else {
                raiseError("Expected key => value syntax for AI.FORECAST, got " + arg);
                break;
            }
        }

        return expr;
    }

    protected Expression parseFeaturesAtTime() {
        match(TokenType.TABLE);
        Expression this_ = parseTable();

        Expression expr = expression(Expression.of(ExprType.FEATURES_AT_TIME, Args.THIS, this_));

        while (match(TokenType.COMMA)) {
            Expression arg = parseLambda(false);

            // Get the LHS of the Kwarg and set the arg to that value, e.g
            // "num_rows => 1" sets the expr's `num_rows` arg
            if (arg != null) {
                setByName(expr, arg.getThis().name(), arg);
            }
        }

        return expr;
    }

    protected Expression parseVectorSearch() {
        match(TokenType.TABLE);
        Expression baseTable = parseTable();

        match(TokenType.COMMA);

        Expression columnToSearch = parseBitwise();
        match(TokenType.COMMA);

        match(TokenType.TABLE);
        Expression queryTable = parseTable();

        Expression expr = expression(Expression.of(ExprType.VECTOR_SEARCH,
                Args.THIS, baseTable, Args.COLUMN_TO_SEARCH, columnToSearch, Args.QUERY_TABLE, queryTable));

        while (match(TokenType.COMMA)) {
            // query_column_to_search can be named argument or positional
            if (match(TokenType.STRING, false)) {
                Expression queryColumn = parseString();
                expr.set(Args.QUERY_COLUMN_TO_SEARCH, queryColumn);
            } else {
                Expression arg = parseLambda(false);
                if (arg != null) {
                    setByName(expr, arg.getThis().name(), arg);
                }
            }
        }

        return expr;
    }

    protected Expression parseExportData() {
        matchTextSeq("DATA");

        Object connection = matchTextSeq("WITH", "CONNECTION") ? parseTableParts() : Boolean.FALSE;
        Expression options = parseProperties(null);
        Object this_ = matchTextSeq("AS") ? parseSelect(true) : Boolean.FALSE;
        return expression(Expression.of(ExprType.EXPORT,
                Args.CONNECTION, connection,
                Args.OPTIONS, options,
                Args.THIS, this_));
    }
}
