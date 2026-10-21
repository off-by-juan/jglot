# jglot

A fast, thread-safe SQL parser and dialect translator for the JVM. Its behaviour follows
[sqlglot](https://github.com/tobymao/sqlglot) (baseline **30.23.0**), and it is written in
idiomatic Java 21 with no runtime dependencies.

```java
String trino = JGlot.transpileOne("SELECT a::INT, NOW() FROM t", "postgres", "trino");
```

## Status

The MVP covers translation (tokenize → parse → AST → generate) between:

| Dialect | Name |
|---|---|
| sqlglot generic dialect | `""` |
| PostgreSQL | `postgres` |
| Trino | `trino` |
| Starburst | `starburst` (currently identical to Trino) |
| Presto | `presto` |
| BigQuery | `bigquery` |
| ClickHouse | `clickhouse` |
| Hive | `hive` |

Alibaba Cloud MaxCompute is next. sqlglot's optimizer (qualification, simplification as a public
API, lineage, execution) is out of scope.

Conformance is measured against sqlglot itself, at three levels:

| Suite | Result |
|---|---|
| sqlglot's dialect tests (every `validate_identity` / `validate_all` case between the dialects above) | **4,151 / 4,151** |
| Differential corpus: sqlglot's `identity.sql`, `pretty.sql` and optimizer fixtures (TPC-H and TPC-DS included), translated into every dialect, comparing plain output, pretty output and whether strict mode raises | **92,367 / 92,367** checks |
| Tokenizer: tokens identical to sqlglot's for every fixture input | **3,719 / 3,719** |

See [`jglot/src/test/resources/fixtures/baseline/REPORT.md`](jglot/src/test/resources/fixtures/baseline/REPORT.md).

Performance (JMH, single thread, no tuning yet): about 6 µs for a short query and 40–60 µs for
TPC-H Q1 or a CTE/window query, end to end. That is roughly 30–45x faster than pure-Python
sqlglot on the same machine.

## Functions

* A function the source dialect knows is parsed into its canonical node (e.g. `NVL`, `IFNULL` and
  `COALESCE` all become `COALESCE`). The target dialect then renders its own equivalent.
* If the target dialect can't express a known function or construct, generation **fails loudly**
  with `UnsupportedError`. This is the default (`GeneratorOptions.unsupportedLevel = RAISE`); sqlglot
  only warns by default.
* `GeneratorOptions.strictFunctions` (on by default) also raises when a known function has no
  specific rendering for the target and the target doesn't recognise its canonical name. sqlglot
  silently prints the canonical name in that case. **This check is only partial:** sqlglot's
  function tables are shared across dialects, so a canonical name the target engine doesn't
  actually have (e.g. `FARM_FINGERPRINT` to Postgres) still passes through. Closing this gap needs
  per-engine function catalogs.
* A function the source dialect doesn't know (a UDF, or a table function) is passed through
  unchanged.

## Thread safety and performance

* `Dialect` instances (from `Dialects.get(name)`) are immutable singletons. Every lookup table
  (keywords, token sets, function registries, type mappings) is resolved once, when the class
  loads, and shared.
* Each `tokenize`/`parse`/`generate` call creates its own short-lived scanner, parser or generator,
  so concurrent use needs no locking.
* Syntax trees are mutable and owned by the thread that created them. Call `copy()` before sharing
  one.
* Dispatch uses enums and `switch`, with no reflection. Benchmarks are in `jglot-benchmarks`
  (JMH).

## Building

Requires JDK 21 and Maven.

```
mvn verify                                      # build and run all tests
mvn -pl jglot test -Djglot.fixtures.updateBaseline=true   # record newly passing sqlglot cases
mvn -q package -DskipTests && java -jar jglot-benchmarks/target/benchmarks.jar
```

## How the port is organised

* `tools/codegen`: generates Java catalogues from sqlglot. That covers the expression types and
  their arguments, token types, data types, and every dialect's resolved tokenizer, parser,
  generator and dialect tables.
* `tools/fixtures`: extracts sqlglot's test cases as language-neutral JSON fixtures. Each one is
  re-checked against sqlglot itself.
* `tools/porting`: method signature contracts and merge tooling for porting sqlglot's parser and
  generator.
* `docs/PORTING.md`: the Python → Java conventions.

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for how to contribute, including how to move to a new
sqlglot release.

## Licence

jglot is MIT-licensed; see [`LICENSE`](LICENSE). It is an independent port of sqlglot, which is
MIT-licensed by Toby Mao. Large parts of jglot are derived from sqlglot, so sqlglot's copyright
and licence notice is reproduced in [`THIRD-PARTY-NOTICES`](THIRD-PARTY-NOTICES) and ships
inside the jar's `META-INF/`.
