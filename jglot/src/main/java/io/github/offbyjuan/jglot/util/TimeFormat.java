package io.github.offbyjuan.jglot.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Port of sqlglot's {@code time.format_time}: maps time-format tokens between dialects. */
public final class TimeFormat {
    private TimeFormat() {}

    /**
     * Converts {@code string} token by token using {@code mapping}, preferring the longest match.
     * Returns null for a null or empty input.
     */
    public static String formatTime(String string, Map<String, String> mapping, Trie trie) {
        if (string == null || string.isEmpty()) {
            return null;
        }
        if (trie == null) {
            trie = Trie.ofStrings(mapping.keySet());
        }
        int start = 0;
        int end = 1;
        int size = string.length();
        Trie current = trie;
        List<String> chunks = new ArrayList<>();
        String sym = null;

        while (end <= size) {
            String chars = string.substring(start, end);
            Trie.Step step = current.step(String.valueOf(chars.charAt(chars.length() - 1)));
            Trie.Result result = step.result();
            current = step.trie();

            if (result == Trie.Result.FAILED) {
                if (sym != null) {
                    end -= 1;
                    chars = sym;
                    sym = null;
                } else {
                    chars = chars.substring(0, 1);
                    end = start + 1;
                }
                start += chars.length();
                chunks.add(chars);
                current = trie;
            } else if (result == Trie.Result.EXISTS) {
                sym = chars;
            }

            end += 1;

            if (result != Trie.Result.FAILED && end > size) {
                chunks.add(chars);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String c : chunks) {
            sb.append(mapping.getOrDefault(c, c));
        }
        return sb.toString();
    }
}
