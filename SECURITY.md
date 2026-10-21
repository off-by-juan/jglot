# Security policy

## Supported versions

jglot is pre-1.0. Security fixes go into the latest release only.

## Reporting a vulnerability

Please don't open a public issue. Report it privately through GitHub's
[private vulnerability reporting](https://github.com/off-by-juan/jglot/security/advisories/new)
instead, with the input that triggers the problem and the dialects involved.

jglot is a library that parses untrusted SQL text, so the issues most likely to matter are inputs
that cause unbounded CPU or memory use, stack overflows that escape as something other than a
`JGlotError`, or output SQL that differs from the input in a security-relevant way (for example a
dropped `WHERE` clause or broken quoting).

This is a personal project maintained in spare time. I'll acknowledge a report as soon as I can
and keep you updated while it is being fixed.
