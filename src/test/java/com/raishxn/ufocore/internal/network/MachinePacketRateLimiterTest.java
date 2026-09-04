package com.raishxn.ufocore.internal.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raishxn.ufocore.api.network.MachineAction;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MachinePacketRateLimiterTest {
    private final MachinePacketRateLimiter limiter = new MachinePacketRateLimiter();

    @Test
    void isolatesPlayersAndActionIds() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        MachineAction scan = new MachineAction("test:scan", 20L);
        MachineAction toggle = new MachineAction("test:toggle", 4L);

        assertTrue(limiter.tryAcquire(first, scan, 100L));
        assertFalse(limiter.tryAcquire(first, scan, 119L));
        assertTrue(limiter.tryAcquire(first, scan, 120L));
        assertTrue(limiter.tryAcquire(first, toggle, 100L));
        assertTrue(limiter.tryAcquire(second, scan, 100L));
    }

    @Test
    void acceptsAfterGameTimeMovesBackwards() {
        UUID player = UUID.randomUUID();
        MachineAction action = new MachineAction("test:start", 4L);
        assertTrue(limiter.tryAcquire(player, action, 500L));
        assertTrue(limiter.tryAcquire(player, action, 10L));
    }
}
