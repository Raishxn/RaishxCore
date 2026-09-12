package com.raishxn.ufocore.api.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class BatchWorkProgressTest {
    @Test
    void advancesExtremeWorkInSafeLongBatches() {
        UfoAmount total = UfoAmount.of(BigInteger.TEN.pow(30));
        BatchWorkProgress progress = new BatchWorkProgress(total);

        assertEquals(Long.MAX_VALUE, progress.nextBatch(Long.MAX_VALUE));
        progress.advance(Long.MAX_VALUE);

        assertEquals(total.subtract(UfoAmount.of(Long.MAX_VALUE)), progress.remaining());
        assertFalse(progress.isComplete());
    }

    @Test
    void rejectsInvalidOrOvercommittedProgress() {
        BatchWorkProgress progress = new BatchWorkProgress(UfoAmount.of(10));

        assertThrows(IllegalArgumentException.class, () -> progress.nextBatch(0));
        assertThrows(IllegalArgumentException.class, () -> progress.advance(11));

        progress.advance(10);
        assertTrue(progress.isComplete());
        assertEquals(0, progress.nextBatch(5));
    }
}
