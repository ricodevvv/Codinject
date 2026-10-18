package com.ricodevvv.codinject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Compares Maven versions and checks Maven ranges, close enough to what Maven does for real world versions.
 */
final class Versions {

    private static final int UNKNOWN_QUALIFIER = 7;

    private Versions() {
    }

    static boolean isRange(String version) {
        return version.startsWith("[") || version.startsWith("(");
    }

    static boolean isDynamic(String version) {
        return "latest".equalsIgnoreCase(version) || "release".equalsIgnoreCase(version);
    }

    /**
     * Picks the highest version that fits the range.
     *
     * @param range     a Maven range like {@code [1.0,2.0)}
     * @param available versions published in the repository
     * @return the best match or {@code null} if nothing fits
     */
    static String pick(String range, Collection<String> available) {
        String best = null;
        for (String version : available) {
            if (inRange(range, version) && (best == null || compare(version, best) > 0)) {
                best = version;
            }
        }
        return best;
    }

    /**
     * Version to use for a range when the repository has no metadata to pick from.
     *
     * @param range a Maven range
     * @return the inclusive lower bound, or {@code null} if there isn't one
     */
    static String lowerBound(String range) {
        if (!range.startsWith("[")) {
            return null;
        }
        int end = range.indexOf(',');
        int close = range.indexOf(']');
        String bound = range.substring(1, end >= 0 ? end : close >= 0 ? close : range.length()).trim();
        return bound.isEmpty() ? null : bound;
    }

    static boolean inRange(String range, String version) {
        int index = 0;
        while (index < range.length()) {
            char open = range.charAt(index);
            if (open == ',' || Character.isWhitespace(open)) {
                index++;
                continue;
            }
            int close = index + 1;
            while (close < range.length() && range.charAt(close) != ']' && range.charAt(close) != ')') {
                close++;
            }
            if (close >= range.length()) {
                return false;
            }
            if (inSet(range.substring(index + 1, close), open == '[', range.charAt(close) == ']', version)) {
                return true;
            }
            index = close + 1;
        }
        return false;
    }

    private static boolean inSet(String set, boolean lowerInclusive, boolean upperInclusive, String version) {
        int comma = set.indexOf(',');
        if (comma < 0) {
            return compare(set.trim(), version) == 0;
        }
        String lower = set.substring(0, comma).trim();
        String upper = set.substring(comma + 1).trim();
        if (!lower.isEmpty()) {
            int result = compare(version, lower);
            if (result < 0 || (result == 0 && !lowerInclusive)) {
                return false;
            }
        }
        if (!upper.isEmpty()) {
            int result = compare(version, upper);
            return result < 0 || (result == 0 && upperInclusive);
        }
        return true;
    }

    static int compare(String a, String b) {
        List<Object> left = tokens(a);
        List<Object> right = tokens(b);
        int size = Math.max(left.size(), right.size());
        for (int i = 0; i < size; i++) {
            int result = compareTokens(i < left.size() ? left.get(i) : null, i < right.size() ? right.get(i) : null);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static List<Object> tokens(String version) {
        List<Object> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char c : version.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '.' || c == '-' || c == '_') {
                flush(current, tokens);
            } else {
                if (current.length() > 0 && Character.isDigit(c) != Character.isDigit(current.charAt(current.length() - 1))) {
                    flush(current, tokens);
                }
                current.append(c);
            }
        }
        flush(current, tokens);
        return tokens;
    }

    private static void flush(StringBuilder current, List<Object> tokens) {
        if (current.length() == 0) {
            return;
        }
        String token = current.toString();
        tokens.add(Character.isDigit(token.charAt(0)) ? new BigInteger(token) : token);
        current.setLength(0);
    }

    private static int compareTokens(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -compareTokens(b, null);
        }
        if (a instanceof BigInteger) {
            if (b == null) {
                return ((BigInteger) a).signum();
            }
            return b instanceof BigInteger ? ((BigInteger) a).compareTo((BigInteger) b) : 1;
        }
        if (b instanceof BigInteger) {
            return -1;
        }
        String left = (String) a;
        String right = b == null ? "" : (String) b;
        int result = Integer.compare(rank(left), rank(right));
        if (result != 0) {
            return result;
        }
        return rank(left) == UNKNOWN_QUALIFIER ? left.compareTo(right) : 0;
    }

    private static int rank(String qualifier) {
        switch (qualifier) {
            case "alpha":
            case "a":
                return 0;
            case "beta":
            case "b":
                return 1;
            case "milestone":
            case "m":
                return 2;
            case "rc":
            case "cr":
                return 3;
            case "snapshot":
                return 4;
            case "":
            case "ga":
            case "final":
            case "release":
                return 5;
            case "sp":
                return 6;
            default:
                return UNKNOWN_QUALIFIER;
        }
    }
}
