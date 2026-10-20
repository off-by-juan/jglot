#!/usr/bin/env bash
# Regenerates the parser/generator signature contracts (stub classes + listings) from sqlglot.
# Usage: tools/porting/regen_contracts.sh /path/to/sqlglot-checkout
set -euo pipefail
SQLGLOT="${1:?path to sqlglot checkout}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/jglot/src/main/java/io/github/offbyjuan/jglot"
IMPORTS="io.github.offbyjuan.jglot.dialects.Dialect,io.github.offbyjuan.jglot.expressions.*,io.github.offbyjuan.jglot.tokens.*,java.util.*"
mkdir -p "$ROOT/tools/porting/contracts"
python3 "$ROOT/tools/porting/signatures.py" --sqlglot "$SQLGLOT" --module sqlglot/parser.py --cls Parser \
  --java-class ParserMethods --extends ParserCore --package io.github.offbyjuan.jglot.parser \
  --ctor "Dialect dialect, ParserOptions options" \
  --out "$SRC/parser/ParserMethods.java" --listing "$ROOT/tools/porting/contracts/parser.txt" \
  --exclude "reset,_advance,_advance_chunk,_retreat,_add_comments,_match,_match_set,_match_pair,_match_texts,_match_text_seq,_is_connected,_find_sql,raise_error,validate_expression,_try_parse,parse,parse_into,check_errors,expression,_parse_batch_statements,_parse,_warn_unsupported,_find_parser,_match_l_paren,_match_r_paren,_parse_csv,_parse_wrapped,_parse_wrapped_csv" --imports "$IMPORTS"
python3 "$ROOT/tools/porting/signatures.py" --sqlglot "$SQLGLOT" --module sqlglot/generator.py --cls Generator \
  --java-class GeneratorMethods --extends GeneratorCore --package io.github.offbyjuan.jglot.generator \
  --ctor "Dialect dialect, GeneratorOptions options" --sql-dispatch --visibility public \
  --out "$SRC/generator/GeneratorMethods.java" --listing "$ROOT/tools/porting/contracts/generator.txt" \
  --exclude "generate,unsupported,sep,seg,sanitize_comment,maybe_comment,wrap,no_identify,normalize_func,indent,sql,binary,function_fallback_sql,function_name,func,format_args,too_wide,expressions,_replace_line_breaks" --imports "$IMPORTS"
