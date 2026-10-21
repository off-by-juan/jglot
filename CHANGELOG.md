# Changelog

All notable changes to jglot are listed here. jglot follows [semantic versioning](https://semver.org/);
while it is below 1.0, minor versions may change the public API.

## 0.1.0 (unreleased)

First release. Ports sqlglot 30.23.0's translation path (tokenize, parse, generate) to Java 21.

* Dialects: the sqlglot base dialect, PostgreSQL, Presto, Trino, Starburst (an alias of Trino),
  BigQuery, ClickHouse and Hive.
* Passes all 4,151 sqlglot dialect-test cases between these dialects, all 92,367 differential
  checks against sqlglot's SQL corpus, and all 3,719 tokenizer conformance cases.
* Fails loudly by default: generation raises `UnsupportedError` when the target can't express a
  construct, and `GeneratorOptions.strictFunctions` raises for known functions without a rendering
  for the target.
* Thread-safe: immutable, shared dialects; per-call parsers and generators.
* No runtime dependencies.
* A named module, `io.github.offbyjuan.jglot`, that exports only the public API packages.
