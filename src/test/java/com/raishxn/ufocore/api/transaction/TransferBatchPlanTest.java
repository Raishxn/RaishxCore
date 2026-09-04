package com.raishxn.ufocore.api.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransferBatchPlanTest {
    @Test
    void aggregatesAndAllocatesPartialCommitInRequestOrder() {
        var plan = new TransferBatchPlan<>(List.of(
                new TransferRequest<>("iron", UfoAmount.of(7)),
                new TransferRequest<>("iron", UfoAmount.of(5)),
                new TransferRequest<>("gold", UfoAmount.of(2))));

        assertEquals(UfoAmount.of(12), plan.totals().get("iron"));
        var allocations = plan.allocate(Map.of("iron", UfoAmount.of(9), "gold", UfoAmount.ONE));
        assertEquals(UfoAmount.of(7), allocations.get(0).accepted());
        assertEquals(UfoAmount.of(2), allocations.get(1).accepted());
        assertEquals(UfoAmount.ONE, allocations.get(2).accepted());
        assertEquals(UfoAmount.of(3), allocations.get(1).remaining());
    }
}
