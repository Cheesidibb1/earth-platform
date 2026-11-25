package io.earthplatform.minecraft.cubic;

import io.earthplatform.geography.globe.NamedThreads;
import io.earthplatform.minecraft.EarthPlatformMod;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fetches the map data ahead of players travelling fast (see {@link Motion}), so the cubes along their way are made
 * from data already on disk. Without it the cube workers, four at most, each waited on its own downloads as a ship
 * came over new ground, and the cubes it flew into came late; a Sable ship's physics, which asks for the terrain
 * about it on the server thread, then held the whole server up while they did.
 *
 * <p>The way ahead is cut into {@link #CELL}-block squares, and each square's data is asked for once, a few at a
 * time on a thread of its own, nearest first. A square asked for before (by any player) is not asked again until
 * {@link #REMEMBERED} others have been.
 */
final class PathPrefetch {
    /** Squares fetched once each, in blocks: within the 3.5 km a near fetch reaches about its middle. */
    static final int CELL = 4096;
    /** Seconds of travel ahead at which the way is fetched, nearest first. */
    static final int[] AHEAD_SECONDS = {4, 12, 30};
    /** Farthest ahead fetched, in blocks, however fast the player goes. */
    static final double MOST_AHEAD = 48_000;
    /** Fetches in flight at once, from every player. */
    static final int IN_FLIGHT = 2;
    /** Squares remembered as fetched. */
    static final int REMEMBERED = 4096;

    /** Fetches a place's near data; blocks until it is in. */
    @FunctionalInterface interface Fetch { void near(int x, int z); }

    private final Fetch fetch;
    private final ExecutorService thread;
    private final LongLinkedOpenHashSet asked = new LongLinkedOpenHashSet();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong fetched = new AtomicLong(), failed = new AtomicLong();
    private volatile boolean closed;

    PathPrefetch(Fetch fetch) {
        this(fetch, Executors.newFixedThreadPool(IN_FLIGHT, NamedThreads.numbered("Earth path prefetch", Thread.MIN_PRIORITY)));
    }

    /** For tests: runs the fetches on {@code thread}. */
    PathPrefetch(Fetch fetch, ExecutorService thread) {
        this.fetch = fetch;
        this.thread = thread;
    }

    /** The square holding a block, packed. */
    static long cell(double x, double z) {
        return (long) Math.floorDiv((long) Math.floor(x), CELL) << 32 | Math.floorDiv((long) Math.floor(z), CELL) & 0xFFFFFFFFL;
    }

    static int cellX(long cell) { return (int) (cell >> 32); }
    static int cellZ(long cell) { return (int) cell; }

    /**
     * Server thread. Asks for the data ahead of a player at {@code x, z} travelling {@code vx, vz} blocks a tick,
     * while fewer than {@link #IN_FLIGHT} fetches are going.
     */
    void ahead(double x, double z, double vx, double vz) {
        if (closed) return;
        double speed = Math.sqrt(vx * vx + vz * vz);
        if (speed <= 0) return;
        for (int seconds : AHEAD_SECONDS) {
            if (inFlight.get() >= IN_FLIGHT) return;
            double reach = Math.min(MOST_AHEAD, speed * 20 * seconds) / speed;
            long cell = cell(x + vx * reach, z + vz * reach);
            if (asked.contains(cell)) continue;
            asked.add(cell);
            if (asked.size() > REMEMBERED) asked.removeFirstLong();
            int cx = cellX(cell) * CELL + CELL / 2, cz = cellZ(cell) * CELL + CELL / 2;
            inFlight.incrementAndGet();
            try {
                thread.execute(() -> {
                    try {
                        if (!closed) fetch.near(cx, cz);
                        fetched.incrementAndGet();
                    } catch (RuntimeException failure) {
                        // Generation asks for whatever is missing again, so a failed fetch only means a slower cube.
                        if (failed.incrementAndGet() % 50 == 1) EarthPlatformMod.LOGGER.warn("Could not fetch Earth data ahead of a traveller at {}, {}", cx, cz, failure);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                inFlight.decrementAndGet();
                return;
            }
        }
    }

    String describe() { return fetched.get() + " squares fetched ahead of travellers" + (failed.get() > 0 ? " (" + failed.get() + " failed)" : ""); }

    void close() {
        closed = true;
        thread.shutdownNow();
    }
}
