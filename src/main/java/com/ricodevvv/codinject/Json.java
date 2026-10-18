package com.ricodevvv.codinject;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON to read Gradle module metadata: objects become {@link Map}, arrays become {@link List},
 * numbers become {@link BigDecimal}.
 */
final class Json {

    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) throws IOException {
        Json json = new Json(text);
        Object value = json.value();
        json.skipWhitespace();
        if (json.index != text.length()) {
            throw json.error("Unexpected content after the JSON value");
        }
        return value;
    }

    private Object value() throws IOException {
        skipWhitespace();
        if (index >= text.length()) {
            throw error("Unexpected end of JSON");
        }
        char c = text.charAt(index);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || Character.isDigit(c)) {
                    return number();
                }
                throw error("Unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object() throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        index++;
        skipWhitespace();
        if (peek() == '}') {
            index++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("Expected a key");
            }
            String key = string();
            skipWhitespace();
            expect(':');
            map.put(key, value());
            skipWhitespace();
            char next = next();
            if (next == '}') {
                return map;
            }
            if (next != ',') {
                throw error("Expected ',' or '}'");
            }
        }
    }

    private List<Object> array() throws IOException {
        List<Object> list = new ArrayList<>();
        index++;
        skipWhitespace();
        if (peek() == ']') {
            index++;
            return list;
        }
        while (true) {
            list.add(value());
            skipWhitespace();
            char next = next();
            if (next == ']') {
                return list;
            }
            if (next != ',') {
                throw error("Expected ',' or ']'");
            }
        }
    }

    private String string() throws IOException {
        index++;
        StringBuilder builder = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return builder.toString();
            }
            if (c != '\\') {
                builder.append(c);
                continue;
            }
            char escaped = next();
            switch (escaped) {
                case 'b':
                    builder.append('\b');
                    break;
                case 'f':
                    builder.append('\f');
                    break;
                case 'n':
                    builder.append('\n');
                    break;
                case 'r':
                    builder.append('\r');
                    break;
                case 't':
                    builder.append('\t');
                    break;
                case 'u':
                    if (index + 4 > text.length()) {
                        throw error("Broken unicode escape");
                    }
                    try {
                        builder.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                    } catch (NumberFormatException e) {
                        throw error("Broken unicode escape");
                    }
                    index += 4;
                    break;
                default:
                    builder.append(escaped);
            }
        }
    }

    private BigDecimal number() throws IOException {
        int start = index;
        while (index < text.length() && "+-0123456789.eE".indexOf(text.charAt(index)) >= 0) {
            index++;
        }
        try {
            return new BigDecimal(text.substring(start, index));
        } catch (NumberFormatException e) {
            throw error("Broken number");
        }
    }

    private Object literal(String word, Object value) throws IOException {
        if (!text.startsWith(word, index)) {
            throw error("Expected " + word);
        }
        index += word.length();
        return value;
    }

    private void expect(char expected) throws IOException {
        if (next() != expected) {
            throw error("Expected '" + expected + "'");
        }
    }

    private char peek() throws IOException {
        if (index >= text.length()) {
            throw error("Unexpected end of JSON");
        }
        return text.charAt(index);
    }

    private char next() throws IOException {
        char c = peek();
        index++;
        return c;
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }

    private IOException error(String message) {
        return new IOException(message + " at position " + index);
    }
}
