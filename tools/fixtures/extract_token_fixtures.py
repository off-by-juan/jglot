#!/usr/bin/env python3
"""Tokenize every fixture input with sqlglot to produce tokenizer conformance fixtures.

Reads the transpilation fixtures (see extract_sqlglot_fixtures.py), tokenizes each distinct
(read dialect, sql) input with sqlglot, and writes one JSON file per dialect:

    {"cases": [{"sql": ..., "tokens": [[type, text, comments...], ...]} | {"sql": ..., "error": true}]}

Usage:
    python tools/fixtures/extract_token_fixtures.py --sqlglot /path/to/sqlglot-checkout \
        --fixtures jglot/src/test/resources/fixtures/sqlglot --out jglot/src/test/resources/fixtures/tokens
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

DIALECTS = ["", "postgres", "presto", "trino", "bigquery", "clickhouse", "hive"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sqlglot", required=True)
    ap.add_argument("--fixtures", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    sys.path.insert(0, str(Path(args.sqlglot).resolve()))

    from sqlglot.dialects.dialect import Dialect
    from sqlglot.errors import TokenError

    inputs: dict[str, dict[str, None]] = defaultdict(dict)
    for f in sorted(Path(args.fixtures).glob("*.json")):
        for case in json.loads(f.read_text())["cases"]:
            if case["read"] in DIALECTS:
                inputs[case["read"]].setdefault(case["sql"], None)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    for d in DIALECTS:
        dialect = Dialect.get_or_raise(d)
        cases = []
        for sql in inputs[d]:
            try:
                tokens = dialect.tokenize(sql)
                cases.append({"sql": sql, "tokens": [[t.token_type.name, t.text, *t.comments] for t in tokens]})
            except TokenError:
                cases.append({"sql": sql, "error": True})
        name = d or "base"
        (out / f"{name}.json").write_text(json.dumps({"dialect": d, "cases": cases}, ensure_ascii=False) + "\n")
        print(f"{name}: {len(cases)} inputs", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
