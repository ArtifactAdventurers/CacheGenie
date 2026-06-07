package dev.gruff.hardstop.cachegenie.viewer;

import java.util.Collection;
import java.util.Map;

/**
 * Tiny, dependency-free JSON writer.
 *
 * <p>The viewer only ever serialises simple values it builds itself
 * ({@link Map}s, {@link Collection}s, {@link String}s, {@link Number}s,
 * {@link Boolean}s and {@code null}), so a full JSON library would be overkill.
 * This keeps the viewer module free of any third-party dependency beyond the
 * JDK and {@code cachegenie-core}.</p>
 */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> writeString(s, sb);
            case Boolean b -> sb.append(b.booleanValue());
            case Number n -> sb.append(n);
            case Map<?, ?> map -> writeObject(map, sb);
            case Collection<?> col -> writeArray(col, sb);
            default -> writeString(String.valueOf(value), sb);
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(String.valueOf(e.getKey()), sb);
            sb.append(':');
            write(e.getValue(), sb);
        }
        sb.append('}');
    }

    private static void writeArray(Collection<?> col, StringBuilder sb) {
        sb.append('[');
        boolean first = true;
        for (Object item : col) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            write(item, sb);
        }
        sb.append(']');
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
