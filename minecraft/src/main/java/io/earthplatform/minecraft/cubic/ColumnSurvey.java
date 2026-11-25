package io.earthplatform.minecraft.cubic;

import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;

/**
 * What the cube workers work out about a cube column for the server thread: the column, its lowest ground, with
 * {@link SurfaceFlight} on its lowest and highest ground or water, its {@link #waterGap}, then the saved buried
 * cubes in it.
 */
final class ColumnSurvey {
    private ColumnSurvey() {}

    /** Every bound of a survey that failed, as the column's data could not be had. */
    static final int FAILED = Integer.MIN_VALUE;

    /** Where the saved cubes start; the water gap comes just before them. */
    static int saved(boolean surfaces) { return surfaces ? 5 : 3; }

    /** Where the water gap is in a survey. */
    static int gapAt(boolean surfaces) { return saved(surfaces) - 1; }

    /**
     * Cubes kept above the highest seabed of a column, however deep the water over it: kelp, seagrass, wrecks and
     * ruins stand within them, and the bed is seen through them from a little way up.
     */
    static final int FLOOR_KEEP = 4;
    /** Cubes kept under the water's lowest top: the sea's surface and the water just under it, seen from a ship or a boat. */
    static final int SURFACE_KEEP = 3;
    /** No water gap: a range that holds nothing. */
    static final long NO_GAP = gap(1, 0);

    static long gap(int from, int to) { return (long) from << 32 | to & 0xFFFFFFFFL; }
    static int gapFrom(long gap) { return (int) (gap >> 32); }
    static int gapTo(long gap) { return (int) gap; }

    /** Whether a cube Y lies in a water gap. A failed survey's gap holds nothing. */
    static boolean inGap(long gap, int cubeY) { return cubeY >= gapFrom(gap) && cubeY <= gapTo(gap); }

    /**
     * Any thread; reads map data. The cube Ys of a cube column, first and last, that hold nothing but water: deep sea
     * or a deep lake, well over every bit of its bed and under every bit of its water's top. Such cubes are made the
     * same every time, so they need not be saved, and a player passing fast over them need not wait for them (see
     * {@link ServerCubeWorld}). {@link #NO_GAP} for a column with no such water.
     */
    static long waterGap(CubeGenerator generator, int cubeX, int cubeZ) {
        int floor = generator.highestGround(cubeX, cubeZ), top = generator.lowestSurface(cubeX, cubeZ);
        if (top <= floor) return NO_GAP;
        int from = (floor >> 4) + FLOOR_KEEP + 1, to = (top >> 4) - SURFACE_KEEP;
        return from <= to ? gap(from, to) : NO_GAP;
    }

    /** Workers. Ground that can't be had yet counts as nothing buried there: its cubes are all made, as before. */
    static long[] of(long column, int x, int z, CubeGenerator generator, CubeStorage storage, boolean surfaces) {
        LongArrayList result = new LongArrayList();
        result.add(column);
        try {
            result.add(generator.lowestGround(x, z));
            if (surfaces) { result.add(generator.lowestSurface(x, z)); result.add(generator.highestSurface(x, z)); }
            result.add(waterGap(generator, x, z));
            result.addAll(storage.savedIn(x, x, z, z));
        }
        catch (IOException | RuntimeException failure) {
            result.size(1);
            for (int i = 1; i < saved(surfaces); i++) result.add(FAILED);
        }
        return result.toLongArray();
    }

    /** Whether a survey failed. */
    static boolean failed(long[] survey) { return survey[1] == FAILED; }
}
