package com.raishxn.ufocore.api.amount;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

/** Compact display formatter. Never use its output as an authoritative value. */
public final class MagnitudeFormatter {
    private static final String[] SUFFIXES = {"", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc"};
    private static final BigInteger THOUSAND = BigInteger.valueOf(1_000L);

    private MagnitudeFormatter() {
    }

    public static String compact(UfoAmount amount) {
        Objects.requireNonNull(amount, "amount");
        BigInteger value = amount.asBigInteger();
        int group = Math.max(0, (value.toString().length() - 1) / 3);
        if (group == 0) return value.toString();
        if (group >= SUFFIXES.length) return scientific(value);
        BigDecimal divisor = new BigDecimal(THOUSAND.pow(group));
        BigDecimal scaled = new BigDecimal(value).divide(divisor, 2, RoundingMode.DOWN).stripTrailingZeros();
        return scaled.toPlainString() + SUFFIXES[group];
    }

    private static String scientific(BigInteger value) {
        String digits = value.toString();
        String mantissa = digits.length() == 1 ? digits
                : digits.substring(0, 1) + "." + digits.substring(1, Math.min(3, digits.length()));
        return String.format(Locale.ROOT, "%se%d", mantissa, digits.length() - 1);
    }
}
