package com.raishxn.ufocore.api.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class CraftingComputeCapacityTest {
    @Test
    void aggregatesCapacityPastLongWithoutOverflow() {
        CraftingComputeCapacity first = new CraftingComputeCapacity(
                UfoAmount.of(BigInteger.TEN.pow(30)), UfoAmount.of(Integer.MAX_VALUE));
        CraftingComputeCapacity total = first.add(first);

        assertEquals(BigInteger.TEN.pow(30).multiply(BigInteger.TWO), total.storageBytes().asBigInteger());
        assertEquals(BigInteger.valueOf(Integer.MAX_VALUE).multiply(BigInteger.TWO), total.parallelLanes().asBigInteger());
    }
}
