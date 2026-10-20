# Porting sqlglot to jglot

jglot ports sqlglot's translation path (tokenizer, parser, AST, generator, dialects) to Java 21.
Its behaviour must match sqlglot exactly; that's what the fixture suite checks. The architecture is
idiomatic Java, though, and the code is written for speed and thread safety. This guide is the
contract for porting sqlglot code. Follow it precisely, because code ported in parallel has to fit
together.

Baseline: **sqlglot 30.23.0**, checked out at `~/.cache/jglot/sqlglot` (use the venv at
`~/.cache/jglot/venv` to run it).

## Architecture

| sqlglot | jglot |
|---|---|
| `exp.Expression` subclasses (1059 of them) | one `Expression` class plus an `ExprType` enum (generated). Traits such as `Func`, `Binary`, `Condition` and `Query` are `ExprType` constants too; `e.is(ExprType.FUNC)` is `isinstance`. |
| `expression.args["x"]` | `e.get(Args.X)` (generated `Arg` constants; `with_` is `Args.WITH_`, `from_` is `Args.FROM_`) |
| class-level data tables (`Parser.ID_VAR_TOKENS`, `Generator.TYPE_MAPPING`, `Dialect.TIME_MAPPING`, ...) | generated, fully resolved per dialect: `ParserData d`, `GeneratorData g`, `DialectData dd`. The field names are the Python names, e.g. `d.ID_VAR_TOKENS`, `g.TYPE_MAPPING`, `dd.TIME_MAPPING` |
| callable tables (`STATEMENT_PARSERS`, `FUNCTION_PARSERS`, `TRANSFORMS`, ...) | the *key set* is generated data (`d.STATEMENT_PARSERS` is a `Set<TokenType>`); the *behaviour* is an overridable dispatch method (`statementParser(TokenType)`), implemented as a `switch` that falls through to `super` |
| `Parser.FUNCTIONS` | `d.FUNCTIONS_FROM_ARG_LIST` (name → ExprType, i.e. plain `from_arg_list` entries) plus `d.FUNCTIONS_CUSTOM` (names whose builder is hand-written in `buildFunction(name, args)`). Use `hasFunction(name)` and `callFunctionBuilder(name, args)`. |
| `Generator.TRANSFORMS` | `g.TRANSFORMS_RENAME` (ExprType → name, i.e. `rename_func` entries) plus `g.TRANSFORMS_CUSTOM` (types handled by `transform(Expression)`) |
| `<key>_sql` generator methods | `<key>Sql(Expression)`, dispatched by the generated `sqlFor` switch |
| dialect subclasses | Java subclasses: `PostgresParser extends Parser`, `TrinoGenerator extends PrestoGenerator`, ... |

Thread safety: dialects and all generated data are immutable singletons. A `Parser` or `Generator`
instance is created per call, so it can hold mutable state. Trees are owned by the calling thread.
**Never introduce static mutable state.**

## Signature contract

Every method of sqlglot's `Parser` and `Generator` has exactly one Java signature, generated into
`ParserMethods.java` / `GeneratorMethods.java` (stubs) and listed in
`tools/porting/contracts/{parser,generator}.txt` (`module:lines  python_name  java_signature  defaults`).

* When you port a method, **override that exact signature** (`@Override protected ...`). Don't
  change it. If it's genuinely wrong (for example a parameter typed `Object` that is always an
  `Expression`), keep it anyway, cast inside, and mention it in your report.
* Python default arguments are generated as `final` convenience overloads for trailing defaults,
  so `self._parse_select()` becomes `parseSelect()` and `self._parse_select(nested=True)` becomes
  `parseSelect(true)`. For keyword calls that skip earlier parameters, call the full-arity method
  and pass the Python defaults (listed in the contract) explicitly.
* Names: `_parse_foo_bar` → `parseFooBar`, `foo_sql` → `fooSql`, `_match_text_seq` → `matchTextSeq`.
  Parameters named after Java keywords get a trailing underscore (`this_`, `default_`).
  `_parse` → `parseImpl`.
* Core methods are hand-written in `ParserCore` / `GeneratorCore` and are not in the contract:
  navigation, matching, errors, `expression(...)`, `parseCsv`, `parseWrapped`, `parseWrappedCsv`,
  `sql`, `expressions`, `func`, `binary`, `seg`, `sep`, `indent`, `wrap`, `maybeComment` and
  similar. Read those files first.

## Python → Java rules

### Parser state (`ParserCore`)

