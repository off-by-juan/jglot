#!/usr/bin/env bash
# Compiles the given Java files against the scaffold classes and runs the sqlglot fixture cases that
# involve the given dialects, without touching the shared build. Usage:
#   tools/porting/test_dialect.sh "postgres" jglot/src/main/java/.../postgres/*.java
#   tools/porting/test_dialect.sh "presto trino starburst" <files...>
# Failures are written to /tmp/jglot-fixtures-<first dialect>.txt
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DIALECTS="$1"; shift
SCAFFOLD="${JGLOT_SCAFFOLD:-$HOME/.cache/jglot/scaffold-classes}"
M2="$HOME/.m2/repository/com/fasterxml/jackson/core"
JACKSON="$M2/jackson-databind/2.22.4/jackson-databind-2.22.4.jar:$M2/jackson-core/2.22.4/jackson-core-2.22.4.jar:$(ls $M2/jackson-annotations/*/jackson-annotations-*.jar | tail -1)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
if [ "$#" -gt 0 ]; then
  javac -proc:none -nowarn -Xmaxerrs 200 -encoding UTF-8 --release 21 -d "$OUT" -cp "$SCAFFOLD" "$@"
fi
FIRST="${DIALECTS%% *}"
java -cp "$OUT:$SCAFFOLD:$JACKSON" "$ROOT/tools/porting/FixtureRunner.java" \
  "$ROOT/jglot/src/test/resources/fixtures/sqlglot" "/tmp/jglot-fixtures-$FIRST.txt" $DIALECTS
