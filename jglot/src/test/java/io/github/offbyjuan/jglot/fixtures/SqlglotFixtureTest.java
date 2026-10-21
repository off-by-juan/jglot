package io.github.offbyjuan.jglot.fixtures;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.errors.ErrorLevel;
import io.github.offbyjuan.jglot.errors.UnsupportedError;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.parser.ParserOptions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Runs the transpilation cases extracted from sqlglot's own test suite (see
 * tools/fixtures/extract_sqlglot_fixtures.py) against jglot.
 *
 * <p>Only cases whose read and write dialects are both implemented are run. The set of cases known
 * to pass is a ratchet kept in {@code fixtures/baseline/passing.txt}: the test fails if any of them
 * regresses. After making more cases pass, record them with
 * {@code mvn test -Djglot.fixtures.updateBaseline=true}, which also rewrites the per-dialect report
 * in {@code fixtures/baseline/REPORT.md}.
 */
class SqlglotFixtureTest {
    private static final String[] FILES = {
        "athena", "bigquery", "clickhouse", "databricks", "dax", "dialect", "doris", "dremio", "drill", "druid",
        "duckdb", "dune", "exasol", "fabric", "hive", "materialize", "mysql", "oracle", "pipe_syntax",
        "postgres", "presto", "prql", "redshift", "risingwave", "singlestore", "snowflake", "solr", "spark",
        "sqlite", "starrocks", "tableau", "teradata", "trino", "tsql"
    };

    private static final Path BASELINE_DIR = Path.of("src/test/resources/fixtures/baseline");

