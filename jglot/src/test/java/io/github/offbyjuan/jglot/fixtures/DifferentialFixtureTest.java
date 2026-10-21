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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * Differential test against sqlglot over its own SQL corpus (identity.sql, pretty.sql, optimizer
 * fixtures including TPC-H/TPC-DS), see tools/fixtures/extract_differential_fixtures.py. Each
 * statement is parsed in its dialect and generated into every jglot dialect; for each target three
 * things are compared with sqlglot: the plain output, the pretty output, and whether strict
 * generation (unsupported level RAISE) fails.
 *
 * <p>Like {@link SqlglotFixtureTest}, passing checks are a ratchet in
 * {@code fixtures/baseline/differential-passing.txt}; update with
 * {@code -Djglot.fixtures.updateBaseline=true}.
 */
class DifferentialFixtureTest {
    private static final Path BASELINE = Path.of("src/test/resources/fixtures/baseline/differential-passing.txt");
    private static final GeneratorOptions PLAIN = GeneratorOptions.builder().unsupportedLevel(ErrorLevel.IGNORE).build();
    private static final GeneratorOptions PRETTY =
            GeneratorOptions.builder().unsupportedLevel(ErrorLevel.IGNORE).pretty(true).build();
    private static final GeneratorOptions STRICT = GeneratorOptions.builder().unsupportedLevel(ErrorLevel.RAISE).build();

    private static String id(String... parts) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\u0001", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 10);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void matchesSqlglotOnItsSqlCorpus() throws IOException {
        JsonNode root;
        try (InputStream in = new GZIPInputStream(
                DifferentialFixtureTest.class.getResourceAsStream("/fixtures/differential/cases.json.gz"))) {
            root = new ObjectMapper().readTree(in);
        }
        Set<String> baseline = new HashSet<>();
        if (Files.exists(BASELINE)) {
            for (String line : Files.readAllLines(BASELINE)) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    baseline.add(line.strip());
                }
            }
        }
        Set<String> passing = new TreeSet<>();
        Map<String, int[]> byCheck = new TreeMap<>();
        List<String> regressions = new ArrayList<>();
        StringBuilder failures = new StringBuilder();

        for (JsonNode c : root.get("cases")) {
            String read = c.get("read").asText();
            String sql = c.get("sql").asText();
            Dialect r = Dialects.find(read);
            Expression tree;
            String parseFailure = null;
            try {
                List<Expression> all = r.parse(sql);
                tree = all.isEmpty() ? null : all.get(0);
            } catch (Throwable t) {
                tree = null;
                parseFailure = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            Iterator<Map.Entry<String, JsonNode>> it = c.get("outputs").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> o = it.next();
                String write = o.getKey();
                Dialect w = Dialects.find(write);
                JsonNode exp = o.getValue();
                if (exp.has("generateError")) {
                    // sqlglot itself crashes generating this (e.g. a TypeError); no reference output
                    continue;
                }
                String pair = (read.isEmpty() ? "base" : read) + " -> " + (write.isEmpty() ? "base" : write);
                check(byCheck, passing, baseline, regressions, failures, pair + " plain", id(read, write, sql, "plain"),
                        sql, exp.get("sql"), tree, parseFailure, w, PLAIN);
                check(byCheck, passing, baseline, regressions, failures, pair + " pretty", id(read, write, sql, "pretty"),
                        sql, exp.get("pretty"), tree, parseFailure, w, PRETTY);
                JsonNode raises = exp.get("raises");
                if (raises != null && !raises.isNull()) {
                    String key = pair + " strict";
                    String cid = id(read, write, sql, "strict");
                    int[] counts = byCheck.computeIfAbsent(key, k -> new int[2]);
                    counts[1]++;
                    String failure;
                    if (tree == null) {
                        failure = parseFailure;
                    } else {
                        boolean raised;
                        try {
                            w.generate(tree, STRICT);
                            raised = false;
                        } catch (UnsupportedError e) {
                            raised = true;
                        } catch (Throwable t) {
                            raised = false;
                        }
                        failure = raised == raises.asBoolean() ? null
                                : "sqlglot raises=" + raises.asBoolean() + ", jglot raises=" + raised;
                    }
                    record(counts, passing, baseline, regressions, failures, key, cid, sql, failure);
                }
            }
        }

        int total = 0;
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, int[]> e : byCheck.entrySet()) {
            total += e.getValue()[1];
            summary.append(String.format("  %-38s %5d / %5d%n", e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        System.out.printf("differential corpus: %d/%d checks passing (baseline %d)%n%s", passing.size(), total,
                baseline.size(), summary);
        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("differential-failures.txt"), failures.toString());

        if (Boolean.getBoolean("jglot.fixtures.updateBaseline")) {
            List<String> lines = new ArrayList<>();
            lines.add("# Differential checks known to pass. Regenerate with mvn test -Djglot.fixtures.updateBaseline=true");
            lines.addAll(passing);
            Files.createDirectories(BASELINE.getParent());
            Files.write(BASELINE, lines);
        }
        if (!regressions.isEmpty()) {
            fail(regressions.size() + " baseline checks regressed:\n"
                    + String.join("\n", regressions.subList(0, Math.min(30, regressions.size()))));
        }
    }

    private static void check(Map<String, int[]> byCheck, Set<String> passing, Set<String> baseline,
            List<String> regressions, StringBuilder failures, String key, String cid, String sql, JsonNode expected,
            Expression tree, String parseFailure, Dialect w, GeneratorOptions opts) {
        int[] counts = byCheck.computeIfAbsent(key, k -> new int[2]);
        counts[1]++;
        String failure;
        if (tree == null) {
            failure = parseFailure == null ? "no statement parsed" : parseFailure;
        } else {
            String actual;
            try {
                actual = w.generate(tree, opts);
            } catch (Throwable t) {
                actual = null;
                if (expected != null && !expected.isNull()) {
                    record(counts, passing, baseline, regressions, failures, key, cid, sql,
                            t.getClass().getSimpleName() + ": " + t.getMessage());
                    return;
                }
            }
            String exp = expected == null || expected.isNull() ? null : expected.asText();
            failure = java.util.Objects.equals(exp, actual) ? null : "expected: " + exp + "\n  actual:   " + actual;
        }
        record(counts, passing, baseline, regressions, failures, key, cid, sql, failure);
    }

    private static void record(int[] counts, Set<String> passing, Set<String> baseline, List<String> regressions,
            StringBuilder failures, String key, String cid, String sql, String failure) {
        if (failure == null) {
            counts[0]++;
            passing.add(cid);
            return;
        }
        String line = "[" + key + "] " + sql + "\n  " + failure;
        failures.append(line).append("\n\n");
        if (baseline.contains(cid)) {
            regressions.add(line);
        }
    }
}
