package io.github.offbyjuan.jglot.tokens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.offbyjuan.jglot.dialects.data.TokenizerData;
import io.github.offbyjuan.jglot.errors.TokenError;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Checks that the tokenizer produces exactly sqlglot's tokens for every fixture input. */
class TokenizerConformanceTest {
    private static final String[] DIALECTS = {"base", "postgres", "presto", "trino", "bigquery", "clickhouse", "hive"};

    @TestFactory
    Stream<DynamicNode> tokensMatchSqlglot() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<DynamicNode> containers = new ArrayList<>();
        for (String name : DIALECTS) {
            JsonNode root;
            try (InputStream in = getClass().getResourceAsStream("/fixtures/tokens/" + name + ".json")) {
                root = mapper.readTree(in);
            }
            String key = root.get("dialect").asText();
            Tokenizer tokenizer = new Tokenizer(TokenizerData.forDialect(key));
            List<DynamicTest> tests = new ArrayList<>();
            int i = 0;
            for (JsonNode c : root.get("cases")) {
                String sql = c.get("sql").asText();
                String label = "#" + (i++) + " " + (sql.length() > 80 ? sql.substring(0, 80) + "..." : sql);
                if (c.has("error")) {
                    tests.add(DynamicTest.dynamicTest(label, () -> assertThrows(TokenError.class, () -> tokenizer.tokenize(sql))));
                    continue;
                }
                List<String> expected = new ArrayList<>();
                for (JsonNode t : c.get("tokens")) {
                    List<String> parts = new ArrayList<>();
                    t.forEach(p -> parts.add(p.asText()));
                    expected.add(String.join(" | ", parts));
                }
                tests.add(DynamicTest.dynamicTest(label, () -> {
                    List<String> actual = new ArrayList<>();
                    for (Token t : tokenizer.tokenize(sql)) {
                        List<String> parts = new ArrayList<>();
                        parts.add(t.type().name());
                        parts.add(t.text());
                        parts.addAll(t.comments());
                        actual.add(String.join(" | ", parts));
                    }
                    assertEquals(String.join("\n", expected), String.join("\n", actual), sql);
                }));
            }
            containers.add(DynamicContainer.dynamicContainer(name, tests));
        }
        return containers.stream();
    }
}
