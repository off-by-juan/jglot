#!/usr/bin/env python3
"""Derive Java method signatures for a sqlglot Python class, and emit compile-ready stubs.

The Java port of a large sqlglot class (e.g. Parser, Generator) is written in parallel fragments.
To make the fragments fit together, every Python method gets one canonical Java signature, derived
mechanically from its name and annotations. This script emits:

  * a stub class declaring every method with that signature and a body that throws, so that every
    fragment compiles against the full API while it is being written; and
  * a plain-text listing (python def -> java signature) used as the porting contract.

    python tools/porting/signatures.py --sqlglot /path/to/sqlglot --module sqlglot/parser.py \
        --cls Parser --java-class ParserMethods --extends ParserCore --package ...parser --out ...

Naming rules:
    _parse_foo_bar  -> parseFooBar        select_sql -> selectSql       _match_text_seq -> matchTextSeq
    parameters named like Java keywords get a trailing underscore: this -> this_, default -> default_
"""

from __future__ import annotations

import argparse
import ast
import re
import sys
from pathlib import Path

# Unannotated parameters with these names hold syntax tree nodes.
EXPR_PARAM_NAMES = {"this", "expression", "query", "node", "e", "unnest", "other", "value", "parent", "table",
                    "column", "func", "args_expr"}

JAVA_KEYWORDS = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
    "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
    "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
    "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
    "volatile", "record", "var", "yield", "sealed", "permits", "true", "false", "null",
}


def camel(name: str) -> str:
    parts = [p for p in name.strip("_").split("_") if p]
    if not parts:
        return name
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def java_param(name: str) -> str:
    if name.strip("_") == "":
        return "unused"
    n = camel(name)
    return n + "_" if n in JAVA_KEYWORDS else n


def norm(ann: str | None) -> str:
    return re.sub(r"\s+", "", ann or "")


def is_expr_type(a: str) -> bool:
    return bool(re.fullmatch(r"(exp\.[A-Za-z0-9_]+|E|T|B|F|Q|TCeilFloor|exp\.Expr|exp\.Expression|exp\.Condition|exp\.Func|"
                             r"exp\.Query|exp\.Selectable)", a))


def map_type(ann: str | None, *, ret: bool = False) -> str:
    a = norm(ann)
    if not a:
        return "Object"
    a = a.replace("t.Optional[", "Optional[").replace("typing.", "t.")
    m = re.fullmatch(r"Optional\[(.*)\]", a)
    if m:
        a = m.group(1) + "|None"
    parts = [p for p in split_union(a)]
    nullable = "None" in parts
    parts = [p for p in parts if p != "None"]
    if not parts:
        return "void" if ret else "Object"
    if all(is_expr_type(p) for p in parts):
        return "Expression"
    if all(re.fullmatch(r"(list|t\.List|t\.Sequence|Sequence|t\.Iterable|Iterable|t\.Collection|Collection)\[(exp\.[A-Za-z0-9_]+|E|exp\.Expr|t\.Any)\]", p) for p in parts):
        return "List<Expression>"
    if len(parts) > 1 and any(is_expr_type(p) for p in parts) and any(p.startswith("list[") for p in parts):
        return "Object"
    p = parts[0] if len(parts) == 1 else None
    if p is None:
        if set(parts) <= {"str", "bool"}:
            return "Object"
        if all(re.fullmatch(r"(t\.)?(Type|type)\[.*\]", x) for x in parts):
            return "ExprType"
        return "Object"
    if p == "bool":
        return "Boolean" if nullable else "boolean"
    if p in ("int", "i64"):
        return "Integer" if nullable else "int"
    if p == "str":
        return "String"
    if p == "TokenType":
        return "TokenType"
    if p in ("Token", "t.Optional[Token]"):
        return "Token"
    if re.fullmatch(r"(t\.)?(Collection|Set|AbstractSet|set|frozenset|t\.AbstractSet)\[TokenType\]", p):
        return "Set<TokenType>"
    if re.fullmatch(r"(t\.)?(Type|type)\[.*\]", p) or p in ("exp.IntoType",):
        return "ExprType"
    if p == "TEXTS_TYPE" or re.fullmatch(r"(t\.)?(Collection|Set|set|list|t\.Iterable|Iterable|Sequence|t\.Sequence)\[str\]", p):
        return "Collection<String>"
    if p == "OPTIONS_TYPE":
        return "Map<String, List<List<String>>>"
    if re.fullmatch(r"(list|t\.List)\[str\]", p):
        return "List<String>"
    if re.fullmatch(r"(list|t\.List)\[Token\]", p):
        return "List<Token>"
    if re.fullmatch(r"(dict|t\.Dict|t\.Mapping|Mapping)\[.*\]", p):
        return "Map<String, Object>"
    if re.fullmatch(r"(t\.)?Callable\[.*\]", p):
        return "ParseFn"
    if p in ("exp.DType", "DType"):
        return "DType"
    if p == "DATA_TYPE":
        return "Object"
    if p.startswith("exp.") and "|" not in p:
        return "Expression"
    if re.fullmatch(r"(list|t\.List)\[.*\]", p):
        return "List<Object>"
    if re.fullmatch(r"(tuple|t\.Tuple)\[.*\]", p):
        return "Object[]"
    if p == "t.Any":
        return "Object"
    return "Object"


