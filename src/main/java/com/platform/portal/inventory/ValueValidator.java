package com.platform.portal.inventory;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.platform.portal.common.Strings;

/** Validates and normalises a single cell value according to its column definition. */
final class ValueValidator {

    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)(/(3[0-2]|[12]?\\d))?$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]{2,39}(/\\d{1,3})?$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private ValueValidator() {
    }

    /** Returns the normalised value, or throws {@link IllegalArgumentException} with a user-facing message. */
    static Object normalize(InventoryColumn column, Object raw, Map<String, Object> options) {
        if (raw == null || (raw instanceof String s && s.isBlank()) || (raw instanceof Collection<?> c && c.isEmpty())) {
            if (column.isRequired()) {
                throw new IllegalArgumentException(column.getLabel() + " is required");
            }
            return null;
        }
        String label = column.getLabel();
        return switch (column.getType()) {
            case TEXT, LONGTEXT -> raw.toString().trim();
            case NUMBER -> {
                try {
                    BigDecimal n = new BigDecimal(raw.toString().trim());
                    yield n.scale() <= 0 ? (Object) n.longValueExact() : n.doubleValue();
                } catch (NumberFormatException | ArithmeticException e) {
                    throw new IllegalArgumentException(label + " must be a number");
                }
            }
            case BOOLEAN -> {
                if (raw instanceof Boolean b) yield b;
                String s = raw.toString().trim().toLowerCase();
                if (List.of("true", "yes", "y", "1").contains(s)) yield true;
                if (List.of("false", "no", "n", "0").contains(s)) yield false;
                throw new IllegalArgumentException(label + " must be true or false");
            }
            case DATE -> {
                String s = raw.toString().trim();
                try {
                    yield LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s).toString();
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(label + " must be a date (YYYY-MM-DD)");
                }
            }
            case DATETIME -> {
                String s = raw.toString().trim();
                try {
                    yield Instant.parse(s).toString();
                } catch (DateTimeParseException e) {
                    try {
                        yield OffsetDateTime.parse(s).toInstant().toString();
                    } catch (DateTimeParseException e2) {
                        throw new IllegalArgumentException(label + " must be an ISO date-time");
                    }
                }
            }
            case SELECT -> {
                String s = raw.toString().trim();
                List<String> choices = choices(options);
                if (!choices.isEmpty() && !choices.contains(s)) {
                    throw new IllegalArgumentException(label + " must be one of " + choices);
                }
                yield s;
            }
            case MULTISELECT -> {
                List<String> values = Strings.asList(raw);
                List<String> choices = choices(options);
                if (!choices.isEmpty()) {
                    for (String v : values) {
                        if (!choices.contains(v)) {
                            throw new IllegalArgumentException(label + ": '" + v + "' is not an allowed option");
                        }
                    }
                }
                yield values;
            }
            case LIST -> Strings.asList(raw);
            case URL -> {
                String s = raw.toString().trim();
                try {
                    URI uri = URI.create(s);
                    if (uri.getScheme() == null || uri.getHost() == null) throw new IllegalArgumentException();
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(label + " must be a valid URL (including scheme)");
                }
                yield s;
            }
            case IP -> {
                String s = raw.toString().trim();
                if (!IPV4.matcher(s).matches() && !(s.contains(":") && IPV6.matcher(s).matches())) {
                    throw new IllegalArgumentException(label + " must be an IPv4/IPv6 address or CIDR");
                }
                yield s;
            }
            case EMAIL -> {
                String s = raw.toString().trim();
                if (!EMAIL.matcher(s).matches()) {
                    throw new IllegalArgumentException(label + " must be an email address");
                }
                yield s;
            }
            case REFERENCE -> {
                try {
                    yield Long.parseLong(raw.toString().trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(label + " must reference a record id");
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    static List<String> choices(Map<String, Object> options) {
        if (options == null || !(options.get("choices") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(c -> c instanceof Map<?, ?> m ? String.valueOf(((Map<String, Object>) m).get("value")) : String.valueOf(c))
                .toList();
    }
}