    record Case(String file, String test, String kind, String read, String write, String sql, String expected,
            boolean expectError, boolean pretty, boolean identify) {

        String id() {
            String key = String.join("\u0001", read, write, sql, expected == null ? "<error>" : expected,
                    Boolean.toString(pretty), Boolean.toString(identify));
            try {
                byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(h, 0, 10);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        String pair() {
            return (read.isEmpty() ? "base" : read) + " -> " + (write.isEmpty() ? "base" : write);
        }
    }

    static List<Case> loadCases() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<Case> cases = new ArrayList<>();
        for (String f : FILES) {
            JsonNode root;
            try (InputStream in = SqlglotFixtureTest.class.getResourceAsStream("/fixtures/sqlglot/" + f + ".json")) {
                if (in == null) {
                    continue;
                }
                root = mapper.readTree(in);
            }
            for (JsonNode c : root.get("cases")) {
                cases.add(new Case(
                        f,
                        c.path("test").asText(),
                        c.get("kind").asText(),
                        c.get("read").asText(),
                        c.get("write").asText(),
                        c.get("sql").asText(),
                        c.has("expected") ? c.get("expected").asText() : null,
                        c.has("expectedError"),
                        c.path("pretty").asBoolean(false),
                        c.path("identify").asBoolean(false)));
            }
        }
        return cases;
    }

    /** Runs one case; returns null on success or a failure description. */
    static String run(Case c) {
        Dialect read = Dialects.find(c.read());
        Dialect write = Dialects.find(c.write());
        try {
            Expression parsed = singleStatement(read, c.sql());
            if (c.expectError()) {
                GeneratorOptions opts = GeneratorOptions.builder().unsupportedLevel(ErrorLevel.RAISE).strictFunctions(false).build();
                try {
                    write.generate(parsed, opts);
                    return "expected UnsupportedError";
                } catch (UnsupportedError expected) {
                    return null;
                }
            }
            GeneratorOptions opts = GeneratorOptions.builder()
                    .unsupportedLevel(ErrorLevel.IGNORE)
                    .pretty(c.pretty())
                    .identify(c.identify())
                    .build();
            String actual = write.generate(parsed, opts);
            if (!actual.equals(c.expected())) {
                return "expected: " + c.expected() + "\n  actual:   " + actual;
            }
            return null;
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
            return msg.length() > 300 ? msg.substring(0, 300) : msg;
        }
    }

    private static Expression singleStatement(Dialect d, String sql) {
        // sqlglot's parse_one: the first statement (validate_* only ever use single statements).
        List<Expression> all = d.parse(sql, ParserOptions.DEFAULT);
        return all.isEmpty() ? null : all.get(0);
    }

    @Test
    void sqlglotConformance() throws IOException {
        List<Case> cases = loadCases();
        Set<String> baseline = readBaseline();
        Set<String> passing = new TreeSet<>();
        List<String> regressions = new ArrayList<>();
        Map<String, int[]> byPair = new TreeMap<>();
        Map<String, List<String>> failuresByPair = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        int run = 0;

        for (Case c : cases) {
            if (Dialects.find(c.read()) == null || Dialects.find(c.write()) == null) {
                continue;
            }
            String id = c.id();
            if (!seen.add(id)) {
                continue;
            }
            run++;
            String failure = run(c);
            int[] counts = byPair.computeIfAbsent(c.pair(), k -> new int[2]);
            counts[1]++;
            if (failure == null) {
                passing.add(id);
                counts[0]++;
            } else {
                failuresByPair.computeIfAbsent(c.pair(), k -> new ArrayList<>())
                        .add("[" + c.file() + " " + c.test() + "] " + c.sql() + "\n  " + failure);
                if (baseline.contains(id)) {
                    regressions.add("[" + id + "] " + c.pair() + " " + c.sql() + "\n  " + failure);
                }
            }
        }

        StringBuilder summary = new StringBuilder();
        summary.append(String.format("sqlglot fixtures: %d/%d passing (baseline %d)%n", passing.size(), run, baseline.size()));
        for (Map.Entry<String, int[]> e : byPair.entrySet()) {
            summary.append(String.format("  %-28s %5d / %5d%n", e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        System.out.println(summary);

        writeFailures(failuresByPair);
        if (Boolean.getBoolean("jglot.fixtures.updateBaseline")) {
            writeBaseline(passing, byPair, run);
        } else {
            int newlyPassing = 0;
            for (String id : passing) {
                if (!baseline.contains(id)) {
                    newlyPassing++;
                }
            }
            if (newlyPassing > 0) {
                System.out.println(newlyPassing + " cases pass that are not in the baseline; "
                        + "run with -Djglot.fixtures.updateBaseline=true to record them.");
            }
        }

        if (!regressions.isEmpty()) {
            fail(regressions.size() + " baseline cases regressed:\n"
                    + String.join("\n", regressions.subList(0, Math.min(40, regressions.size()))));
        }
    }

    private static Set<String> readBaseline() throws IOException {
        Path p = BASELINE_DIR.resolve("passing.txt");
        Set<String> out = new HashSet<>();
        if (Files.exists(p)) {
            for (String line : Files.readAllLines(p)) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    out.add(line.strip());
                }
            }
        }
        return out;
    }

    private static void writeBaseline(Set<String> passing, Map<String, int[]> byPair, int run) throws IOException {
        Files.createDirectories(BASELINE_DIR);
        List<String> lines = new ArrayList<>();
        lines.add("# Fixture cases known to pass (ids from SqlglotFixtureTest.Case#id). Regenerate with");
        lines.add("# mvn test -Djglot.fixtures.updateBaseline=true");
        lines.addAll(passing);
        Files.write(BASELINE_DIR.resolve("passing.txt"), lines);

        StringBuilder md = new StringBuilder();
        md.append("# sqlglot fixture conformance\n\n");
        md.append("Generated by `mvn test -Djglot.fixtures.updateBaseline=true`.\n\n");
        md.append(String.format("**%d / %d** cases passing.%n%n", passing.size(), run));
        md.append("| read -> write | passing | total |\n|---|---:|---:|\n");
        for (Map.Entry<String, int[]> e : byPair.entrySet()) {
            md.append(String.format("| %s | %d | %d |%n", e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        Files.writeString(BASELINE_DIR.resolve("REPORT.md"), md.toString());
    }

    /** Writes every failure, grouped by dialect pair, to target/fixture-failures.txt. */
    private static void writeFailures(Map<String, List<String>> failuresByPair) throws IOException {
        StringBuilder fails = new StringBuilder();
        for (Map.Entry<String, List<String>> e : failuresByPair.entrySet()) {
            fails.append("## ").append(e.getKey()).append("\n\n");
            for (String f : e.getValue()) {
                fails.append(f).append("\n\n");
            }
        }
        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("fixture-failures.txt"), fails.toString());
    }
}
