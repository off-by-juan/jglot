package io.github.offbyjuan.jglot.generator;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.data.DialectData;
import io.github.offbyjuan.jglot.dialects.data.GeneratorData;
import io.github.offbyjuan.jglot.errors.ErrorLevel;
import io.github.offbyjuan.jglot.errors.UnsupportedError;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Generator state, dispatch, comment handling and formatting helpers: the hand-written core of the
 * port of sqlglot's {@code Generator}. A generator instance is used for a single call and never
 * shared between threads; all dialect tables live in the immutable {@link GeneratorData}.
 */
public abstract class GeneratorCore {
    protected static final Logger LOGGER = Logger.getLogger("jglot");

    public static final String SENTINEL_LINE_BREAK = "__SQLGLOT__LB__";
    public static final String UNSUPPORTED_TEMPLATE = "Argument '%s' is not supported for expression '%s' when targeting %s.";

    protected final Dialect dialect;
    /** The dialect's resolved generator tables and flags ({@code self.X} in sqlglot's Generator). */
    protected final GeneratorData g;
    /** The dialect's resolved settings ({@code self.dialect.X} in sqlglot). */
    protected final DialectData dd;

    protected final boolean pretty;
    protected Object identify;
    protected final boolean normalize;
    protected final int pad;
    protected final int indentSize;
    protected final Object normalizeFunctions;
    protected final ErrorLevel unsupportedLevel;
    protected final int maxUnsupported;
    protected final boolean leadingComma;
    protected final int maxTextWidth;
    protected final boolean preserveComments;
    protected final boolean strictFunctions;

    protected List<String> unsupportedMessages = new ArrayList<>();
    protected final String escapedQuoteEnd;
    protected final String escapedByteQuoteEnd;
    protected final String escapedIdentifierEnd;
    protected final java.util.Map<String, String> identifierEscapedSequences;
    protected final String identifierStart;
    protected final String identifierEnd;
    protected boolean quoteJsonPathKeyUsingBrackets = true;
    private int nextNameCounter;

    protected GeneratorCore(Dialect dialect, GeneratorOptions options) {
        this.dialect = dialect;
        this.g = dialect.generatorData();
        this.dd = dialect.data();
        this.pretty = options.pretty();
        this.identify = options.identify();
        this.normalize = options.normalize();
        this.pad = options.pad();
        this.indentSize = options.indent();
        this.unsupportedLevel = options.unsupportedLevel();
        this.maxUnsupported = options.maxUnsupported();
        this.leadingComma = options.leadingComma();
        this.maxTextWidth = options.maxTextWidth();
        this.preserveComments = options.comments();
        this.strictFunctions = options.strictFunctions();
        this.normalizeFunctions = options.normalizeFunctions() == null ? dd.NORMALIZE_FUNCTIONS : options.normalizeFunctions();

        String firstStringEscape = dialect.tokenizerConfig().stringEscapesOrdered().get(0);
        this.escapedQuoteEnd = firstStringEscape + dd.QUOTE_END;
        this.escapedByteQuoteEnd = dd.BYTE_END != null ? firstStringEscape + dd.BYTE_END : "";
        this.escapedIdentifierEnd = dd.IDENTIFIER_END + dd.IDENTIFIER_END;
        this.identifierEscapedSequences = dd.IDENTIFIER_ESCAPED_SEQUENCES;
        this.identifierStart = dd.IDENTIFIER_START;
        this.identifierEnd = dd.IDENTIFIER_END;
    }

    public Dialect dialect() {
        return dialect;
    }

    protected static RuntimeException notPorted(String pythonMethod) {
        return new UnsupportedOperationException("not yet ported from sqlglot: Generator." + pythonMethod);
    }

    /** sqlglot's {@code name_sequence("_t")}. */
    public String nextName() {
        return "_t" + (nextNameCounter++);
    }

    // ------------------------------------------------------------------------------------------
    // Entry point

