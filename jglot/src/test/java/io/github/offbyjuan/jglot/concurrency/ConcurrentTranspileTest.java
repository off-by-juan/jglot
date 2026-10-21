package io.github.offbyjuan.jglot.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.offbyjuan.jglot.JGlot;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Many threads translating different queries between different dialects at the same time must get
 * exactly the results a single thread gets: dialects and their tables are shared and immutable,
 * and all per-call state lives in the per-call tokenizer/parser/generator.
 */
class ConcurrentTranspileTest {
    private static final String[][] CASES = {
        {"SELECT a, b FROM t WHERE c = 1", "postgres", "trino"},
        {"SELECT CAST(x AS INT) AS y FROM t ORDER BY y DESC LIMIT 10", "trino", "postgres"},
        {"SELECT COALESCE(a, b), LOWER(c) FROM t AS t1 JOIN u ON t1.id = u.id", "bigquery", "clickhouse"},
        {"WITH c AS (SELECT 1 AS x) SELECT x FROM c UNION ALL SELECT 2", "hive", "postgres"},
        {"SELECT COUNT(DISTINCT a) FROM t GROUP BY b HAVING COUNT(*) > 1", "starburst", "bigquery"},
        {"SELECT CASE WHEN a > 1 THEN 'x' ELSE 'y' END FROM t", "clickhouse", "hive"},
    };

    @Test
    void concurrentTranspilationMatchesSequential() throws Exception {
        List<String> expected = new ArrayList<>();
        for (String[] c : CASES) {
            expected.add(JGlot.transpileOne(c[0], c[1], c[2]));
        }

        int threads = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
        int iterations = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                final int offset = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    int checked = 0;
                    for (int i = 0; i < iterations; i++) {
                        int k = (i + offset) % CASES.length;
                        String[] c = CASES[k];
                        String actual = JGlot.transpileOne(c[0], c[1], c[2]);
                        assertEquals(expected.get(k), actual, "thread " + offset + " case " + k);
                        checked++;
                    }
                    return checked;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> f : futures) {
                total += f.get(120, TimeUnit.SECONDS);
            }
            assertEquals(threads * iterations, total);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }
}
