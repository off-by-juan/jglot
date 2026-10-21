package io.github.offbyjuan.jglot.parser;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.tokens.Token;
import io.github.offbyjuan.jglot.tokens.TokenType;
import io.github.offbyjuan.jglot.util.Py;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parser part B: statements and DDL ({@code _parse_command} through {@code _parse_value}). */
abstract class ParserPartB extends ParserMethods {
    ParserPartB(Dialect dialect, ParserOptions options) {
        super(dialect, options);
    }

    private static final Set<TokenType> R_PAREN_OR_COMMA = EnumSet.of(TokenType.R_PAREN, TokenType.COMMA);
    private static final Set<TokenType> FUNCTION_OR_PROCEDURE =
            EnumSet.of(TokenType.FUNCTION, TokenType.PROCEDURE);
    private static final Set<TokenType> FIRST_OR_ALL = EnumSet.of(TokenType.FIRST, TokenType.ALL);
    private static final Set<String> CASCADE_RESTRICT = Set.of("CASCADE", "RESTRICT");
    private static final Set<String> IMMEDIATE_DEFERRED = Set.of("IMMEDIATE", "DEFERRED");
    private static final Set<String> ROW_STATEMENT = Set.of("ROW", "STATEMENT");
    private static final Set<String> MIN_MINIMUM = Set.of("MIN", "MINIMUM");
    private static final Set<String> MAX_MAXIMUM = Set.of("MAX", "MAXIMUM");
    private static final Set<String> BLOCKSIZE_UNITS = Set.of("BYTES", "KBYTES", "KILOBYTES");
    private static final Set<String> EXCL_EXCLUSIVE = Set.of("EXCL", "EXCLUSIVE");
    private static final Set<String> INCLUDING_EXCLUDING = Set.of("INCLUDING", "EXCLUDING");
    private static final Set<String> CONNECTION_QUERY = Set.of("CONNECTION", "QUERY");

    // ------------------------------------------------------------------------------------------
    // Private helpers (Python builtins / exp builders with semantics not covered by shared APIs)

    /** {@code exp.var(name)}: raises on an empty name and takes an expression's name. */
    private static Expression pyVar(Object name) {
        if (!Expression.truthy(name)) {
            throw new IllegalArgumentException("Cannot convert empty name into var.");
        }
        if (name instanceof Expression e) {
            name = e.name();
        }
        return Exp.var((String) name);
    }

    /** Python's {@code str(x)} / f-string interpolation of a value. */
    private static String pyStr(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Expression e) {
            return e.sql(Dialects.base());
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        return v.toString();
    }

    /** Python's {@code list(iterable)} for the result of a generator-returning parse method. */
    private static List<Object> pyList(Object v) {
        List<Object> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        if (v instanceof Iterable<?> it) {
            for (Object o : it) {
                out.add(o);
            }
        } else if (v instanceof Iterator<?> it) {
            while (it.hasNext()) {
                out.add(it.next());
            }
        } else {
            throw new IllegalArgumentException("not iterable: " + v);
        }
        return out;
    }

    /** {@code extend_props} closure of {@code _parse_create}. */
    @SuppressWarnings("unchecked")
    private static void extendProps(Expression[] properties, Expression tempProps) {
        if (properties[0] != null && tempProps != null) {
            // properties.expressions.extend(temp_props.expressions): a raw list extend (no re-parenting)
            Object raw = properties[0].get(Args.EXPRESSIONS);
            List<Expression> extra = tempProps.getExpressions();
            if (raw instanceof List<?> l) {
                ((List<Object>) l).addAll(extra);
            } else {
                List<Object> combined = new ArrayList<>(Py.ensureList(raw));
                combined.addAll(extra);
                properties[0].set(Args.EXPRESSIONS, combined);
            }
        } else if (tempProps != null) {
            properties[0] = tempProps;
        }
    }

    // ------------------------------------------------------------------------------------------

    @Override
    protected Expression parseCommand() {
        warnUnsupported();
        List<String> comments = prevComments;
        return expression(
                Expression.of(ExprType.COMMAND, Args.THIS, Py.upper(prev.text()), Args.EXPRESSION, parseString()),
                comments);
    }

    @Override
    protected Expression parseComment(boolean allowExists) {
        Token start = prev;
        Boolean exists = allowExists ? parseExists() : null;

        match(TokenType.ON);

        boolean materialized = matchTextSeq("MATERIALIZED");
        Token kind = matchSet(d.CREATABLES) ? prev : null;
        if (kind == null) {
            return parseAsCommand(start);
        }

        Expression this_;
        if (kind.type() == TokenType.FUNCTION || kind.type() == TokenType.PROCEDURE) {
            this_ = parseUserDefinedFunction(kind.type());
        } else if (kind.type() == TokenType.TABLE) {
            this_ = parseTable(false, false, d.COMMENT_TABLE_ALIAS_TOKENS);
        } else if (kind.type() == TokenType.COLUMN) {
            this_ = parseColumn();
        } else {
            this_ = parseTableParts(true);
        }

        match(TokenType.IS);

        return expression(Expression.of(ExprType.COMMENT,
                Args.THIS, this_,
                Args.KIND, kind.text(),
                Args.EXPRESSION, parseString(),
                Args.EXISTS, exists,
                Args.MATERIALIZED, materialized));
    }

    @Override
    protected Expression parseToTable() {
        Expression table = parseTableParts(true);
        return expression(Expression.of(ExprType.TO_TABLE_PROPERTY, Args.THIS, table));
    }

    private Expression parseTtlAction() {
        Expression this_ = parseBitwise();

        if (matchTextSeq("DELETE")) {
            return expression(Expression.of(ExprType.MERGE_TREE_TTL_ACTION, Args.THIS, this_, Args.DELETE, true));
        }
        if (matchTextSeq("RECOMPRESS")) {
            return expression(Expression.of(ExprType.MERGE_TREE_TTL_ACTION,
                    Args.THIS, this_, Args.RECOMPRESS, parseBitwise()));
        }
        if (matchTextSeq("TO", "DISK")) {
            return expression(Expression.of(ExprType.MERGE_TREE_TTL_ACTION,
                    Args.THIS, this_, Args.TO_DISK, parseString()));
        }
        if (matchTextSeq("TO", "VOLUME")) {
            return expression(Expression.of(ExprType.MERGE_TREE_TTL_ACTION,
                    Args.THIS, this_, Args.TO_VOLUME, parseString()));
        }

        return this_;
    }

    // https://clickhouse.com/docs/en/engines/table-engines/mergetree-family/mergetree#mergetree-table-ttl
    @Override
    protected Expression parseTtl() {
        List<Expression> expressions = parseCsv(this::parseTtlAction);
        Expression where = parseWhere();
        Expression group = parseGroup();

        List<Expression> aggregates = null;
        if (group != null && match(TokenType.SET)) {
            aggregates = parseCsv(() -> parseSetItem());
        }

        return expression(Expression.of(ExprType.MERGE_TREE_TTL,
                Args.EXPRESSIONS, expressions, Args.WHERE, where, Args.GROUP, group, Args.AGGREGATES, aggregates));
    }

    @Override
    protected Expression parseCondition() {
        return parseWrapped(() -> parseExpression(), true);
    }

    @Override
    protected Expression parseBlock() {
        return expression(Expression.of(ExprType.BLOCK,
                Args.EXPRESSIONS, parseBatchStatements(p -> p.parseStatement(), true)));
    }

    @Override
    protected Expression parseWhileblock() {
        return expression(Expression.of(ExprType.WHILE_BLOCK, Args.THIS, parseCondition(), Args.BODY, parseBlock()));
    }

    @Override
    protected Expression parseStatement() {
        if (!present(curr)) {
            return null;
        }

        if (matchSet(d.STATEMENT_PARSERS)) {
            List<String> comments = prevComments;
            Expression stmt = statementParser(prev.type());
            stmt.addComments(comments, true);
            return stmt;
        }

        if (matchSet(dialect.tokenizerConfig().commands())) {
            return parseCommand();
        }

        if (matchTextSeq("WHILE")) {
            return parseWhileblock();
        }

        Expression expression = parseExpression();
        expression = expression != null ? parseSetOperations(expression) : parseSelect();

        if (expression != null && expression.is(ExprType.SUBQUERY) && match(TokenType.PIPE_GT, false)) {
            expression = parsePipeSyntaxQuery(expression);
        }

        return (Expression) parseQueryModifiers(expression);
    }