| Python | Java |
|---|---|
| `self._curr`, `self._next`, `self._prev` | `curr`, `next`, `prev` (never null; the sentinel is `SENTINEL_NONE`) |
| `if self._curr:` / `not self._next` | `present(curr)` / `!present(next)` |
| `self._curr.token_type`, `.text` | `curr.type()`, `curr.text()` |
| `self._index`, `self._tokens`, `self._tokens_size`, `self.sql` | `tokenIndex`, `tokenList`, `tokenCount`, `sqlText` |
| `self._prev_comments` | `prevComments` |
| `self.dialect` | `dialect` (a `Dialect`); dialect constants are `dd.X` |
| `self.X` (a class-level table or flag) | `d.X` |
| `self._match(TokenType.L_PAREN)` | `match(TokenType.L_PAREN)` |
| `self._match(T, advance=False)` | `match(T, false)` |
| `self._match_set(self.X)` | `matchSet(d.X)` |
| `self._match_text_seq("A", "B")` | `matchTextSeq("A", "B")`; with `advance=False`: `matchTextSeq(false, "A", "B")` |
| `self._match_texts(("A", "B"))` | `matchTexts(Set.of("A", "B"))` or `matchTexts(d.X)` |
| `self._parse_csv(self._parse_x)` | `parseCsv(this::parseX)`; for non-expression results, `parseCsvOf(...)` |
| `self._parse_wrapped_csv(self._parse_x, optional=True)` | `parseWrappedCsv(this::parseX, true)` |
| `self._parse_wrapped(lambda: ...)` | `parseWrapped(() -> ...)` |
| `self.expression(exp.Foo(this=a, expression=b))` | `expression(Expression.of(ExprType.FOO, Args.THIS, a, Args.EXPRESSION, b))`, or the shorthand `build(ExprType.FOO, Args.THIS, a, Args.EXPRESSION, b)` |
| `self.expression(instance, token)` / `comments=c` | `expression(instance, token)` / `expression(instance, c)` |
| `self.raise_error("msg")` | `raiseError("msg")` |
| `self._try_parse(self._parse_x)` | `tryParse(this::parseX)`, or `tryParse(() -> parseX(true), true)` for `retreat=True` |
| `self._find_parser(self.SHOW_PARSERS, self.SHOW_TRIE)` | `String key = findParser(dialect.showTrie())`, then `showParser(key)` |
| `self.STATEMENT_PARSERS[t](self)` | `statementParser(t)` (only after checking `d.STATEMENT_PARSERS.contains(t)`) |
| `self.FUNCTIONS.get(name)` | `hasFunction(name)` / `callFunctionBuilder(name, args)` |

Python locals called `curr`, `prev` or `next` must be renamed (for example `currText`) so they don't
shadow the fields.

### Building and inspecting nodes

