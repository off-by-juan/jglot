package io.github.offbyjuan.jglot.dialects.postgres;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.DialectHelpers;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
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
import java.util.List;
import java.util.Map;

/** Postgres parser: a port of sqlglot's {@code sqlglot.parsers.postgres}. */
public class PostgresParser extends Parser {
    public PostgresParser(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }

    // ------------------------------------------------------------------------------------------
    // Module-level builders

    private static Expression buildGenerateSeries(List<Expression> args) {
        // The goal is to convert step values like '1 day' or INTERVAL '1 day' into INTERVAL '1' day
        // Note: postgres allows calls with just two arguments -- the "step" argument defaults to 1
        Expression step = Py.seqGet(args, 2);
        if (step != null) {
            if (step.isString()) {
                args.set(2, Exp.toInterval(step.thisValue()));
            } else if (step.is(ExprType.INTERVAL) && !step.has(Args.UNIT)) {
                args.set(2, Exp.toInterval(step.getThis().thisValue()));
            }
        }

        return Exp.fromArgList(ExprType.EXPLODING_GENERATE_SERIES, args);
    }

    private Expression buildToTimestamp(List<Expression> args) {
        // TO_TIMESTAMP accepts either a single double argument or (text, text)
        if (args.size() == 1) {
            // https://www.postgresql.org/docs/current/functions-datetime.html#FUNCTIONS-DATETIME-TABLE
            return Exp.fromArgList(ExprType.UNIX_TO_TIME, args);
        }

        // https://www.postgresql.org/docs/current/functions-formatting.html
        return DialectHelpers.buildFormattedTime(ExprType.STR_TO_TIME, null, args, dialect);
    }

    private Expression buildRegexpReplace(List<Expression> args) {
        // The signature of REGEXP_REPLACE is:
        // regexp_replace(source, pattern, replacement [, start [, N ]] [, flags ])
        //
        // Any one of `start`, `N` and `flags` can be column references, meaning that
        // unless we can statically see that the last argument is a non-integer string
        // (eg. not '0'), then it's not possible to construct the correct AST
        Expression regexpReplace = null;
        if (args.size() > 3) {
            Expression last = args.get(args.size() - 1);
            if (!Py.isInt(last.name())) {
                if (last.dataType() == null || last.isType(DType.UNKNOWN, DType.NULL)) {
                    last = TypeAnnotator.annotateTypes(last, dialect);
                }

                if (last.isType(DataTypes.TEXT_TYPES.toArray())) {
                    regexpReplace = Exp.fromArgList(ExprType.REGEXP_REPLACE, new ArrayList<>(args.subList(0, args.size() - 1)));
                    regexpReplace.set(Args.MODIFIERS, last);
                }
            }
        }

        if (regexpReplace == null) {
            regexpReplace = Exp.fromArgList(ExprType.REGEXP_REPLACE, args);
        }
        regexpReplace.set(Args.SINGLE_REPLACE, Boolean.TRUE);
        return regexpReplace;
    }

    private static Expression buildLevenshteinLessEqual(List<Expression> args) {
        // Postgres has two signatures for levenshtein_less_equal function, but in both cases
        // max_dist is the last argument
        // levenshtein_less_equal(source, target, ins_cost, del_cost, sub_cost, max_d)
        // levenshtein_less_equal(source, target, max_d)
        Expression maxDist = args.remove(args.size() - 1);

        return Expression.of(ExprType.LEVENSHTEIN,
                Args.THIS, Py.seqGet(args, 0),
                Args.EXPRESSION, Py.seqGet(args, 1),
                Args.INS_COST, Py.seqGet(args, 2),
                Args.DEL_COST, Py.seqGet(args, 3),
                Args.SUB_COST, Py.seqGet(args, 4),
                Args.MAX_DIST, maxDist);
    }

    // ------------------------------------------------------------------------------------------
    // FUNCTIONS

