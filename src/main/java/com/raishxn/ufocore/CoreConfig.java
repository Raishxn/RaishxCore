package com.raishxn.ufocore;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Runtime switches for the platform. The planner kill-switch is the field escape
 * hatch: with it off, new crafting requests delegate to AE2's built-in planner
 * before graph capture. Already submitted calculations are cancelled when the
 * planner is disabled or their source grid revision becomes obsolete.
 */
public final class CoreConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue PLANNER_ENABLED = BUILDER
            .comment("Replace AE2's crafting planner with the RaishxCore iterative planner.",
                    "When false, every crafting calculation is served by AE2's built-in planner.")
            .define("planner.enabled", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * Test-only override for {@link #isPlannerEnabled()}; never set outside automated
     * tests because the COMMON switch is an instance-wide decision.
     */
    public static volatile Boolean plannerEnabledTestOverride;

    private CoreConfig() {
    }

    /**
     * Read on every planning request. Defaults to enabled while the config file has
     * not loaded (unit tests, early startup), so the bridge never blocks startup.
     */
    public static boolean isPlannerEnabled() {
        Boolean override = plannerEnabledTestOverride;
        if (override != null) return override;
        return !SPEC.isLoaded() || PLANNER_ENABLED.get();
    }
}