| Python | Java |
|---|---|
| `exp.Foo(this=x, kind="A")` | `Expression.of(ExprType.FOO, Args.THIS, x, Args.KIND, "A")`. Null values are skipped. Construction-time normalizations such as TimeUnit units happen in `of(...)`. |
| `exp.Foo.from_arg_list(args)` | `Exp.fromArgList(ExprType.FOO, args)` |
| `isinstance(x, exp.Foo)` | `x != null && x.is(ExprType.FOO)`; for an `Object`: `Expression.isInstance(o, ExprType.FOO)` |
| `isinstance(x, (exp.A, exp.B))` | `x.is(ExprType.A, ExprType.B)` |
| `type(x) is exp.Foo` / `type(x) == type(y)` | `x.type() == ExprType.FOO` / `x.type() == y.type()` |
| `x.args.get("k")` / `x.args["k"]` | `x.get(Args.K)` (raw `Object`); `x.expr(Args.K)` (an `Expression` or null); `x.str(Args.K)` |
| `x.args.get("k")` used as a boolean | `x.has(Args.K)` (Python truthiness) |
| `x.this` / `x.expression` / `x.expressions` | `x.getThis()` (`Expression`) or `x.thisValue()` (raw, e.g. a `String` or `DType`) / `x.getExpression()` / `x.getExpressions()` |
| `x.name`, `x.alias`, `x.alias_or_name`, `x.output_name`, `x.text("k")` | `x.name()`, `x.alias()`, `x.aliasOrName()`, `x.outputName()`, `x.text(Args.K)` |
| `x.is_string`, `x.is_number`, `x.is_int`, `x.is_star`, `x.to_py()` | `x.isString()`, `x.isNumber()`, `x.isInt()`, `x.isStar()`, `x.toPy()` |
| `x.set("k", v)`, `x.append("k", v)`, `x.set("k", v, index)` | `x.set(Args.K, v)`, `x.append(Args.K, v)`, `x.set(Args.K, v, index, true)` |
| `x.copy()`, `x.replace(y)`, `x.pop()`, `x.parent`, `x.arg_key`, `x.index` | the same names: `copy()`, `replace(y)`, `pop()`, `parent()`, `argKey()`, `index()` |
| `x.find(exp.A)`, `x.find_all(exp.A)`, `x.find_ancestor(exp.A)`, `x.walk()`, `x.dfs()`, `x.bfs()` | `find(...)`, `findAll(...)` (BFS order), `findAncestor(...)`, `walk()`, `dfs()`, `bfs()` |
| `x.transform(fn)` | `x.transform(fn)` (copies, like Python) or `x.transform(fn, false)` |
| `x.unnest()`, `x.unalias()`, `x.flatten()`, `x.meta`, `x.comments`, `x.type` | `unnest()`, `unalias()`, `flattenList()`, `meta()`, `comments()`, `dataType()` |
| `exp.Literal.string(s)`, `exp.Literal.number(n)`, `exp.var(s)`, `exp.to_identifier(s)` | `Exp.string(s)`, `Exp.number(n)`, `Exp.var(s)`, `Exp.toIdentifier(s)` |
| `exp.column(...)`, `exp.alias_(e, a)`, `exp.paren(e)`, `exp.and_(...)`, `exp.not_(e)`, `exp.cast(e, t)` | `Exp.column(...)`, `Exp.alias(e, a)`, `Exp.paren(e)`, `Exp.and(...)`, `Exp.not(e)`, `Exp.cast(e, t)` (see `Exp` for the rest) |
| `exp.null()`, `exp.true()`, `exp.false()` | `Exp.nul()`, `Exp.bool(true)`, `Exp.bool(false)` |
| `exp.DataType.build("int")`, `exp.DType.INT`, `dt.is_type(...)` | `DataTypes.build("int")`, `DType.INT`, `x.isType(...)` |
| query builders: `select.where(c, copy=False)`, `.from_()`, `.join()`, `.select()`, ... | instance methods on `Expression`: `where(append, copy, ...)`, `from(...)`, `join(...)`, `select(...)` (see the builder section of `Expression`) |

Use `ExprType.X` and `TokenType.X` fully qualified. **Don't** static-import both enums, because they
share constant names (`SELECT`, `TABLE`, ...).

### Python semantics to preserve

* **Truthiness.** `if x:` on an Expression means `x != null`. On a list it means `!l.isEmpty()`. On
  a str it means non-empty. On an arbitrary value use `Expression.truthy(v)`.
* **`a and b` / `a or b` return operands, not booleans.** `kind=self._match(X) and self._prev.text`
  stores `False` when the match fails, so write `match(X) ? prev.text() : Boolean.FALSE`. Store
  `Boolean.FALSE` wherever Python would store `False`: the required-argument check treats `False`
  as present, but treats `None` as missing.
* **Argument evaluation order.** Python evaluates keyword arguments left to right, and so do Java
  varargs. Keep the same order whenever the calls consume tokens.
* **Strings.** Use `Locale.ROOT` for case changes (`Py.upper`, `Py.lower`). For slicing use
  `Py.slice(s, start, end)` (negative indices, clamping). Python's `split()` with no arguments is
  `Py.split(s)`; `str.strip/lstrip/rstrip` are `.strip()`, `Py.lstrip`, `Py.rstrip`, and
  `Py.strip(s, chars)`. f-strings become plain concatenation.
* **Collections.** `seq_get` → `Py.seqGet`; `ensure_list` → `Py.ensureList`. Set algebra on token
  sets is `Py.union(d.X, TokenType.Y)` / `Py.minus(...)`. Ordered dicts are `LinkedHashMap`.
* **Errors.** `raise ValueError` → `IllegalArgumentException`; `UnsupportedError` and `ParseError`
  are in `io.github.offbyjuan.jglot.errors`; `logger.warning` → `LOGGER.warning`.
* **Generator methods with `@unsupported_args("a", ("b", "msg"))`** start with
  `unsupportedArgs(e, Args.A, Args.B, "msg");`.
* **Closures and nested functions.** These become private helper methods or lambdas. Lambdas need
  effectively-final captures, so use one-element arrays for mutated captures.

### Generator (`GeneratorCore`)

