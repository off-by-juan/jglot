#!/usr/bin/env python3
"""Extract language-neutral transpilation fixtures from sqlglot's dialect test suite.

Runs sqlglot's own ``tests/dialects`` unit tests with the ``Validator`` helpers patched to
record every ``validate_identity`` / ``validate_all`` / ``validate_transpile`` case. Each
recorded case is re-checked against sqlglot itself and only kept when sqlglot agrees with
the expectation, so every fixture is a known-good (input, dialects, expected output) triple.

Usage:
    python tools/fixtures/extract_sqlglot_fixtures.py --sqlglot /path/to/sqlglot-checkout \
        --out src/test/resources/fixtures/sqlglot

The sqlglot checkout must be importable (``pip install -e``) and should be the tagged
release recorded in ``src/test/resources/fixtures/sqlglot/VERSION``.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import unittest
from collections import defaultdict
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sqlglot", required=True, help="path to a sqlglot source checkout")
    ap.add_argument("--out", required=True, help="output directory for fixture json files")
    args = ap.parse_args()

    root = Path(args.sqlglot).resolve()
    out = Path(args.out).resolve()
    sys.path.insert(0, str(root))
    os.chdir(root)
    logging.disable(logging.CRITICAL)

    import sqlglot
    from sqlglot import ErrorLevel, UnsupportedError, parse_one
    from sqlglot._version import __version__
    from tests.dialects import test_dialect

    Validator = test_dialect.Validator
    cases: dict[str, list[dict]] = defaultdict(list)
    current: dict[str, str] = {}
    stats = defaultdict(int)

    def norm(d):
        return "" if d is None else (d if isinstance(d, str) else d.__name__.lower())

    def emit(case: dict, check) -> None:
        try:
            ok = check()
        except Exception:
            ok = False
        if ok:
            case["test"] = current["test"]
            cases[current["file"]].append(case)
            stats["kept"] += 1
        else:
            stats["dropped"] += 1

    def run_sql(sql, read, write, pretty, identify, unsupported_level=ErrorLevel.IGNORE):
        return parse_one(sql, read=read).sql(
            dialect=write, pretty=pretty, identify=identify, unsupported_level=unsupported_level
        )

    def validate_identity(self, sql, write_sql=None, pretty=False, check_command_warning=False, identify=False):
        d = norm(self.dialect)
        expected = write_sql or sql
        case = {"kind": "identity", "read": d, "write": d, "sql": sql, "expected": expected}
        if pretty:
            case["pretty"] = True
        if identify:
            case["identify"] = True
        if check_command_warning:
            case["command"] = True
        emit(case, lambda: parse_one(sql, read=self.dialect).sql(
            dialect=self.dialect, pretty=pretty, identify=identify) == expected)
        return parse_one(sql, read=self.dialect)

    def validate_transpile(self, sql, write_sql, write_dialect=None):
        case = {"kind": "transpile", "read": norm(self.dialect), "write": norm(write_dialect),
                "sql": sql, "expected": write_sql}
        emit(case, lambda: parse_one(sql, read=self.dialect).sql(dialect=write_dialect) == write_sql)
        return parse_one(sql, read=self.dialect)

    def validate_all(self, sql, read=None, write=None, pretty=False, identify=False):
        d = norm(self.dialect)
        extra = {}
        if pretty:
            extra["pretty"] = True
        if identify:
            extra["identify"] = True
        for rd, rsql in (read or {}).items():
            case = {"kind": "read", "read": norm(rd), "write": d, "sql": rsql, "expected": sql, **extra}
            emit(case, lambda rd=rd, rsql=rsql: run_sql(rsql, rd, self.dialect, pretty, identify) == sql)
        for wd, wsql in (write or {}).items():
            if wsql is UnsupportedError:
                case = {"kind": "write", "read": d, "write": norm(wd), "sql": sql,
                        "expectedError": "unsupported", **extra}

                def check(wd=wd):
                    try:
                        parse_one(sql, read=self.dialect).sql(wd, unsupported_level=ErrorLevel.RAISE)
                    except UnsupportedError:
                        return True
                    return False

                emit(case, check)
            else:
                case = {"kind": "write", "read": d, "write": norm(wd), "sql": sql, "expected": wsql, **extra}
                emit(case, lambda wd=wd, wsql=wsql: run_sql(sql, self.dialect, wd, pretty, identify) == wsql)
        return parse_one(sql, read=self.dialect)

    Validator.validate_identity = validate_identity
    Validator.validate_transpile = validate_transpile
    Validator.validate_all = validate_all

    loader = unittest.TestLoader()
    suite = loader.discover(str(root / "tests" / "dialects"), top_level_dir=str(root))

    def iter_tests(s):
        for t in s:
            if isinstance(t, unittest.TestSuite):
                yield from iter_tests(t)
            else:
                yield t

    result = unittest.TestResult()
    tests = list(iter_tests(suite))
    broken = [t for t in tests if type(t).__name__ == "_FailedTest"]
    if broken:
        for t in broken:
            print(f"failed to import {t._testMethodName}: {t._exception}", file=sys.stderr)
        return 1
    for test in tests:
        module = type(test).__module__.rsplit(".", 1)[-1]
        current["file"] = module.removeprefix("test_")
        current["test"] = f"{type(test).__name__}.{test._testMethodName}"
        test.run(result)

    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("*.json"):
        old.unlink()
    for name, items in sorted(cases.items()):
        with open(out / f"{name}.json", "w", encoding="utf-8") as fh:
            json.dump({"sqlglotVersion": __version__, "source": f"tests/dialects/test_{name}.py",
                       "cases": items}, fh, ensure_ascii=False, indent=1)
            fh.write("\n")
    (out / "VERSION").write_text(__version__ + "\n")
    print(f"sqlglot {__version__}: kept {stats['kept']} cases, dropped {stats['dropped']} "
          f"(sqlglot disagreed or errored) across {len(cases)} files", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
