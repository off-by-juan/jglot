#!/usr/bin/env bash
# Compiles porting fragment(s) in isolation against the stable scaffold classes, so fragments that
# are being written in parallel don't break each other's builds.
#   tools/porting/check.sh jglot/src/porting/java/.../ParserPart2.java [more files...]
set -euo pipefail
SCAFFOLD="${JGLOT_SCAFFOLD:-$HOME/.cache/jglot/scaffold-classes}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -proc:none -nowarn -Xmaxerrs 200 -encoding UTF-8 --release 21 -d "$OUT" -cp "$SCAFFOLD" "$@"
echo "OK: $*"
