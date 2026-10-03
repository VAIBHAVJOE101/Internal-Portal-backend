package com.platform.portal.common;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class Strings {

    private Strings() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 3) + "...";
    }

    /** Converts a free-form label into a lowercase slug ("Kafka Instances" -> "kafka-instances"). */
    public static String slugify(String input) {
        String slug = input.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "item" : slug;
    }

    /** Converts a label into a camelCase key ("Expiry Date" -> "expiryDate"). */
    public static String keyify(String input) {
        String[] parts = input.trim().replaceAll("[^A-Za-z0-9]+", " ").trim().split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.isEmpty()) {
                // acronyms such as "VIP" or "IP" become "vip" / "ip", not "vIP"
                sb.append(p.equals(p.toUpperCase()) ? p.toLowerCase() : p.substring(0, 1).toLowerCase() + p.substring(1));
            } else {
                sb.append(p.substring(0, 1).toUpperCase()).append(p.substring(1));
            }
        }
        String key = sb.toString();
        if (key.isEmpty()) {
            return "field";
        }
        return Character.isDigit(key.charAt(0)) ? "f" + key : key;
    }

    /** Reads a value that may be a JSON list or a comma/newline separated string into a list of trimmed strings. */
    public static List<String> asList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> c) {
            return c.stream().filter(v -> v != null).map(v -> v.toString().trim()).filter(v -> !v.isEmpty()).toList();
        }
        return Arrays.stream(value.toString().split("[,\\n]")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    public static String str(Map<String, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }
}