def split_union(a: str) -> list[str]:
    out, depth, cur = [], 0, ""
    for ch in a:
        if ch in "[(":
            depth += 1
        elif ch in "])":
            depth -= 1
        if ch == "|" and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    if cur:
        out.append(cur)
    # t.Union[a, b]
    res = []
    for p in out:
        m = re.fullmatch(r"(t\.)?Union\[(.*)\]", p)
        if m:
            res += [x for x in split_args(m.group(2))]
        else:
            res.append(p)
    return res


def split_args(s: str) -> list[str]:
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch in "[(":
            depth += 1
        elif ch in "])":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    if cur:
        out.append(cur)
    return out


def default_java(node, jtype: str) -> str:
    if node is None:
        return ""
    src = ast.unparse(node)
    if src == "None":
        return "null"
    if src == "True":
        return "true" if jtype in ("boolean", "Boolean", "Object") else src
    if src == "False":
        return "false" if jtype in ("boolean", "Boolean", "Object") else src
    if re.fullmatch(r"-?\d+", src):
        return src
    if re.fullmatch(r"(['\"]).*\1", src):
        return '"' + ast.literal_eval(src).replace("\\", "\\\\").replace('"', '\\"') + '"'
    if src.startswith("TokenType."):
        return src
    if src.startswith("SENTINEL"):
        return "SENTINEL_NONE"
    if src.startswith("exp."):
        return "ExprType." + src[4:]
    return f"/* {src} */ null"


class Method:
    def __init__(self, py_name, java_name, ret, params, py_sig, lineno, end_lineno):
        self.py_name = py_name
        self.java_name = java_name
        self.ret = ret
        self.params = params  # list of (jtype, jname, default_java or "")
        self.py_sig = py_sig
        self.lineno = lineno
        self.end_lineno = end_lineno

    def java_sig(self) -> str:
        ps = ", ".join(f"{t} {n}" for t, n, _ in self.params)
        return f"{self.ret} {self.java_name}({ps})"


def collect(module_path: Path, cls_name: str) -> list[Method]:
    tree = ast.parse(module_path.read_text())
    cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == cls_name)
    methods: dict[str, Method] = {}
    for node in cls.body:
        if not isinstance(node, ast.FunctionDef):
            continue
        if any(ast.unparse(d) in ("t.overload", "overload") for d in node.decorator_list):
            continue
        if any(ast.unparse(d) in ("classmethod", "staticmethod", "property") for d in node.decorator_list):
            static = True
        else:
            static = False
        if node.name.startswith("__"):
            continue
        a = node.args
        params = []
        positional = a.args[1:] if not static or (a.args and a.args[0].arg in ("cls", "self")) else a.args
        defaults = [None] * (len(positional) - len(a.defaults)) + list(a.defaults)
        for arg, d in zip(positional, defaults):
            jt = map_type(ast.unparse(arg.annotation) if arg.annotation else None)
            if not arg.annotation and arg.arg in EXPR_PARAM_NAMES:
                jt = "Expression"
            params.append((jt, java_param(arg.arg), default_java(d, jt)))
        if a.vararg:
            ann = ast.unparse(a.vararg.annotation) if a.vararg.annotation else None
            jt = map_type(ann)
            if jt == "Object":
                jt = "Object"
            params.append((jt + "...", java_param(a.vararg.arg), ""))
        for arg, d in zip(a.kwonlyargs, a.kw_defaults):
            jt = map_type(ast.unparse(arg.annotation) if arg.annotation else None)
            params.append((jt, java_param(arg.arg), default_java(d, jt)))
        if a.kwarg:
            params.append(("Map<Arg, Object>", java_param(a.kwarg.arg), "null"))
        # varargs must be last in Java
        va = [p for p in params if p[0].endswith("...")]
        if va and params[-1] is not va[0]:
            params = [p for p in params if not p[0].endswith("...")] + va
        ret = map_type(ast.unparse(node.returns) if node.returns else None, ret=True)
        if node.name.endswith("_sql") and ret == "Object":
            ret = "String"
        jname = camel(node.name)
        if jname in JAVA_KEYWORDS:
            jname += "_"
        py_sig = f"def {node.name}({ast.unparse(node.args)})" + (f" -> {ast.unparse(node.returns)}" if node.returns else "")
        m = Method(node.name, jname, ret, params, py_sig, node.lineno, node.end_lineno)
        if jname in methods:
            m.java_name = jname + "Impl"
        methods[m.java_name] = m
    return list(methods.values())


