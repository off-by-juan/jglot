package io.github.offbyjuan.jglot.dialects.trino;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.presto.PrestoParser;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.parser.ParserOptions;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.util.Py;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Trino parser: a port of sqlglot's {@code sqlglot.parsers.trino}. */
public class TrinoParser extends PrestoParser {
    private static final Pattern FRACTIONAL_SECONDS_RE = Pattern.compile(":\\d+\\.(\\d+)");

    public TrinoParser(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }

    @Override
    protected Expression typeLiteralParser(DType t, Expression this_, Expression dataType) {
        return switch (t) {
            case TIMESTAMP, TIMESTAMPTZ, TIME, TIMETZ -> parseTemporalLiteral(this_, dataType);
            default -> super.typeLiteralParser(t, this_, dataType);
        };
    }

    @Override
    protected Expression functionParser(String key) {
        return switch (key) {
            case "TRIM" -> parseTrim();
            case "JSON_QUERY" -> parseJsonQuery();
            case "JSON_VALUE" -> parseJsonValue();
            case "LISTAGG" -> parseStringAgg();
            default -> super.functionParser(key);
        };
    }

    protected Expression parseJsonQueryQuote() {
        if (!(matchTextSeq("KEEP", "QUOTES") || matchTextSeq("OMIT", "QUOTES"))) {
            return null;
        }

        return expression(Expression.of(ExprType.JSON_EXTRACT_QUOTE,
                Args.OPTION, Py.upper(tokenList.get(tokenIndex - 2).text()),
                Args.SCALAR, matchTextSeq("ON", "SCALAR", "STRING") ? Boolean.TRUE : null));
    }

    protected Expression parseJsonQuery() {
        return expression(Expression.of(ExprType.JSON_EXTRACT,
                Args.THIS, parseBitwise(),
                Args.EXPRESSION, match(TokenType.COMMA) ? parseBitwise() : null,
                Args.OPTION, parseVarFromOptions(d.JSON_QUERY_OPTIONS, false),
                Args.JSON_QUERY, Boolean.TRUE,
                Args.QUOTE, parseJsonQueryQuote(),
                Args.ON_CONDITION, parseOnCondition()));
    }

    @Override
    protected Expression parseCte() {
        // A `WITH` clause entry that starts with `FUNCTION <name>` is an inline SQL UDF
        // specification (https://trino.io/docs/current/udf/sql.html), as opposed to a
        // CTE that happens to be named "function", e.g. `WITH function AS (SELECT 1)`
        if (match(TokenType.FUNCTION, false) && present(next) && d.ID_VAR_TOKENS.contains(next.type())) {
            advance();
            return parseFunctionSpecification();
        }

        return super.parseCte();
    }

    protected Expression parseFunctionSpecification() {
        Expression this_ = parseUserDefinedFunction(TokenType.FUNCTION);

        // Collected separately (rather than one _parse_properties() call) so the
        // generator can place `WITH (...)` in its own bracketed clause at the end,
        // instead of it blending into the bare characteristics list.
        List<Expression> characteristics = new ArrayList<>();
        List<Expression> properties = new ArrayList<>();

        while (true) {
            if (match(TokenType.WITH)) {
                properties.addAll(parseWrappedCsv(() -> parseKeyValueProperty()));
                continue;
            }

            Object characteristic = parseProperty();
            if (!Expression.truthy(characteristic)) {
                break;
            }

            for (Object c : Py.ensureList(characteristic)) {
                characteristics.add((Expression) c);
            }
        }

        Expression characteristicsNode = !characteristics.isEmpty()
                ? expression(Expression.of(ExprType.PROPERTIES, Args.EXPRESSIONS, characteristics))
                : null;
        Expression propertiesNode = !properties.isEmpty()
                ? expression(Expression.of(ExprType.PROPERTIES, Args.EXPRESSIONS, properties))
                : null;
        return expression(Expression.of(ExprType.FUNCTION_SPECIFICATION,
                Args.THIS, this_,
                Args.CHARACTERISTICS, characteristicsNode,
                Args.PROPERTIES, propertiesNode,
                Args.EXPRESSION, parseRoutineStatement()));
    }