| Python | Java |
|---|---|
| `self.sql(e)`, `self.sql(e, "this")` | `sql(e)`, `sql(e, Args.THIS)` |
| `self.sql(e, comment=False)` | `sql(e, false)` |
| `self.expressions(e)` / `self.expressions(e, key="x", flat=True, sep=" ")` | `expressions(e)` / `expressions(opts(e).key(Args.X).flat().sep(" "))` |
| `self.expressions(sqls=[...])` | `expressions(optsSqls(list))` |
| `self.func("NAME", a, b)` / with `prefix=`, `suffix=`, `normalize=` | `func("NAME", a, b)` / `funcWith(name, prefix, suffix, normalize, a, b)` |
| `self.format_args(a, b)`, `sep=` | `formatArgs(a, b)`, `formatArgsSep(sep, a, b)` |
| `self.seg("WHERE")`, `self.sep()`, `self.indent(s, ...)`, `self.wrap(e)` | `seg("WHERE")`, `sep()`, `indent(s, level, pad, skipFirst, skipLast)` / `indent(s)`, `wrap(e)` |
| `self.binary(e, "+")`, `self.unsupported("...")`, `self.maybe_comment(...)` | the same names |
| `self.no_identify(self.expressions, e)` | `noIdentify(() -> expressions(e))` |
| `self.pretty`, `self.identify`, `self.normalize`, `self.pad`, `self.comments` | `pretty`, `identify`, `normalize`, `pad`, `preserveComments` |
| `self.dialect.X` | `dd.X` (or `dialect.someMethod()`) |
| `self.X` (class table or flag) | `g.X` |
| `transforms.preprocess([t1, t2])` as a TRANSFORMS value | `applyTransforms(e, Transforms::t1, Transforms::t2)` |
| `rename_func("X")` | already data (`g.TRANSFORMS_RENAME`), so it needs no code |

`transform(Expression e)` handles the custom TRANSFORMS entries:

```java
@Override
protected String transform(Expression e) {
    return switch (e.type()) {
        case ADJACENT -> binary(e, "-|-");
        // ...
        default -> super.transform(e);
    };
}
```

A dialect generator overrides `transform` for its own entries and calls `super.transform(e)` for
the rest. It overrides `<key>Sql` methods directly. If a dialect defines `foo_sql` for a type that
the base `Generator` has no `foo_sql` for, override `sqlFor`: handle the type, then return
`super.sqlFor(e)`.

### Dialect parsers

* Dialect tables (keywords, token sets, flags) are already generated, so don't re-declare them.
  Only behaviour is hand-written.
* Override the dispatch hooks for entries the dialect adds or replaces: `functionParser`,
  `buildFunction`, `propertyParser`, `rangeParser` and so on. Handle the keys that differ in a
  `switch` and call `super` for the rest. The key sets in `d` decide which keys exist, so an entry
  the dialect removes is never dispatched.
* sqlglot builder factories like `build_formatted_time(exp.StrToTime, "postgres")` become methods
  that take the factory's arguments followed by `args`, e.g.
  `buildFormattedTime(ExprType.STR_TO_TIME, "postgres", null, args)`.

## Fragments

Large classes are ported in fragments under `jglot/src/porting/java/` (not compiled by Maven):

```java
package io.github.offbyjuan.jglot.parser;

abstract class ParserPart2 extends ParserMethods {
    ParserPart2(Dialect dialect, ParserOptions options) { super(dialect, options); }

    @Override
    protected Expression parseSelect(boolean nested, boolean table, boolean parseSubqueryAlias,
            boolean parseSetOperation, boolean consumePipe) { ... }
}
```

* Compile your fragment in isolation with `tools/porting/check.sh <file>`. It compiles against
  the stable scaffold classes, so other people's in-progress fragments can't break your build.
  **Don't run `mvn`** while fragments are being written in parallel.
* Only edit the files you own. If you need a helper that doesn't exist (on `Expression`, `Exp`,
  `DataTypes`, `Py`, or a contract method with the wrong shape), write a private helper in your
  fragment and list it under "Missing APIs" in your report. Don't edit shared files.
* Fragments are merged into `Parser.java` / `Generator.java` once all of them compile.

## Fidelity checklist

* Port line by line, keeping control flow, order of token consumption, and error messages. Don't
  "improve" logic; the fixtures compare exact output.
* Keep sqlglot's comments where they explain non-obvious behaviour.
* Prefer `switch` and enum sets in hot paths. Avoid regex in hot paths (precompile a `static final
  Pattern` if you must use one).
