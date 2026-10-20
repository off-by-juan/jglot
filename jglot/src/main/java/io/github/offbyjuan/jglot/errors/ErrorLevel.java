package io.github.offbyjuan.jglot.errors;

/** How parse and generation errors are reported. */
public enum ErrorLevel {
    /** Ignore all errors. */
    IGNORE,
    /** Log all errors. */
    WARN,
    /** Collect all errors and raise a single exception. */
    RAISE,
    /** Immediately raise an exception on the first error found. */
    IMMEDIATE
}