    @Override
    protected Expression parseDrop(boolean exists, String kind) {
        Token start = prev;

        boolean temporary;
        boolean materialized;
        boolean iceberg;
        if (kind == null || kind.isEmpty()) {
            temporary = match(TokenType.TEMPORARY);
            materialized = matchTextSeq("MATERIALIZED");
            iceberg = matchTextSeq("ICEBERG");
            kind = matchSet(d.CREATABLES) ? Py.upper(prev.text()) : null;
        } else {
            temporary = false;
            materialized = false;
            iceberg = false;
        }

        if (kind == null || kind.isEmpty() || (iceberg && !kind.equals("TABLE"))) {
            return parseAsCommand(start);
        }

        boolean concurrently = kind.equals("INDEX")
                && d.ID_VAR_TOKENS.contains(next.type())
                && matchTextSeq("CONCURRENTLY");

        Boolean ifExists = exists ? Boolean.TRUE : parseExists();

        Object tables;
        if (kind.equals("COLUMN")) {
            // A dropped column named after a keyword, like `any` or `charset`, is only a name
            Expression name = d.NO_PAREN_FUNCTION_PARSERS.contains(Py.upper(curr.text())) ? null : parseColumn();
            if (name == null) {
                name = parseIdVar();
            }
            tables = name != null && name.is(ExprType.IDENTIFIER)
                    ? expression(Expression.of(ExprType.COLUMN, Args.THIS, name))
                    : name;
        } else if (kind.equals("TABLE") || kind.equals("VIEW")) {
            tables = parseCsv(() -> parseTableParts(true));
        } else {
            tables = parseTableParts(true, kind.equals("SCHEMA"));
        }

        Expression cluster = null;
        if (match(TokenType.ON)) {
            if (kind.equals("INDEX")) {
                cluster = expression(Expression.of(ExprType.ON_PROPERTY, Args.THIS, parseTableParts()));
            } else {
                cluster = parseOnProperty();
            }
        }

        List<Expression> expressions;
        if (match(TokenType.L_PAREN, false)) {
            expressions = parseWrappedCsv(() -> parseTypes());
        } else {
            expressions = null;
        }

        Object cascadeOrRestrict = matchTexts(CASCADE_RESTRICT) ? Py.upper(prev.text()) : Boolean.FALSE;

        String mappedKind = dd.CREATABLE_KIND_MAPPING.get(kind);
        return expression(Expression.of(ExprType.DROP,
                Args.EXISTS, ifExists,
                Args.TABLES, Py.ensureList(tables),
                Args.EXPRESSIONS, expressions,
                Args.KIND, mappedKind != null && !mappedKind.isEmpty() ? mappedKind : kind,
                Args.TEMPORARY, temporary,
                Args.MATERIALIZED, materialized,
                Args.CASCADE, "CASCADE".equals(cascadeOrRestrict),
                Args.RESTRICT, "RESTRICT".equals(cascadeOrRestrict),
                Args.CONSTRAINTS, matchTextSeq("CONSTRAINTS"),
                Args.PURGE, matchTextSeq("PURGE"),
                Args.CLUSTER, cluster,
                Args.CONCURRENTLY, concurrently,
                Args.SYNC, matchTextSeq("SYNC"),
                Args.ICEBERG, iceberg,
                Args.FORCE, matchTextSeq("FORCE")));
    }

    @Override
    protected Boolean parseExists(boolean not) {
        if (not) {
            return matchTextSeq("IF", "NOT", "EXISTS");
        }
        return matchTextSeq("IF", "EXISTS");
    }

    @Override
    protected Expression parseCreate() {
        // Note: this can't be None because we've matched a statement parser
        Token start = prev;

        boolean replace = start.type() == TokenType.REPLACE
                || matchPair(TokenType.OR, TokenType.REPLACE)
                || matchPair(TokenType.OR, TokenType.ALTER);
        boolean refresh = matchPair(TokenType.OR, TokenType.REFRESH);

        boolean unique = match(TokenType.UNIQUE);

        Boolean clustered;
        if (matchTextSeq("CLUSTERED", "COLUMNSTORE")) {
            clustered = true;
        } else if (matchTextSeq("NONCLUSTERED", "COLUMNSTORE") || matchTextSeq("COLUMNSTORE")) {
            clustered = false;
        } else {
            clustered = null;
        }

        if (matchPair(TokenType.TABLE, TokenType.FUNCTION, false)) {
            advance();
        }

        Expression[] properties = {null};
        Token createToken = matchSet(d.CREATABLES) ? prev : null;

        if (createToken == null) {
            // exp.Properties.Location.POST_CREATE
            properties[0] = parseProperties();
            createToken = matchSet(d.CREATABLES) ? prev : null;

            if (properties[0] == null || createToken == null) {
                return parseAsCommand(start);
            }
        }

        TokenType createTokenType = createToken.type();

        boolean concurrently = matchTextSeq("CONCURRENTLY");
        Boolean exists = parseExists(true);
        Expression this_ = null;
        Expression expression = null;
        List<Expression> indexes = null;
        Boolean noSchemaBinding = null;
        Boolean begin = null;
        Expression clone = null;

        if (createTokenType == TokenType.FUNCTION || createTokenType == TokenType.PROCEDURE) {
            this_ = parseUserDefinedFunction(createTokenType);

            // exp.Properties.Location.POST_SCHEMA ("schema" here is the UDF's type signature)
            extendProps(properties, parseProperties());

            expression = match(TokenType.ALIAS) ? parseHeredoc() : null;

            boolean isTable;
            boolean overloadMode;
            if (expression == null
                    && createTokenType == TokenType.FUNCTION
                    && this_ != null && this_.is(ExprType.USER_DEFINED_FUNCTION)
                    && this_.has(Args.WRAPPED)) {
                int preTableIndex = tokenIndex;
                isTable = match(TokenType.TABLE);

                expression = parseExpression();
                overloadMode = expression != null
                        && curr.type() == TokenType.COMMA
                        && next.type() == TokenType.L_PAREN;
                if (!overloadMode) {
                    retreat(preTableIndex);
                    isTable = false;
                    expression = null;
                }
            } else {
                isTable = false;
                overloadMode = false;
            }

            extendProps(properties, parseFunctionProperties());

            if (expression == null) {
                if (match(TokenType.COMMAND)) {
                    expression = parseAsCommand(prev);
                } else {
                    begin = match(TokenType.BEGIN);
                    boolean return_ = matchTextSeq("RETURN");

                    if (match(TokenType.STRING, false)) {
                        // Takes care of BigQuery's JavaScript UDF definitions that end in an OPTIONS property
                        // # https://cloud.google.com/bigquery/docs/reference/standard-sql/data-definition-language#create_function_statement
                        expression = parseString();
                        extendProps(properties, parseProperties());
                    } else {
                        expression = createTokenType == TokenType.FUNCTION
                                ? parseUserDefinedFunctionExpression()
                                : parseBlock();
                    }

                    if (return_) {
                        expression = expression(Expression.of(ExprType.RETURN, Args.THIS, expression));
                    }
                }
            }

            if (overloadMode && expression != null) {
                expression = parseMacroOverloads(this_, expression, isTable);
            }
        } else if (createTokenType == TokenType.INDEX) {
            // Postgres allows anonymous indexes, eg. CREATE INDEX IF NOT EXISTS ON t(c)
            Expression index;
            boolean anonymous;
            if (!match(TokenType.ON)) {
                index = parseIdVar();
                anonymous = false;
            } else {
                index = null;
                anonymous = true;
            }

            this_ = parseIndex(index, anonymous);
        } else if ((createTokenType == TokenType.CONSTRAINT && match(TokenType.TRIGGER))
                || createTokenType == TokenType.TRIGGER) {
            boolean isConstraint = createTokenType == TokenType.CONSTRAINT;
            if (isConstraint) {
                createToken = prev;
            }

            Expression triggerName = parseIdVar();
            if (triggerName == null) {
                return parseAsCommand(start);
            }

            Expression timingVar = parseVarFromOptions(d.TRIGGER_TIMING, false);
            Object timing = timingVar != null ? timingVar.thisValue() : null;
            if (!Expression.truthy(timing)) {
                return parseAsCommand(start);
            }

            List<Expression> events = parseTriggerEvents();
            if (!match(TokenType.ON)) {
                raiseError("Expected ON in trigger definition");
            }

            Expression table = parseTableParts();
            Expression referencedTable = match(TokenType.FROM) ? parseTableParts() : null;
            Object[] deferrableInitially = parseTriggerDeferrable();
            Object deferrable = deferrableInitially[0];
            Object initially = deferrableInitially[1];
            Expression referencing = parseTriggerReferencing();
            String forEach = parseTriggerForEach();
            Object when = matchTextSeq("WHEN") ? parseWrapped(() -> parseDisjunction(), true) : Boolean.FALSE;
            Expression execute = parseTriggerExecute();

            if (execute == null) {
                return parseAsCommand(start);
            }

            Expression triggerProps = expression(Expression.of(ExprType.TRIGGER_PROPERTIES,
                    Args.TABLE, table,
                    Args.TIMING, timing,
                    Args.EVENTS, events,
                    Args.EXECUTE, execute,
                    Args.CONSTRAINT, isConstraint,
                    Args.REFERENCED_TABLE, referencedTable,
                    Args.DEFERRABLE, deferrable,
                    Args.INITIALLY, initially,
                    Args.REFERENCING, referencing,
                    Args.FOR_EACH, forEach,
                    Args.WHEN, when));

            this_ = triggerName;
            List<Expression> triggerList = new ArrayList<>();
            if (triggerProps != null) {
                triggerList.add(triggerProps);
            }
            extendProps(properties, Expression.of(ExprType.PROPERTIES, Args.EXPRESSIONS, triggerList));
        } else if (createTokenType == TokenType.TYPE) {
            this_ = parseTableParts(true);
            if (this_ == null || !match(TokenType.ALIAS)) {
                return parseAsCommand(start);
            }

            if (match(TokenType.ENUM)) {
                expression = Expression.of(ExprType.DATA_TYPE,
                        Args.THIS, DType.ENUM,
                        Args.EXPRESSIONS, parseWrappedCsv(() -> parseString()));
            } else if (match(TokenType.L_PAREN, false)) {
                expression = parseSchema();
            } else {
                return parseAsCommand(start);
            }
        } else if (d.DB_CREATABLES.contains(createTokenType)) {
            Expression tableParts = parseTableParts(true, createTokenType == TokenType.SCHEMA);

            // exp.Properties.Location.POST_NAME
            match(TokenType.COMMA);
            extendProps(properties, parseProperties(true));

            this_ = parseSchema(tableParts);

            // exp.Properties.Location.POST_SCHEMA and POST_WITH
            extendProps(properties, parseProperties());

            boolean hasAlias = match(TokenType.ALIAS);
            if (!matchSet(d.DDL_SELECT_TOKENS, false)) {
                // exp.Properties.Location.POST_ALIAS
                extendProps(properties, parseProperties());
            }

            if (createTokenType == TokenType.SEQUENCE) {
                expression = parseTypes();
                Expression props = parseProperties();
                if (props != null) {
                    Expression sequenceProps = Expression.of(ExprType.SEQUENCE_PROPERTIES);
                    List<Object> options = new ArrayList<>();
                    // Iterates the live list like Python's `for prop in props`, so popping an
                    // element skips the one after it, as in sqlglot.
                    List<Object> live = props.list(Args.EXPRESSIONS);
                    for (int i = 0; i < live.size(); i++) {
                        Object prop = live.get(i);
                        if (prop instanceof Expression p && p.is(ExprType.SEQUENCE_PROPERTIES)) {
                            for (Map.Entry<Arg, Object> entry : p.argEntries()) {
                                if (entry.getKey() == Args.OPTIONS) {
                                    options.addAll(Py.ensureList(entry.getValue()));
                                } else {
                                    sequenceProps.set(entry.getKey(), entry.getValue());
                                }
                            }
                            p.pop();
                        }
                    }

                    if (!options.isEmpty()) {
                        sequenceProps.set(Args.OPTIONS, options);
                    }

                    props.append(Args.EXPRESSIONS, sequenceProps);
                    extendProps(properties, props);
                }
            } else {
                expression = parseDdlSelect();

                // Some dialects also support using a table as an alias instead of a SELECT.
                // Here we fallback to this as an alternative.
                if (expression == null && hasAlias) {
                    expression = tryParse(() -> parseTableParts());
                }
            }

            if (createTokenType == TokenType.TABLE) {
                // exp.Properties.Location.POST_EXPRESSION
                extendProps(properties, parseProperties());

                indexes = new ArrayList<>();
                while (true) {
                    Expression index = parseIndex();

                    // exp.Properties.Location.POST_INDEX
                    extendProps(properties, parseProperties());
                    if (index == null) {
                        break;
                    } else {
                        match(TokenType.COMMA);
                        indexes.add(index);
                    }
                }
            } else if (createTokenType == TokenType.VIEW) {
                if (matchTextSeq("WITH", "NO", "SCHEMA", "BINDING")) {
                    noSchemaBinding = true;
                }
            } else if (createTokenType == TokenType.SINK || createTokenType == TokenType.SOURCE) {
                extendProps(properties, parseProperties());
            }

            boolean shallow = matchTextSeq("SHALLOW");

            if (matchTexts(d.CLONE_KEYWORDS)) {
                boolean copy = Py.lower(prev.text()).equals("copy");
                clone = expression(Expression.of(ExprType.CLONE,
                        Args.THIS, parseTable(true), Args.SHALLOW, shallow, Args.COPY, copy));
            }
        }

        if (present(curr) && !matchSet(R_PAREN_OR_COMMA, false)) {
            return parseAsCommand(start);
        }

        String createKindText = Py.upper(createToken.text());
        String mappedKind = dd.CREATABLE_KIND_MAPPING.get(createKindText);
        return expression(Expression.of(ExprType.CREATE,
                Args.THIS, this_,
                Args.KIND, mappedKind != null && !mappedKind.isEmpty() ? mappedKind : createKindText,
                Args.REPLACE, replace,
                Args.REFRESH, refresh,
                Args.UNIQUE, unique,
                Args.EXPRESSION, expression,
                Args.EXISTS, exists,
                Args.PROPERTIES, properties[0],
                Args.INDEXES, indexes,
                Args.NO_SCHEMA_BINDING, noSchemaBinding,
                Args.BEGIN, begin,
                Args.CLONE, clone,
                Args.CONCURRENTLY, concurrently,
                Args.CLUSTERED, clustered));
    }

