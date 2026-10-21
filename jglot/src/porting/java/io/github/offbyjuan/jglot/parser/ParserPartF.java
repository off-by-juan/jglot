package io.github.offbyjuan.jglot.parser;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.tokens.Token;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.util.Py;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parser part F: transactions, ALTER, ANALYZE, MERGE, SET/SHOW, COPY, GRANT, pipe syntax and the
 * rest (sqlglot/parser.py from {@code _parse_transaction} to the end of the class).
 */
abstract class ParserPartF extends ParserMethods {
    ParserPartF(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }

    private static final Set<String> TRANSACTION_WORK = Set.of("TRANSACTION", "WORK");
    private static final Set<String> CASCADE_RESTRICT = Set.of("CASCADE", "RESTRICT");
    private static final Set<TokenType> FROM_IN = Collections.unmodifiableSet(EnumSet.of(TokenType.FROM, TokenType.IN));
    private static final Set<TokenType> ASC_DESC = Collections.unmodifiableSet(EnumSet.of(TokenType.ASC, TokenType.DESC));

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    /** Python's {@code str(value)} / f-string interpolation (expressions render as base-dialect SQL). */
    private static String pyStr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Expression e) {
            return e.sql(Dialects.get(""));
        }
        return value.toString();
    }

    /** {@code exists or None}. */
    private static Object orNone(Object v) {
        return Expression.truthy(v) ? v : null;
    }

    /** sqlglot's {@code Expression.update_positions(token)}. */
    private static Expression updatePositions(Expression e, Token token) {
        if (present(token)) {
            Map<String, Object> meta = e.meta();
            meta.put("line", token.line());
            meta.put("col", token.col());
            meta.put("start", token.start());
            meta.put("end", token.end());
        }
        return e;
    }

    /** sqlglot's {@code Limit.is_limit_all}. */
    private static boolean isLimitAll(Expression limit) {
        Expression le = limit.expr(Args.EXPRESSION);
        return le != null && le.is(ExprType.VAR) && upper(le.name()).equals("ALL");
    }

    private static BigDecimal toDecimal(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        return new BigDecimal(v.toString());
    }

    private static Object pyAdd(Object a, Object b) {
        if (a instanceof BigInteger x && b instanceof BigInteger y) {
            return x.add(y);
        }
        return toDecimal(a).add(toDecimal(b)).toPlainString();
    }

    // ------------------------------------------------------------------------------------------

    @Override
    protected Expression parseTransaction() {
        String this_ = null;
        if (matchTexts(d.TRANSACTION_KIND)) {
            this_ = prev.text();
        }

        matchTexts(TRANSACTION_WORK);

        List<String> modes = new ArrayList<>();
        while (true) {
            List<String> mode = new ArrayList<>();
            while (match(TokenType.VAR) || match(TokenType.NOT)) {
                mode.add(prev.text());
            }

            if (!mode.isEmpty()) {
                modes.add(String.join(" ", mode));
            }
            if (!match(TokenType.COMMA)) {
                break;
            }
        }

        return build(ExprType.TRANSACTION, Args.THIS, this_, Args.MODES, modes);
    }

    @Override
    protected Expression parseCommitOrRollback() {
        Boolean chain = null;
        Expression savepoint = null;
        boolean isRollback = prev.type() == TokenType.ROLLBACK;

        matchTexts(TRANSACTION_WORK);

        if (matchTextSeq("TO")) {
            matchTextSeq("SAVEPOINT");
            savepoint = parseIdVar();
        }

        if (match(TokenType.AND)) {
            chain = !matchTextSeq("NO");
            matchTextSeq("CHAIN");
        }

        if (isRollback) {
            return build(ExprType.ROLLBACK, Args.SAVEPOINT, savepoint);
        }

        return build(ExprType.COMMIT, Args.CHAIN, chain);
    }

    @Override
    protected Expression parseRefresh() {
        Token start = prev;

        String kind;
        if (matchTextSeq("EXTERNAL", "TABLE")) {
            kind = "EXTERNAL TABLE";
        } else if (match(TokenType.TABLE)) {
            kind = "TABLE";
        } else if (matchTextSeq("MATERIALIZED", "VIEW")) {
            kind = "MATERIALIZED VIEW";
        } else {
            kind = "";
        }

        Expression this_ = parseString();
        if (kind.isEmpty() && !(this_ != null && this_.is(ExprType.LITERAL))) {
            return parseAsCommand(start);
        }

        return build(ExprType.REFRESH, Args.THIS, this_ != null ? this_ : parseTable(), Args.KIND, kind);
    }

    @Override
    protected Object parseColumnDefWithExists() {
        int start = tokenIndex;
        match(TokenType.COLUMN);

        Boolean existsColumn = parseExists(true);
        Expression expression = parseFieldDef();

        if (!(expression != null && expression.is(ExprType.COLUMN_DEF))) {
            retreat(start);
            return null;
        }

        expression.set(Args.EXISTS, existsColumn);

        return expression;
    }

    @Override
    protected Expression parseAddColumn() {
        if (!upper(prev.text()).equals("ADD")) {
            return null;
        }

        return (Expression) parseColumnDefWithExists();
    }

    @Override
    protected Expression parseDropColumn() {
        if (!match(TokenType.DROP)) {
            return null;
        }

        String kind = null;
        if (!d.ALTER_DROP_REQUIRES_COLUMN) {
            // A bare `DROP [IF EXISTS] c [CASCADE | RESTRICT]` drops a column
            int index = tokenIndex;
            parseExists();
            if (curr.type() != TokenType.COLUMN
                    && curr.type() != TokenType.CONSTRAINT
                    && matchSet(d.ID_VAR_TOKENS)
                    && (!present(curr)
                            || match(TokenType.COMMA, false)
                            || matchTexts(CASCADE_RESTRICT, false))) {
                kind = "COLUMN";
            }
            retreat(index);
        }

        return parseDrop(false, kind);
    }

    @Override
    protected Expression parseAlterDropAction() {
        Expression drop = parseDropColumn();

        if (drop != null
                && drop.is(ExprType.DROP)
                && "COLUMN".equals(drop.get(Args.KIND))
                && !dd.ALTER_TABLE_DROP_REQUIRED_FOR_EACH_COLUMN
                && match(TokenType.COMMA)) {
            List<Object> tables = new ArrayList<>(drop.list(Args.TABLES));
            tables.addAll(parseCsv(this::parseColumn));
            drop.set(Args.TABLES, tables);
        }

        return drop;
    }

    // https://docs.aws.amazon.com/athena/latest/ug/alter-table-drop-partition.html
    @Override
    protected Expression parseDropPartition(Boolean exists) {
        return build(ExprType.DROP_PARTITION, Args.EXPRESSIONS, parseCsv(this::parsePartition), Args.EXISTS, exists);
    }

    private Expression parseAddAlteration() {
        matchTextSeq("ADD");
        if (matchSet(d.ADD_CONSTRAINT_TOKENS, false) || matchTexts(d.ADD_CONSTRAINT_KEYWORDS, false)) {
            return build(ExprType.ADD_CONSTRAINT, Args.EXPRESSIONS, parseCsv(this::parseConstraint));
        }

        Expression columnDef = parseAddColumn();
        if (columnDef != null && columnDef.is(ExprType.COLUMN_DEF)) {
            return columnDef;
        }

        Boolean exists = parseExists(true);
        if (matchPair(TokenType.PARTITION, TokenType.L_PAREN, false)) {
            return build(ExprType.ADD_PARTITION,
                    Args.EXISTS, exists,
                    Args.THIS, parseField(true),
                    Args.LOCATION, matchTextSeq(false, "LOCATION") ? parseProperty() : Boolean.FALSE);
        }

        return null;
    }

    @Override
    protected List<Expression> parseAlterTableAdd() {
        if (!matchSet(d.ADD_CONSTRAINT_TOKENS, false)
                && (!dd.ALTER_TABLE_ADD_REQUIRED_FOR_EACH_COLUMN || matchTextSeq("COLUMNS"))) {
            Expression schema = parseSchema();

            return schema != null
                    ? Py.ensureList(schema)
                    : parseCsv(() -> (Expression) parseColumnDefWithExists());
        }

        return parseCsv(this::parseAddAlteration);
    }

    @Override
    protected Expression parseAlterTableAlter() {
        if (matchTexts(d.ALTER_ALTER_PARSERS)) {
            return alterAlterParser(upper(prev.text()));
        }

        // Many dialects support the ALTER [COLUMN] syntax, so if there is no
        // keyword after ALTER we default to parsing this statement
        match(TokenType.COLUMN);
        Boolean exists = parseExists();
        Expression column = parseField(true);

        if (matchPair(TokenType.DROP, TokenType.DEFAULT)) {
            return build(ExprType.ALTER_COLUMN, Args.THIS, column, Args.DROP, Boolean.TRUE, Args.EXISTS, orNone(exists));
        }
        if (matchPair(TokenType.SET, TokenType.DEFAULT)) {
            return build(ExprType.ALTER_COLUMN,
                    Args.THIS, column, Args.DEFAULT, parseDisjunction(), Args.EXISTS, orNone(exists));
        }
        if (match(TokenType.COMMENT)) {
            return build(ExprType.ALTER_COLUMN,
                    Args.THIS, column, Args.COMMENT, parseString(), Args.EXISTS, orNone(exists));
        }
        if (matchTextSeq("DROP", "NOT", "NULL")) {
            return build(ExprType.ALTER_COLUMN,
                    Args.THIS, column, Args.DROP, Boolean.TRUE, Args.ALLOW_NULL, Boolean.TRUE,
                    Args.EXISTS, orNone(exists));
        }
        if (matchTextSeq("SET", "NOT", "NULL")) {
            return build(ExprType.ALTER_COLUMN,
                    Args.THIS, column, Args.ALLOW_NULL, Boolean.FALSE, Args.EXISTS, orNone(exists));
        }

        if (matchTextSeq("SET", "VISIBLE")) {
            return build(ExprType.ALTER_COLUMN,
                    Args.THIS, column, Args.VISIBLE, "VISIBLE", Args.EXISTS, orNone(exists));
        }
        if (matchTextSeq("SET", "INVISIBLE")) {
            return build(ExprType.ALTER_COLUMN,
                    Args.THIS, column, Args.VISIBLE, "INVISIBLE", Args.EXISTS, orNone(exists));
        }

        matchTextSeq("SET", "DATA");
        Expression dtype = matchTextSeq("TYPE") || !d.ALTER_COLUMN_TYPE_REQUIRES_KEYWORD ? parseTypes() : null;
        if (dtype == null) {
            return null;
        }

        return build(ExprType.ALTER_COLUMN,
                Args.THIS, column,
                Args.DTYPE, dtype,
                Args.COLLATE, match(TokenType.COLLATE) ? parseTerm() : Boolean.FALSE,
                Args.USING, match(TokenType.USING) ? parseDisjunction() : Boolean.FALSE,
                Args.EXISTS, orNone(exists));
    }

    @Override
    protected Expression parseAlterDiststyle() {
        if (matchTexts(Set.of("ALL", "EVEN", "AUTO"))) {
            return build(ExprType.ALTER_DIST_STYLE, Args.THIS, Exp.var(upper(prev.text())));
        }

        matchTextSeq("KEY", "DISTKEY");
        return build(ExprType.ALTER_DIST_STYLE, Args.THIS, parseColumn());
    }

    @Override
    protected Expression parseAlterSortkey(Boolean compound) {
        if (Expression.truthy(compound)) {
            matchTextSeq("SORTKEY");
        }

        if (match(TokenType.L_PAREN, false)) {
            return build(ExprType.ALTER_SORT_KEY, Args.EXPRESSIONS, parseWrappedIdVars(), Args.COMPOUND, compound);
        }

        matchTexts(Set.of("AUTO", "NONE"));
        return build(ExprType.ALTER_SORT_KEY, Args.THIS, Exp.var(upper(prev.text())), Args.COMPOUND, compound);
    }

    @Override
    protected List<Expression> parseAlterTableDrop() {
        int index = tokenIndex - 1;

        Boolean partitionExists = parseExists();
        if (match(TokenType.PARTITION, false)) {
            return parseCsv(() -> parseDropPartition(partitionExists));
        }

        retreat(index);
        return parseCsv(this::parseAlterDropAction);
    }

    @Override
    protected Expression parseAlterTableRename() {
        if (match(TokenType.COLUMN) || (!d.ALTER_RENAME_REQUIRES_COLUMN && !matchTextSeq(false, "TO"))) {
            Boolean exists = parseExists();
            Expression oldColumn = parseColumn();
            boolean to = matchTextSeq("TO");
            Expression newColumn = parseColumn();

            if (oldColumn == null || !to || newColumn == null) {
                return null;
            }

            return build(ExprType.RENAME_COLUMN, Args.THIS, oldColumn, Args.TO, newColumn, Args.EXISTS, exists);
        }

        matchTextSeq("TO");
        return build(ExprType.ALTER_RENAME, Args.THIS, parseTable(true));
    }

    @Override
    protected Expression parseAlterTableSet() {
        Expression alterSet = expression(Expression.of(ExprType.ALTER_SET));

        if (match(TokenType.L_PAREN, false) || matchTextSeq("TABLE", "PROPERTIES")) {
            alterSet.set(Args.EXPRESSIONS, parseWrappedCsv(this::parseAssignment));
        } else if (matchTextSeq(false, "FILESTREAM_ON")) {
            alterSet.set(Args.EXPRESSIONS, listOf(parseAssignment()));
        } else if (matchTexts(Set.of("LOGGED", "UNLOGGED"))) {
            alterSet.set(Args.OPTION, Exp.var(upper(prev.text())));
        } else if (matchTextSeq("WITHOUT") && matchTexts(Set.of("CLUSTER", "OIDS"))) {
            alterSet.set(Args.OPTION, Exp.var("WITHOUT " + upper(prev.text())));
        } else if (matchTextSeq("LOCATION")) {
            alterSet.set(Args.LOCATION, parseField());
        } else if (matchTextSeq("ACCESS", "METHOD")) {
            alterSet.set(Args.ACCESS_METHOD, parseField());
        } else if (matchTextSeq("TABLESPACE")) {
            alterSet.set(Args.TABLESPACE, parseField());
        } else if (matchTextSeq("FILE", "FORMAT") || matchTextSeq("FILEFORMAT")) {
            alterSet.set(Args.FILE_FORMAT, listOf(parseField()));
        } else if (matchTextSeq("STAGE_FILE_FORMAT")) {
            alterSet.set(Args.FILE_FORMAT, parseWrappedOptions());
        } else if (matchTextSeq("STAGE_COPY_OPTIONS")) {
            alterSet.set(Args.COPY_OPTIONS, parseWrappedOptions());
        } else if (matchTextSeq("TAG") || matchTextSeq("TAGS")) {
            alterSet.set(Args.TAG, parseCsv(this::parseAssignment));
        } else {
            if (matchTextSeq("SERDE")) {
                alterSet.set(Args.SERDE, parseField());
            }

            Expression properties = parseWrapped(() -> parseProperties(), true);
            alterSet.set(Args.EXPRESSIONS, listOf(properties));
        }

        return alterSet;
    }

    /** Python's {@code [x]}: a one-element mutable list that may hold None. */
    private static List<Object> listOf(Object x) {
        List<Object> l = new ArrayList<>(1);
        l.add(x);
        return l;
    }

    @Override
    protected Expression parseAlterSession() {
        // Parse ALTER SESSION SET/UNSET statements.
        if (match(TokenType.SET)) {
            List<Expression> expressions = parseCsv(() -> parseSetItemAssignment());
            return build(ExprType.ALTER_SESSION, Args.EXPRESSIONS, expressions, Args.UNSET, Boolean.FALSE);
        }

        matchTextSeq("UNSET");
        List<Expression> expressions = parseCsv(() -> build(ExprType.SET_ITEM, Args.THIS, parseIdVar(true)));
        return build(ExprType.ALTER_SESSION, Args.EXPRESSIONS, expressions, Args.UNSET, Boolean.TRUE);
    }

    @Override
    protected Expression parseAlter() {
        Token start = prev;

        boolean iceberg = matchTextSeq("ICEBERG");

        Token alterToken = matchSet(d.ALTERABLES) ? prev : null;
        if (alterToken == null) {
            return parseAsCommand(start);
        }
        if (iceberg && alterToken.type() != TokenType.TABLE) {
            return parseAsCommand(start);
        }

        Boolean exists = parseExists();
        boolean only = matchTextSeq("ONLY");

        Expression this_;
        Boolean check;
        Expression cluster;
        String parser;
        if (alterToken.type() == TokenType.SESSION) {
            this_ = null;
            check = null;
            cluster = null;
            String key = upper(alterToken.text());
            parser = d.ALTER_PARSERS.contains(key) ? key : null;
        } else {
            this_ = parseTable(true, false, null, false, false, d.ALTER_TABLE_PARTITIONS);
            check = matchTextSeq("WITH", "CHECK");
            cluster = match(TokenType.ON) ? parseOnProperty() : null;
            parser = matchTexts(d.ALTER_PARSERS) ? upper(prev.text()) : null;
        }

        List<Object> options = new ArrayList<>();
        Expression properties;
        if (parser == null && alterToken.type() == TokenType.TABLE && !d.ALTER_TABLE_REQUIRES_ACTION) {
            // Table options may precede the actions, e.g. MySQL's ENGINE=InnoDB, ADD COLUMN a INT.
            // A clause the property parser rejects, like PARTITION BY RANGE ... MAXVALUE, stays a Command
            properties = tryParse(() -> parseProperties());
            options = properties != null ? new ArrayList<>(properties.getExpressions()) : new ArrayList<>();
        }

        List<Object> actions = Py.ensureList(parser != null ? alterParser(parser) : null);
        if (!actions.isEmpty() || !options.isEmpty()) {
            // Actions of different kinds and table options may alternate, e.g. MySQL's
            // ADD c INT, AUTO_INCREMENT=5, ADD d INT. The comma before an item may have already
            // been consumed by the previous parser
            while (d.ALTER_TABLE_MIXED_ACTIONS
                    && (match(TokenType.COMMA) || prev.type() == TokenType.COMMA)) {
                if (!matchTexts(d.PROPERTY_PARSERS, false) && matchTexts(d.ALTER_PARSERS)) {
                    String key = upper(prev.text());
                    List<Object> parsed = Py.ensureList(tryParse(() -> alterParser(key)));
                    boolean anyCommand = false;
                    for (Object action : parsed) {
                        if (Expression.isInstance(action, ExprType.COMMAND)) {
                            anyCommand = true;
                            break;
                        }
                    }
                    if (parsed.isEmpty() || anyCommand) {
                        return parseAsCommand(start);
                    }

                    actions.addAll(parsed);
                } else if ((properties = tryParse(() -> parseProperties())) != null) {
                    options.addAll(properties.getExpressions());
                } else {
                    break;
                }
            }

            boolean notValid = matchTextSeq("NOT", "VALID");
            if ((properties = tryParse(() -> parseProperties())) != null) {
                options.addAll(properties.getExpressions());
            }
            boolean cascade = dd.ALTER_TABLE_SUPPORTS_CASCADE && matchTextSeq("CASCADE");

            if (!present(curr)) {
                return build(ExprType.ALTER,
                        Args.THIS, this_,
                        Args.KIND, upper(alterToken.text()),
                        Args.EXISTS, exists,
                        Args.ACTIONS, actions,
                        Args.ONLY, only,
                        Args.OPTIONS, options,
                        Args.CLUSTER, cluster,
                        Args.NOT_VALID, notValid,
                        Args.CHECK, check,
                        Args.CASCADE, cascade,
                        Args.ICEBERG, iceberg);
            }
        }

        return parseAsCommand(start);
    }

    @Override
    protected Expression parseAnalyze() {
        Token start = prev;
        // https://duckdb.org/docs/sql/statements/analyze
        if (!present(curr)) {
            return expression(Expression.of(ExprType.ANALYZE));
        }

        List<String> options = new ArrayList<>();
        while (matchTexts(d.ANALYZE_STYLES)) {
            if (upper(prev.text()).equals("BUFFER_USAGE_LIMIT")) {
                options.add("BUFFER_USAGE_LIMIT " + pyStr(parseNumber()));
            } else {
                options.add(upper(prev.text()));
            }
        }

        Object tables = null;
        Expression innerExpression = null;

        String kind = present(curr) ? upper(curr.text()) : null;

        if (match(TokenType.TABLE)) {
            tables = parseCsv(() -> parseTableParts());
        } else if (match(TokenType.INDEX)) {
            tables = parseTableParts();
        } else if (matchTextSeq("TABLES")) {
            if (matchSet(FROM_IN)) {
                kind = kind + " " + upper(prev.text());
                tables = parseTable(true, false, null, false, true);
            }
        } else if (matchTextSeq("DATABASE")) {
            tables = parseTable(true, false, null, false, true);
        } else if (matchTextSeq("CLUSTER")) {
            tables = parseTable();
        // Try matching inner expr keywords before fallback to parse table.
        } else if (matchTexts(d.ANALYZE_EXPRESSION_PARSERS)) {
            kind = null;
            innerExpression = analyzeExpressionParser(upper(prev.text()));
        } else {
            // Empty kind  https://prestodb.io/docs/current/sql/analyze.html
            kind = null;
            tables = parseCsv(() -> parseTableParts());
        }

        Expression partition = tryParse(this::parsePartition);
        if (partition == null && matchTexts(d.PARTITION_KEYWORDS)) {
            return parseAsCommand(start);
        }

        // https://docs.starrocks.io/docs/sql-reference/sql-statements/cbo_stats/ANALYZE_TABLE/
        String mode;
        if (matchTextSeq("WITH", "SYNC", "MODE") || matchTextSeq("WITH", "ASYNC", "MODE")) {
            mode = "WITH " + upper(tokenList.get(tokenIndex - 2).text()) + " MODE";
        } else {
            mode = null;
        }

        if (matchTexts(d.ANALYZE_EXPRESSION_PARSERS)) {
            innerExpression = analyzeExpressionParser(upper(prev.text()));
        }

        Expression properties = parseProperties();
        return build(ExprType.ANALYZE,
                Args.KIND, kind,
                Args.TABLES, Py.ensureList(tables),
                Args.MODE, mode,
                Args.PARTITION, partition,
                Args.PROPERTIES, properties,
                Args.EXPRESSION, innerExpression,
                Args.OPTIONS, options);
    }

    // https://spark.apache.org/docs/3.5.1/sql-ref-syntax-aux-analyze-table.html
    @Override
    protected Expression parseAnalyzeStatistics() {
        String this_ = null;
        String kind = upper(prev.text());
        String option = matchTextSeq("DELTA") ? upper(prev.text()) : null;
        List<Expression> expressions = new ArrayList<>();

        if (!matchTextSeq("STATISTICS")) {
            raiseError("Expecting token STATISTICS");
        }

        if (matchTextSeq("NOSCAN")) {
            this_ = "NOSCAN";
        } else if (match(TokenType.FOR)) {
            if (matchTextSeq("ALL", "COLUMNS")) {
                this_ = "FOR ALL COLUMNS";
            }
            if (matchTextSeq("COLUMNS")) {
                this_ = "FOR COLUMNS";
                expressions = parseCsv(this::parseColumnReference);
            }
        } else if (matchTextSeq("SAMPLE")) {
            Expression sample = parseNumber();
            expressions = new ArrayList<>();
            expressions.add(build(ExprType.ANALYZE_SAMPLE,
                    Args.SAMPLE, sample,
                    Args.KIND, match(TokenType.PERCENT) ? upper(prev.text()) : null));
        }

        return build(ExprType.ANALYZE_STATISTICS,
                Args.KIND, kind, Args.OPTION, option, Args.THIS, this_, Args.EXPRESSIONS, expressions);
    }

    // https://docs.oracle.com/en/database/oracle/oracle-database/21/sqlrf/ANALYZE.html
    @Override
    protected Expression parseAnalyzeValidate() {
        String kind = null;
        String this_ = null;
        Expression expression = null;
        if (matchTextSeq("REF", "UPDATE")) {
            kind = "REF";
            this_ = "UPDATE";
            if (matchTextSeq("SET", "DANGLING", "TO", "NULL")) {
                this_ = "UPDATE SET DANGLING TO NULL";
            }
        } else if (matchTextSeq("STRUCTURE")) {
            kind = "STRUCTURE";
            if (matchTextSeq("CASCADE", "FAST")) {
                this_ = "CASCADE FAST";
            } else if (matchTextSeq("CASCADE", "COMPLETE") && matchTexts(Set.of("ONLINE", "OFFLINE"))) {
                this_ = "CASCADE COMPLETE " + upper(prev.text());
                expression = parseIntoImpl();
            }
        }

        return build(ExprType.ANALYZE_VALIDATE, Args.KIND, kind, Args.THIS, this_, Args.EXPRESSION, expression);
    }

    @Override
    protected Expression parseAnalyzeColumns() {
        String this_ = upper(prev.text());
        if (matchTextSeq("COLUMNS")) {
            return build(ExprType.ANALYZE_COLUMNS, Args.THIS, this_ + " " + upper(prev.text()));
        }
        return null;
    }

    @Override
    protected Expression parseAnalyzeDelete() {
        String kind = matchTextSeq("SYSTEM") ? upper(prev.text()) : null;
        if (matchTextSeq("STATISTICS")) {
            return build(ExprType.ANALYZE_DELETE, Args.KIND, kind);
        }
        return null;
    }

    @Override
    protected Expression parseAnalyzeList() {
        if (matchTextSeq("CHAINED", "ROWS")) {
            return build(ExprType.ANALYZE_LIST_CHAINED_ROWS, Args.EXPRESSION, parseIntoImpl());
        }
        return null;
    }

    // https://dev.mysql.com/doc/refman/8.4/en/analyze-table.html
    @Override
    protected Expression parseAnalyzeHistogram() {
        String this_ = upper(prev.text());
        Expression expression = null;
        List<Expression> expressions = new ArrayList<>();
        String updateOptions = null;

        if (matchTextSeq("HISTOGRAM", "ON")) {
            expressions = parseCsv(this::parseColumnReference);
            List<String> withExpressions = new ArrayList<>();
            while (match(TokenType.WITH)) {
                // https://docs.starrocks.io/docs/sql-reference/sql-statements/cbo_stats/ANALYZE_TABLE/
                if (matchTexts(Set.of("SYNC", "ASYNC"))) {
                    if (matchTextSeq(false, "MODE")) {
                        withExpressions.add(upper(prev.text()) + " MODE");
                        advance();
                    }
                } else {
                    Expression buckets = parseNumber();
                    if (matchTextSeq("BUCKETS")) {
                        withExpressions.add(pyStr(buckets) + " BUCKETS");
                    }
                }
            }
            if (!withExpressions.isEmpty()) {
                expression = build(ExprType.ANALYZE_WITH, Args.EXPRESSIONS, withExpressions);
            }

            if (matchTexts(Set.of("MANUAL", "AUTO")) && match(TokenType.UPDATE, false)) {
                updateOptions = upper(prev.text());
                advance();
            } else if (matchTextSeq("USING", "DATA")) {
                expression = build(ExprType.USING_DATA, Args.THIS, parseString());
            }
        }

        return build(ExprType.ANALYZE_HISTOGRAM,
                Args.THIS, this_,
                Args.EXPRESSIONS, expressions,
                Args.EXPRESSION, expression,
                Args.UPDATE_OPTIONS, updateOptions);
    }

    @Override
    protected Expression parseMerge() {
        match(TokenType.INTO);
        Expression target = parseTable();

        if (target != null && match(TokenType.ALIAS, false)) {
            target.set(Args.ALIAS, parseTableAlias());
        }

        match(TokenType.USING);
        Expression using = parseTable();

        return build(ExprType.MERGE,
                Args.THIS, target,
                Args.USING, using,
                Args.ON, match(TokenType.ON) ? parseDisjunction() : Boolean.FALSE,
                Args.USING_COND, match(TokenType.USING) ? parseUsingIdentifiers() : Boolean.FALSE,
                Args.WHENS, parseWhenMatched(),
                Args.RETURNING, parseReturning());
    }

    @Override
    protected Expression parseWhenMatched() {
        List<Expression> whens = new ArrayList<>();

        while (match(TokenType.WHEN)) {
            boolean matched = !match(TokenType.NOT);
            matchTextSeq("MATCHED");
            boolean source = matchTextSeq("BY", "TARGET") ? false : matchTextSeq("BY", "SOURCE");
            Expression condition = match(TokenType.AND) ? parseDisjunction() : null;

            match(TokenType.THEN);

            Expression then;
            if (match(TokenType.INSERT)) {
                Expression this_ = parseStar();
                if (this_ != null) {
                    then = build(ExprType.INSERT, Args.THIS, this_);
                } else {
                    then = build(ExprType.INSERT,
                            Args.THIS, matchTextSeq("ROW") ? Exp.var("ROW") : parseValue(false),
                            Args.EXPRESSION, matchTextSeq("VALUES") ? parseValue() : Boolean.FALSE,
                            Args.WHERE, parseWhere());
                }
            } else if (match(TokenType.UPDATE)) {
                Expression expressions = parseStar();
                if (expressions != null) {
                    then = build(ExprType.UPDATE, Args.EXPRESSIONS, expressions);
                } else {
                    then = build(ExprType.UPDATE,
                            Args.EXPRESSIONS, match(TokenType.SET)
                                    ? parseCsv(this::parseUpdateAssignment)
                                    : Boolean.FALSE,
                            Args.WHERE, parseWhere());
                }
            } else if (match(TokenType.DELETE)) {
                then = build(ExprType.VAR, Args.THIS, prev.text());
            } else {
                then = parseVarFromOptions(d.CONFLICT_ACTIONS);
            }

            whens.add(build(ExprType.WHEN,
                    Args.MATCHED, matched, Args.SOURCE, source, Args.CONDITION, condition, Args.THEN, then));
        }
        return build(ExprType.WHENS, Args.EXPRESSIONS, whens);
    }

    @Override
    protected Expression parseShow() {
        String parser = findParser(dialect.showTrie());
        if (parser != null) {
            return showParser(parser);
        }
        return parseAsCommand(prev);
    }

    @Override
    protected Expression parseSetItemAssignment(String kind) {
        int index = tokenIndex;

        if (("GLOBAL".equals(kind) || "SESSION".equals(kind)) && matchTextSeq("TRANSACTION")) {
            return parseSetTransaction("GLOBAL".equals(kind));
        }

        Expression left = parsePrimary();
        if (left == null) {
            left = parseColumn();
        }
        boolean assignmentDelimiter = matchTexts(d.SET_ASSIGNMENT_DELIMITERS);

        if (left == null || (d.SET_REQUIRES_ASSIGNMENT_DELIMITER && !assignmentDelimiter)) {
            retreat(index);
            return null;
        }

        Expression right = parseStatement();
        if (right == null) {
            right = parseIdVar();
        }
        if (right != null && right.is(ExprType.COLUMN, ExprType.IDENTIFIER)) {
            right = Exp.var(right.name());
        }

        Expression this_ = build(ExprType.EQ, Args.THIS, left, Args.EXPRESSION, right);
        return build(ExprType.SET_ITEM, Args.THIS, this_, Args.KIND, kind);
    }

    @Override
    protected Expression parseSetTransaction(boolean global) {
        matchTextSeq("TRANSACTION");
        List<Expression> characteristics = parseCsv(() -> parseVarFromOptions(d.TRANSACTION_CHARACTERISTICS));
        return build(ExprType.SET_ITEM,
                Args.EXPRESSIONS, characteristics, Args.KIND, "TRANSACTION", Args.GLOBAL_, global);
    }

    @Override
    protected Expression parseSetItem() {
        String parser = findParser(dialect.setTrie());
        return parser != null ? setParser(parser) : parseSetItemAssignment(null);
    }

    @Override
    protected Expression parseSet(boolean unset, boolean tag) {
        int index = tokenIndex;
        Expression set = build(ExprType.SET,
                Args.EXPRESSIONS, parseCsv(this::parseSetItem), Args.UNSET, unset, Args.TAG, tag);

        if (present(curr)) {
            retreat(index);
            return parseAsCommand(prev);
        }

        return set;
    }

    @Override
    protected Expression parseVarFromOptions(Map<String, List<List<String>>> options, boolean raiseUnmatched) {
        Token start = curr;
        if (!present(start)) {
            return null;
        }

        String option = upper(start.text());
        List<List<String>> continuations =
                d.TEXT_MATCH_EXCLUDED_TOKENS.contains(start.type()) ? null : options.get(option);

        int index = tokenIndex;
        advance();
        boolean matched = false;
        if (continuations != null) {
            for (List<String> keywords : continuations) {
                if (matchTextSeq(keywords.toArray(new String[0]))) {
                    option = option + " " + String.join(" ", keywords);
                    matched = true;
                    break;
                }
            }
        }
        if (!matched) {
            if (continuations == null || !continuations.isEmpty()) {
                if (raiseUnmatched) {
                    raiseError("Unknown option " + option);
                }

                retreat(index);
                return null;
            }
        }

        return Exp.var(option);
    }

    @Override
    protected Expression parseAsCommand(Token start) {
        while (present(curr)) {
            advance();
        }
        String text = findSql(start, prev);
        int size = start.text().length();
        warnUnsupported();
        return Expression.of(ExprType.COMMAND,
                Args.THIS, Py.slice(text, null, size), Args.EXPRESSION, Py.slice(text, size));
    }

    @Override
    protected Expression parseDictProperty(String this_) {
        List<Expression> settings = new ArrayList<>();

        matchLParen();
        Expression kind = parseIdVar();

        if (match(TokenType.L_PAREN)) {
            while (true) {
                Expression key = parseIdVar();
                Expression value = parseFunction();
                if (value == null) {
                    value = parsePrimaryOrVar();
                }
                if (key == null && value == null) {
                    break;
                }
                settings.add(build(ExprType.DICT_SUB_PROPERTY, Args.THIS, key, Args.VALUE, value));
            }
            match(TokenType.R_PAREN);
        }

        matchRParen();

        return build(ExprType.DICT_PROPERTY,
                Args.THIS, this_, Args.KIND, kind != null ? kind.thisValue() : null, Args.SETTINGS, settings);
    }

    @Override
    protected Expression parseDictRange(String this_) {
        matchLParen();
        boolean hasMin = matchTextSeq("MIN");
        Expression min;
        Expression max;
        if (hasMin) {
            min = parseVar();
            if (min == null) {
                min = parsePrimary();
            }
            matchTextSeq("MAX");
            max = parseVar();
            if (max == null) {
                max = parsePrimary();
            }
        } else {
            max = parseVar();
            if (max == null) {
                max = parsePrimary();
            }
            min = Exp.number(0);
        }
        matchRParen();
        return build(ExprType.DICT_RANGE, Args.THIS, this_, Args.MIN, min, Args.MAX, max);
    }

    @Override
    protected Expression parseComprehension(Expression this_) {
        int index = tokenIndex;
        Expression expression = parseIdVar();
        Expression position = match(TokenType.COMMA) ? parseIdVar() : null;

        if (!match(TokenType.IN)) {
            retreat(index - 1);
            return null;
        }
        Expression iterator = parseColumn();
        Expression condition = matchTextSeq("IF") ? parseDisjunction() : null;
        return build(ExprType.COMPREHENSION,
                Args.THIS, this_,
                Args.EXPRESSION, expression,
                Args.POSITION, position,
                Args.ITERATOR, iterator,
                Args.CONDITION, condition);
    }

    @Override
    protected Expression parseHeredoc() {
        if (match(TokenType.HEREDOC_STRING)) {
            return build(ExprType.HEREDOC, Args.THIS, prev.text());
        }

        if (!matchTextSeq("$")) {
            return null;
        }

        List<String> tags = new ArrayList<>();
        tags.add("$");
        String tagText = null;

        if (isConnected()) {
            advance();
            tags.add(upper(prev.text()));
        } else {
            raiseError("No closing $ found");
        }

        if (!tags.get(tags.size() - 1).equals("$")) {
            if (isConnected() && matchTextSeq("$")) {
                tagText = tags.get(tags.size() - 1);
                tags.add("$");
            } else {
                raiseError("No closing $ found");
            }
        }

        Token heredocStart = curr;

        String[] tagArray = tags.toArray(new String[0]);
        while (present(curr)) {
            if (matchTextSeq(false, tagArray)) {
                String this_ = findSql(heredocStart, prev);
                advance(tags.size());
                return build(ExprType.HEREDOC, Args.THIS, this_, Args.TAG, tagText);
            }

            advance();
        }

        raiseError("No closing " + String.join("", tags) + " found");
        return null;
    }

    @Override
    protected Expression replaceLambda(Expression node, List<Expression> expressions) {
        if (node == null) {
            return node;
        }

        Map<String, Object> lambdaTypes = new LinkedHashMap<>();
        for (Expression e : expressions) {
            Object to = e.get(Args.TO);
            lambdaTypes.put(e.name(), Expression.truthy(to) ? to : Boolean.FALSE);
        }

        for (Expression column : node.findAll(ExprType.COLUMN)) {
            Object typ = lambdaTypes.get(column.parts().get(0).name());
            if (typ != null) {
                Expression dotOrId = !column.text(Args.TABLE).isEmpty() ? column.toDot(true) : column.getThis();

                if (Expression.truthy(typ)) {
                    dotOrId = build(ExprType.CAST, Args.THIS, dotOrId, Args.TO, typ);
                }

                Expression parent = column.parent();

                boolean broke = false;
                while (parent != null && parent.is(ExprType.DOT)) {
                    Expression grand = parent.parent();
                    if (!(grand != null && grand.is(ExprType.DOT))) {
                        parent.replace(dotOrId);
                        broke = true;
                        break;
                    }
                    parent = grand;
                }
                if (!broke) {
                    if (column == node) {
                        node = dotOrId;
                    } else {
                        column.replace(dotOrId);
                    }
                }
            }
        }
        return node;
    }

    @Override
    protected Expression parseTruncateTable() {
        Token start = prev;

        // Not to be confused with TRUNCATE(number, decimals) function call
        if (match(TokenType.L_PAREN)) {
            retreat(tokenIndex - 2);
            return parseFunction();
        }

        // Clickhouse supports TRUNCATE DATABASE as well
        boolean isDatabase = match(TokenType.DATABASE);

        match(TokenType.TABLE);

        Boolean exists = parseExists(false);

        List<Expression> expressions = parseCsv(() -> parseTable(true, false, null, false, isDatabase));

        Expression cluster = match(TokenType.ON) ? parseOnProperty() : null;

        String identity;
        if (matchTextSeq("RESTART", "IDENTITY")) {
            identity = "RESTART";
        } else if (matchTextSeq("CONTINUE", "IDENTITY")) {
            identity = "CONTINUE";
        } else {
            identity = null;
        }

        String option;
        if (matchTextSeq("CASCADE") || matchTextSeq("RESTRICT")) {
            option = prev.text();
        } else {
            option = null;
        }

        Expression partition = parsePartition();

        // Fallback case
        if (present(curr)) {
            return parseAsCommand(start);
        }

        return build(ExprType.TRUNCATE_TABLE,
                Args.EXPRESSIONS, expressions,
                Args.IS_DATABASE, isDatabase,
                Args.EXISTS, exists,
                Args.CLUSTER, cluster,
                Args.IDENTITY, identity,
                Args.OPTION, option,
                Args.PARTITION, partition);
    }

    @Override
    protected Expression parseIndexedColumn() {
        return parseOrdered(this::parseOpclass);
    }

    @Override
    protected Expression parseWithOperator() {
        Expression this_ = parseIndexedColumn();

        if (!match(TokenType.WITH)) {
            return this_;
        }

        Expression op = parseVar(true, d.RESERVED_TOKENS);

        return build(ExprType.WITH_OPERATOR, Args.THIS, this_, Args.OP, op);
    }

    @Override
    protected List<Expression> parseWrappedOptions() {
        match(TokenType.EQ);
        match(TokenType.L_PAREN);

        List<Expression> opts = new ArrayList<>();
        Object option;
        while (present(curr) && !match(TokenType.R_PAREN)) {
            if (matchTextSeq("FORMAT_NAME", "=")) {
                // The FORMAT_NAME can be set to an identifier for Snowflake and T-SQL
                option = parseFormatName();
            } else {
                option = parseProperty();
            }

            if (option == null) {
                raiseError("Unable to parse option");
                break;
            }

            opts.addAll(Py.<Expression>ensureList(option));
        }

        return opts;
    }

    @Override
    protected List<Expression> parseCopyParameters() {
        TokenType sep = dd.COPY_PARAMS_ARE_CSV ? TokenType.COMMA : null;

        List<Expression> options = new ArrayList<>();
        while (present(curr) && !match(TokenType.R_PAREN, false)) {
            Expression option = parseVar(true);
            String prevText = upper(prev.text());

            // Different dialects might separate options and values by white space, "=" and "AS"
            match(TokenType.EQ);
            match(TokenType.ALIAS);

            Expression param = build(ExprType.COPY_PARAMETER, Args.THIS, option);

            if (d.COPY_INTO_VARLEN_OPTIONS.contains(prevText) && match(TokenType.L_PAREN, false)) {
                // Snowflake FILE_FORMAT case, Databricks COPY & FORMAT options
                param.set(Args.EXPRESSIONS, parseWrappedOptions());
            } else if (prevText.equals("FILE_FORMAT")) {
                // T-SQL's external file format case
                param.set(Args.EXPRESSION, parseField());
            } else if (prevText.equals("FORMAT")
                    && prev.type() == TokenType.ALIAS
                    && matchTexts(Set.of("AVRO", "JSON"))) {
                param.set(Args.THIS, Exp.var("FORMAT AS " + upper(prev.text())));
                param.set(Args.EXPRESSION, parseField());
            } else {
                Expression value = parseUnquotedField();
                param.set(Args.EXPRESSION, value != null ? value : parseBracket());
            }

            options.add(param);

            if (sep != null) {
                match(sep);
            }
        }

        return options;
    }

    @Override
    protected Expression parseCredentials() {
        Expression expr = expression(Expression.of(ExprType.CREDENTIALS));

        if (matchTextSeq("STORAGE_INTEGRATION", "=")) {
            expr.set(Args.STORAGE, parseField());
        }
        if (matchTextSeq("CREDENTIALS")) {
            // Snowflake case: CREDENTIALS = (...), Redshift case: CREDENTIALS <string>
            Object creds = match(TokenType.EQ) ? parseWrappedOptions() : parseField();
            expr.set(Args.CREDENTIALS, creds);
        }
        if (matchTextSeq("ENCRYPTION")) {
            expr.set(Args.ENCRYPTION, parseWrappedOptions());
        }
        if (matchTextSeq("IAM_ROLE")) {
            expr.set(Args.IAM_ROLE, match(TokenType.DEFAULT) ? Exp.var(prev.text()) : parseField());
        }
        if (matchTextSeq("REGION")) {
            expr.set(Args.REGION, parseField());
        }

        return expr;
    }

    @Override
    protected Expression parseFileLocation() {
        return parseField();
    }

    @Override
    protected Expression parseCopy() {
        Token start = prev;

        match(TokenType.INTO);

        Expression this_ = match(TokenType.L_PAREN, false)
                ? parseSelect(true, false, false)
                : parseTable(true);

        boolean kind = match(TokenType.FROM) || !matchTextSeq("TO");

        List<Expression> files = parseCsv(this::parseFileLocation);
        if (match(TokenType.EQ, false)) {
            // Backtrack one token since we've consumed the lhs of a parameter assignment here.
            // This can happen for Snowflake dialect. Instead, we'd like to parse the parameter
            // list via `_parse_wrapped(..)` below.
            advance(-1);
            files = new ArrayList<>();
        }

        Expression credentials = parseCredentials();

        matchTextSeq("WITH");

        List<Expression> params = parseWrapped(this::parseCopyParameters, true);

        // Fallback case
        if (present(curr)) {
            return parseAsCommand(start);
        }

        return build(ExprType.COPY,
                Args.THIS, this_, Args.KIND, kind, Args.CREDENTIALS, credentials, Args.FILES, files,
                Args.PARAMS, params);
    }

    @Override
    protected Expression parseNormalize() {
        return build(ExprType.NORMALIZE,
                Args.THIS, parseBitwise(), Args.FORM, match(TokenType.COMMA) ? parseVar() : Boolean.FALSE);
    }

    @Override
    protected Expression parseCeilFloor(ExprType exprType) {
        List<Expression> args = parseCsv(() -> parseLambda());

        Expression this_ = Py.seqGet(args, 0);
        Expression decimals = Py.seqGet(args, 1);

        return Expression.of(exprType,
                Args.THIS, this_,
                Args.DECIMALS, decimals,
                Args.TO, matchTextSeq("TO") ? parseVar() : null);
    }

    @Override
    protected Expression parseStarOps() {
        Token starToken = prev;

        if (matchTextSeq(false, "COLUMNS", "(")) {
            Expression this_ = parseFunction();
            if (this_ != null && this_.is(ExprType.COLUMNS)) {
                this_.set(Args.UNPACK, Boolean.TRUE);
            }
            return this_;
        }

        int index = tokenIndex;
        Expression ilike = match(TokenType.ILIKE) ? parseString() : null;
        if (ilike == null) {
            // ILIKE without a string pattern is not a star filter, e.g. `* ILIKE (foo)`
            retreat(index);
        }

        return updatePositions(build(ExprType.STAR,
                Args.ILIKE, ilike,
                Args.EXCEPT_, parseStarOp("EXCEPT", "EXCLUDE"),
                Args.REPLACE, parseStarOp("REPLACE"),
                Args.RENAME, parseStarOp("RENAME")), starToken);
    }

    @Override
    protected Expression parseGrantPrivilege() {
        List<String> privilegeParts = new ArrayList<>();

        // Keep consuming consecutive keywords until comma (end of this privilege) or ON
        // (end of privilege list) or L_PAREN (start of column list) are met
        while (present(curr) && !matchSet(d.PRIVILEGE_FOLLOW_TOKENS, false)) {
            privilegeParts.add(upper(curr.text()));
            advance();
        }

        if (privilegeParts.isEmpty()) {
            raiseError("Expected privilege");
            return null;
        }

        Expression this_ = Exp.var(String.join(" ", privilegeParts));
        List<Expression> expressions = match(TokenType.L_PAREN, false) ? parseWrappedCsv(this::parseColumn) : null;

        return build(ExprType.GRANT_PRIVILEGE, Args.THIS, this_, Args.EXPRESSIONS, expressions);
    }

    @Override
    protected Expression parseGrantPrincipal() {
        Object kind = matchTexts(Set.of("ROLE", "GROUP")) ? upper(prev.text()) : Boolean.FALSE;
        Expression principal = parseIdVar();

        if (principal == null) {
            return null;
        }

        return build(ExprType.GRANT_PRINCIPAL, Args.THIS, principal, Args.KIND, kind);
    }

    @Override
    protected Object[] parseGrantRevokeCommon() {
        List<Expression> privileges = parseCsv(this::parseGrantPrivilege);

        match(TokenType.ON);
        String kind = matchSet(d.CREATABLES) ? upper(prev.text()) : null;

        // Attempt to parse the securable e.g. MySQL allows names
        // such as "foo.*", "*.*" which are not easily parseable yet
        Expression securable = tryParse(() -> parseTableParts());

        return new Object[] {privileges, kind, securable};
    }

    @Override
    protected Expression parseGrant() {
        Token start = prev;

        Object[] common = parseGrantRevokeCommon();
        Object privileges = common[0];
        Object kind = common[1];
        Expression securable = (Expression) common[2];

        if (securable == null || !matchTextSeq("TO")) {
            return parseAsCommand(start);
        }

        List<Expression> principals = parseCsv(this::parseGrantPrincipal);

        boolean grantOption = matchTextSeq("WITH", "GRANT", "OPTION");

        if (present(curr)) {
            return parseAsCommand(start);
        }

        return build(ExprType.GRANT,
                Args.PRIVILEGES, privileges,
                Args.KIND, kind,
                Args.SECURABLE, securable,
                Args.PRINCIPALS, principals,
                Args.GRANT_OPTION, grantOption);
    }

    @Override
    protected Expression parseRevoke() {
        Token start = prev;

        boolean grantOption = matchTextSeq("GRANT", "OPTION", "FOR");

        Object[] common = parseGrantRevokeCommon();
        Object privileges = common[0];
        Object kind = common[1];
        Expression securable = (Expression) common[2];

        if (securable == null || !matchTextSeq("FROM")) {
            return parseAsCommand(start);
        }

        List<Expression> principals = parseCsv(this::parseGrantPrincipal);

        String cascade = null;
        if (matchTexts(CASCADE_RESTRICT)) {
            cascade = upper(prev.text());
        }

        if (present(curr)) {
            return parseAsCommand(start);
        }

        return build(ExprType.REVOKE,
                Args.PRIVILEGES, privileges,
                Args.KIND, kind,
                Args.SECURABLE, securable,
                Args.PRINCIPALS, principals,
                Args.GRANT_OPTION, grantOption,
                Args.CASCADE, cascade);
    }

    private Expression parseOverlayArg(String text) {
        return match(TokenType.COMMA) || matchTextSeq(text) ? parseBitwise() : null;
    }

    @Override
    protected Expression parseOverlay() {
        return build(ExprType.OVERLAY,
                Args.THIS, parseBitwise(),
                Args.EXPRESSION, parseOverlayArg("PLACING"),
                Args.FROM_, parseOverlayArg("FROM"),
                Args.FOR_, parseOverlayArg("FOR"));
    }

    @Override
    protected Expression parseFormatName() {
        // Note: Although not specified in the docs, Snowflake does accept a string/identifier
        // for FILE_FORMAT = <format_name>
        Expression value = parseString();
        return build(ExprType.PROPERTY,
                Args.THIS, Exp.var("FORMAT_NAME"), Args.VALUE, value != null ? value : parseTableParts());
    }

    @Override
    protected Expression parseDistinctArgFunction(ExprType func, int distinctIndex) {
        boolean isDistinct = match(TokenType.DISTINCT);
        if (!isDistinct) {
            match(TokenType.ALL);
        }

        List<Expression> args = new ArrayList<>();
        args.add(parseLambda());
        if (match(TokenType.COMMA)) {
            args.addAll(parseFunctionArgs());
        }

        Expression target = Py.seqGet(args, distinctIndex);
        if (isDistinct && target != null) {
            args.set(distinctIndex, build(ExprType.DISTINCT, Args.EXPRESSIONS, List.of(target)));
        }

        return Exp.fromArgList(func, args);
    }

    @Override
    protected Expression identifierExpression(Token token, Boolean quoted) {
        Token tok = token != null && present(token) ? token : prev;
        return expression(Expression.of(ExprType.IDENTIFIER, Args.THIS, tok.text(), Args.QUOTED, quoted), tok);
    }

    @Override
    protected Expression buildPipeCte(Expression query, List<Expression> expressions, Expression aliasCte) {
        Object newCte;
        if (aliasCte != null) {
            newCte = aliasCte;
        } else {
            pipeCteCounter += 1;
            newCte = "__tmp" + pipeCteCounter;
        }

        Expression with = query.expr(Args.WITH_);
        Expression ctes = with != null ? with.pop() : null;

        Expression newSelect = Exp.select(expressions.toArray()).from(newCte, false);
        if (ctes != null) {
            newSelect.set(Args.WITH_, ctes);
        }

        return newSelect.with(newCte, query, false, true, false);
    }

    @Override
    protected Expression parsePipeSyntaxSelect(Expression query) {
        Expression select = parseSelect(false, false, true, true, false);
        if (select == null) {
            return query;
        }

        return buildPipeCte(query.select(false, true, select.getExpressions().toArray()), listOfStar());
    }

    private static List<Expression> listOfStar() {
        List<Expression> l = new ArrayList<>(1);
        l.add(Expression.of(ExprType.STAR));
        return l;
    }

    @Override
    protected Expression parsePipeSyntaxLimit(Expression query) {
        Expression limit = parseLimit();
        Expression offset = parseOffset();
        if (limit != null && limit.is(ExprType.LIMIT)) {
            Object rawCurrLimit = query.get(Args.LIMIT);
            Expression currLimit = rawCurrLimit instanceof Expression e ? e : limit;
            boolean currIsLimitAll = currLimit.is(ExprType.LIMIT) && isLimitAll(currLimit);

            // LIMIT ALL means "no cap", so it's never tighter than a numeric limit.
            boolean shouldReplace;
            if (isLimitAll(limit)) {
                shouldReplace = currIsLimitAll;
            } else {
                shouldReplace = currIsLimitAll
                        || toDecimal(currLimit.getExpression().toPy())
                                        .compareTo(toDecimal(limit.getExpression().toPy())) >= 0;
            }

            if (shouldReplace) {
                query.limit(limit, false);
            }
        }
        if (offset != null) {
            Expression currOffsetNode = query.expr(Args.OFFSET);
            Object currOffset = currOffsetNode != null ? currOffsetNode.getExpression().toPy() : BigInteger.ZERO;
            query.offset(Exp.number(pyAdd(currOffset, offset.getExpression().toPy())), false);
        }

        return query;
    }

    @Override
    protected Expression parsePipeSyntaxAggregateFields() {
        Expression this_ = parseDisjunction();
        if (matchTextSeq(false, "GROUP", "AND")) {
            return this_;
        }

        Expression aliased = parseAlias(this_);

        if (matchSet(ASC_DESC, false)) {
            return parseOrdered(() -> aliased);
        }

        return aliased;
    }

    @Override
    protected Expression parsePipeSyntaxAggregateGroupOrderBy(Expression query, boolean groupByExists) {
        List<Expression> expr = parseCsv(this::parsePipeSyntaxAggregateFields);
        List<Expression> aggregatesOrGroups = new ArrayList<>();
        List<Expression> orders = new ArrayList<>();
        for (Expression element : expr) {
            Expression this_;
            if (element.is(ExprType.ORDERED)) {
                this_ = element.getThis();
                if (this_ != null && this_.is(ExprType.ALIAS)) {
                    element.set(Args.THIS, this_.get(Args.ALIAS));
                }
                orders.add(element);
            } else {
                this_ = element;
            }
            aggregatesOrGroups.add(this_);
        }

        if (groupByExists) {
            List<Object> projections = new ArrayList<>(aggregatesOrGroups);
            projections.addAll(query.getExpressions());
            List<Object> groups = new ArrayList<>();
            for (Expression projection : aggregatesOrGroups) {
                Object alias = projection.get(Args.ALIAS);
                groups.add(alias != null ? alias : projection);
            }
            query.select(false, false, projections.toArray()).groupBy(true, false, groups.toArray());
        } else {
            query.select(false, false, aggregatesOrGroups.toArray());
        }

        if (!orders.isEmpty()) {
            return query.orderBy(false, false, orders.toArray());
        }

        return query;
    }

    @Override
    protected Expression parsePipeSyntaxAggregate(Expression query) {
        matchTextSeq("AGGREGATE");
        query = parsePipeSyntaxAggregateGroupOrderBy(query, false);

        if (match(TokenType.GROUP_BY) || (matchTextSeq("GROUP", "AND") && match(TokenType.ORDER_BY))) {
            query = parsePipeSyntaxAggregateGroupOrderBy(query);
        }

        return buildPipeCte(query, listOfStar());
    }

    private Expression parseAndUnwrapQuery() {
        Expression expr = parseParen();
        return expr != null ? expr.assertIs(ExprType.SUBQUERY).unnest() : null;
    }

    /**
     * {@code query.union/except_/intersect(*setops, copy=False, **template.args)}: sqlglot reduces
     * the operands into nested set operations of the template's type, each built with the
     * template's remaining args (distinct, by_name, side, kind, on, ...).
     */
    private static Expression applySetOperation(Expression query, List<Expression> setops, Expression template) {
        Object distinct = template.get(Args.DISTINCT);
        if (distinct == null) {
            distinct = Boolean.TRUE;
        }
        Expression result = query;
        for (Expression setop : setops) {
            Map<Arg, Object> args = new LinkedHashMap<>();
            args.put(Args.THIS, result);
            args.put(Args.EXPRESSION, setop);
            args.put(Args.DISTINCT, distinct);
            for (Map.Entry<Arg, Object> entry : template.argEntries()) {
                Arg k = entry.getKey();
                if (k != Args.THIS && k != Args.EXPRESSION && k != Args.DISTINCT) {
                    args.put(k, entry.getValue());
                }
            }
            result = Expression.of(template.type(), args);
        }
        return result;
    }

    @Override
    protected Expression parsePipeSyntaxSetOperator(Expression query) {
        Expression firstSetop = parseSetOperation(query);
        if (firstSetop == null) {
            return null;
        }

        firstSetop.getThis().pop();

        List<Expression> setops = new ArrayList<>();
        setops.add(firstSetop.getExpression().pop().assertIs(ExprType.SUBQUERY).unnest());
        setops.addAll(parseCsv(this::parseAndUnwrapQuery));

        query = buildPipeCte(query, listOfStar());
        Expression with = query.expr(Args.WITH_);
        Expression ctes = with != null ? with.pop() : null;

        // Union, Except and Intersect each map to the matching builder (union/except_/intersect)
        query = applySetOperation(query, setops, firstSetop);

        query.set(Args.WITH_, ctes);

        return buildPipeCte(query, listOfStar());
    }

    @Override
    protected Expression parsePipeSyntaxJoin(Expression query) {
        Expression join = parseJoin();
        if (join == null) {
            return null;
        }

        if (query.is(ExprType.SELECT)) {
            return query.join(join, null, null, null, null, false);
        }

        return query;
    }

    @Override
    protected Expression parsePipeSyntaxPivot(Expression query) {
        List<Expression> pivots = parsePivots();
        if (pivots == null || pivots.isEmpty()) {
            return query;
        }

        Expression from = query.expr(Args.FROM_);
        if (from != null) {
            from.getThis().set(Args.PIVOTS, pivots);
        }

        return buildPipeCte(query, listOfStar());
    }

    @Override
    protected Expression parsePipeSyntaxExtend(Expression query) {
        matchTextSeq("EXTEND");
        List<Object> projections = new ArrayList<>();
        projections.add(Expression.of(ExprType.STAR));
        projections.addAll(parseExpressions());
        query.select(false, false, projections.toArray());
        return buildPipeCte(query, listOfStar());
    }

    @Override
    protected Expression parsePipeSyntaxTablesample(Expression query) {
        Expression sample = parseTableSample();

        Expression with = query.expr(Args.WITH_);
        if (with != null) {
            List<Expression> ctes = with.getExpressions();
            ctes.get(ctes.size() - 1).getThis().set(Args.SAMPLE, sample);
        } else {
            query.set(Args.SAMPLE, sample);
        }

        return query;
    }

    @Override
    protected Expression parsePipeSyntaxQuery(Expression query) {
        if (query.is(ExprType.SUBQUERY)) {
            query = Exp.select("*").from(query, false);
        }

        if (!query.has(Args.FROM_)) {
            query = Exp.select("*").from(query.subquery(null, false), false);
        }

        while (match(TokenType.PIPE_GT)) {
            int startIndex = tokenIndex;
            String startText = upper(curr.text());
            if (!d.PIPE_SYNTAX_TRANSFORM_PARSERS.contains(startText)) {
                // The set operators (UNION, etc) and the JOIN operator have a few common starting
                // keywords, making it tricky to disambiguate them without lookahead. The approach
                // here is to try and parse a set operation and if that fails, then try to parse a
                // join operator. If that fails as well, then the operator is not supported.
                Expression parsedQuery = parsePipeSyntaxSetOperator(query);
                if (parsedQuery == null) {
                    parsedQuery = parsePipeSyntaxJoin(query);
                }
                if (parsedQuery == null) {
                    retreat(startIndex);
                    raiseError("Unsupported pipe syntax operator: '" + startText + "'.");
                    break;
                }
                query = parsedQuery;
            } else {
                query = pipeSyntaxTransformParser(startText, query);
            }
        }

        return query;
    }

    @Override
    protected Expression parseDeclareitem() {
        matchTexts(Set.of("VAR", "VARIABLE"));

        List<Expression> vars = parseCsv(() -> parseIdVar());
        if (vars.isEmpty()) {
            return null;
        }

        match(TokenType.ALIAS);
        Expression kind = match(TokenType.TABLE) ? parseSchema() : parseTypes();
        Object default_ = match(TokenType.DEFAULT) || match(TokenType.EQ) ? parseBitwise() : Boolean.FALSE;

        return build(ExprType.DECLARE_ITEM, Args.THIS, vars, Args.KIND, kind, Args.DEFAULT, default_);
    }

    @Override
    protected Expression parseDeclare() {
        Token start = prev;
        boolean replace = matchTextSeq("OR", "REPLACE");
        List<Expression> expressions = tryParse(() -> parseCsv(this::parseDeclareitem));

        if (expressions == null || expressions.isEmpty() || present(curr)) {
            return parseAsCommand(start);
        }

        return build(ExprType.DECLARE, Args.EXPRESSIONS, expressions, Args.REPLACE, replace);
    }

    @Override
    protected Expression buildCast(boolean strict, Map<Arg, Object> kwargs) {
        ExprType expClass = strict ? ExprType.CAST : ExprType.TRY_CAST;
        Map<Arg, Object> args = kwargs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(kwargs);

        if (expClass == ExprType.TRY_CAST) {
            args.put(Args.REQUIRES_STRING, dd.TRY_CAST_REQUIRES_STRING);
        }

        return expression(Expression.of(expClass, args));
    }

    @Override
    protected Expression parseJsonValue(boolean jsonPath) {
        Expression this_ = parseBitwise();
        match(TokenType.COMMA);
        Expression path = parseBitwise();

        Object returning = match(TokenType.RETURNING) ? parseTypes() : Boolean.FALSE;

        return build(ExprType.JSON_VALUE,
                Args.THIS, this_,
                Args.PATH, jsonPath ? dialect.toJsonPath(path) : path,
                Args.RETURNING, returning,
                Args.ON_CONDITION, parseOnCondition());
    }

    /** {@code concat_exprs} closure of {@code _parse_group_concat} ({@code args} is the enclosing list). */
    private Expression concatExprs(Expression node, List<Expression> exprs, List<Expression> args) {
        if (node != null && node.is(ExprType.DISTINCT) && node.getExpressions().size() > 1) {
            List<Expression> concatExprs = new ArrayList<>();
            concatExprs.add(build(ExprType.CONCAT,
                    Args.EXPRESSIONS, node.getExpressions(),
                    Args.SAFE, Boolean.TRUE,
                    Args.COALESCE, dd.CONCAT_COALESCE));
            node.set(Args.EXPRESSIONS, concatExprs);
            return node;
        }
        if (exprs.size() == 1) {
            return exprs.get(0);
        }
        return build(ExprType.CONCAT, Args.EXPRESSIONS, args, Args.SAFE, Boolean.TRUE, Args.COALESCE, dd.CONCAT_COALESCE);
    }

    @Override
    protected Expression parseGroupConcat() {
        List<Expression> args = parseCsv(() -> parseLambda());

        Expression this_;
        if (!args.isEmpty()) {
            Expression last = args.get(args.size() - 1);
            Expression order = last.is(ExprType.ORDER) ? last : null;

            if (order != null) {
                // Order By is the last (or only) expression in the list and has consumed the 'expr' before it,
                // remove 'expr' from exp.Order and add it back to args
                args.set(args.size() - 1, order.getThis());
                order.set(Args.THIS, concatExprs(order.getThis(), args, args));
            }

            this_ = order != null ? order : concatExprs(args.get(0), args, args);
        } else {
            this_ = null;
        }

        Expression separator = match(TokenType.SEPARATOR) ? parseField() : null;

        return build(ExprType.GROUP_CONCAT, Args.THIS, this_, Args.SEPARATOR, separator);
    }

    @Override
    protected Expression parseInitcap() {
        Expression expr = Exp.fromArgList(ExprType.INITCAP, parseFunctionArgs());

        // attach dialect's default delimiters
        if (expr.get(Args.EXPRESSION) == null) {
            expr.set(Args.EXPRESSION, Exp.string(dd.INITCAP_DEFAULT_DELIMITER_CHARS));
        }

        return expr;
    }

    @Override
    protected Expression parseOperator(Expression this_) {
        if (!match(TokenType.L_PAREN)) {
            retreat(tokenIndex - 1);
            return null;
        }

        StringBuilder op = new StringBuilder();
        while (present(curr) && !match(TokenType.R_PAREN)) {
            op.append(curr.text());
            advance();
        }

        List<String> comments = prevComments;
        return expression(Expression.of(ExprType.OPERATOR,
                Args.THIS, this_, Args.OPERATOR, op.toString(), Args.EXPRESSION, parseBitwise()), comments);
    }
}
