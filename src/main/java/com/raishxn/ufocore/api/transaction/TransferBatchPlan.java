package com.raishxn.ufocore.api.transaction;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic aggregation and allocation for one simulate/commit cycle. */
public final class TransferBatchPlan<K> {
    private final List<TransferRequest<K>> requests;
    private final Map<K, UfoAmount> totals;

    public TransferBatchPlan(List<TransferRequest<K>> requests) {
        this.requests = List.copyOf(Objects.requireNonNull(requests, "requests"));
        LinkedHashMap<K, UfoAmount> aggregated = new LinkedHashMap<>();
        for (TransferRequest<K> request : this.requests) {
            aggregated.merge(request.key(), request.amount(), UfoAmount::add);
        }
        this.totals = Collections.unmodifiableMap(aggregated);
    }

    public List<TransferRequest<K>> requests() {
        return requests;
    }

    public Map<K, UfoAmount> totals() {
        return totals;
    }

    public List<TransferAllocation<K>> allocate(Map<K, UfoAmount> committed) {
        Objects.requireNonNull(committed, "committed");
        LinkedHashMap<K, UfoAmount> remaining = new LinkedHashMap<>();
        for (Map.Entry<K, UfoAmount> entry : committed.entrySet()) {
            UfoAmount limit = totals.get(entry.getKey());
            if (limit != null) remaining.put(entry.getKey(), entry.getValue().min(limit));
        }
        List<TransferAllocation<K>> allocations = new ArrayList<>(requests.size());
        for (TransferRequest<K> request : requests) {
            UfoAmount available = remaining.getOrDefault(request.key(), UfoAmount.ZERO);
            UfoAmount accepted = request.amount().min(available);
            allocations.add(new TransferAllocation<>(request.key(), request.amount(), accepted));
            remaining.put(request.key(), available.subtract(accepted));
        }
        return List.copyOf(allocations);
    }
}
