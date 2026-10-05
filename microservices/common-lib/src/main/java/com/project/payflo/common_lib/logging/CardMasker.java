package com.project.payflo.common_lib.logging;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds card numbers in text and masks them, and prints objects without their {@link MaskedCard} fields.
 */
public final class CardMasker {

    private static final int MIN_PAN_DIGITS = 13;
    private static final int MAX_PAN_DIGITS = 19;
    private static final String FULL_MASK = "***";

    private CardMasker() {
    }

    /** {@code ****1111} for a card number, or {@code ***} when it is too short to be one. */
    public static String mask(String pan) {
        if (pan == null) {
            return "null";
        }
        String digits = digitsOf(pan);
        return digits.length() >= 4 ? "****" + digits.substring(digits.length() - 4) : FULL_MASK;
    }

    /**
     * Replaces every card number in {@code text} with {@code [PAN ****1111]}. A card number is a run of 13 to 19
     * digits, which may be grouped by single spaces or dashes, that starts with a digit a card network uses and
     * passes the Luhn check; that keeps order numbers, timestamps and other long numbers readable.
     */
    public static String maskAll(String text) {
        if (text == null || text.length() < MIN_PAN_DIGITS) {
            return text;
        }
        StringBuilder out = null;
        int copied = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (!isDigit(text.charAt(i)) || (i > 0 && isDigit(text.charAt(i - 1)))) {
                i++;
                continue;
            }
            // a run of digits starting at i, grouped by single spaces or dashes
            int j = i;
            int digits = 0;
            int end = i;
            while (j < n) {
                char c = text.charAt(j);
                if (isDigit(c)) {
                    digits++;
                    j++;
                    end = j;
                } else if ((c == ' ' || c == '-') && j + 1 < n && isDigit(text.charAt(j + 1))) {
                    j++;
                } else {
                    break;
                }
            }
            if (digits >= MIN_PAN_DIGITS && digits <= MAX_PAN_DIGITS) {
                String candidate = digitsOf(text.substring(i, end));
                if (looksLikeCardNumber(candidate)) {
                    if (out == null) {
                        out = new StringBuilder(n);
                    }
                    out.append(text, copied, i).append("[PAN ****").append(candidate.substring(candidate.length() - 4)).append(']');
                    copied = end;
                }
            }
            i = Math.max(end, i + 1);
        }
        if (out == null) {
            return text;
        }
        return out.append(text, copied, n).toString();
    }

    /**
     * {@code Type[field=value, ...]} for a record or an object, with every {@link MaskedCard} field masked. For a
     * {@code toString()} on a type that carries card data.
     */
    public static String describe(Object object) {
        if (object == null) {
            return "null";
        }
        Class<?> type = object.getClass();
        List<String> parts = new ArrayList<>();
        try {
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    Object value = component.getAccessor().invoke(object);
                    MaskedCard marker = component.getAccessor().getAnnotation(MaskedCard.class);
                    if (marker == null) {
                        marker = component.getAnnotation(MaskedCard.class);
                    }
                    parts.add(component.getName() + "=" + render(value, marker));
                }
            } else {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    parts.add(field.getName() + "=" + render(field.get(object), field.getAnnotation(MaskedCard.class)));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Never let describing an object fail a log call, and never fall back to printing it
            return type.getSimpleName() + "[unprintable]";
        }
        return type.getSimpleName() + "[" + String.join(", ", parts) + "]";
    }

    private static String render(Object value, MaskedCard marker) {
        if (marker == null) {
            return String.valueOf(value);
        }
        if (value == null) {
            return "null";
        }
        return marker.full() ? FULL_MASK : mask(String.valueOf(value));
    }

    private static boolean looksLikeCardNumber(String digits) {
        char first = digits.charAt(0);
        // 2 and 5 Mastercard, 3 Amex/Diners/JCB, 4 Visa, 6 Discover/RuPay, 8 RuPay. Not 0, 1 (epoch millis) or 7, 9.
        if (first != '2' && first != '3' && first != '4' && first != '5' && first != '6' && first != '8') {
            return false;
        }
        return passesLuhn(digits);
    }

    private static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    private static String digitsOf(String text) {
        StringBuilder digits = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (isDigit(text.charAt(i))) {
                digits.append(text.charAt(i));
            }
        }
        return digits.toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