    @Override
    protected Expression buildFunction(String name, List<Expression> args) {
        return switch (name) {
            case "ARRAY_PREPEND" -> Expression.of(ExprType.ARRAY_PREPEND,
                    Args.THIS, Py.seqGet(args, 1), Args.EXPRESSION, Py.seqGet(args, 0));
            case "DATE_TRUNC" -> DialectHelpers.buildTimestampTrunc(args, dialect);
            case "DIV" -> Exp.cast(DialectHelpers.binaryFromFunction(ExprType.INT_DIV, args, dialect), DType.DECIMAL);
            case "GENERATE_SERIES" -> buildGenerateSeries(args);
            case "GET_BIT" -> Expression.of(ExprType.GETBIT,
                    Args.THIS, Py.seqGet(args, 0), Args.EXPRESSION, Py.seqGet(args, 1), Args.ZERO_IS_MSB, Boolean.TRUE);
            case "JSON_EXTRACT_PATH" -> DialectHelpers.buildJsonExtractPath(ExprType.JSON_EXTRACT, args, dialect);
            case "JSON_EXTRACT_PATH_TEXT" -> DialectHelpers.buildJsonExtractPath(ExprType.JSON_EXTRACT_SCALAR, args, dialect);
            case "LENGTH" -> Expression.of(ExprType.LENGTH,
                    Args.THIS, Py.seqGet(args, 0), Args.ENCODING, Py.seqGet(args, 1));
            case "REGEXP_REPLACE" -> buildRegexpReplace(args);
            case "TO_CHAR" -> DialectHelpers.buildFormattedTime(ExprType.TIME_TO_STR, null, args, dialect);
            case "TO_DATE" -> DialectHelpers.buildFormattedTime(ExprType.STR_TO_DATE, null, args, dialect);
            case "TO_TIMESTAMP" -> buildToTimestamp(args);
            case "SHA256" -> Expression.of(ExprType.SHA2, Args.THIS, Py.seqGet(args, 0), Args.LENGTH, Exp.number(256));
            case "SHA384" -> Expression.of(ExprType.SHA2, Args.THIS, Py.seqGet(args, 0), Args.LENGTH, Exp.number(384));
            case "SHA512" -> Expression.of(ExprType.SHA2, Args.THIS, Py.seqGet(args, 0), Args.LENGTH, Exp.number(512));
            case "LEVENSHTEIN_LESS_EQUAL" -> buildLevenshteinLessEqual(args);
            case "JSON_OBJECT_AGG" -> Expression.of(ExprType.JSON_OBJECT_AGG, Args.EXPRESSIONS, args);
            case "WIDTH_BUCKET" -> args.size() == 2
                    ? Expression.of(ExprType.WIDTH_BUCKET, Args.THIS, Py.seqGet(args, 0), Args.THRESHOLD, Py.seqGet(args, 1))
                    : Exp.fromArgList(ExprType.WIDTH_BUCKET, args);
            case "UUID" -> !args.isEmpty() ? Exp.cast(args.get(0), DType.UUID) : Expression.of(ExprType.UUID);
            default -> super.buildFunction(name, args);
        };
    }

    // ------------------------------------------------------------------------------------------
    // Callable tables

    @Override
    protected Object propertyParser(String key, Map<String, Object> kwargs) {
        if (key.equals("SET")) {
            return expression(Expression.of(ExprType.SET_CONFIG_PROPERTY, Args.THIS, parseSet()));
        }
        return super.propertyParser(key, kwargs);
    }

    @Override
    protected Expression placeholderParser(TokenType t) {
        return switch (t) {
            case PLACEHOLDER -> expression(Expression.of(ExprType.PLACEHOLDER, Args.JDBC, Boolean.TRUE));
            case MOD -> parseQueryParameter();
            default -> super.placeholderParser(t);
        };
    }

    @Override
    protected Expression noParenFunctionParser(String key) {
        if (key.equals("VARIADIC")) {
            return expression(Expression.of(ExprType.VARIADIC, Args.THIS, parseBitwise()));
        }
        return super.noParenFunctionParser(key);
    }

    @Override
    protected Expression functionParser(String key) {
        return switch (key) {
            case "DATE_PART" -> parseDatePart();
            case "JSON_AGG" -> {
                Expression this_ = parseLambda();
                Expression order = parseOrder();
                yield expression(Expression.of(ExprType.JSON_ARRAY_AGG, Args.THIS, this_, Args.ORDER, order));
            }
            case "JSONB_EXISTS" -> parseJsonbExists();
            case "JSON_VALUE" -> parseJsonValue(false);
            default -> super.functionParser(key);
        };
    }

    @Override
    protected Expression rangeParser(TokenType t, Expression this_) {
        return switch (t) {
            case DAMP -> binaryRangeParser(ExprType.ARRAY_OVERLAPS, this_);
            case DAT -> expression(Expression.of(ExprType.MATCH_AGAINST,
                    Args.THIS, parseBitwise(), Args.EXPRESSIONS, List.of(this_)));
            default -> super.rangeParser(t, this_);
        };
    }

    @Override
    protected Expression statementParser(TokenType t) {
        if (t == TokenType.END) {
            return parseCommitOrRollback();
        }
        return super.statementParser(t);
    }

    @Override
    protected Expression unaryParser(TokenType t) {
        // The `~` token is remapped from TILDE to RLIKE in Postgres due to the binary REGEXP LIKE operator
        if (t == TokenType.RLIKE) {
            return expression(Expression.of(ExprType.BITWISE_NOT, Args.THIS, parseUnary()));
        }
        return super.unaryParser(t);
    }

    @Override
    protected Expression jsonOperator(TokenType t, Expression this_, Expression path) {
        return switch (t) {
            case ARROW -> validateExpression(DialectHelpers.buildJsonExtractPath(
                    ExprType.JSON_EXTRACT, true, d.JSON_ARROWS_REQUIRE_JSON_TYPE, null,
                    new ArrayList<>(java.util.Arrays.asList(this_, path)), dialect));
            case DARROW -> validateExpression(DialectHelpers.buildJsonExtractPath(
                    ExprType.JSON_EXTRACT_SCALAR, true, d.JSON_ARROWS_REQUIRE_JSON_TYPE, null,
                    new ArrayList<>(java.util.Arrays.asList(this_, path)), dialect));
            case HASH_ARROW -> buildJsonbExtract(this_, path);
            case DHASH_ARROW -> buildJsonbExtractScalar(this_, path);
            case PLACEHOLDER -> buildJsonbContainsTopKey(this_, path);
            default -> super.jsonOperator(t, this_, path);
        };
    }

