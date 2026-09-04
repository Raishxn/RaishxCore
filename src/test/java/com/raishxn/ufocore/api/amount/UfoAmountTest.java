package com.raishxn.ufocore.api.amount;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class UfoAmountTest {
    @Test
    void supportsAmountsBeyondLongWithoutPrecisionLoss() {
        UfoAmount huge = UfoAmount.of(BigInteger.TEN.pow(100));
        assertEquals(BigInteger.TEN.pow(100).add(BigInteger.ONE), huge.add(UfoAmount.ONE).asBigInteger());
        assertEquals(Long.MAX_VALUE, huge.longValueSaturated());
    }

    @Test
    void rejectsNegativeAndUnderflow() {
        assertThrows(IllegalArgumentException.class, () -> UfoAmount.of(-1));
        assertThrows(ArithmeticException.class, () -> UfoAmount.ONE.subtract(UfoAmount.of(2)));
        assertEquals(UfoAmount.ZERO, UfoAmount.ONE.subtractClamped(UfoAmount.of(2)));
    }

    @Test
    void encodingIsBoundedAndRoundTrips() {
        UfoAmount value = UfoAmount.parse("123456789012345678901234567890");
        byte[] encoded = UfoAmountEncoding.encode(value, 64);
        assertEquals(value, UfoAmountEncoding.decode(encoded, 64));
        assertThrows(IllegalArgumentException.class, () -> UfoAmountEncoding.decode(new byte[65], 64));
    }
}
