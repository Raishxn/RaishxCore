package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raishxn.ufocore.api.amount.UfoAmount;
import com.raishxn.ufocore.api.crafting.planner.PlanningResult;
import org.junit.jupiter.api.Test;

/**
 * The exporter only earns its place if an outside consumer can read it, so these tests pin the
 * payload rather than the code: the schema travels with it, every group of counters is present, and
 * an absent plan is distinguishable from a plan that measured zero.
 */
class PlannerDiagnosticsReportTest {

    /**
     * The command is the only thing that renders these payloads, so the rendering is tested here where
     * the payload helper already lives. An empty list is a sentence rather than an empty reply, and the
     * cap is stated instead of a long reply quietly stopping.
     */
    @Test void rendersOneJsonLinePerLivePlannerAndSaysWhatItLeftOut() {
        assertEquals(java.util.List.of("raishxcore planner: no live planner"),
                PlannerDiagnosticsCommand.render(java.util.List.of()));

        var one = diagnostics(null, "idle");
        var single = PlannerDiagnosticsCommand.render(java.util.List.of(one));
        assertEquals(1, single.size());
        assertTrue(single.getFirst().startsWith("{\"schema\":"), single.getFirst());

        java.util.List<Ae2PlannerBridge.Diagnostics> many = new java.util.ArrayList<>();
        for (int i = 0; i < PlannerDiagnosticsCommand.MAX_REPORTED + 3; i++) {
            many.add(one);
        }
        var capped = PlannerDiagnosticsCommand.render(many);
        assertEquals(PlannerDiagnosticsCommand.MAX_REPORTED + 1, capped.size());
        assertEquals("raishxcore planner: 3 more live planner(s) not shown", capped.getLast());
    }

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
                diagnostics(new PlanningResult.Diagnostics(7L, 1234L, 12, 987654L, noShortage()),
                        "COMPLETE"));
        assertTrue(reported.contains("\"lastPlan\":{\"graphRevision\":7,\"operations\":1234,"
                + "\"maximumDepth\":12,\"elapsedNanos\":987654,"), reported);
        assertFalse(reported.contains("\"lastPlan\":null"), reported);
    }

    /**
     * The split is the part that tells an operator a shortage includes a catalyst they get back rather
     * than material they have to find, so it has to survive the rendering as numbers.
     */
    @Test
    void reportsTheShortageSplitInsideTheLastPlan() {
        var shortage = new PlanningResult.ShortageSummary(UfoAmount.of(5L), UfoAmount.ONE, UfoAmount.of(2L),
                1, 1, 1);
        String json = PlannerDiagnosticsReport.toJson(diagnostics(
                new PlanningResult.Diagnostics(7L, 1234L, 12, 987654L, shortage), "MISSING_INGREDIENTS"));

        assertTrue(json.contains("\"missingConsumable\":5"), json);
        assertTrue(json.contains("\"missingSeed\":1"), json);
        assertTrue(json.contains("\"missingCarrier\":2"), json);
        assertTrue(json.contains("\"missingConsumableKinds\":1"), json);
        assertTrue(json.contains("\"missingSeedKinds\":1"), json);
        assertTrue(json.contains("\"missingCarrierKinds\":1"), json);
    }

    private static PlanningResult.ShortageSummary noShortage() {
        return new PlanningResult.ShortageSummary(UfoAmount.ZERO, UfoAmount.ZERO, UfoAmount.ZERO, 0, 0, 0);
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
