package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raishxn.ufocore.api.crafting.planner.PlanningResult;
import org.junit.jupiter.api.Test;

/**
 * The exporter only earns its place if an outside consumer can read it, so these tests pin the
 * payload rather than the code: the schema travels with it, every group of counters is present, and
 * an absent plan is distinguishable from a plan that measured zero.
 */
class PlannerDiagnosticsReportTest {

    private static Ae2PlannerBridge.Diagnostics diagnostics(PlanningResult.Diagnostics plan, String status) {
        return new Ae2PlannerBridge.Diagnostics(7L, 3L, 4L, status, plan, 1, 5L, 2L, 0L, 2, 1, 0L, 0L,
                "CLOSED", 0, 0, 0L, 9L, 0L, 12, 1, 2048L, 0L, 1_500_000L,
                new CaptureSliceMetrics.Snapshot(3L, 10L, 20L, 30L, 40L, 60L, 5L, 2L, 11L, 21L, 31L, 41L, 50L,
                        5L, 6L, 7L, 0L));
    }

    @Test
    void carriesTheSchemaAndEveryGroupOfCounters() {
        String json = PlannerDiagnosticsReport.toJson(diagnostics(null, "idle"));

        assertTrue(json.startsWith("{\"schema\":" + PlannerDiagnosticsReport.SCHEMA_VERSION + ","), json);
        assertTrue(json.endsWith("}"), json);
        for (String group : new String[] {"\"requests\":", "\"cache\":", "\"workers\":", "\"pressure\":",
                "\"capture\":", "\"percentiles\":"}) {
            assertTrue(json.contains(group), () -> "missing " + group + " in " + json);
        }
        assertTrue(json.contains("\"revision\":7"), json);
        assertTrue(json.contains("\"submitted\":5"), json);
        assertTrue(json.contains("\"sliceP95Nanos\":20"), json);
        assertTrue(json.contains("\"tickBudgetRemainingNanos\":1500000"), json);
    }

    @Test
    void anAbsentPlanIsNotEmptyRatherThanZero() {
        assertTrue(PlannerDiagnosticsReport.toJson(diagnostics(null, "idle")).contains("\"lastPlan\":null"));

        String reported = PlannerDiagnosticsReport.toJson(
                diagnostics(new PlanningResult.Diagnostics(7L, 1234L, 12, 987654L), "COMPLETE"));
        assertTrue(reported.contains("\"lastPlan\":{\"graphRevision\":7,\"operations\":1234,"
                + "\"maximumDepth\":12,\"elapsedNanos\":987654}"), reported);
        assertFalse(reported.contains("\"lastPlan\":null"), reported);
    }

    @Test
    void escapesAStatusThatWouldOtherwiseBreakThePayload() {
        String json = PlannerDiagnosticsReport.toJson(diagnostics(null, "ae2: \"fallback\"\nnext"));

        assertTrue(json.contains("\"status\":\"ae2: \\\"fallback\\\"\\nnext\""), json);
        assertEquals(0, unbalancedBraces(json), () -> "braces must stay balanced: " + json);
    }

    /** Counts braces outside string literals, which is what a consumer's parser has to survive. */
    private static int unbalancedBraces(String json) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < json.length(); index++) {
            char character = json.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
            }
        }
        return depth;
    }
}