    protected List<Expression> parseRoutineStatements(String... terminators) {
        // Unlike _parse_block(), stops on any of `terminators` even when tokens
        // follow (Trino's own END is always followed by the enclosing query, left
        // for the caller), matching them as text rather than just TokenType.END, so
        // this same chunk-continuation loop also serves IF/ELSEIF/ELSE.
        List<Expression> statements = new ArrayList<>();
        Set<String> terms = Set.of(terminators);

        while (!matchTexts(terms)) {
            if (!present(curr)) {
                if (chunkIndex >= chunks.size()) {
                    raiseError("Unexpected end of routine body");
                    break;
                }

                advanceChunk();
            } else if (!match(TokenType.SEMICOLON)) {
                Expression statement = parseRoutineStatement();
                if (statement == null) {
                    break;
                }

                statements.add(statement);
            }
        }

        return statements;
    }

    protected Expression parseRoutineBlock() {
        match(TokenType.BEGIN);
        List<Expression> statements = parseRoutineStatements("END");
        statements.add(Expression.of(ExprType.END_STATEMENT));

        return expression(Expression.of(ExprType.BLOCK, Args.EXPRESSIONS, statements, Args.BEGIN, Boolean.TRUE));
    }

    private Expression parseRoutineIfBranch() {
        Expression condition = parseDisjunction();
        matchTextSeq("THEN");
        Expression true_ = expression(Expression.of(ExprType.BLOCK,
                Args.EXPRESSIONS, parseRoutineStatements("ELSEIF", "ELSE", "END")));
        return expression(Expression.of(ExprType.IF_BLOCK, Args.THIS, condition, Args.TRUE, true_));
    }

    protected Expression parseRoutineIf() {
        // https://trino.io/docs/current/udf/sql/if.html
        // ELSEIF chains nest into `false` rather than a flat list, reusing the
        // existing binary IfBlock instead of a new N-way expression; each loop
        // iteration links the next branch into the previous one's `false` slot.
        Expression this_ = parseRoutineIfBranch();
        Expression tail = this_;
        while (Py.upper(prev.text()).equals("ELSEIF")) {
            Expression node = parseRoutineIfBranch();
            tail.set(Args.FALSE, node);
            tail = node;
        }

        if (Py.upper(prev.text()).equals("ELSE")) {
            tail.set(Args.FALSE,
                    expression(Expression.of(ExprType.BLOCK, Args.EXPRESSIONS, parseRoutineStatements("END"))));
        }

        matchTextSeq("IF");
        return this_;
    }

    private Expression parseRoutineCaseBranch() {
        Expression condition = parseDisjunction();
        matchTextSeq("THEN");
        Expression true_ = expression(Expression.of(ExprType.BLOCK,
                Args.EXPRESSIONS, parseRoutineStatements("WHEN", "ELSE", "END")));
        return expression(Expression.of(ExprType.IF, Args.THIS, condition, Args.TRUE, true_));
    }

    protected Expression parseRoutineCase() {
        // https://trino.io/docs/current/udf/sql/case.html
        Expression this_ = parseDisjunction();

        List<Expression> ifs = new ArrayList<>();
        matchTextSeq("WHEN");
        while (Py.upper(prev.text()).equals("WHEN")) {
            ifs.add(parseRoutineCaseBranch());
        }

        Expression default_ = null;
        if (Py.upper(prev.text()).equals("ELSE")) {
            default_ = expression(Expression.of(ExprType.BLOCK, Args.EXPRESSIONS, parseRoutineStatements("END")));
        }

        matchTextSeq("CASE");
        return expression(Expression.of(ExprType.CASE_STATEMENT,
                Args.THIS, this_, Args.IFS, ifs, Args.DEFAULT, default_));
    }