    @Override
    protected Expression parseSequenceProperties() {
        Expression seq = Expression.of(ExprType.SEQUENCE_PROPERTIES);

        List<Expression> options = new ArrayList<>();
        int index = tokenIndex;

        while (present(curr)) {
            match(TokenType.COMMA);
            if (matchTextSeq("INCREMENT")) {
                matchTextSeq("BY");
                matchTextSeq("=");
                seq.set(Args.INCREMENT, parseTerm());
            } else if (matchTextSeq("MINVALUE")) {
                seq.set(Args.MINVALUE, parseTerm());
            } else if (matchTextSeq("MAXVALUE")) {
                seq.set(Args.MAXVALUE, parseTerm());
            } else if (matchTextSeq("START")) {
                matchTextSeq("WITH");
                matchTextSeq("=");
                seq.set(Args.START, parseTerm());
            } else if (matchTextSeq("CACHE")) {
                // T-SQL allows empty CACHE which is initialized dynamically
                Expression number = parseNumber();
                seq.set(Args.CACHE, number != null ? number : (Object) Boolean.TRUE);
            } else if (matchTextSeq("OWNED", "BY")) {
                // "OWNED BY NONE" is the default
                seq.set(Args.OWNED, matchTextSeq("NONE") ? null : parseColumn());
            } else {
                Expression opt = parseVarFromOptions(d.CREATE_SEQUENCE, false);
                if (opt != null) {
                    options.add(opt);
                } else {
                    break;
                }
            }
        }

        seq.set(Args.OPTIONS, !options.isEmpty() ? options : null);
        return tokenIndex == index ? null : seq;
    }

    @Override
    protected List<Expression> parseTriggerEvents() {
        List<Expression> events = new ArrayList<>();

        while (true) {
            Object eventType = matchSet(d.TRIGGER_EVENTS) ? Py.upper(prev.text()) : Boolean.FALSE;

            if (!Expression.truthy(eventType)) {
                raiseError("Expected trigger event (INSERT, UPDATE, DELETE, TRUNCATE)");
            }

            List<Expression> columns = "UPDATE".equals(eventType) && matchTextSeq("OF")
                    ? parseCsv(() -> parseColumn())
                    : null;

            events.add(expression(Expression.of(ExprType.TRIGGER_EVENT, Args.THIS, eventType, Args.COLUMNS, columns)));

            if (!match(TokenType.OR)) {
                break;
            }
        }

        return events;
    }

    @Override
    protected Object[] parseTriggerDeferrable() {
        Expression deferrableVar = parseVarFromOptions(d.TRIGGER_DEFERRABLE, false);
        Object deferrable = deferrableVar != null ? deferrableVar.thisValue() : null;

        String initially = null;
        if (Expression.truthy(deferrable) && matchTextSeq("INITIALLY")) {
            initially = matchTexts(IMMEDIATE_DEFERRED) ? Py.upper(prev.text()) : null;
        }

        return new Object[] {deferrable, initially};
    }

    @Override
    protected Expression parseTriggerReferencingClause(String keyword) {
        if (!matchTextSeq(keyword)) {
            return null;
        }
        if (!matchTextSeq("TABLE")) {
            raiseError("Expected TABLE after " + keyword + " in REFERENCING clause");
        }
        matchTextSeq("AS");
        return parseIdVar();
    }

    @Override
    protected Expression parseTriggerReferencing() {
        if (!matchTextSeq("REFERENCING")) {
            return null;
        }

        Expression oldAlias = null;
        Expression newAlias = null;

        while (true) {
            Expression alias;
            if ((alias = parseTriggerReferencingClause("OLD")) != null) {
                if (oldAlias != null) {
                    raiseError("Duplicate OLD clause in REFERENCING");
                }
                oldAlias = alias;
            } else if ((alias = parseTriggerReferencingClause("NEW")) != null) {
                if (newAlias != null) {
                    raiseError("Duplicate NEW clause in REFERENCING");
                }
                newAlias = alias;
            } else {
                break;
            }
        }

        if (oldAlias == null && newAlias == null) {
            raiseError("REFERENCING clause requires at least OLD TABLE or NEW TABLE");
        }

        return expression(Expression.of(ExprType.TRIGGER_REFERENCING, Args.OLD, oldAlias, Args.NEW, newAlias));
    }

    @Override
    protected String parseTriggerForEach() {
        if (!matchTextSeq("FOR", "EACH")) {
            return null;
        }

        return matchTexts(ROW_STATEMENT) ? Py.upper(prev.text()) : null;
    }

    @Override
    protected Expression parseTriggerExecute() {
        if (!match(TokenType.EXECUTE)) {
            return null;
        }

        if (!matchSet(FUNCTION_OR_PROCEDURE)) {
            raiseError("Expected FUNCTION or PROCEDURE after EXECUTE");
        }

        Expression funcCall = parseColumn();
        return expression(Expression.of(ExprType.TRIGGER_EXECUTE, Args.THIS, funcCall));
    }