def cast_default(jtype: str, dflt: str) -> str:
    """Casts null defaults so overload resolution is unambiguous."""
    if dflt == "null" or dflt.endswith("null"):
        return f"({jtype}) null"
    return dflt


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sqlglot", required=True)
    ap.add_argument("--module", required=True)
    ap.add_argument("--cls", required=True)
    ap.add_argument("--java-class", required=True)
    ap.add_argument("--extends", required=True)
    ap.add_argument("--package", required=True)
    ap.add_argument("--out", required=True, help="output .java path for the stubs")
    ap.add_argument("--listing", required=True, help="output .txt path for the contract listing")
    ap.add_argument("--exclude", default="", help="comma-separated python method names implemented by hand")
    ap.add_argument("--imports", default="")
    ap.add_argument("--ctor", default="", help="constructor params passed through to super, e.g. 'Dialect dialect, ParserOptions options'")
    ap.add_argument("--sql-dispatch", action="store_true", help="emit sqlFor(): <key>_sql dispatch for generator classes")
    args = ap.parse_args()

    methods = collect(Path(args.sqlglot) / args.module, args.cls)
    exclude = set(filter(None, args.exclude.split(",")))
    methods = [m for m in methods if m.py_name not in exclude]

    L = ["// GENERATED by tools/porting/signatures.py. Porting scaffold: every method here is overridden",
         "// by a hand-ported fragment. Do not add logic here.",
         f"package {args.package};\n"]
    for imp in filter(None, args.imports.split(",")):
        L.append(f"import {imp};")
    L += ["",
          f"/** Signature contract for the ported {args.cls}: one stub per sqlglot method. */",
          "@SuppressWarnings({\"unused\", \"RedundantThrows\"})",
          f"public abstract class {args.java_class} extends {args.extends} {{"]
    if args.ctor:
        names = ", ".join(p.strip().split(" ")[-1] for p in args.ctor.split(","))
        L += [f"    protected {args.java_class}({args.ctor}) {{", f"        super({names});", "    }\n"]
    if args.sql_dispatch:
        sys.path.insert(0, str(Path(args.sqlglot).resolve()))
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "codegen"))
        from sqlglot import exp
        from generate_catalog import const_name
        by_name = {m.py_name: m for m in methods}
        L.append("    /** Dispatches to the {@code <key>_sql} method for the node's exact type, or returns null. */")
        L.append("    @Override")
        L.append("    protected String sqlFor(Expression e) {")
        L.append("        return switch (e.type()) {")
        for key, cls in sorted(exp.EXPR_CLASSES.items()):
            m = by_name.get(f"{key}_sql")
            if m and issubclass(cls, exp.Expression):
                L.append(f"            case {const_name(cls.__name__)} -> {m.java_name}(e);")
        L.append("            default -> null;")
        L.append("        };")
        L.append("    }\n")
    for m in methods:
        L.append(f"    /** {args.module}:{m.lineno} {m.py_name} */")
        L.append(f"    protected {m.java_sig()} {{")
        L.append(f"        throw notPorted(\"{m.py_name}\");")
        L.append("    }\n")
        # Convenience overloads for trailing default arguments, mirroring Python positional calls.
        params = m.params
        n_req = len(params)
        while n_req > 0 and params[n_req - 1][2] and not params[n_req - 1][0].endswith("..."):
            n_req -= 1
        if any(p[0].endswith("...") for p in params):
            continue
        for k in range(n_req, len(params)):
            head = params[:k]
            sig = ", ".join(f"{t} {n}" for t, n, _ in head)
            call = ", ".join([n for _, n, _ in head] + [cast_default(t, dflt) for t, _, dflt in params[k:]])
            ret = "" if m.ret == "void" else "return "
            L.append(f"    protected final {m.ret} {m.java_name}({sig}) {{")
            L.append(f"        {ret}{m.java_name}({call});")
            L.append("    }\n")
    L.append("}")
    Path(args.out).write_text("\n".join(L) + "\n")

    T = []
    for m in methods:
        defaults = ", ".join(f"{n}={d}" for _, n, d in m.params if d)
        T.append(f"{args.module}:{m.lineno}-{m.end_lineno}\t{m.py_name}\t{m.java_sig()}" + (f"\tdefaults: {defaults}" if defaults else ""))
    Path(args.listing).write_text("\n".join(T) + "\n")
    print(f"{len(methods)} methods", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
