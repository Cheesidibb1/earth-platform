package io.earthplatform.minecraft.cubic;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Players bucketed by where they are, in squares of cube columns, so a question about a cube asks only the players
 * near it. Deciding which of tens of thousands of loaded cubes anyone still watches asked every player about every
 * cube: on a server with a hundred players, millions of checks each pass.
 *
 * <p>A square is {@code size} cube columns across, so every entry within {@code size} columns of a column is in the
 * square holding that column or one of the eight round it. An entry may be added at several places.
 */
final class PlayerGrid<T> {
    private final int size;
    private final Long2ObjectOpenHashMap<List<T>> squares = new Long2ObjectOpenHashMap<>();

    PlayerGrid(int size) {
        if (size < 1) throw new IllegalArgumentException("size " + size);
        this.size = size;
    }

    private static long key(int sx, int sz) { return (long) sx << 32 | sz & 0xFFFFFFFFL; }

    /** Adds an entry at a cube column; adding it at the same square twice adds it once. */
    void add(int cubeX, int cubeZ, T entry) {
        List<T> square = squares.computeIfAbsent(key(Math.floorDiv(cubeX, size), Math.floorDiv(cubeZ, size)), k -> new ArrayList<>(2));
        if (square.isEmpty() || square.get(square.size() - 1) != entry) square.add(entry);
    }

    boolean isEmpty() { return squares.isEmpty(); }

    /** Whether any entry near a cube column (within {@code size} columns, and maybe a little further) passes {@code test}. */
    boolean any(int cubeX, int cubeZ, Predicate<T> test) {
        int sx = Math.floorDiv(cubeX, size), sz = Math.floorDiv(cubeZ, size);
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            List<T> square = squares.get(key(sx + dx, sz + dz));
            if (square == null) continue;
            for (int i = 0; i < square.size(); i++) if (test.test(square.get(i))) return true;
        }
        return false;
    }
}