    @Override
    protected Object parsePropertyBefore() {
        // only used for teradata currently
        match(TokenType.COMMA);

        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("no", matchTextSeq("NO"));
        kwargs.put("dual", matchTextSeq("DUAL"));
        kwargs.put("before", matchTextSeq("BEFORE"));
        kwargs.put("default", matchTextSeq("DEFAULT"));
        Object local;
        if (matchTextSeq("LOCAL")) {
            local = "LOCAL";
        } else if (matchTextSeq("NOT", "LOCAL")) {
            local = "NOT LOCAL";
        } else {
            local = Boolean.FALSE;
        }
        kwargs.put("local", local);
        kwargs.put("after", matchTextSeq("AFTER"));
        kwargs.put("minimum", matchTexts(MIN_MINIMUM));
        kwargs.put("maximum", matchTexts(MAX_MAXIMUM));

        if (matchTexts(d.PROPERTY_PARSERS)) {
            String key = Py.upper(prev.text());
            Map<String, Object> passed = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : kwargs.entrySet()) {
                if (Expression.truthy(e.getValue())) {
                    passed.put(e.getKey(), e.getValue());
                }
            }
            // sqlglot catches the TypeError raised when a property parser doesn't accept the
            // given keyword arguments; jglot's property parsers signal that with
            // IllegalArgumentException.
            try {
                return propertyParser(key, passed);
            } catch (IllegalArgumentException e) {
                raiseError("Cannot parse property '" + prev.text() + "'");
            }
        }

        if (matchPair(TokenType.CHAR, TokenType.SET)) {
            return parseCharacterSet(Expression.truthy(kwargs.get("default")));
        }