    /** Generates SQL for {@code expression}; with {@code copy} the input tree is left untouched. */
    public String generate(Expression expression, boolean copy) {
        if (copy) {
            expression = expression.copy();
        }
        expression = preprocess(expression);

        unsupportedMessages = new ArrayList<>();
        String out = sql(expression).strip();

        if (pretty) {
            out = out.replace(SENTINEL_LINE_BREAK, "\n");
        }

        if (unsupportedLevel == ErrorLevel.IGNORE) {
            return out;
        }
        if (unsupportedLevel == ErrorLevel.WARN) {
            for (String msg : unsupportedMessages) {
                LOGGER.warning(msg);
            }
        } else if (unsupportedLevel == ErrorLevel.RAISE && !unsupportedMessages.isEmpty()) {
            throw new UnsupportedError(concatMessages(unsupportedMessages, maxUnsupported));
        }
        return out;
    }

    private static String concatMessages(List<String> msgs, int maximum) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(msgs.size(), maximum);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append(msgs.get(i));
        }
        int remaining = msgs.size() - maximum;
        if (remaining > 0) {
            sb.append("\n\n... and ").append(remaining).append(" more");
        }
        return sb.toString();
    }

    /** Generic preprocessing applied before generation; ported in the Generator fragments. */
    public abstract Expression preprocess(Expression expression);

    /** Records that the target dialect cannot express something. */
    public void unsupported(String message) {
        if (unsupportedLevel == ErrorLevel.IMMEDIATE) {
            throw new UnsupportedError(message);
        }
        unsupportedMessages.add(message);
    }

    /** sqlglot's {@code @unsupported_args}: flags args that the target cannot express. */
    public void unsupportedArgs(Expression expression, Object... argsAndDiagnostics) {
        String exprName = expression.type().className();
        String dialectName = dialect.getClass().getSimpleName();
        for (int i = 0; i < argsAndDiagnostics.length; i++) {
            Object a = argsAndDiagnostics[i];
            Arg arg;
            String diagnostic = null;
            if (a instanceof Arg x) {
                arg = x;
                if (i + 1 < argsAndDiagnostics.length && argsAndDiagnostics[i + 1] instanceof String s) {
                    diagnostic = s;
                    i++;
                }
            } else {
                continue;
            }
            if (expression.has(arg)) {
                unsupported(diagnostic != null ? diagnostic : String.format(UNSUPPORTED_TEMPLATE, arg.name(), exprName, dialectName));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Layout

    public String sep(String sep) {
        return pretty ? sep.strip() + "\n" : sep;
    }

    public String sep() {
        return sep(" ");
    }

    public String seg(String sqlText, String sep) {
        return sep(sep) + sqlText;
    }

    public String seg(String sqlText) {
        return seg(sqlText, " ");
    }

    public String sanitizeComment(String comment) {
        if (!comment.isEmpty() && !comment.substring(0, 1).isBlank()) {
            comment = " " + comment;
        }
        if (!comment.isEmpty() && !comment.substring(comment.length() - 1).isBlank()) {
            comment = comment + " ";
        }
        return comment.replace("*/", "* /").replace("/*", "/ *");
    }

    public String replaceLineBreaks(String s) {
        return pretty ? s.replace("\n", SENTINEL_LINE_BREAK) : s;
    }

    /** sqlglot's {@code maybe_comment}: appends (or prepends) an expression's comments. */
    public String maybeComment(String sqlText, Expression expression, List<String> comments, boolean separated) {
        List<String> cs = this.preserveComments ? (comments == null ? (expression == null ? null : expression.comments()) : comments) : null;

        if (cs == null || cs.isEmpty() || (expression != null && isAny(expression, g.EXCLUDE_COMMENTS))) {
            return sqlText;
        }

        List<String> commentsList = new ArrayList<>();
        for (String c : cs) {
            if (c != null && !c.isEmpty()) {
                commentsList.add("/*" + replaceLineBreaks(sanitizeComment(c)) + "*/");
            }
        }
        if (commentsList.isEmpty()) {
            return sqlText;
        }

        if (separated || (expression != null && isAny(expression, g.WITH_SEPARATED_COMMENTS))) {
            String commentsSql = String.join(sep(), commentsList);
            return sqlText.isEmpty() || Character.isWhitespace(sqlText.charAt(0))
                    ? sep() + commentsSql + sqlText
                    : commentsSql + sep() + sqlText;
        }
        return sqlText + " " + String.join(" ", commentsList);
    }

    public String maybeComment(String sqlText, Expression expression) {
        return maybeComment(sqlText, expression, null, false);
    }

    public String maybeComment(String sqlText, List<String> comments) {
        return maybeComment(sqlText, null, comments, false);
    }

    /** {@code isinstance(e, (A, B, ...))} for a list of types (sqlglot tuple class attributes). */
    public static boolean isAny(Expression e, java.util.Collection<ExprType> types) {
        for (ExprType t : types) {
            if (e.is(t)) {
                return true;
            }
        }
        return false;
    }

    /** sqlglot's {@code wrap}: parenthesizes a query or the {@code this} of a wrapper. */
    public String wrap(Object expression) {
        String thisSql;
        if (expression instanceof Expression e && (e.is(ExprType.SELECT) || e.is(ExprType.SET_OPERATION))) {
            thisSql = sql(e);
        } else if (expression instanceof Expression e) {
            thisSql = sql(e, Args.THIS);
        } else {
            thisSql = expression == null ? "" : expression.toString();
        }
        if (thisSql.isEmpty()) {
            return "()";
        }
        thisSql = indent(thisSql, 1, 0, false, false);
        return "(" + sep("") + thisSql + seg(")", "");
    }

    /** Runs {@code fn} with identifier quoting disabled. */
    public String noIdentify(Supplier<String> fn) {
        Object original = identify;
        identify = Boolean.FALSE;
        try {
            return fn.get();
        } finally {
            identify = original;
        }
    }

    public String normalizeFunc(String name) {
        Object nf = normalizeFunctions;
        if ("upper".equals(nf) || Boolean.TRUE.equals(nf)) {
            return name.toUpperCase(Locale.ROOT);
        }
        if ("lower".equals(nf)) {
            return name.toLowerCase(Locale.ROOT);
        }
        return name;
    }

    public String indent(String sqlText, int level, Integer pad, boolean skipFirst, boolean skipLast) {
        if (!pretty || sqlText.isEmpty()) {
            return sqlText;
        }
        int p = pad == null ? this.pad : pad;
        String[] lines = sqlText.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        String prefix = " ".repeat(level * indentSize + p);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            if ((skipFirst && i == 0) || (skipLast && i == lines.length - 1)) {
                sb.append(lines[i]);
            } else {
                sb.append(prefix).append(lines[i]);
            }
        }
        return sb.toString();
    }

    public String indent(String sqlText) {
        return indent(sqlText, 0, null, false, false);
    }

    // ------------------------------------------------------------------------------------------
    // Dispatch

    /**
     * sqlglot's {@code sql(expression)}: empty for null/false/empty values, strings verbatim, and
     * expressions rendered through the dialect's dispatch (with comments).
     */
    public String sql(Object expression) {
        return sql(expression, true);
    }

    public String sql(Object expression, boolean comment) {
        if (!Expression.truthy(expression)) {
            return "";
        }
        if (expression instanceof String s) {
            return s;
        }
        if (!(expression instanceof Expression e)) {
            throw new IllegalArgumentException("Unsupported value " + expression);
        }
        String out = dispatch(e);
        return this.preserveComments && comment ? maybeComment(out, e) : out;
    }

    /** sqlglot's {@code sql(expression, key)}: renders the value of one arg, or "" if absent. */
    public String sql(Expression expression, Arg key) {
        if (expression == null) {
            return "";
        }
        Object value = expression.get(key);
        if (Expression.truthy(value)) {
            return sql(value);
        }
        return "";
    }

    /**
     * Dispatches on the exact node type: rename entries of TRANSFORMS, then custom TRANSFORMS
     * entries ({@link #transform}), then the {@code <key>_sql} method ({@link #sqlFor}), then the
     * function/property fallbacks.
     */
    public String dispatch(Expression e) {
        ExprType t = e.type();
        String rename = g.TRANSFORMS_RENAME.get(t);
        if (rename != null) {
            return renameFunc(rename, e);
        }
        if (g.TRANSFORMS_CUSTOM.contains(t)) {
            return transform(e);
        }
        String out = sqlFor(e);
        if (out != null) {
            return out;
        }
        if (e.is(ExprType.FUNC)) {
            if (strictFunctions && !isTargetFunction(e)) {
                unsupported("Function " + functionName(e) + " (" + t.className() + ") has no known translation in "
                        + (dialect.name().isEmpty() ? "the base dialect" : dialect.name()));
            }
            return functionFallbackSql(e);
        }
        if (e.is(ExprType.PROPERTY)) {
            return propertySql(e);
        }
        throw new IllegalArgumentException("Unsupported expression type " + t.className());
    }

    /**
     * Whether rendering {@code e} under its canonical name is a translation the target dialect
     * knows: the target's own parser maps that name to a function (sqlglot round-trip
     * consistency). Used when the generator has no specific rendering for a known function.
     */
    protected boolean isTargetFunction(Expression e) {
        String name = functionName(e).toUpperCase(Locale.ROOT);
        io.github.offbyjuan.jglot.dialects.data.ParserData pd = dialect.parserData();
        ExprType mapped = pd.FUNCTIONS_FROM_ARG_LIST.get(name);
        if (mapped != null) {
            return mapped == e.type();
        }
        return pd.FUNCTIONS_CUSTOM.contains(name)
                || pd.FUNCTION_PARSERS.contains(name)
                || pd.NO_PAREN_FUNCTION_PARSERS.contains(name);
    }

    /** Custom (non-rename) entries of the dialect's TRANSFORMS table. */
    public String transform(Expression e) {
        throw notPorted("TRANSFORMS[" + e.type().className() + "]");
    }

    /** The {@code <key>_sql} method for this node type, or null if the generator has none. */
    public abstract String sqlFor(Expression e);

    /** sqlglot's {@code property_sql}; ported in the Generator fragments. */
    public abstract String propertySql(Expression e);

    /** sqlglot's {@code rename_func(name)}: the function's args under a different name. */
    public String renameFunc(String name, Expression e) {
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < e.type().argCount(); i++) {
            Object v = e.get(e.type().argAt(i));
            if (v instanceof List<?> l) {
                args.addAll(l);
            } else if (v != null) {
                args.add(v);
            }
        }
        return func(name, args.toArray());
    }

    /**
     * sqlglot's {@code transforms.preprocess([t1, t2, ...])} used as a TRANSFORMS entry: applies the
     * tree transforms in order, then renders the result with its {@code <key>_sql} method (or, if
     * it changed type, its TRANSFORMS entry).
     */
    @SafeVarargs
    public final String applyTransforms(Expression expression, java.util.function.UnaryOperator<Expression>... transforms) {
        return applyTransformsThen(expression, null, transforms);
    }

    /** {@code transforms.preprocess(transforms, generator)}: as above, then renders with {@code then}. */
    @SafeVarargs
    public final String applyTransformsThen(Expression expression, java.util.function.Function<Expression, String> then,
            java.util.function.UnaryOperator<Expression>... transforms) {
        ExprType originalType = expression.type();
        try {
            for (java.util.function.UnaryOperator<Expression> t : transforms) {
                expression = t.apply(expression);
            }
        } catch (UnsupportedError e) {
            unsupported(e.getMessage());
        }
        if (then != null) {
            return then.apply(expression);
        }
        String out = sqlFor(expression);
        if (out != null) {
            return out;
        }
        ExprType t = expression.type();
        String rename = g.TRANSFORMS_RENAME.get(t);
        if (rename != null || g.TRANSFORMS_CUSTOM.contains(t)) {
            if (originalType == t) {
                if (expression.is(ExprType.FUNC)) {
                    return functionFallbackSql(expression);
                }
                throw new IllegalArgumentException("Expr type " + t.className()
                        + " requires a _sql method in order to be transformed.");
            }
            return rename != null ? renameFunc(rename, expression) : transform(expression);
        }
        throw new IllegalArgumentException("Unsupported expression type " + t.className() + ".");
    }

    // ------------------------------------------------------------------------------------------
    // Common rendering helpers

    /** sqlglot's {@code binary}: left-deep chains of the same operator are rendered iteratively. */
    public String binary(Expression expression, String op) {
        StringBuilder sb = new StringBuilder();
        ArrayDeque<Object> stack = new ArrayDeque<>();
        stack.push(expression);
        ExprType binaryType = expression.type();
        final String NONE = "\u0000none";
        while (!stack.isEmpty()) {
            Object node = stack.pop();
            if (node instanceof Expression n && n.type() == binaryType) {
                Object opFunc = n.get(Args.OPERATOR);
                if (Expression.truthy(opFunc)) {
                    op = "OPERATOR(" + sql(opFunc) + ")";
                }
                Object right = n.get(Args.EXPRESSION);
                stack.push(right == null ? NONE : right);
                stack.push(" " + maybeComment(op, null, n.comments(), false) + " ");
                Object left = n.get(Args.THIS);
                stack.push(left == null ? NONE : left);
            } else if (node == NONE) {
                // sql(None) == ""
            } else {
                sb.append(sql(node));
            }
        }
        return sb.toString();
    }

    /** sqlglot's {@code function_fallback_sql}: NAME(arg, ...) over all set args in order. */
    public String functionFallbackSql(Expression expression) {
        List<Object> args = new ArrayList<>();
        ExprType t = expression.type();
        for (int i = 0; i < t.argCount(); i++) {
            Object v = expression.get(t.argAt(i));
            if (v instanceof List<?> l) {
                args.addAll(l);
            } else if (v != null) {
                args.add(v);
            }
        }
        return func(functionName(expression), args.toArray());
    }

    public String functionName(Expression expression) {
        String key = dd.ORIGINAL_NAME_META_KEY;
        Object original = key == null ? null : expression.metaGet(key);
        if (original instanceof String s && !s.isEmpty()) {
            return s;
        }
        return expression.type().sqlName();
    }

    /** {@code func(name, *args)}: NAME(args) with the dialect's function-name normalization. */
    public String func(String name, Object... args) {
        return funcWith(name, "(", ")", true, args);
    }

    /** {@code func(name, *args, prefix=, suffix=, normalize=)}. */
    public String funcWith(String name, String prefix, String suffix, boolean normalize, Object... args) {
        String n = normalize ? normalizeFunc(name) : name;
        return n + prefix + formatArgs(args) + suffix;
    }

    /** {@code format_args(*args)}: comma-joins the SQL of non-null, non-boolean args. */
    public String formatArgs(Object... args) {
        return formatArgsSep(", ", args);
    }

    public String formatArgsSep(String sep, Object... args) {
        List<String> argSqls = new ArrayList<>(args.length);
        for (Object a : args) {
            if (a != null && !(a instanceof Boolean)) {
                argSqls.add(sql(a));
            }
        }
        if (pretty && tooWide(argSqls)) {
            return indent("\n" + String.join(sep.strip() + "\n", argSqls) + "\n", 0, null, true, true);
        }
        return String.join(sep, argSqls);
    }

    public boolean tooWide(List<String> args) {
        int total = 0;
        for (String a : args) {
            total += a.length();
        }
        return total > maxTextWidth;
    }

    /** Options for {@link #expressions(ExprsOpts)}, mirroring sqlglot's keyword arguments. */
    public static final class ExprsOpts {
        Expression expression;
        Arg key;
        List<?> sqls;
        boolean flat;
        boolean indent = true;
        boolean skipFirst;
        boolean skipLast;
        String sep = ", ";
        String prefix = "";
        boolean dynamic;
        boolean newLine;

        public ExprsOpts key(Arg k) {
            key = k;
            return this;
        }

        public ExprsOpts sqls(List<?> s) {
            sqls = s;
            return this;
        }

        public ExprsOpts flat() {
            flat = true;
            return this;
        }

        public ExprsOpts flat(boolean v) {
            flat = v;
            return this;
        }

        public ExprsOpts indent(boolean v) {
            indent = v;
            return this;
        }

        public ExprsOpts skipFirst() {
            skipFirst = true;
            return this;
        }

        public ExprsOpts skipFirst(boolean v) {
            skipFirst = v;
            return this;
        }

        public ExprsOpts skipLast() {
            skipLast = true;
            return this;
        }

        public ExprsOpts skipLast(boolean v) {
            skipLast = v;
            return this;
        }

        public ExprsOpts sep(String s) {
            sep = s;
            return this;
        }

        public ExprsOpts prefix(String p) {
            prefix = p;
            return this;
        }

        public ExprsOpts dynamic() {
            dynamic = true;
            return this;
        }

        public ExprsOpts dynamic(boolean v) {
            dynamic = v;
            return this;
        }

        public ExprsOpts newLine() {
            newLine = true;
            return this;
        }

        public ExprsOpts newLine(boolean v) {
            newLine = v;
            return this;
        }
    }

    /** Starts options for {@code expressions(expression, ...)}. */
    public static ExprsOpts opts(Expression expression) {
        ExprsOpts o = new ExprsOpts();
        o.expression = expression;
        return o;
    }

    /** Starts options for {@code expressions(sqls=...)}. */
    public static ExprsOpts optsSqls(List<?> sqls) {
        ExprsOpts o = new ExprsOpts();
        o.sqls = sqls;
        return o;
    }

    /** {@code expressions(expression)}: renders the {@code expressions} list arg. */
    public String expressions(Expression expression) {
        return expressions(opts(expression));
    }

    /** {@code expressions(expression, key=key)}. */
    public String expressions(Expression expression, Arg key) {
        return expressions(opts(expression).key(key));
    }

    /** sqlglot's {@code expressions(...)} with all keyword options. */
    public String expressions(ExprsOpts o) {
        List<?> exprs;
        if (o.expression != null) {
            Object v = o.expression.get(o.key == null ? Args.EXPRESSIONS : o.key);
            if (v instanceof List<?> l) {
                exprs = l;
            } else if (v == null || Boolean.FALSE.equals(v)) {
                exprs = null;
            } else {
                exprs = List.of(v);
            }
        } else {
            exprs = o.sqls;
        }

        if (exprs == null || exprs.isEmpty()) {
            return "";
        }

        if (o.flat) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Object e : exprs) {
                String s = sql(e);
                if (!s.isEmpty()) {
                    if (!first) {
                        sb.append(o.sep);
                    }
                    sb.append(s);
                    first = false;
                }
            }
            return sb.toString();
        }

        int numSqls = exprs.size();
        List<String> resultSqls = new ArrayList<>(numSqls);

        for (int i = 0; i < numSqls; i++) {
            Object e = exprs.get(i);
            String s = sql(e, false);
            if (s.isEmpty()) {
                continue;
            }
            String commentSql = e instanceof Expression ex ? maybeComment("", ex) : "";

            if (pretty) {
                if (leadingComma) {
                    resultSqls.add((i > 0 ? o.sep : "") + o.prefix + s + commentSql);
                } else {
                    String sepPart = i + 1 < numSqls ? (!commentSql.isEmpty() ? rstrip(o.sep) : o.sep) : "";
                    resultSqls.add(o.prefix + s + sepPart + commentSql);
                }
            } else {
                resultSqls.add(o.prefix + s + commentSql + (i + 1 < numSqls ? o.sep : ""));
            }
        }

        String resultSql;
        if (pretty && (!o.dynamic || tooWide(resultSqls))) {
            if (o.newLine) {
                resultSqls.add(0, "");
                resultSqls.add("");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < resultSqls.size(); i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(rstrip(resultSqls.get(i)));
            }
            resultSql = sb.toString();
        } else {
            resultSql = String.join("", resultSqls);
        }

        return o.indent ? indent(resultSql, 0, null, o.skipFirst, o.skipLast) : resultSql;
    }

    /** Python's {@code str.rstrip()}. */
    protected static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** Python's {@code str.lstrip()}. */
    protected static String lstrip(String s) {
        int start = 0;
        while (start < s.length() && Character.isWhitespace(s.charAt(start))) {
            start++;
        }
        return s.substring(start);
    }
}
