package io.github.offbyjuan.jglot.expressions;

import java.util.HashMap;
import java.util.Map;

/**
 * An interned argument key, such as {@code this} or {@code expressions}. Every key that sqlglot uses
 * has a constant in {@link Args}; instances compare by identity.
 */
public final class Arg {
    private static final Map<String, Arg> BY_NAME = new HashMap<>();

    private final String name;
    private final int id;

    private Arg(String name, int id) {
        this.name = name;
        this.id = id;
    }

    static Arg register(String name, int id) {
        Arg arg = new Arg(name, id);
        BY_NAME.put(name, arg);
        return arg;
    }

    /** Looks up an arg key by its sqlglot name, or returns null if there is none. */
    public static Arg forName(String name) {
        // Touching Args guarantees (via class-initialization locking) that every key is registered
        // and visible to this thread before the map is read.
        Args.THIS.id();
        return BY_NAME.get(name);
    }

    public String name() {
        return name;
    }

    public int id() {
        return id;
    }

    @Override
    public String toString() {
        return name;
    }
}
