package io.github.offbyjuan.jglot.functions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.offbyjuan.jglot.JGlot;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.errors.ErrorLevel;
import io.github.offbyjuan.jglot.errors.UnsupportedError;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The function-handling contract: map known functions, fail loudly when untranslatable, pass unknown ones through. */
class FunctionTranslationTest {

    @Test
    void knownFunctionsAreMappedToTheTargetEquivalent() {
        assertEquals("SELECT COALESCE(a, b), COALESCE(c, d) FROM t",
                JGlot.transpileOne("SELECT NVL(a, b), IFNULL(c, d) FROM t", "hive", "postgres"));
        assertEquals("SELECT DATE_ADD(ts, INTERVAL 1 DAY), APPROX_COUNT_DISTINCT(x) FROM t",
                JGlot.transpileOne("SELECT DATE_ADD('day', 1, ts), APPROX_DISTINCT(x) FROM t", "trino", "bigquery"));
        assertEquals("SELECT EDIT_DISTANCE(a, b) FROM t",
                JGlot.transpileOne("SELECT levenshtein(a, b) FROM t", "postgres", "bigquery"));
        assertEquals("SELECT a ~ 'x' FROM t", JGlot.transpileOne("SELECT REGEXP_LIKE(a, 'x') FROM t", "trino", "postgres"));
    }

    @Test
    void unknownFunctionsPassThrough() {
        assertEquals("SELECT MY_UDF(a, 1) FROM t", JGlot.transpileOne("SELECT my_udf(a, 1) FROM t", "postgres", "trino"));
        assertEquals("SELECT * FROM my_table_fn(1, 2)",
                JGlot.transpileOne("SELECT * FROM my_table_fn(1, 2)", "trino", "bigquery"));
        // Dialects that normalize function names upper-case them (sqlglot behaviour), unknown ones included.
        assertEquals("SELECT * FROM MY_TABLE_FN(1, 2)",
                JGlot.transpileOne("SELECT * FROM my_table_fn(1, 2)", "trino", "starburst"));
    }

    @Test
    void untranslatableConstructsFailLoudlyByDefault() {
        // sqlglot marks these as unsupported for the target; jglot raises by default.
        UnsupportedError e = assertThrows(UnsupportedError.class,
                () -> JGlot.transpileOne("SELECT a FROM t PIVOT(SUM(b) FOR c IN ('x', 'y'))", "bigquery", "postgres"));
        assertTrue(e.getMessage().toLowerCase().contains("pivot"), e.getMessage());
    }

    @Test
    void knownFunctionWhoseNameMeansSomethingElseInTheTargetFailsLoudly() {
        // CONTAINS(str, substr) has no Presto/Trino rendering, and CONTAINS there is array membership.
        String sql = "SELECT CONTAINS_SUBSTR(a, 'x') FROM t";
        assertThrows(UnsupportedError.class, () -> JGlot.transpileOne(sql, "bigquery", "trino"));
        // ...unless strict function checking is turned off (sqlglot behaviour).
        GeneratorOptions lax = GeneratorOptions.builder().strictFunctions(false).unsupportedLevel(ErrorLevel.RAISE).build();
        List<String> out = JGlot.transpile(sql, Dialects.get("bigquery"), Dialects.get("trino"), lax);
        assertEquals(1, out.size());
    }
}
