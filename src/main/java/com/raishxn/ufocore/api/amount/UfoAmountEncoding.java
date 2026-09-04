package com.raishxn.ufocore.api.amount;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

/** Bounded wire/save representation for untrusted exact amounts. */
public final class UfoAmountEncoding {
    public static final int DEFAULT_MAX_BYTES = UfoAmount.DEFAULT_MAX_BITS / Byte.SIZE;

    private UfoAmountEncoding() {
    }

    public static byte[] encode(UfoAmount amount, int maxBytes) {
        Objects.requireNonNull(amount, "amount");
        validateLimit(maxBytes);
        byte[] signed = amount.asBigInteger().toByteArray();
        byte[] unsigned = signed.length > 1 && signed[0] == 0 ? Arrays.copyOfRange(signed, 1, signed.length) : signed;
        if (unsigned.length > maxBytes) throw new IllegalArgumentException("amount exceeds encoded byte limit");
        return unsigned;
    }

    public static UfoAmount decode(byte[] bytes, int maxBytes) {
        Objects.requireNonNull(bytes, "bytes");
        validateLimit(maxBytes);
        if (bytes.length == 0 || bytes.length > maxBytes) {
            throw new IllegalArgumentException("invalid encoded amount length");
        }
        return UfoAmount.of(new BigInteger(1, bytes));
    }

    private static void validateLimit(int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
    }
}
