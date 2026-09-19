package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class Ae2PlannerBridgeAmountTest {
    @Test
    void exactMissingAmountRemainsExactInsideAe2Range() {
        assertEquals(5_700_000_000_000L,
                Ae2PlannerBridge.toAe2MissingDisplayAmount(UfoAmount.of(5_700_000_000_000L)));
    }

    @Test
    void oversizedMissingAmountIsCappedOnlyAtTheAe2DisplayBoundary() {
        UfoAmount amount = UfoAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        assertEquals(Long.MAX_VALUE, Ae2PlannerBridge.toAe2MissingDisplayAmount(amount));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), amount.asBigInteger());
    }
}
