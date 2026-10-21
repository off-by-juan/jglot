#!/usr/bin/env python3
"""Merge ported fragments into the final source tree.

Parser fragments (ParserPart*.java) are concatenated into parser/Parser.java and generator
fragments (GeneratorPart*.java) into generator/Generator.java. Other files under
jglot/src/porting/java (e.g. transforms/Transforms.java, optimizer/*.java,
dialects/DialectHelpers.java) replace their counterparts in jglot/src/main/java.

    python tools/porting/merge_fragments.py [--dry-run]
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PORTING = ROOT / "jglot/src/porting/java/io/github/offbyjuan/jglot"
MAIN = ROOT / "jglot/src/main/java/io/github/offbyjuan/jglot"

TARGETS = {
    "parser": ("ParserPart", "Parser", "ParserMethods", "ParserOptions",
               "The SQL parser: a port of sqlglot's {@code Parser}. Dialects subclass it to override individual\n"
               " * parse methods and dispatch hooks.\n *\n"
               " * <p>Create parsers through {@link Dialect#newParser}; an instance parses one input and is not\n"
               " * thread-safe, but creating one is cheap since all tables are shared, immutable dialect data."),
    "generator": ("GeneratorPart", "Generator", "GeneratorMethods", "GeneratorOptions",
                  "The SQL generator: a port of sqlglot's {@code Generator}. Dialects subclass it to override\n"
                  " * individual {@code <key>Sql} methods and the {@link #transform} hook.\n *\n"
                  " * <p>Create generators through {@link Dialect#newGenerator}; an instance renders one tree and is\n"
                  " * not thread-safe, but creating one is cheap since all tables are shared, immutable dialect data."),
}


def split_fragment(text: str, cls: str) -> tuple[list[str], str]:
    imports = re.findall(r"^import [^;]+;", text, flags=re.M)
    m = re.search(r"^(?:abstract\s+)?(?:public\s+)?(?:abstract\s+)?class\s+" + re.escape(cls) + r"\b[^{]*\{", text, flags=re.M)
    if not m:
        raise SystemExit(f"cannot find class {cls}")
    body = text[m.end():]
    body = body[: body.rstrip().rfind("}")]
    # drop the pass-through constructor
    body = re.sub(r"\n\s*" + re.escape(cls) + r"\s*\([^)]*\)\s*\{\s*super\([^)]*\);\s*\}\s*\n", "\n", body, count=1)
    return imports, body.strip("\n")


def merge(kind: str, dry_run: bool) -> None:
    prefix, final, base, opts, doc = TARGETS[kind]
    parts = sorted((PORTING / kind).glob(f"{prefix}*.java"))
    if not parts:
        print(f"{kind}: no fragments", file=sys.stderr)
        return
    imports: set[str] = {f"import io.github.offbyjuan.jglot.dialects.Dialect;"}
    bodies = []
    for p in parts:
        imps, body = split_fragment(p.read_text(), p.stem)
        imports.update(imps)
        bodies.append(f"    // ---------------------------------------------------------------------------------------\n"
                      f"    // From {p.stem}\n\n{body}\n")
    pkg = f"io.github.offbyjuan.jglot.{kind}"
    imports = {i for i in imports if not re.match(rf"import {re.escape(pkg)}\.[A-Z]\w*;", i)}
    out = [f"package {pkg};\n"]
    out += sorted(imports)
    out += ["", "/**", f" * {doc}", " */", f"public class {final} extends {base} {{",
            f"    public {final}(Dialect dialect, {opts} options) {{", "        super(dialect, options);", "    }\n"]
    out += bodies
    out.append("}")
    target = MAIN / kind / f"{final}.java"
    if dry_run:
        print(f"would write {target} from {[p.name for p in parts]}")
    else:
        target.write_text("\n".join(out) + "\n")
        print(f"wrote {target.relative_to(ROOT)} from {len(parts)} fragments")


def move_others(dry_run: bool) -> None:
    for p in sorted(PORTING.rglob("*.java")):
        rel = p.relative_to(PORTING)
        if rel.parts[0] in TARGETS and (p.stem.startswith("ParserPart") or p.stem.startswith("GeneratorPart")):
            continue
        dest = MAIN / rel
        if dry_run:
            print(f"would copy {rel}")
        else:
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(p, dest)
            print(f"copied {rel}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    for kind in TARGETS:
        merge(kind, args.dry_run)
    move_others(args.dry_run)
    return 0


if __name__ == "__main__":
    sys.exit(main())