    protected Expression parseRoutineWhile(Expression label) {
        // https://trino.io/docs/current/udf/sql/while.html
        Expression condition = parseDisjunction();
        matchTextSeq("DO");
        Expression body = expression(Expression.of(ExprType.BLOCK, Args.EXPRESSIONS, parseRoutineStatements("END")));
        matchTextSeq("WHILE");
        return expression(Expression.of(ExprType.WHILE_BLOCK,
                Args.THIS, condition, Args.BODY, body, Args.LABEL, label));
    }

    protected Expression parseRoutineLoop(Expression label) {
        // https://trino.io/docs/current/udf/sql/loop.html
        Expression body = expression(Expression.of(ExprType.BLOCK, Args.EXPRESSIONS, parseRoutineStatements("END")));
        matchTextSeq("LOOP");
        return expression(Expression.of(ExprType.LOOP_BLOCK, Args.BODY, body, Args.LABEL, label));
    }

    protected Expression parseRoutineRepeat(Expression label) {
        // https://trino.io/docs/current/udf/sql/repeat.html - unlike WHILE/LOOP,
        // the body is evaluated at least once, with UNTIL <condition> tested after
        Expression body = expression(Expression.of(ExprType.BLOCK, Args.EXPRESSIONS, parseRoutineStatements("UNTIL")));
        Expression until = parseDisjunction();
        matchTextSeq("END", "REPEAT");
        return expression(Expression.of(ExprType.REPEAT_BLOCK,
                Args.BODY, body, Args.UNTIL, until, Args.LABEL, label));
    }

    protected Expression parseRoutineStatement() {
        // An optional `label :` can precede WHILE, LOOP, or REPEAT to name the block for ITERATE/LEAVE.
        // Any non-reserved keyword (SET, IF, ITERATE etc) is a valid label name, so the colon lookahead
        // has to run first, before the statement keywords are matched.
        Expression label = null;
        if (present(next) && next.type() == TokenType.COLON) {
            label = parseIdVar();
            match(TokenType.COLON);
        }

        if (match(TokenType.BEGIN, false)) {
            return parseRoutineBlock();
        }

        if (matchTextSeq("RETURN")) {
            return expression(Expression.of(ExprType.RETURN, Args.THIS, parseDisjunction()));
        }

        if (matchTextSeq("IF")) {
            return parseRoutineIf();
        }

        if (matchTextSeq("CASE")) {
            return parseRoutineCase();
        }

        if (match(TokenType.DECLARE)) {
            return parseDeclare();
        }

        if (match(TokenType.SET)) {
            return parseSet();
        }

        if (matchTextSeq("ITERATE")) {
            return expression(Expression.of(ExprType.ITERATE, Args.THIS, parseIdVar()));
        }

        if (matchTextSeq("LEAVE")) {
            return expression(Expression.of(ExprType.LEAVE, Args.THIS, parseIdVar()));
        }

        if (matchTextSeq("WHILE")) {
            return parseRoutineWhile(label);
        }

        if (matchTextSeq("LOOP")) {
            return parseRoutineLoop(label);
        }

        if (matchTextSeq("REPEAT")) {
            return parseRoutineRepeat(label);
        }

        raiseError("Expected routine statement");
        return null;
    }

    protected Expression parseTemporalLiteral(Expression this_, Expression dataType) {
        if (dataType.getExpressions().isEmpty()) {
            Matcher m = FRACTIONAL_SECONDS_RE.matcher(this_.name());
            int digits = m.find() ? m.group(1).length() : 0;
            List<Expression> params = new ArrayList<>();
            params.add(Expression.of(ExprType.DATA_TYPE_PARAM, Args.THIS, Exp.number(digits)));
            dataType.set(Args.EXPRESSIONS, params);
        }

        return expression(Expression.of(ExprType.CAST, Args.THIS, this_, Args.TO, dataType));
    }
}
