# Contributing to jglot

Thanks for your interest. jglot ports sqlglot's translation path to Java, and its output must match
sqlglot exactly. Most contributions are one of three kinds: fixing a case where jglot and sqlglot
disagree, porting a new dialect, or moving to a new sqlglot release.

## Building and testing

Requires JDK 21 and Maven.

```
mvn verify                                                # build and run all tests
mvn -pl jglot test -Djglot.fixtures.updateBaseline=true   # record newly passing sqlglot cases
mvn -q package -DskipTests && java -jar jglot-benchmarks/target/benchmarks.jar
```

The test suite has three conformance checks, all driven by fixtures extracted from sqlglot:

| Test | Fixtures | Rule |
|---|---|---|
| `SqlglotFixtureTest` | `fixtures/sqlglot/*.json` (sqlglot's dialect tests) | ratchet: cases in `baseline/passing.txt` must keep passing |
| `DifferentialFixtureTest` | `fixtures/differential/cases.json.gz` (sqlglot's SQL corpus × dialects) | ratchet: checks in `baseline/differential-passing.txt` must keep passing |
| `TokenizerConformanceTest` | `fixtures/tokens/*.json` | every case must pass |

A ratchet test fails only when a case in its baseline regresses. When you make more cases pass,
record them with `-Djglot.fixtures.updateBaseline=true`, which also rewrites
`baseline/REPORT.md`, and commit the updated baseline files with your change.

## Making changes

* Read [`docs/PORTING.md`](docs/PORTING.md) first. It is the contract for translating sqlglot's
  Python into jglot's Java: naming, the generated method signatures, parser and generator idioms,
  and the Python semantics to preserve.
* Port sqlglot's logic as written. Don't "improve" it: the fixtures compare exact output, and the
  goal is parity.
* Don't introduce static mutable state. Dialects and generated data are shared, immutable
  singletons, and every parse or generate call must be safe to run concurrently.
* Don't edit generated files by hand (they start with `// GENERATED`). Change the generator under
  `tools/codegen` and regenerate.
* jglot has no runtime dependencies, and should stay that way.
* Run `mvn verify` before opening a pull request, and include any baseline updates.

## Moving to a new sqlglot version

jglot tracks one sqlglot release at a time, recorded in
`jglot/src/test/resources/fixtures/sqlglot/VERSION`. Moving to a newer release means regenerating
everything derived from sqlglot, then porting the behaviour that changed until the tests pass
again. Do it as a single pull request, so `main` always matches one sqlglot version.

### 1. Set up sqlglot

Check out the new tag and install it into a virtualenv. The fixture scripts import sqlglot's test
suite, so a pip install from PyPI isn't enough.

```
git clone https://github.com/tobymao/sqlglot ~/.cache/jglot/sqlglot   # first time only
git -C ~/.cache/jglot/sqlglot fetch --tags && git -C ~/.cache/jglot/sqlglot checkout vNEW
python -m venv ~/.cache/jglot/venv && . ~/.cache/jglot/venv/bin/activate
pip install -e ~/.cache/jglot/sqlglot -r tools/requirements.txt
SQLGLOT=~/.cache/jglot/sqlglot
```

### 2. Read what changed

Read sqlglot's `CHANGELOG.md` for the releases in between, and keep the source diff for the files
jglot ports open while you work:

```
git -C $SQLGLOT diff vOLD vNEW -- sqlglot/tokens.py sqlglot/parser.py sqlglot/generator.py \
    sqlglot/transforms.py sqlglot/expressions sqlglot/dialects/dialect.py \
    sqlglot/dialects/{postgres,presto,trino,bigquery,clickhouse,hive}.py \
    sqlglot/optimizer/{annotate_types,simplify,canonicalize,normalize,scope}.py
```

### 3. Regenerate the generated code

```
python tools/codegen/generate_catalog.py --sqlglot $SQLGLOT        # TokenType, ExprType, Args, DType, ExprCatalog
python tools/codegen/generate_dialect_data.py --sqlglot $SQLGLOT   # per-dialect tokenizer/parser/generator/dialect tables
tools/porting/regen_contracts.sh $SQLGLOT                          # ParserMethods, GeneratorMethods, contracts/*.txt
```

Use `git diff` on the results as a to-do list:

* `tools/porting/contracts/{parser,generator}.txt` lists every sqlglot parser and generator method
  with its source line range. Added lines are new methods to port. Changed line ranges point at
  methods whose bodies may have changed, so compare them against the diff from step 2. Removed
  lines are methods whose Java overrides must be deleted or renamed.
* Renamed or removed `ExprType`, `Args` or `TokenType` constants show up as compile errors. Fix
  them first.
* `optimizer/TypeMetadata.java` isn't produced by these scripts. If `annotate_types` or
  `Dialect.EXPRESSION_METADATA` changed, update it by hand and bump the version in its comments.

### 4. Regenerate the fixtures

Run these in this order, because the token fixtures are built from the sqlglot fixtures:

```
F=jglot/src/test/resources/fixtures
python tools/fixtures/extract_sqlglot_fixtures.py --sqlglot $SQLGLOT --out $F/sqlglot   # also writes VERSION
python tools/fixtures/extract_token_fixtures.py --sqlglot $SQLGLOT --fixtures $F/sqlglot --out $F/tokens
python tools/fixtures/extract_differential_fixtures.py --sqlglot $SQLGLOT --out $F/differential/cases.json.gz
```

### 5. Port the changes until the tests pass

Run `mvn verify`. Expect three kinds of failure:

* **Regressions**: baseline cases that used to pass but don't now. These almost always come from
  a behaviour change in sqlglot. Port it, using the diff from step 2.
* **New cases**: cases sqlglot added aren't in the baseline, so they're reported but don't fail
  the build. The aim is still to make all of them pass before the upgrade lands.
* **Tokenizer failures**: there's no baseline here, so every case must pass.

Cases that sqlglot removed or changed drop out of the run and simply leave the baseline. That's
expected. Failing cases are written to `jglot/target/fixture-failures.txt` and
`jglot/target/differential-failures.txt`.

When everything passes, record the result:

```
mvn -pl jglot test -Djglot.fixtures.updateBaseline=true
```

The pass counts in the new `REPORT.md` should be at least as high as before.

### 6. Update the version references

* `README.md`: the baseline version and the conformance numbers.
* `docs/PORTING.md`: the baseline version.
* `CHANGELOG.md`: add an entry naming the new sqlglot version.

## Adding a dialect

Add the dialect name to `DIALECTS` in `tools/codegen/generate_dialect_data.py` and in the three
scripts under `tools/fixtures`, then regenerate (steps 3 and 4 above). Implement
`<Name>`, `<Name>Parser` and `<Name>Generator` under `dialects/<name>/`, extending the class its
sqlglot counterpart extends, and register it in `Dialects`. Add the dialect to the tables in
`README.md`, then run `mvn verify` and record the newly passing cases in the baseline.

## Reporting bugs

Open an issue with the input SQL, the read and write dialects, jglot's output, and sqlglot's
output for the same input. If sqlglot itself gets it wrong, report it to sqlglot first: jglot
follows sqlglot's behaviour. For security issues, see [`SECURITY.md`](SECURITY.md).

## Licence

By contributing, you agree that your contributions are licensed under the MIT licence in
[`LICENSE`](LICENSE).
