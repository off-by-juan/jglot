package io.github.offbyjuan.jglot.bench;

import io.github.offbyjuan.jglot.dialects.Dialect;
import io.github.offbyjuan.jglot.dialects.Dialects;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Tokenize / parse / generate / end-to-end transpile throughput for representative queries.
 *
 * <pre>
 *   mvn -q package -DskipTests
 *   java -jar jglot-benchmarks/target/benchmarks.jar -prof gc
 * </pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class TranspileBenchmark {
    public static final String SHORT = "SELECT a, b FROM t WHERE c = 1";

    public static final String TPCH_Q1 = """
            SELECT l_returnflag, l_linestatus, SUM(l_quantity) AS sum_qty, SUM(l_extendedprice) AS sum_base_price,
              SUM(l_extendedprice * (1 - l_discount)) AS sum_disc_price,
              SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge, AVG(l_quantity) AS avg_qty,
              AVG(l_extendedprice) AS avg_price, AVG(l_discount) AS avg_disc, COUNT(*) AS count_order
            FROM lineitem
            WHERE l_shipdate <= CAST('1998-12-01' AS DATE) - INTERVAL '90' DAY
            GROUP BY l_returnflag, l_linestatus
            ORDER BY l_returnflag, l_linestatus""";

    public static final String ANALYTIC = """
            WITH recent AS (
              SELECT user_id, event_type, ts, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY ts DESC) AS rn
              FROM events WHERE ts > CURRENT_TIMESTAMP - INTERVAL '7' DAY AND event_type IN ('click', 'view')
            )
            SELECT r.user_id, COALESCE(u.name, 'unknown') AS name, COUNT(*) AS n,
              CASE WHEN COUNT(*) > 10 THEN 'heavy' ELSE 'light' END AS bucket,
              LOWER(TRIM(u.email)) AS email, SUBSTRING(u.country, 1, 2) AS cc
            FROM recent AS r LEFT JOIN users AS u ON r.user_id = u.id
            WHERE r.rn <= 100
            GROUP BY r.user_id, u.name, u.email, u.country
            HAVING COUNT(*) > 1
            ORDER BY n DESC
            LIMIT 50""";

    @Param({"SHORT", "TPCH_Q1", "ANALYTIC"})
    public String query;

    @Param({"postgres->trino", "trino->postgres", "bigquery->clickhouse", "hive->postgres"})
    public String pair;

    private String sql;
    private Dialect read;
    private Dialect write;
    private Expression parsed;

    @Setup
    public void setup() {
        sql = switch (query) {
            case "SHORT" -> SHORT;
            case "TPCH_Q1" -> TPCH_Q1;
            default -> ANALYTIC;
        };
        String[] p = pair.split("->");
        read = Dialects.get(p[0]);
        write = Dialects.get(p[1]);
        parsed = read.parse(sql).get(0);
    }

    @Benchmark
    public void tokenize(Blackhole bh) {
        bh.consume(read.tokenize(sql));
    }

    @Benchmark
    public void parse(Blackhole bh) {
        bh.consume(read.parse(sql));
    }

    @Benchmark
    public void generate(Blackhole bh) {
        bh.consume(write.generate(parsed, GeneratorOptions.DEFAULT));
    }

    @Benchmark
    public void transpile(Blackhole bh) {
        List<Expression> trees = read.parse(sql);
        for (Expression e : trees) {
            bh.consume(write.newGenerator(GeneratorOptions.DEFAULT).generate(e, false));
        }
    }

    @Benchmark
    @Threads(8)
    public void transpileConcurrent(Blackhole bh) {
        transpile(bh);
    }
}
