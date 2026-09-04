package com.raishxn.ufocore.api.amount;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UfoRatioTest {
    @Test
    void reducesAndAppliesWithExplicitRounding() {
        UfoRatio ratio = UfoRatio.of(2, 4);
        assertEquals("1/2", ratio.toString());
        assertEquals(UfoAmount.of(2), ratio.applyFloor(UfoAmount.of(5)));
        assertEquals(UfoAmount.of(3), ratio.applyCeil(UfoAmount.of(5)));
    }

    @Test
    void preservesTinyRatesExactly() {
        UfoRatio tiny = UfoRatio.of(1, 1_000_000_000L);
        assertEquals(UfoAmount.ONE, tiny.applyCeil(UfoAmount.ONE));
        assertEquals(UfoAmount.ONE, tiny.applyFloor(UfoAmount.of(1_000_000_000L)));
    }
}
