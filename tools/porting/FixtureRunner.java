import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.errors.ErrorLevel;
import io.github.offbyjuan.jglot.errors.UnsupportedError;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs the sqlglot fixture cases that involve the given dialects (as read or write dialect) and
 * prints pass counts per dialect pair; failures go to the given output file. Used while porting a
 * dialect in isolation (see tools/porting/test_dialect.sh).
 *
 *   java -cp ... FixtureRunner.java <fixtures-dir> <failures-out> <dialect> [<dialect>...]
 */
public class FixtureRunner {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Path out = Path.of(args[1]);
        List<String> focus = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            focus.add(args[i].equals("base") ? "" : args[i]);
        }
        ObjectMapper mapper = new ObjectMapper();
        Map<String, int[]> byPair = new TreeMap<>();
        StringBuilder failures = new StringBuilder();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        File[] files = dir.toFile().listFiles((d, n) -> n.endsWith(".json"));
        java.util.Arrays.sort(files);
        for (File f : files) {
            for (JsonNode c : mapper.readTree(f).get("cases")) {
                String read = c.get("read").asText();
                String write = c.get("write").asText();
                if (!focus.contains(read) && !focus.contains(write)) {
                    continue;
                }
                Dialect r = Dialects.find(read);
                Dialect w = Dialects.find(write);
                if (r == null || w == null) {
                    continue;
                }
                String sql = c.get("sql").asText();
                String expected = c.has("expected") ? c.get("expected").asText() : null;
                boolean pretty = c.path("pretty").asBoolean(false);
                boolean identify = c.path("identify").asBoolean(false);
                if (!seen.add(read + "\u0001" + write + "\u0001" + sql + "\u0001" + expected + pretty + identify)) {
                    continue;
                }
                String pair = (read.isEmpty() ? "base" : read) + " -> " + (write.isEmpty() ? "base" : write);
                int[] counts = byPair.computeIfAbsent(pair, k -> new int[2]);
                counts[1]++;
                String failure;
                try {
                    List<Expression> parsed = r.parse(sql);
                    Expression e = parsed.isEmpty() ? null : parsed.get(0);
                    if (c.has("expectedError")) {
                        try {
                            w.generate(e, GeneratorOptions.builder().unsupportedLevel(ErrorLevel.RAISE).build());
                            failure = "expected UnsupportedError";
                        } catch (UnsupportedError ok) {
                            failure = null;
                        }
                    } else {
                        String actual = w.generate(e, GeneratorOptions.builder().unsupportedLevel(ErrorLevel.IGNORE)
                                .pretty(pretty).identify(identify).build());
                        failure = actual.equals(expected) ? null : "expected: " + expected + "\n  actual:   " + actual;
                    }
                } catch (Throwable t) {
                    StackTraceElement[] st = t.getStackTrace();
                    String where = st.length > 0 ? " @ " + st[0] : "";
                    failure = t.getClass().getSimpleName() + ": " + t.getMessage() + where;
                }
                if (failure == null) {
                    counts[0]++;
                } else {
                    failures.append("[").append(pair).append("] [").append(f.getName()).append(" ")
                            .append(c.path("test").asText()).append("] ").append(sql).append("\n  ")
                            .append(failure).append("\n\n");
                }
            }
        }
        int pass = 0;
        int total = 0;
        for (Map.Entry<String, int[]> e : byPair.entrySet()) {
            System.out.printf("  %-28s %5d / %5d%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
            pass += e.getValue()[0];
            total += e.getValue()[1];
        }
        System.out.printf("TOTAL %d / %d%n", pass, total);
        Files.writeString(out, failures.toString());
        System.out.println("failures written to " + out);
    }
}
