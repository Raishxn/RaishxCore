package com.raishxn.ufocore.api.multiblock;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, game-independent structural definition shared by runtime and viewers. */
public record MultiblockDefinition(
        String id,
        int schemaVersion,
        int width,
        int height,
        int depth,
        int anchorX,
        int anchorY,
        int anchorZ,
        List<MultiblockCell> cells) {

    public MultiblockDefinition {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("definition id must not be blank");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        if (width < 1 || height < 1 || depth < 1) throw new IllegalArgumentException("dimensions must be positive");
        requireInside(anchorX, anchorY, anchorZ, width, height, depth, "anchor");
        cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
        Set<CellPosition> occupied = new HashSet<>();
        for (MultiblockCell cell : cells) {
            requireInside(cell.x(), cell.y(), cell.z(), width, height, depth, "cell");
            if (!occupied.add(new CellPosition(cell.x(), cell.y(), cell.z()))) {
                throw new IllegalArgumentException("duplicate cell at " + cell.x() + "," + cell.y() + "," + cell.z());
            }
        }
    }

    public long volume() {
        return Math.multiplyExact(Math.multiplyExact((long) width, height), depth);
    }

    private static void requireInside(int x, int y, int z, int width, int height, int depth, String label) {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) {
            throw new IllegalArgumentException(label + " is outside definition bounds");
        }
    }

    private record CellPosition(int x, int y, int z) {
    }
}
