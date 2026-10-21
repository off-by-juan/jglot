package io.github.offbyjuan.jglot.dialects.presto;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.DialectHelpers;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.parser.Parser;
import io.github.offbyjuan.jglot.parser.ParserOptions;
import io.github.offbyjuan.jglot.util.Py;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.util.TimeFormat;
import io.github.offbyjuan.jglot.util.Trie;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Presto parser: a port of sqlglot's {@code sqlglot.parsers.presto}. */
public class PrestoParser extends Parser {
    public PrestoParser(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }

    static Expression buildApproxPercentile(List<Expression> args) {
        if (args.size() == 4) {
            return Expression.of(ExprType.APPROX_QUANTILE,
                    Args.THIS, Py.seqGet(args, 0),
                    Args.WEIGHT, Py.seqGet(args, 1),
                    Args.QUANTILE, Py.seqGet(args, 2),
                    Args.ACCURACY, Py.seqGet(args, 3));
        }
        if (args.size() == 3) {
            return Expression.of(ExprType.APPROX_QUANTILE,
                    Args.THIS, Py.seqGet(args, 0),
                    Args.QUANTILE, Py.seqGet(args, 1),
                    Args.ACCURACY, Py.seqGet(args, 2));
        }
        return Exp.fromArgList(ExprType.APPROX_QUANTILE, args);
    }

    static Expression buildFromUnixtime(List<Expression> args) {
        if (args.size() == 3) {
            return Expression.of(ExprType.UNIX_TO_TIME,
                    Args.THIS, Py.seqGet(args, 0),
                    Args.HOURS, Py.seqGet(args, 1),
                    Args.MINUTES, Py.seqGet(args, 2));
        }
        if (args.size() == 2) {
            return Expression.of(ExprType.UNIX_TO_TIME, Args.THIS, Py.seqGet(args, 0), Args.ZONE, Py.seqGet(args, 1));
        }
        return Exp.fromArgList(ExprType.UNIX_TO_TIME, args);
    }

    static Expression buildToChar(List<Expression> args, Dialect dialect) {
        Expression fmt = Py.seqGet(args, 1);
        if (fmt != null && fmt.is(ExprType.LITERAL)) {
            // We uppercase this to match Teradata's format mapping keys
            fmt.set(Args.THIS, Py.upper((String) fmt.thisValue()));
        }

        // We use "teradata" on purpose here, because the time formats are different in Presto.
        // See https://prestodb.io/docs/current/functions/teradata.html?highlight=to_char#to_char
        if (Dialects.find("teradata") != null) {
            return DialectHelpers.buildFormattedTime(ExprType.TIME_TO_STR, "teradata", args, dialect);
        }

        // jglot has no Teradata dialect yet: inline build_formatted_time(exp.TimeToStr, "teradata")
        // using Teradata's TIME_MAPPING.
        Expression format = Py.seqGet(args, 1);
        if (format != null && format.isString()) {
            format = Exp.string(TimeFormat.formatTime((String) format.thisValue(), TERADATA_TIME_MAPPING, TERADATA_TIME_TRIE));
        }
        return Expression.of(ExprType.TIME_TO_STR, Args.THIS, Py.seqGet(args, 0), Args.FORMAT, format);
    }

    /** sqlglot's {@code Teradata.TIME_MAPPING} (used by TO_CHAR). */
    private static final Map<String, String> TERADATA_TIME_MAPPING = teradataTimeMapping();
    private static final Trie TERADATA_TIME_TRIE = Trie.ofStrings(TERADATA_TIME_MAPPING.keySet());

    private static Map<String, String> teradataTimeMapping() {
        String[] kv = {
            "YY", "%y", "Y4", "%Y", "YYYY", "%Y", "M4", "%B", "M3", "%b", "M", "%-M", "MI", "%M", "MM", "%m",
            "MMM", "%b", "MMMM", "%B", "D", "%-d", "DD", "%d", "D3", "%j", "DDD", "%j", "H", "%-H", "HH", "%H",
            "HH24", "%H", "S", "%-S", "SS", "%S", "SSSSSS", "%f", "E", "%a", "EE", "%a", "E3", "%a", "E4", "%A",
            "EEE", "%a", "EEEE", "%A",
        };
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }

    @Override
    protected Expression buildFunction(String name, List<Expression> args) {
        return switch (name) {
            case "APPROX_PERCENTILE" -> buildApproxPercentile(args);
            case "BITWISE_AND" -> DialectHelpers.binaryFromFunction(ExprType.BITWISE_AND, args, dialect);
            case "BITWISE_NOT" -> Expression.of(ExprType.BITWISE_NOT, Args.THIS, Py.seqGet(args, 0));
            case "BITWISE_OR" -> DialectHelpers.binaryFromFunction(ExprType.BITWISE_OR, args, dialect);
            case "BITWISE_XOR" -> DialectHelpers.binaryFromFunction(ExprType.BITWISE_XOR, args, dialect);
            case "DATE_ADD" -> Expression.of(ExprType.DATE_ADD,
                    Args.THIS, Py.seqGet(args, 2), Args.EXPRESSION, Py.seqGet(args, 1), Args.UNIT, Py.seqGet(args, 0));
            case "DATE_DIFF" -> Expression.of(ExprType.DATE_DIFF,
                    Args.THIS, Py.seqGet(args, 2), Args.EXPRESSION, Py.seqGet(args, 1), Args.UNIT, Py.seqGet(args, 0));
            case "DATE_FORMAT" -> DialectHelpers.buildFormattedTime(ExprType.TIME_TO_STR, null, args, dialect);
            case "DATE_PARSE" -> DialectHelpers.buildFormattedTime(ExprType.STR_TO_TIME, null, args, dialect);
            case "DATE_TRUNC" -> DialectHelpers.dateTruncToTime(args, dialect);
            case "ELEMENT_AT" -> Expression.of(ExprType.BRACKET,
                    Args.THIS, Py.seqGet(args, 0),
                    Args.EXPRESSIONS, java.util.Arrays.asList(Py.seqGet(args, 1)),
                    Args.OFFSET, 1,
                    Args.SAFE, Boolean.TRUE);
            case "FROM_UNIXTIME" -> buildFromUnixtime(args);
            case "FROM_UTF8" -> Expression.of(ExprType.DECODE,
                    Args.THIS, Py.seqGet(args, 0),
                    Args.REPLACE, Py.seqGet(args, 1),
                    Args.CHARSET, Exp.string("utf-8"));
            case "JSON_FORMAT" -> Expression.of(ExprType.JSON_FORMAT,
                    Args.THIS, Py.seqGet(args, 0),
                    Args.OPTIONS, Py.seqGet(args, 1),
                    Args.IS_JSON, Boolean.TRUE);
            case "REGEXP_EXTRACT" -> DialectHelpers.buildRegexpExtract(ExprType.REGEXP_EXTRACT, args, dialect);
            case "REGEXP_EXTRACT_ALL" -> DialectHelpers.buildRegexpExtract(ExprType.REGEXP_EXTRACT_ALL, args, dialect);
            case "REGEXP_REPLACE" -> {
                Expression replacement = Py.seqGet(args, 2);
                yield Expression.of(ExprType.REGEXP_REPLACE,
                        Args.THIS, Py.seqGet(args, 0),
                        Args.EXPRESSION, Py.seqGet(args, 1),
                        Args.REPLACEMENT, replacement != null ? replacement : Exp.string(""));
            }
            case "REPLACE" -> DialectHelpers.buildReplaceWithOptionalReplacement(args, dialect);
            case "STRPOS" -> Expression.of(ExprType.STR_POSITION,
                    Args.THIS, Py.seqGet(args, 0),
                    Args.SUBSTR, Py.seqGet(args, 1),
                    Args.OCCURRENCE, Py.seqGet(args, 2));
            case "TO_CHAR" -> buildToChar(args, dialect);
            case "TO_UTF8" -> Expression.of(ExprType.ENCODE, Args.THIS, Py.seqGet(args, 0), Args.CHARSET, Exp.string("utf-8"));
            case "SHA256" -> Expression.of(ExprType.SHA2_DIGEST, Args.THIS, Py.seqGet(args, 0), Args.LENGTH, Exp.number(256));
            case "SHA512" -> Expression.of(ExprType.SHA2_DIGEST, Args.THIS, Py.seqGet(args, 0), Args.LENGTH, Exp.number(512));
            default -> super.buildFunction(name, args);
        };
    }
}
