package io.github.offbyjuan.jglot.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable-after-construction trie over sequences of string atoms (single characters for time
 * formats, whole words for multi-word keyword options). Equivalent to sqlglot's {@code new_trie} /
 * {@code in_trie}.
 */
public final class Trie {
    public enum Result {
        FAILED,
        PREFIX,
        EXISTS
    }

    private final Map<String, Trie> children = new HashMap<>();
    private boolean terminal;

    public static Trie ofStrings(Iterable<String> keys) {
        Trie t = new Trie();
        for (String k : keys) {
            Trie cur = t;
            for (int i = 0; i < k.length(); i++) {
                cur = cur.children.computeIfAbsent(String.valueOf(k.charAt(i)), x -> new Trie());
            }
            cur.terminal = true;
        }
        return t;
    }

    public static Trie ofWords(Iterable<? extends List<String>> keys) {
        Trie t = new Trie();
        for (List<String> k : keys) {
            Trie cur = t;
            for (String w : k) {
                cur = cur.children.computeIfAbsent(w, x -> new Trie());
            }
            cur.terminal = true;
        }
        return t;
    }

    public Trie child(String atom) {
        return children.get(atom);
    }

    public Trie child(char atom) {
        return children.get(String.valueOf(atom));
    }

    public boolean isTerminal() {
        return terminal;
    }

    public boolean isEmpty() {
        return children.isEmpty() && !terminal;
    }

    /** sqlglot's {@code in_trie} for a single atom step: FAILED, PREFIX or EXISTS plus the subtrie. */
    public Step step(String atom) {
        Trie next = children.get(atom);
        if (next == null) {
            return new Step(Result.FAILED, this);
        }
        return new Step(next.terminal ? Result.EXISTS : Result.PREFIX, next);
    }

    /** sqlglot's {@code in_trie} for a whole key of character atoms. */
    public Step inTrie(String key) {
        if (key.isEmpty()) {
            return new Step(Result.FAILED, this);
        }
        Trie cur = this;
        for (int i = 0; i < key.length(); i++) {
            Trie next = cur.children.get(String.valueOf(key.charAt(i)));
            if (next == null) {
                return new Step(Result.FAILED, cur);
            }
            cur = next;
        }
        return new Step(cur.terminal ? Result.EXISTS : Result.PREFIX, cur);
    }

    /** sqlglot's {@code in_trie} for a key of word atoms. */
    public Step inTrie(List<String> key) {
        if (key.isEmpty()) {
            return new Step(Result.FAILED, this);
        }
        Trie cur = this;
        for (String w : key) {
            Trie next = cur.children.get(w);
            if (next == null) {
                return new Step(Result.FAILED, cur);
            }
            cur = next;
        }
        return new Step(cur.terminal ? Result.EXISTS : Result.PREFIX, cur);
    }

    public record Step(Result result, Trie trie) {}
}
