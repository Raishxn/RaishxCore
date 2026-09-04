package com.raishxn.ufocore.internal.network;

import com.raishxn.ufocore.api.network.MachineAction;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread rate limiter based on game ticks, isolated by player and action. */
public final class MachinePacketRateLimiter {
    private static final long PRUNE_INTERVAL_TICKS = 20L * 60L * 5L;
    private static final long ENTRY_MAX_AGE_TICKS = 20L * 60L * 10L;

    private final Map<Key, Long> lastAcceptedTicks = new HashMap<>();
    private long nextPruneTick = PRUNE_INTERVAL_TICKS;

    public boolean tryAcquire(UUID playerId, MachineAction action, long gameTick) {
        pruneIfNeeded(gameTick);
        Key key = new Key(playerId, action.id());
        Long previous = this.lastAcceptedTicks.get(key);
        if (previous != null && gameTick >= previous
                && gameTick - previous < action.minimumIntervalTicks()) {
            return false;
        }
        this.lastAcceptedTicks.put(key, gameTick);
        return true;
    }

    private void pruneIfNeeded(long gameTick) {
        if (gameTick < this.nextPruneTick) return;
        long oldestRetainedTick = gameTick - ENTRY_MAX_AGE_TICKS;
        this.lastAcceptedTicks.entrySet().removeIf(entry -> entry.getValue() < oldestRetainedTick);
        this.nextPruneTick = gameTick + PRUNE_INTERVAL_TICKS;
    }

    private record Key(UUID playerId, String actionId) {
    }
}
