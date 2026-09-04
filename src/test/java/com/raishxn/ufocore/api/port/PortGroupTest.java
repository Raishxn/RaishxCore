package com.raishxn.ufocore.api.port;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PortGroupTest {
    @Test
    void clampsBrokenPortsAndAggregatesInOrder() {
        EnergyPortGroup group = new EnergyPortGroup(List.of(
                (amount, simulate) -> amount + 100,
                (amount, simulate) -> amount));
        assertEquals(25L, group.extract(25L, false));
    }

    @Test
    void transactionalItemCommitCannotExceedSimulation() {
        ItemPort<String> port = new ItemPort<>() {
            @Override
            public long extractItem(String item, long amount, boolean simulate) {
                return simulate ? 10L : 7L;
            }

            @Override
            public long insertItem(String item, long amount, boolean simulate) {
                return 0L;
            }
        };
        assertEquals(7L, new ItemPortGroup<>(List.of(port)).extractTransactional("iron", 20L));
    }
}
