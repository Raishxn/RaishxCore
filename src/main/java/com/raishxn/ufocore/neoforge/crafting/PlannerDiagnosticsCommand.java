package com.raishxn.ufocore.neoforge.crafting;

import com.raishxn.ufocore.neoforge.crafting.Ae2PlannerBridge.Diagnostics;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Renders planner diagnostics where an operator can read them.
 *
 * <p>The report existed, was tested, and had no caller, which is the same as not existing: the roadmap
 * asks for observability through a command or an API, and a JSON payload nothing can reach is neither.
 *
 * <p>The rendering is a pure function of the payloads so it can be tested without a server, and the
 * command is the thin part that sends the lines. A reply is capped because more than a handful of live
 * planners in one chat message is noise rather than diagnosis, and the cap is stated rather than
 * silently applied.
 */
public final class PlannerDiagnosticsCommand {

    /** More live planners than this in one reply is noise; the remainder is counted, not printed. */
    static final int MAX_REPORTED = 8;

    private PlannerDiagnosticsCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("raishxcore")
                .then(Commands.literal("planner")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> send(context.getSource()))));
    }

    private static int send(CommandSourceStack source) {
        List<String> lines = render(Ae2PlannerBridge.activeDiagnostics());
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return lines.size();
    }

    /** One JSON line per live planner, most of them, plus a count of the ones left out. */
    static List<String> render(List<Diagnostics> active) {
        if (active.isEmpty()) {
            return List.of("raishxcore planner: no live planner");
        }
        List<String> lines = new ArrayList<>(Math.min(active.size(), MAX_REPORTED) + 1);
        int reported = Math.min(active.size(), MAX_REPORTED);
        for (int i = 0; i < reported; i++) {
            lines.add(PlannerDiagnosticsReport.toJson(active.get(i)));
        }
        if (active.size() > reported) {
            lines.add("raishxcore planner: " + (active.size() - reported)
                    + " more live planner(s) not shown");
        }
        return List.copyOf(lines);
    }
}
