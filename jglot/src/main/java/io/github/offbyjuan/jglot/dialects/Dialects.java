package io.github.offbyjuan.jglot.dialects;

import io.github.offbyjuan.jglot.dialects.bigquery.BigQuery;
import io.github.offbyjuan.jglot.dialects.clickhouse.ClickHouse;
import io.github.offbyjuan.jglot.dialects.hive.Hive;
import io.github.offbyjuan.jglot.dialects.postgres.Postgres;
import io.github.offbyjuan.jglot.dialects.presto.Presto;
import io.github.offbyjuan.jglot.dialects.starburst.Starburst;
import io.github.offbyjuan.jglot.dialects.trino.Trino;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Registry of the built-in dialects. Each dialect is created lazily on first use and then shared;
 * dialects are immutable, so the shared instances are safe to use from any thread.
 */
public final class Dialects {
    private Dialects() {}

    /** Names accepted by {@link #get(String)}. The empty string is sqlglot's generic base dialect. */
    public static final List<String> NAMES =
            List.of("", "bigquery", "clickhouse", "hive", "postgres", "presto", "starburst", "trino");

    private static final class Base {
        static final Dialect INSTANCE = new Dialect();
    }

    private static final class BigQueryHolder {
        static final Dialect INSTANCE = new BigQuery();
    }

    private static final class ClickHouseHolder {
        static final Dialect INSTANCE = new ClickHouse();
    }

    private static final class HiveHolder {
        static final Dialect INSTANCE = new Hive();
    }

    private static final class PostgresHolder {
        static final Dialect INSTANCE = new Postgres();
    }

    private static final class PrestoHolder {
        static final Dialect INSTANCE = new Presto();
    }

    private static final class StarburstHolder {
        static final Dialect INSTANCE = new Starburst();
    }

    private static final class TrinoHolder {
        static final Dialect INSTANCE = new Trino();
    }

    /** The dialect for {@code name} (case-insensitive), or throws if it isn't supported. */
    public static Dialect get(String name) {
        Dialect d = find(name);
        if (d == null) {
            throw new IllegalArgumentException("Unknown dialect '" + name + "'. Supported: " + NAMES);
        }
        return d;
    }

    /** The dialect for {@code name}, or null if it isn't supported. */
    public static Dialect find(String name) {
        String key = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        Supplier<Dialect> s = switch (key) {
            case "", "sqlglot" -> () -> Base.INSTANCE;
            case "bigquery" -> () -> BigQueryHolder.INSTANCE;
            case "clickhouse" -> () -> ClickHouseHolder.INSTANCE;
            case "hive" -> () -> HiveHolder.INSTANCE;
            case "postgres", "postgresql" -> () -> PostgresHolder.INSTANCE;
            case "presto" -> () -> PrestoHolder.INSTANCE;
            case "starburst" -> () -> StarburstHolder.INSTANCE;
            case "trino" -> () -> TrinoHolder.INSTANCE;
            default -> null;
        };
        return s == null ? null : s.get();
    }

    public static Dialect base() {
        return Base.INSTANCE;
    }
}
