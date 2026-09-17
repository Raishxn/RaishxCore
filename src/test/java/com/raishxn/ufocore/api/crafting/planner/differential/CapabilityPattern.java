package com.raishxn.ufocore.api.crafting.planner.differential;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Engine-neutral, immutable crafting pattern of the differential corpus. */
public record CapabilityPattern(String id, int priority, List<CapabilityInput> inputs,
                                List<CapabilityOutput> outputs, Set<String> craftableOutputs) {

    public CapabilityPattern {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(outputs, "outputs");
        if (id.isBlank()) throw new IllegalArgumentException("pattern id must not be blank");
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        if (outputs.isEmpty()) throw new IllegalArgumentException("pattern must declare an output");
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (CapabilityOutput output : outputs) {
            keys.add(output.key());
        }
        if (craftableOutputs == null) {
            LinkedHashSet<String> primary = new LinkedHashSet<>();
            for (CapabilityOutput output : outputs) {
                if (output.kind() == CapabilityOutput.Kind.PRIMARY) {
                    primary.add(output.key());
                }
            }
            craftableOutputs = primary;
        }
        craftableOutputs = Set.copyOf(craftableOutputs);
        if (craftableOutputs.isEmpty()) {
            throw new IllegalArgumentException("pattern must declare a craftable output");
        }
        if (!keys.containsAll(craftableOutputs)) {
            throw new IllegalArgumentException("craftable outputs must be declared outputs");
        }
    }

    public static CapabilityPattern of(String id, List<CapabilityInput> inputs,
                                       List<CapabilityOutput> outputs) {
        return new CapabilityPattern(id, 0, inputs, outputs, null);
    }

    /** True when every input is exact and every output is deterministic. */
    public boolean isExactDeterministic() {
        return inputs.stream().allMatch(input -> input.kind() == CapabilityInput.Kind.EXACT)
                && outputs.stream().noneMatch(output -> output.kind() == CapabilityOutput.Kind.PROBABILISTIC);
    }

    /** True when this pattern declares the key as a selectable route. */
    public boolean routesTo(String key) {
        return craftableOutputs.contains(key);
    }
}
