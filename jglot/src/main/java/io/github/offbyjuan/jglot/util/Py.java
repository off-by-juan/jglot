package io.github.offbyjuan.jglot.util;

import io.github.offbyjuan.jglot.expressions.Expression;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Small helpers with Python semantics, used where ported sqlglot code relies on them (sqlglot's
 * {@code helper} module, slicing, truthiness, set algebra).
 */
public final class Py {
    private Py() {}

    /** sqlglot's {@code seq_get}: the item at {@code index} (negative counts from the end), or null. */
    public static <T> T seqGet(List<T> seq, int index) {
        if (seq == null) {
            return null;
        }
        int size = seq.size();
        int i = index < 0 ? size + index : index;
        return i >= 0 && i < size ? seq.get(i) : null;
    }

    /** sqlglot's {@code ensure_list}: null becomes empty, a list is copied, anything else is wrapped. */
    @SuppressWarnings("unchecked")
    public static <T> List<T> ensureList(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (value instanceof Collection<?> c) {
            return new ArrayList<>((Collection<T>) c);
        }
        List<T> out = new ArrayList<>();
        out.add((T) value);
        return out;
    }

    /** Python truthiness. */
    public static boolean truthy(Object v) {
        return Expression.truthy(v);
    }

    private static int clampIndex(Integer i, int size, int dflt) {
        if (i == null) {
            return dflt;
        }
        int x = i < 0 ? size + i : i;
        return Math.max(0, Math.min(size, x));
    }

    /** Python {@code s[start:end]} with negative indices and clamping; null means omitted. */
    public static String slice(String s, Integer start, Integer end) {
        int n = s.length();
        int a = clampIndex(start, n, 0);
        int b = clampIndex(end, n, n);
        return a < b ? s.substring(a, b) : "";
    }

    /** Python {@code s[start:]}. */
    public static String slice(String s, int start) {
        return slice(s, start, null);
    }

    /** Python {@code list[start:end]} (a new list). */
    public static <T> List<T> slice(List<T> l, Integer start, Integer end) {
        int n = l.size();
        int a = clampIndex(start, n, 0);
        int b = clampIndex(end, n, n);
        return a < b ? new ArrayList<>(l.subList(a, b)) : new ArrayList<>();
    }

    public static <T> List<T> slice(List<T> l, int start) {
        return slice(l, start, null);
    }

    /** Python {@code str.split()} with no arguments: splits on runs of whitespace, dropping empties. */
    public static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.strip().split("\\s+")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    public static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** Python {@code str.rstrip()}. */
    public static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** Python {@code str.lstrip()}. */
    public static String lstrip(String s) {
        int start = 0;
        while (start < s.length() && Character.isWhitespace(s.charAt(start))) {
            start++;
        }
        return s.substring(start);
    }

    /** Python {@code str.strip(chars)}. */
    public static String strip(String s, String chars) {
        int a = 0;
        int b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(a, b);
    }

    /** sqlglot's {@code is_int}: whether {@code text} parses as a Python int. */
    public static boolean isInt(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip().replace("_", "");
        if (t.isEmpty()) {
            return false;
        }
        int i = t.charAt(0) == '+' || t.charAt(0) == '-' ? 1 : 0;
        if (i == t.length()) {
            return false;
        }
        for (; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** sqlglot's {@code is_float}: whether {@code text} parses as a Python float. */
    public static boolean isFloat(String text) {
        if (text == null) {
            return false;
        }
        try {
            Double.parseDouble(text.strip());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** sqlglot's {@code csv(*args, sep=", ")}: joins the non-empty strings. */
    public static String csv(String sep, String... args) {
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (a != null && !a.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(sep);
                }
                sb.append(a);
            }
        }
        return sb.toString();
    }

    public static String csv(String... args) {
        return csv(", ", args);
    }

    /** Python {@code a | b} for sets. */
    @SafeVarargs
    public static <T> Set<T> union(Set<T> a, T... extra) {
        Set<T> out = copyOf(a);
        for (T x : extra) {
            out.add(x);
        }
        return out;
    }

    public static <T> Set<T> union(Set<T> a, Collection<T> b) {
        Set<T> out = copyOf(a);
        out.addAll(b);
        return out;
    }

    /** Python {@code a - b} for sets. */
    @SafeVarargs
    public static <T> Set<T> minus(Set<T> a, T... remove) {
        Set<T> out = copyOf(a);
        for (T x : remove) {
            out.remove(x);
        }
        return out;
    }

    public static <T> Set<T> minus(Set<T> a, Collection<T> b) {
        Set<T> out = copyOf(a);
        out.removeAll(b);
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Set<T> copyOf(Set<T> a) {
        if (!a.isEmpty() && a.iterator().next() instanceof Enum<?>) {
            return (Set<T>) EnumSet.copyOf((Set) a);
        }
        return new HashSet<>(a);
    }

    /** Python {@code x in (a, b, ...)} using equals. */
    @SafeVarargs
    public static <T> boolean in(T x, T... options) {
        for (T o : options) {
            if (x == null ? o == null : x.equals(o)) {
                return true;
            }
        }
        return false;
    }

    /** sqlglot's {@code to_bool}: "true"/"1" -> true, "false"/"0" -> false, other values unchanged. */
    public static Object toBool(Object value) {
        if (value instanceof String s) {
            String u = s.toUpperCase(Locale.ROOT);
            if (u.equals("TRUE") || u.equals("1")) {
                return Boolean.TRUE;
            }
            if (u.equals("FALSE") || u.equals("0")) {
                return Boolean.FALSE;
            }
        }
        return value;
    }
}
