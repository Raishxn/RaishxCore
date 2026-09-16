package com.raishxn.ufocore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The planner kill-switch must default to enabled and obey the test override. */
class CoreConfigTest {
    @AfterEach
    void clearOverride() { CoreConfig.plannerEnabledTestOverride = null; }
    @Test
    void testOverrideDrivesTheKillSwitch() {
        CoreConfig.plannerEnabledTestOverride = true;
        assertTrue(CoreConfig.isPlannerEnabled(), "override true must enable the planner");
        CoreConfig.plannerEnabledTestOverride = false;
        assertFalse(CoreConfig.isPlannerEnabled(), "override false must disable the planner");
        CoreConfig.plannerEnabledTestOverride = null;
    }

    @Test
    void unloadedConfigDefaultsToEnabled() {
        // Unit tests never load the config file; the bridge must not block startup.
        assertTrue(CoreConfig.isPlannerEnabled(), "unloaded config should default to enabled");
    }

    @Test
    void unloadedConfigUsesDocumentedBoundedPlannerDefaults() {
        assertEquals(new CoreConfig.PlannerPolicy(2, 32, 2_000, 10_000_000L, 100_000, 128,
                50, 100_000, 25_000, 64L * 1024 * 1024, 16,
                4, 3, 10_000), CoreConfig.plannerPolicy());
    }

    @Test
    void plannerPolicyRejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new CoreConfig.PlannerPolicy(
                0, 32, 2_000, 10_000_000L, 100_000, 128,
                50, 100_000, 25_000, 64L * 1024 * 1024, 16,
                4, 3, 10_000));
    }
}