    // ------------------------------------------------------------------------------------------
    // Parse methods

    /**
     * Parse PostgreSQL function parameter mode (IN, OUT, INOUT, VARIADIC).
     *
     * <p>Disambiguates between mode keywords and identifiers with the same name: MODE TYPE -> keyword
     * is identifier (e.g., "out INT"); MODE NAME TYPE -> keyword is mode (e.g., "OUT x INT").
     *
     * @return mode token type if current token is a mode keyword, null otherwise.
     */
    protected TokenType parseParameterMode() {
        if (!matchSet(d.ARG_MODE_TOKENS, false) || !present(next)) {
            return null;
        }

        Token modeToken = curr;

        // Check Pattern 1: MODE TYPE
        // Try parsing next token as a built-in type (not UDT)
        // If successful, the keyword is an identifier, not a mode
        Expression isFollowedByBuiltinType = tryParse(() -> {
            advance();
            return parseTypes(false, false, false, false);
        }, true);
        if (isFollowedByBuiltinType != null) {
            return null; // Pattern: "out INT" -> out is parameter name
        }

        // Check Pattern 2: MODE NAME TYPE
        // If next token is an identifier, check if there's a type after it
        // The type can be built-in or user-defined (allow_identifiers=True)
        if (!d.ID_VAR_TOKENS.contains(next.type())) {
            return null;
        }

        Expression isFollowedByAnyType = tryParse(() -> {
            advance(2);
            return parseTypes(false, false, true, false);
        }, true);

        if (isFollowedByAnyType != null) {
            return modeToken.type(); // Pattern: "OUT x INT" -> OUT is mode
        }

        return null;
    }

    /** Create parameter mode constraint for function parameters. */
    protected Expression createModeConstraint(TokenType paramMode) {
        return expression(Expression.of(ExprType.IN_OUT_COLUMN_CONSTRAINT,
                Args.INPUT_, paramMode == TokenType.IN || paramMode == TokenType.INOUT,
                Args.OUTPUT, paramMode == TokenType.OUT || paramMode == TokenType.INOUT,
                Args.VARIADIC, paramMode == TokenType.VARIADIC));
    }

    @Override
    protected Expression parseFunctionParameter() {
        TokenType paramMode = parseParameterMode();

        if (paramMode != null) {
            advance();
        }

        // Parse parameter name and type
        Expression paramName = parseIdVar();
        Expression columnDef = parseColumnDef(paramName, false);

        // Attach mode as constraint
        if (paramMode != null && columnDef != null) {
            Expression constraint = createModeConstraint(paramMode);
            List<Object> constraints = new ArrayList<>();
            constraints.add(constraint);
            if (columnDef.get(Args.CONSTRAINTS) instanceof List<?> existing) {
                constraints.addAll(existing);
            }
            columnDef.set(Args.CONSTRAINTS, constraints);
        }

        return columnDef;
    }

    protected Expression parseQueryParameter() {
        Expression this_ = match(TokenType.L_PAREN, false) ? parseWrapped(() -> parseIdVar()) : null;
        matchTextSeq("S");
        return expression(Expression.of(ExprType.PLACEHOLDER, Args.THIS, this_));
    }

    protected Expression parseDatePart() {
        Expression part = parseType();
        match(TokenType.COMMA);
        Expression value = parseBitwise();

        if (part != null && part.is(ExprType.COLUMN, ExprType.LITERAL)) {
            part = Exp.var(Py.upper(part.name()));
        }

        return expression(Expression.of(ExprType.EXTRACT, Args.THIS, part, Args.EXPRESSION, value));
    }

    @Override
    protected Expression parseUniqueKey() {
        return null;
    }

    protected Expression parseJsonbExists() {
        Expression this_ = parseBitwise();
        Object path = match(TokenType.COMMA) ? dialect.toJsonPath(parseBitwise()) : Boolean.FALSE;
        return expression(Expression.of(ExprType.JSONB_EXISTS, Args.THIS, this_, Args.PATH, path));
    }

    @Override
    protected Expression parseGeneratedAsIdentity() {
        Expression this_ = super.parseGeneratedAsIdentity();

        if (matchTextSeq("STORED")) {
            this_ = expression(Expression.of(ExprType.COMPUTED_COLUMN_CONSTRAINT, Args.THIS, this_.getExpression()));
        }

        return this_;
    }

    @Override
    protected Expression parseUserDefinedType(Expression identifier) {
        Expression udtType = identifier;

        while (match(TokenType.DOT)) {
            Expression part = parseIdVar();
            if (part != null) {
                udtType = Expression.of(ExprType.DOT, Args.THIS, udtType, Args.EXPRESSION, part);
            }
        }

        return DataTypes.build(udtType, null, true, true);
    }
}