        return null;
    }

    @Override
    protected List<Object> parseWrappedProperties() {
        return parseWrapped(() -> parseCsvOf(() -> parseProperty(), TokenType.COMMA));
    }

    @Override
    protected Object parseProperty() {
        if (matchTexts(d.PROPERTY_PARSERS)) {
            return propertyParser(Py.upper(prev.text()), Map.of());
        }

        if (matchPair(TokenType.CHAR, TokenType.SET)) {
            return parseCharacterSet();
        }

        if (match(TokenType.DEFAULT)) {
            if (matchTexts(d.PROPERTY_PARSERS)) {
                return propertyParser(Py.upper(prev.text()), Map.of("default", Boolean.TRUE));
            }

            if (matchPair(TokenType.CHAR, TokenType.SET)) {
                return parseCharacterSet(true);
            }
        }

        if (matchTextSeq("COMPOUND", "SORTKEY")) {
            return parseSortkey(true);
        }

        if (matchTextSeq("PARAMETER", "STYLE", "PANDAS")) {
            return expression(Expression.of(ExprType.PARAMETER_STYLE_PROPERTY, Args.THIS, "PANDAS"));
        }

        if (matchTextSeq("NOT", "DETERMINISTIC")) {
            return expression(Expression.of(ExprType.STABILITY_PROPERTY, Args.THIS, Exp.string("VOLATILE")));
        }

        int index = tokenIndex;

        Expression seqProps = parseSequenceProperties();
        if (seqProps != null) {
            return seqProps;
        }

        retreat(index);
        return parseKeyValueProperty();
    }

    @Override
    protected Expression parseKeyValueProperty(ParseFn parseValue) {
        int index = tokenIndex;
        Expression key = parseColumn();

        if (!match(TokenType.EQ)) {
            retreat(index);
            return null;
        }

        // Transform the key to exp.Dot if it's dotted identifiers wrapped in exp.Column or to exp.Var otherwise
        if (key != null && key.is(ExprType.COLUMN)) {
            key = key.parts().size() > 1 ? key.toDot(true) : pyVar(key.name());
        }

        Expression value;
        if (parseValue != null) {
            value = parseValue.parse();
        } else {
            value = parseBitwise();
            if (value == null) {
                value = parseVar(true);
            }
        }

        // Transform the value to exp.Var if it was parsed as exp.Column(exp.Identifier())
        if (value != null && value.is(ExprType.COLUMN)) {
            value = pyVar(value.name());
        }

        return expression(Expression.of(ExprType.PROPERTY, Args.THIS, key, Args.VALUE, value));
    }

    @Override
    protected Expression parseStored() {
        if (matchTextSeq("BY")) {
            return expression(Expression.of(ExprType.STORAGE_HANDLER_PROPERTY, Args.THIS, parseVarOrString()));
        }

        match(TokenType.ALIAS);
        Expression inputFormat = matchTextSeq("INPUTFORMAT") ? parseString() : null;
        Expression outputFormat = matchTextSeq("OUTPUTFORMAT") ? parseString() : null;

        Expression this_;
        if (inputFormat != null || outputFormat != null) {
            this_ = expression(Expression.of(ExprType.INPUT_OUTPUT_FORMAT,
                    Args.INPUT_FORMAT, inputFormat, Args.OUTPUT_FORMAT, outputFormat));
        } else {
            this_ = parseVarOrString();
            if (this_ == null) {
                this_ = parseNumber();
            }
            if (this_ == null) {
                this_ = parseIdVar();
            }
        }

        return expression(Expression.of(ExprType.FILE_FORMAT_PROPERTY, Args.THIS, this_, Args.HIVE_FORMAT, true));
    }

    @Override
    protected Expression parseUnquotedField() {
        Expression field = parseField();
        if (field != null && field.is(ExprType.IDENTIFIER) && !field.has(Args.QUOTED)) {
            field = pyVar(field);
        }

        return field;
    }

    @Override
    protected Expression parsePropertyAssignment(ExprType expClass, Map<Arg, Object> kwargs) {
        match(TokenType.EQ);
        match(TokenType.ALIAS);

        Map<Arg, Object> args = new LinkedHashMap<>();
        args.put(Args.THIS, parseUnquotedField());
        if (kwargs != null) {
            args.putAll(kwargs);
        }
        return expression(Expression.of(expClass, args));
    }

    @Override
    protected Expression parseProperties(Boolean before) {
        List<Expression> properties = new ArrayList<>();
        while (true) {
            Object prop;
            if (Boolean.TRUE.equals(before)) {
                prop = parsePropertyBefore();
            } else {
                prop = parseProperty();
            }
            if (!Expression.truthy(prop)) {
                break;
            }
            for (Object p : Py.ensureList(prop)) {
                properties.add((Expression) p);
            }

            match(TokenType.COMMA, true, properties.get(properties.size() - 1));
        }

        if (!properties.isEmpty()) {
            return expression(Expression.of(ExprType.PROPERTIES, Args.EXPRESSIONS, properties));
        }

        return null;
    }

    @Override
    protected Expression parseFallback(boolean no) {
        return expression(Expression.of(ExprType.FALLBACK_PROPERTY,
                Args.NO, no, Args.PROTECTION, matchTextSeq("PROTECTION")));
    }

    @Override
    protected Expression parseSqlSecurity() {
        return expression(Expression.of(ExprType.SQL_SECURITY_PROPERTY,
                Args.THIS, matchTexts(d.SECURITY_PROPERTY_KEYWORDS) ? Py.upper(prev.text()) : Boolean.FALSE));
    }

    @Override
    protected Expression parseSettingsProperty() {
        return expression(Expression.of(ExprType.SETTINGS_PROPERTY,
                Args.EXPRESSIONS, parseCsv(() -> parseAssignment())));
    }

    @Override
    protected Expression parseCalledOnNullInputProperty() {
        if (!matchTextSeq("ON", "NULL", "INPUT")) {
            retreat(tokenIndex - 1);
            return null;
        }

        return expression(Expression.of(ExprType.CALLED_ON_NULL_INPUT_PROPERTY));
    }

    @Override
    protected Expression parseVolatileProperty() {
        Token preVolatileToken;
        if (tokenIndex >= 2) {
            preVolatileToken = tokenList.get(tokenIndex - 2);
        } else {
            preVolatileToken = null;
        }

        if (present(preVolatileToken) && d.PRE_VOLATILE_TOKENS.contains(preVolatileToken.type())) {
            return Expression.of(ExprType.VOLATILE_PROPERTY);
        }

        return expression(Expression.of(ExprType.STABILITY_PROPERTY, Args.THIS, Exp.string("VOLATILE")));
    }

    @Override
    protected Expression parseRetentionPeriod() {
        // Parse TSQL's HISTORY_RETENTION_PERIOD: {INFINITE | <number> DAY | DAYS | MONTH ...}
        Expression number = parseNumber();
        String numberStr = number != null ? pyStr(number) + " " : "";
        Expression unit = parseVar(true);
        return pyVar(numberStr + pyStr(unit));
    }

    @Override
    protected Expression parseSystemVersioningProperty(boolean with) {
        match(TokenType.EQ);
        Expression prop = expression(Expression.of(ExprType.WITH_SYSTEM_VERSIONING_PROPERTY,
                Args.ON, true, Args.WITH_, with));

        if (matchTextSeq("OFF")) {
            prop.set(Args.ON, false);
            return prop;
        }

        match(TokenType.ON);
        if (match(TokenType.L_PAREN)) {
            while (present(curr) && !match(TokenType.R_PAREN)) {
                if (matchTextSeq("HISTORY_TABLE", "=")) {
                    prop.set(Args.THIS, parseTableParts());
                } else if (matchTextSeq("DATA_CONSISTENCY_CHECK", "=")) {
                    prop.set(Args.DATA_CONSISTENCY, advanceAny() != null ? Py.upper(prev.text()) : null);
                } else if (matchTextSeq("HISTORY_RETENTION_PERIOD", "=")) {
                    prop.set(Args.RETENTION_PERIOD, parseRetentionPeriod());
                }

                match(TokenType.COMMA);
            }
        }

        return prop;
    }

    @Override
    protected Expression parseDataDeletionProperty() {
        match(TokenType.EQ);
        boolean on = matchTextSeq("ON") || !matchTextSeq("OFF");
        Expression prop = expression(Expression.of(ExprType.DATA_DELETION_PROPERTY, Args.ON, on));

        if (match(TokenType.L_PAREN)) {
            while (present(curr) && !match(TokenType.R_PAREN)) {
                if (matchTextSeq("FILTER_COLUMN", "=")) {
                    prop.set(Args.FILTER_COLUMN, parseColumn());
                } else if (matchTextSeq("RETENTION_PERIOD", "=")) {
                    prop.set(Args.RETENTION_PERIOD, parseRetentionPeriod());
                }

                match(TokenType.COMMA);
            }
        }

        return prop;
    }

    @Override
    protected Expression parseDistributedProperty() {
        String kind = "HASH";
        List<Expression> expressions = null;
        if (matchTextSeq("BY", "HASH")) {
            expressions = parseWrappedCsv(() -> parseIdVar());
        } else if (matchTextSeq("BY", "RANDOM")) {
            kind = "RANDOM";
        }

        // If the BUCKETS keyword is not present, the number of buckets is AUTO
        Expression buckets = null;
        if (matchTextSeq("BUCKETS") && !matchTextSeq("AUTO")) {
            buckets = parseNumber();
        }

        return expression(Expression.of(ExprType.DISTRIBUTED_BY_PROPERTY,
                Args.EXPRESSIONS, expressions, Args.KIND, kind, Args.BUCKETS, buckets, Args.ORDER, parseOrder()));
    }

    @Override
    protected Expression parseCompositeKeyProperty(ExprType exprType) {
        matchTextSeq("KEY");
        List<Expression> expressions = parseWrappedIdVars();
        return expression(Expression.of(exprType, Args.EXPRESSIONS, expressions));
    }

    @Override
    protected Object parseWithProperty() {
        if (matchTextSeq("(", "SYSTEM_VERSIONING")) {
            Expression prop = parseSystemVersioningProperty(true);
            matchRParen();
            return prop;
        }

        if (match(TokenType.L_PAREN, false)) {
            List<Expression> result = new ArrayList<>();
            for (Object i : parseWrappedProperties()) {
                if (i instanceof List<?> l) {
                    for (Object o : l) {
                        result.add((Expression) o);
                    }
                } else {
                    result.add((Expression) i);
                }
            }
            return result;
        }

        if (matchTextSeq("JOURNAL")) {
            return parseWithjournaltable();
        }

        if (matchTexts(d.VIEW_ATTRIBUTES)) {
            return expression(Expression.of(ExprType.VIEW_ATTRIBUTE_PROPERTY, Args.THIS, Py.upper(prev.text())));
        }

        if (matchTextSeq("DATA")) {
            return parseWithdata(false);
        } else if (matchTextSeq("NO", "DATA")) {
            return parseWithdata(true);
        }

        if (match(TokenType.SERDE_PROPERTIES, false)) {
            return parseSerdeProperties(true);
        }

        if (match(TokenType.SCHEMA)) {
            return expression(Expression.of(ExprType.WITH_SCHEMA_BINDING_PROPERTY,
                    Args.THIS, parseVarFromOptions(d.SCHEMA_BINDING_OPTIONS)));
        }

        if (matchTexts(d.PROCEDURE_OPTIONS.keySet(), false)) {
            return expression(Expression.of(ExprType.WITH_PROCEDURE_OPTIONS,
                    Args.EXPRESSIONS, parseCsv(() -> parseProcedureOption())));
        }

        if (!present(next)) {
            return null;
        }

        return parseWithisolatedloading();
    }

    @Override
    protected Expression parseProcedureOption() {
        if (matchTextSeq("EXECUTE", "AS")) {
            Expression this_ = parseVarFromOptions(d.EXECUTE_AS_OPTIONS, false);
            if (this_ == null) {
                this_ = parseString();
            }
            return expression(Expression.of(ExprType.EXECUTE_AS_PROPERTY, Args.THIS, this_));
        }

        return parseVarFromOptions(d.PROCEDURE_OPTIONS);
    }

    // https://dev.mysql.com/doc/refman/8.0/en/create-view.html
    @Override
    protected Expression parseDefiner() {
        match(TokenType.EQ);

        Expression user = parseIdVar();
        match(TokenType.PARAMETER);
        Object host = parseIdVar();
        if (host == null) {
            host = match(TokenType.MOD) ? prev.text() : Boolean.FALSE;
        }

        if (user == null || !Expression.truthy(host)) {
            return null;
        }

        return Expression.of(ExprType.DEFINER_PROPERTY, Args.THIS, pyStr(user) + "@" + pyStr(host));
    }

    @Override
    protected Expression parseWithjournaltable() {
        match(TokenType.TABLE);
        match(TokenType.EQ);
        return expression(Expression.of(ExprType.WITH_JOURNAL_TABLE_PROPERTY, Args.THIS, parseTableParts()));
    }

    @Override
    protected Expression parseLog(boolean no) {
        return expression(Expression.of(ExprType.LOG_PROPERTY, Args.NO, no));
    }

    @Override
    protected Expression parseJournal(Map<Arg, Object> kwargs) {
        return expression(Expression.of(ExprType.JOURNAL_PROPERTY, kwargs == null ? Map.<Arg, Object>of() : kwargs));
    }

    @Override
    protected Expression parseChecksum() {
        match(TokenType.EQ);

        Boolean on = null;
        if (match(TokenType.ON)) {
            on = true;
        } else if (matchTextSeq("OFF")) {
            on = false;
        }

        return expression(Expression.of(ExprType.CHECKSUM_PROPERTY,
                Args.ON, on, Args.DEFAULT, match(TokenType.DEFAULT)));
    }

    @Override
    protected Expression parseCluster() {
        match(TokenType.CLUSTER_BY);
        return expression(Expression.of(ExprType.CLUSTER, Args.EXPRESSIONS, parseCsv(() -> parseColumn())));
    }

    @Override
    protected Expression parseClusterProperty() {
        return expression(Expression.of(ExprType.CLUSTER_PROPERTY,
                Args.EXPRESSIONS, parseWrappedCsv(() -> parseColumn())));
    }

    @Override
    protected Expression parseClusteredBy() {
        matchTextSeq("BY");

        matchLParen();
        List<Expression> expressions = parseCsv(() -> parseColumn());
        matchRParen();

        List<Expression> sortedBy;
        if (matchTextSeq("SORTED", "BY")) {
            matchLParen();
            sortedBy = parseCsv(() -> parseOrdered());
            matchRParen();
        } else {
            sortedBy = null;
        }

        match(TokenType.INTO);
        Expression buckets = parseNumber();
        matchTextSeq("BUCKETS");

        return expression(Expression.of(ExprType.CLUSTERED_BY_PROPERTY,
                Args.EXPRESSIONS, expressions, Args.SORTED_BY, sortedBy, Args.BUCKETS, buckets));
    }

    @Override
    protected Expression parseCopyProperty() {
        if (!matchTextSeq("GRANTS")) {
            retreat(tokenIndex - 1);
            return null;
        }

        return expression(Expression.of(ExprType.COPY_GRANTS_PROPERTY));
    }

    @Override
    protected Expression parseFreespace() {
        match(TokenType.EQ);
        return expression(Expression.of(ExprType.FREESPACE_PROPERTY,
                Args.THIS, parseNumber(), Args.PERCENT, match(TokenType.PERCENT)));
    }

    @Override
    protected Expression parseMergeblockratio(boolean no, boolean default_) {
        if (match(TokenType.EQ)) {
            return expression(Expression.of(ExprType.MERGE_BLOCK_RATIO_PROPERTY,
                    Args.THIS, parseNumber(), Args.PERCENT, match(TokenType.PERCENT)));
        }

        return expression(Expression.of(ExprType.MERGE_BLOCK_RATIO_PROPERTY, Args.NO, no, Args.DEFAULT, default_));
    }

    @Override
    protected Expression parseDatablocksize(Boolean default_, Boolean minimum, Boolean maximum) {
        match(TokenType.EQ);
        Expression size = parseNumber();

        String units = null;
        if (matchTexts(BLOCKSIZE_UNITS)) {
            units = prev.text();
        }

        return expression(Expression.of(ExprType.DATA_BLOCKSIZE_PROPERTY,
                Args.SIZE, size, Args.UNITS, units, Args.DEFAULT, default_,
                Args.MINIMUM, minimum, Args.MAXIMUM, maximum));
    }

    @Override
    protected Expression parseBlockcompression() {
        match(TokenType.EQ);
        boolean always = matchTextSeq("ALWAYS");
        boolean manual = matchTextSeq("MANUAL");
        boolean never = matchTextSeq("NEVER");
        boolean default_ = matchTextSeq("DEFAULT");

        Expression autotemp = null;
        if (matchTextSeq("AUTOTEMP")) {
            autotemp = parseSchema();
        }

        return expression(Expression.of(ExprType.BLOCK_COMPRESSION_PROPERTY,
                Args.ALWAYS, always, Args.MANUAL, manual, Args.NEVER, never,
                Args.DEFAULT, default_, Args.AUTOTEMP, autotemp));
    }

    @Override
    protected Expression parseWithisolatedloading() {
        int index = tokenIndex;
        boolean no = matchTextSeq("NO");
        boolean concurrent = matchTextSeq("CONCURRENT");

        if (!matchTextSeq("ISOLATED", "LOADING")) {
            retreat(index);
            return null;
        }

        Expression target = parseVarFromOptions(d.ISOLATED_LOADING_OPTIONS, false);
        return expression(Expression.of(ExprType.ISOLATED_LOADING_PROPERTY,
                Args.NO, no, Args.CONCURRENT, concurrent, Args.TARGET, target));
    }

    @Override
    protected Expression parseLocking() {
        String kind;
        if (match(TokenType.TABLE)) {
            kind = "TABLE";
        } else if (match(TokenType.VIEW)) {
            kind = "VIEW";
        } else if (match(TokenType.ROW)) {
            kind = "ROW";
        } else if (matchTextSeq("DATABASE")) {
            kind = "DATABASE";
        } else {
            kind = null;
        }

        Expression this_;
        if (kind != null && (kind.equals("DATABASE") || kind.equals("TABLE") || kind.equals("VIEW"))) {
            this_ = parseTableParts();
        } else {
            this_ = null;
        }

        String forOrIn;
        if (match(TokenType.FOR)) {
            forOrIn = "FOR";
        } else if (match(TokenType.IN)) {
            forOrIn = "IN";
        } else {
            forOrIn = null;
        }

        String lockType;
        if (matchTextSeq("ACCESS")) {
            lockType = "ACCESS";
        } else if (matchTexts(EXCL_EXCLUSIVE)) {
            lockType = "EXCLUSIVE";
        } else if (matchTextSeq("SHARE")) {
            lockType = "SHARE";
        } else if (matchTextSeq("READ")) {
            lockType = "READ";
        } else if (matchTextSeq("WRITE")) {
            lockType = "WRITE";
        } else if (matchTextSeq("CHECKSUM")) {
            lockType = "CHECKSUM";
        } else {
            lockType = null;
        }

        boolean override = matchTextSeq("OVERRIDE");

        return expression(Expression.of(ExprType.LOCKING_PROPERTY,
                Args.THIS, this_, Args.KIND, kind, Args.FOR_OR_IN, forOrIn,
                Args.LOCK_TYPE, lockType, Args.OVERRIDE, override));
    }

    @Override
    protected List<Expression> parsePartitionBy() {
        if (match(TokenType.PARTITION_BY)) {
            return parseCsv(() -> parseDisjunction());
        }
        return new ArrayList<>();
    }

    private Expression parsePartitionBoundExpr() {
        if (matchTextSeq("MINVALUE")) {
            return Exp.var("MINVALUE");
        }
        if (matchTextSeq("MAXVALUE")) {
            return Exp.var("MAXVALUE");
        }
        return parseBitwise();
    }

    @Override
    protected Expression parsePartitionBoundSpec() {
        Object this_ = null;
        Expression expression = null;
        List<Expression> fromExpressions = null;
        List<Expression> toExpressions = null;

        if (match(TokenType.IN)) {
            this_ = parseWrappedCsv(() -> parseBitwise());
        } else if (match(TokenType.FROM)) {
            fromExpressions = parseWrappedCsv(this::parsePartitionBoundExpr);
            matchTextSeq("TO");
            toExpressions = parseWrappedCsv(this::parsePartitionBoundExpr);
        } else if (matchTextSeq("WITH", "(", "MODULUS")) {
            this_ = parseNumber();
            matchTextSeq(",", "REMAINDER");
            expression = parseNumber();
            matchRParen();
        } else {
            raiseError("Failed to parse partition bound spec.");
        }

        return expression(Expression.of(ExprType.PARTITION_BOUND_SPEC,
                Args.THIS, this_,
                Args.EXPRESSION, expression,
                Args.FROM_EXPRESSIONS, fromExpressions,
                Args.TO_EXPRESSIONS, toExpressions));
    }

    // https://www.postgresql.org/docs/current/sql-createtable.html
    @Override
    protected Expression parsePartitionedOf() {
        if (!matchTextSeq("OF")) {
            retreat(tokenIndex - 1);
            return null;
        }

        Expression this_ = parseTable(true);

        Expression expression = null;
        if (match(TokenType.DEFAULT)) {
            expression = Exp.var("DEFAULT");
        } else if (matchTextSeq("FOR", "VALUES")) {
            expression = parsePartitionBoundSpec();
        } else {
            raiseError("Expecting either DEFAULT or FOR VALUES clause.");
        }

        return expression(Expression.of(ExprType.PARTITIONED_OF_PROPERTY, Args.THIS, this_, Args.EXPRESSION, expression));
    }

    @Override
    protected Expression parsePartitionedBy() {
        match(TokenType.EQ);
        Expression this_ = parseSchema();
        if (this_ == null) {
            this_ = parseBracket(parseField());
        }
        return expression(Expression.of(ExprType.PARTITIONED_BY_PROPERTY, Args.THIS, this_));
    }

    @Override
    protected Expression parseWithdata(boolean no) {
        Boolean statistics;
        if (matchTextSeq("AND", "STATISTICS")) {
            statistics = true;
        } else if (matchTextSeq("AND", "NO", "STATISTICS")) {
            statistics = false;
        } else {
            statistics = null;
        }

        return expression(Expression.of(ExprType.WITH_DATA_PROPERTY, Args.NO, no, Args.STATISTICS, statistics));
    }

    @Override
    protected Expression parseContainsProperty() {
        if (matchTextSeq("SQL")) {
            return expression(Expression.of(ExprType.SQL_READ_WRITE_PROPERTY, Args.THIS, "CONTAINS SQL"));
        }
        return null;
    }

    @Override
    protected Expression parseModifiesProperty() {
        if (matchTextSeq("SQL", "DATA")) {
            return expression(Expression.of(ExprType.SQL_READ_WRITE_PROPERTY, Args.THIS, "MODIFIES SQL DATA"));
        }
        return null;
    }

    @Override
    protected Expression parseNoProperty() {
        if (matchTextSeq("PRIMARY", "INDEX")) {
            return Expression.of(ExprType.NO_PRIMARY_INDEX_PROPERTY);
        }
        if (matchTextSeq("SQL")) {
            return expression(Expression.of(ExprType.SQL_READ_WRITE_PROPERTY, Args.THIS, "NO SQL"));
        }
        return null;
    }

    @Override
    protected Expression parseOnProperty() {
        if (matchTextSeq("COMMIT", "PRESERVE", "ROWS")) {
            return Expression.of(ExprType.ON_COMMIT_PROPERTY);
        }
        if (matchTextSeq("COMMIT", "DELETE", "ROWS")) {
            return Expression.of(ExprType.ON_COMMIT_PROPERTY, Args.DELETE, true);
        }
        return expression(Expression.of(ExprType.ON_PROPERTY, Args.THIS, parseSchema(parseIdVar())));
    }

    @Override
    protected Expression parseReadsProperty() {
        if (matchTextSeq("SQL", "DATA")) {
            return expression(Expression.of(ExprType.SQL_READ_WRITE_PROPERTY, Args.THIS, "READS SQL DATA"));
        }
        return null;
    }

    @Override
    protected Expression parseDistkey() {
        return expression(Expression.of(ExprType.DIST_KEY_PROPERTY, Args.THIS, parseWrapped(() -> parseIdVar())));
    }

    @Override
    protected Expression parseCreateLike() {
        Expression table = parseTable(true);

        List<Expression> options = new ArrayList<>();
        while (matchTexts(INCLUDING_EXCLUDING)) {
            String this_ = Py.upper(prev.text());

            Expression idVar = parseIdVar();
            if (idVar == null) {
                return null;
            }

            options.add(expression(Expression.of(ExprType.PROPERTY,
                    Args.THIS, this_, Args.VALUE, pyVar(Py.upper((String) idVar.thisValue())))));
        }

        return expression(Expression.of(ExprType.LIKE_PROPERTY, Args.THIS, table, Args.EXPRESSIONS, options));
    }

    @Override
    protected Expression parseSortkey(boolean compound) {
        return expression(Expression.of(ExprType.SORT_KEY_PROPERTY,
                Args.THIS, parseWrappedIdVars(), Args.COMPOUND, compound));
    }

    @Override
    protected Expression parseCharacterSet(boolean default_) {
        match(TokenType.EQ);
        return expression(Expression.of(ExprType.CHARACTER_SET_PROPERTY,
                Args.THIS, parseVarOrString(), Args.DEFAULT, default_));
    }

    @Override
    protected Expression parseRemoteWithConnection() {
        matchTextSeq("WITH", "CONNECTION");
        return expression(Expression.of(ExprType.REMOTE_WITH_CONNECTION_MODEL_PROPERTY,
                Args.THIS, parseTableParts()));
    }

    @Override
    protected Expression parseReturns() {
        Expression value;
        Boolean null_ = null;
        boolean isTable = match(TokenType.TABLE);

        if (isTable) {
            if (match(TokenType.LT)) {
                value = expression(Expression.of(ExprType.SCHEMA,
                        Args.THIS, "TABLE", Args.EXPRESSIONS, parseCsv(() -> parseStructTypes())));
                if (!match(TokenType.GT)) {
                    raiseError("Expecting >");
                }
            } else {
                value = parseSchema(Exp.var("TABLE"));
            }
        } else if (matchTextSeq("NULL", "ON", "NULL", "INPUT")) {
            null_ = true;
            value = null;
        } else {
            value = parseTypes();
        }

        return expression(Expression.of(ExprType.RETURNS_PROPERTY,
                Args.THIS, value, Args.IS_TABLE, isTable, Args.NULL, null_));
    }

    @Override
    protected Expression parseDescribe() {
        String kind = matchSet(d.CREATABLES) ? prev.text() : null;
        String style = matchTexts(d.DESCRIBE_STYLES) ? Py.upper(prev.text()) : null;
        if (match(TokenType.DOT)) {
            style = null;
            retreat(tokenIndex - 2);
        }

        Object format = match(TokenType.FORMAT, false) ? parseProperty() : null;

        Expression this_;
        if (matchSet(d.STATEMENT_PARSERS, false)) {
            this_ = parseStatement();
        } else {
            this_ = parseTable(true);
        }

        Expression properties = parseProperties();
        List<Expression> expressions = properties != null ? properties.getExpressions() : null;
        Expression partition = parsePartition();
        return expression(Expression.of(ExprType.DESCRIBE,
                Args.THIS, this_,
                Args.STYLE, style,
                Args.KIND, kind,
                Args.EXPRESSIONS, expressions,
                Args.PARTITION, partition,
                Args.FORMAT, format,
                Args.AS_JSON, matchTextSeq("AS", "JSON")));
    }

    private Expression parseConditionalInsert() {
        Expression expression;
        if (match(TokenType.WHEN)) {
            expression = parseDisjunction();
            match(TokenType.THEN);
        } else {
            expression = null;
        }

        boolean else_ = match(TokenType.ELSE);

        if (!match(TokenType.INTO)) {
            return null;
        }

        return expression(Expression.of(ExprType.CONDITIONAL_INSERT,
                Args.THIS, expression(Expression.of(ExprType.INSERT,
                        Args.THIS, parseTable(true),
                        Args.EXPRESSION, parseDerivedTableValues())),
                Args.EXPRESSION, expression,
                Args.ELSE_, else_));
    }

    @Override
    protected Expression parseMultitableInserts(Collection<String> comments, String kind) {
        kind = kind != null && !kind.isEmpty() ? kind : Py.upper(prev.text());
        List<Expression> expressions = new ArrayList<>();

        Expression expression = parseConditionalInsert();
        while (expression != null) {
            expressions.add(expression);
            expression = parseConditionalInsert();
        }

        return expression(
                Expression.of(ExprType.MULTITABLE_INSERTS,
                        Args.KIND, kind, Args.EXPRESSIONS, expressions, Args.SOURCE, parseTable()),
                comments == null ? null : new ArrayList<>(comments));
    }

    @Override
    protected Expression parseInsert() {
        List<String> comments = new ArrayList<>();
        Expression hint = parseHint();
        boolean overwrite = match(TokenType.OVERWRITE);
        boolean ignore = match(TokenType.IGNORE);
        boolean local = matchTextSeq("LOCAL");
        Object alternative = null;
        Boolean isFunction = null;

        Expression this_;
        if (matchTextSeq("DIRECTORY")) {
            this_ = expression(Expression.of(ExprType.DIRECTORY,
                    Args.THIS, parseVarOrString(),
                    Args.LOCAL, local,
                    Args.ROW_FORMAT, parseRowFormat(true)));
        } else {
            if (matchSet(FIRST_OR_ALL)) {
                comments.addAll(Py.ensureList(prevComments));
                return parseMultitableInserts(comments);
            }

            // Oracle's conditional form defaults to ALL when the keyword is omitted
            if (match(TokenType.WHEN, false)) {
                return parseMultitableInserts(comments, "ALL");
            }

            if (match(TokenType.OR)) {
                alternative = matchTexts(d.INSERT_ALTERNATIVES) ? prev.text() : Boolean.FALSE;
            }

            match(TokenType.INTO);
            comments.addAll(Py.ensureList(prevComments));
            match(TokenType.TABLE);
            isFunction = match(TokenType.FUNCTION);

            this_ = isFunction ? parseFunction() : parseInsertTable();
        }

        // MySQL's INSERT ... SET is normalized into the INSERT ... (cols) VALUES (vals) variant
        Expression setValues = null;
        if (match(TokenType.SET)) {
            List<Expression> columns = new ArrayList<>();
            List<Expression> values = new ArrayList<>();

            parseCsv(() -> {
                Expression target = parseColumn();
                if (target != null && target.is(ExprType.COLUMN) && match(TokenType.EQ)) {
                    Expression value;
                    if (dd.SUPPORTS_VALUES_DEFAULT && match(TokenType.DEFAULT)) {
                        value = pyVar(Py.upper(prev.text()));
                    } else {
                        value = parseDisjunction();
                    }

                    if (value != null) {
                        columns.add(target.getThis());
                        values.add(value);
                        return value;
                    }
                }

                raiseError("Expected column assignment in INSERT ... SET");
                return null;
            });

            this_ = expression(Expression.of(ExprType.SCHEMA, Args.THIS, this_, Args.EXPRESSIONS, columns));
            setValues = expression(Expression.of(ExprType.VALUES,
                    Args.EXPRESSIONS, List.of(Expression.of(ExprType.TUPLE, Args.EXPRESSIONS, values)),
                    Args.ALIAS, parseTableAlias()));
        }

        Expression returning = parseReturning(); // TSQL allows RETURNING before source

        Object stored = matchTextSeq("STORED") ? parseStored() : Boolean.FALSE;
        boolean byName = matchTextSeq("BY", "NAME");
        Boolean exists = parseExists();
        Expression replaceWhere = null;
        List<Expression> replaceUsing = null;

        if (match(TokenType.REPLACE)) {
            if (match(TokenType.WHERE)) {
                replaceWhere = parseDisjunction();
            } else if (match(TokenType.USING)) {
                replaceUsing = parseUsingIdentifiers();
            }
        }

        Expression finalSetValues = setValues;
        return expression(
                Expression.of(ExprType.INSERT,
                        Args.HINT, hint,
                        Args.IS_FUNCTION, isFunction,
                        Args.THIS, this_,
                        Args.STORED, stored,
                        Args.BY_NAME, byName,
                        Args.EXISTS, exists,
                        Args.WHERE, replaceWhere,
                        Args.USING, replaceUsing,
                        Args.PARTITION, match(TokenType.PARTITION_BY) ? parsePartitionedBy() : Boolean.FALSE,
                        Args.SETTINGS, matchTextSeq("SETTINGS") ? parseSettingsProperty() : Boolean.FALSE,
                        Args.DEFAULT, matchTextSeq("DEFAULT", "VALUES"),
                        Args.EXPRESSION, insertExpression(finalSetValues),
                        Args.CONFLICT, parseOnConflict(),
                        Args.RETURNING, returning != null ? returning : parseReturning(),
                        Args.OVERWRITE, overwrite,
                        Args.ALTERNATIVE, alternative,
                        Args.IGNORE, ignore,
                        Args.SOURCE, match(TokenType.TABLE) ? parseTable() : Boolean.FALSE),
                comments);
    }

    /** {@code set_values or self._parse_derived_table_values(allow_value_synonym=True) or self._parse_ddl_select()}. */
    private Expression insertExpression(Expression setValues) {
        if (setValues != null) {
            return setValues;
        }
        Expression e = parseDerivedTableValues(true);
        return e != null ? e : parseDdlSelect();
    }

    @Override
    protected Expression parseInsertTable() {
        Expression this_ = parseTable(true, false, null, false, false, true);
        if (this_ != null && this_.is(ExprType.TABLE) && match(TokenType.ALIAS, false)) {
            this_.set(Args.ALIAS, parseTableAlias());
        }
        return this_;
    }

    @Override
    protected Expression parseKill() {
        Expression kind = matchTexts(CONNECTION_QUERY) ? pyVar(prev.text()) : null;

        return expression(Expression.of(ExprType.KILL, Args.THIS, parsePrimary(), Args.KIND, kind));
    }

    @Override
    protected Expression parseOnConflict() {
        boolean conflict = matchTextSeq("ON", "CONFLICT");
        boolean duplicate = matchTextSeq("ON", "DUPLICATE", "KEY");

        if (!conflict && !duplicate) {
            return null;
        }

        List<Expression> conflictKeys = null;
        Expression constraint = null;

        if (conflict) {
            if (matchTextSeq("ON", "CONSTRAINT")) {
                constraint = parseIdVar();
            } else if (match(TokenType.L_PAREN)) {
                conflictKeys = parseCsv(() -> parseIndexedColumn());
                matchRParen();
            }
        }

        Expression indexPredicate = parseWhere();

        Expression action = parseVarFromOptions(d.CONFLICT_ACTIONS);
        List<Expression> expressions;
        if (prev.type() == TokenType.UPDATE) {
            match(TokenType.SET);
            expressions = parseCsv(() -> parseUpdateAssignment());
        } else {
            expressions = null;
        }

        return expression(Expression.of(ExprType.ON_CONFLICT,
                Args.DUPLICATE, duplicate,
                Args.EXPRESSIONS, expressions,
                Args.ACTION, action,
                Args.CONFLICT_KEYS, conflictKeys,
                Args.INDEX_PREDICATE, indexPredicate,
                Args.CONSTRAINT, constraint,
                Args.WHERE, parseWhere()));
    }

    @Override
    protected Expression parseReturning() {
        if (!match(TokenType.RETURNING)) {
            return null;
        }
        return expression(Expression.of(ExprType.RETURNING,
                Args.EXPRESSIONS, parseCsv(() -> parseExpression()),
                Args.INTO, match(TokenType.INTO) ? parseTablePart() : Boolean.FALSE));
    }

    @Override
    protected Expression parseRow() {
        if (!match(TokenType.FORMAT)) {
            return null;
        }
        return parseRowFormat();
    }

    @Override
    protected Expression parseSerdeProperties(boolean with) {
        int index = tokenIndex;
        boolean with_ = with || matchTextSeq("WITH");

        if (!match(TokenType.SERDE_PROPERTIES)) {
            retreat(index);
            return null;
        }
        return expression(Expression.of(ExprType.SERDE_PROPERTIES,
                Args.EXPRESSIONS, parseWrappedProperties(), Args.WITH_, with_));
    }

    @Override
    protected Expression parseRowFormat(boolean matchRow) {
        if (matchRow && !matchPair(TokenType.ROW, TokenType.FORMAT)) {
            return null;
        }

        if (matchTextSeq("SERDE")) {
            Expression this_ = parseString();

            Expression serdeProperties = parseSerdeProperties();

            return expression(Expression.of(ExprType.ROW_FORMAT_SERDE_PROPERTY,
                    Args.THIS, this_, Args.SERDE_PROPERTIES, serdeProperties));
        }

        matchTextSeq("DELIMITED");

        Map<Arg, Object> kwargs = new LinkedHashMap<>();

        if (matchTextSeq("FIELDS", "TERMINATED", "BY")) {
            kwargs.put(Args.FIELDS, parseString());
            if (matchTextSeq("ESCAPED", "BY")) {
                kwargs.put(Args.ESCAPED, parseString());
            }
        }
        if (matchTextSeq("COLLECTION", "ITEMS", "TERMINATED", "BY")) {
            kwargs.put(Args.COLLECTION_ITEMS, parseString());
        }
        if (matchTextSeq("MAP", "KEYS", "TERMINATED", "BY")) {
            kwargs.put(Args.MAP_KEYS, parseString());
        }
        if (matchTextSeq("LINES", "TERMINATED", "BY")) {
            kwargs.put(Args.LINES, parseString());
        }
        if (matchTextSeq("NULL", "DEFINED", "AS")) {
            kwargs.put(Args.NULL, parseString());
        }

        return expression(Expression.of(ExprType.ROW_FORMAT_DELIMITED_PROPERTY, kwargs));
    }

    @Override
    protected Expression parseLoad() {
        if (matchTextSeq("DATA")) {
            boolean local = matchTextSeq("LOCAL");
            matchTextSeq("INPATH");
            Expression inpath = parseString();
            boolean overwrite = match(TokenType.OVERWRITE);
            Boolean temp = null;
            if (match(TokenType.INTO)) {
                temp = match(TokenType.TEMPORARY);
                match(TokenType.TABLE);
            }

            return expression(Expression.of(ExprType.LOAD_DATA,
                    Args.THIS, parseTable(true),
                    Args.LOCAL, local,
                    Args.OVERWRITE, overwrite,
                    Args.TEMP, temp,
                    Args.INPATH, inpath,
                    Args.FILES, matchTextSeq("FROM", "FILES")
                            ? Expression.of(ExprType.PROPERTIES, Args.EXPRESSIONS, parseWrappedProperties())
                            : Boolean.FALSE,
                    Args.PARTITION, parsePartition(),
                    Args.INPUT_FORMAT, matchTextSeq("INPUTFORMAT") ? parseString() : Boolean.FALSE,
                    Args.SERDE, matchTextSeq("SERDE") ? parseString() : Boolean.FALSE));
        }
        return parseAsCommand(prev);
    }

    @Override
    protected Expression parseDelete() {
        Expression hint = parseHint();

        // This handles MySQL's "Multiple-Table Syntax"
        // https://dev.mysql.com/doc/refman/8.0/en/delete.html
        List<Expression> tables = null;
        if (!match(TokenType.FROM, false)) {
            tables = parseCsv(() -> parseTable());
            if (tables.isEmpty()) {
                tables = null;
            }
        }

        Expression returning = parseReturning();

        return expression(Expression.of(ExprType.DELETE,
                Args.HINT, hint,
                Args.TABLES, tables,
                Args.THIS, match(TokenType.FROM) ? parseTable(false, true) : Boolean.FALSE,
                Args.USING, match(TokenType.USING) ? parseCsv(() -> parseTable(false, true)) : Boolean.FALSE,
                Args.CLUSTER, match(TokenType.ON) ? parseOnProperty() : Boolean.FALSE,
                Args.WHERE, parseWhere(),
                Args.RETURNING, returning != null ? returning : parseReturning(),
                Args.ORDER, parseOrder(),
                Args.LIMIT, parseLimit()));
    }

    @Override
    protected Expression parseUpdateAssignment() {
        Expression this_ = parseComparison();
        if (match(TokenType.EQ)) {
            List<String> comments = prevComments;
            this_ = expression(
                    Expression.of(ExprType.EQ, Args.THIS, this_, Args.EXPRESSION, parseDisjunction()), comments);
        }
        return this_;
    }

    @Override
    protected Expression parseUpdate() {
        Expression hint = parseHint();
        Map<Arg, Object> kwargs = new LinkedHashMap<>();
        kwargs.put(Args.HINT, hint);
        kwargs.put(Args.THIS, parseTable(false, true, d.UPDATE_ALIAS_TOKENS));
        while (present(curr)) {
            if (match(TokenType.SET)) {
                kwargs.put(Args.EXPRESSIONS, parseCsv(() -> parseUpdateAssignment()));
            } else if (match(TokenType.RETURNING, false)) {
                kwargs.put(Args.RETURNING, parseReturning());
            } else if (match(TokenType.FROM, false)) {
                Expression from = parseFrom(true);
                Expression table = from != null ? from.getThis() : null;
                if (table != null && table.is(ExprType.SUBQUERY) && match(TokenType.JOIN, false)) {
                    List<Object> joins = pyList(parseJoins());
                    table.set(Args.JOINS, joins.isEmpty() ? null : joins);
                }

                kwargs.put(Args.FROM_, from);
            } else if (match(TokenType.WHERE, false)) {
                kwargs.put(Args.WHERE, parseWhere());
            } else if (match(TokenType.ORDER_BY, false)) {
                kwargs.put(Args.ORDER, parseOrder());
            } else if (match(TokenType.LIMIT, false)) {
                kwargs.put(Args.LIMIT, parseLimit());
            } else {
                break;
            }
        }

        return expression(Expression.of(ExprType.UPDATE, kwargs));
    }

    @Override
    protected Expression parseUse() {
        return expression(Expression.of(ExprType.USE,
                Args.KIND, parseVarFromOptions(d.USABLES, false),
                Args.THIS, parseTable(false)));
    }

    @Override
    protected Expression parseUncache() {
        if (!match(TokenType.TABLE)) {
            raiseError("Expecting TABLE after UNCACHE");
        }

        return expression(Expression.of(ExprType.UNCACHE,
                Args.EXISTS, parseExists(), Args.THIS, parseTable(true)));
    }

    @Override
    protected Expression parseCache() {
        boolean lazy = matchTextSeq("LAZY");
        match(TokenType.TABLE);
        Expression table = parseTable(true);

        List<Expression> options = new ArrayList<>();
        if (matchTextSeq("OPTIONS")) {
            matchLParen();
            Expression k = parseString();
            match(TokenType.EQ);
            Expression v = parseString();
            options = new ArrayList<>(Arrays.asList(k, v));
            matchRParen();
        }

        match(TokenType.ALIAS);
        return expression(Expression.of(ExprType.CACHE,
                Args.THIS, table, Args.LAZY, lazy, Args.OPTIONS, options,
                Args.EXPRESSION, parseSelect(true)));
    }

    @Override
    protected Expression parsePartition() {
        if (!matchTexts(d.PARTITION_KEYWORDS)) {
            return null;
        }

        return expression(Expression.of(ExprType.PARTITION,
                Args.SUBPARTITION, Py.upper(prev.text()).equals("SUBPARTITION"),
                Args.EXPRESSIONS, parseWrappedCsv(() -> parseDisjunction())));
    }

    private Expression parseValueExpression() {
        if (dd.SUPPORTS_VALUES_DEFAULT && match(TokenType.DEFAULT)) {
            return pyVar(Py.upper(prev.text()));
        }
        return parseExpression();
    }

    @Override
    protected Expression parseValue(boolean values) {
        if (match(TokenType.L_PAREN)) {
            List<Expression> expressions = parseCsv(this::parseValueExpression);
            matchRParen();
            return expression(Expression.of(ExprType.TUPLE, Args.EXPRESSIONS, expressions));
        }

        // In some dialects we can have VALUES 1, 2 which results in 1 column & 2 rows.
        Expression expression = parseExpression();
        if (expression != null) {
            return expression(Expression.of(ExprType.TUPLE, Args.EXPRESSIONS, List.of(expression)));
        }
        return null;
    }
}
