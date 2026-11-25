package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PathPrefetchTest {
    /** Runs what it is given only when told to. */
    private static final class Held extends AbstractExecutorService {
        final List<Runnable> queued = new ArrayList<>();
        boolean shut;
        @Override public void execute(Runnable command) { queued.add(command); }
        void runAll() { List<Runnable> now = new ArrayList<>(queued); queued.clear(); now.forEach(Runnable::run); }
        @Override public void shutdown() { shut = true; }
        @Override public List<Runnable> shutdownNow() { shut = true; return queued; }
        @Override public boolean isShutdown() { return shut; }
        @Override public boolean isTerminated() { return shut; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    @Test void theWayAheadIsFetchedNearestFirstAFewAtATimeAndOnce() {
        List<int[]> fetched = new ArrayList<>();
        Held thread = new Held();
        PathPrefetch prefetch = new PathPrefetch((x, z) -> fetched.add(new int[] {x, z}), thread);
        // Five blocks a tick east (100 a second): 4 s is 400 blocks, 12 s 1,200 and 30 s 3,000.
        prefetch.ahead(100, 100, 5, 0);
        assertEquals(1, thread.queued.size(), "0 to 3,000 blocks east is one square, asked once");
        prefetch.ahead(100, 100, 5, 0);
        assertEquals(1, thread.queued.size(), "a square asked for is not asked again");
        thread.runAll();
        assertArrayEquals(new int[] {PathPrefetch.CELL / 2, PathPrefetch.CELL / 2}, fetched.get(0), "its middle");

        // Faster, the 12 and 30 s points are squares further on (9,700 and 24,100 blocks east); the 4 s one is done.
        prefetch.ahead(100, 100, 40, 0);
        assertEquals(2, thread.queued.size());
        thread.runAll();
        prefetch.ahead(100, 100, 40, 0);
        assertTrue(thread.queued.isEmpty(), "each square once");
        // Faster still, 8,100 and 48,100 blocks: the 30 s point stops at the most ahead.
        prefetch.ahead(100, 100, 100, 0);
        assertEquals(PathPrefetch.IN_FLIGHT, thread.queued.size(), "no more than IN_FLIGHT at once");
        thread.runAll();
        assertEquals(5, fetched.size());
        assertEquals(PathPrefetch.cell(100 + PathPrefetch.MOST_AHEAD, 100), PathPrefetch.cell(fetched.get(4)[0], fetched.get(4)[1]),
                "no further than the most ahead");
    }

    @Test void aFailedFetchIsLeftAndOthersCarryOn() {
        Held thread = new Held();
        PathPrefetch prefetch = new PathPrefetch((x, z) -> { throw new IllegalStateException("offline"); }, thread);
        prefetch.ahead(0, 0, 0, 5);
        thread.runAll();
        prefetch.ahead(0, 50_000, 0, 5);
        assertEquals(1, thread.queued.size(), "nothing stays in flight after a failure");
        prefetch.close();
        prefetch.ahead(0, 900_000, 0, 5);
        assertEquals(1, thread.queued.size(), "closed, nothing more is asked");
    }

    @Test void cellsCoverNegativeCoordinates() {
        assertEquals(-1, PathPrefetch.cellX(PathPrefetch.cell(-1, 5)));
        assertEquals(0, PathPrefetch.cellZ(PathPrefetch.cell(-1, 5)));
        assertEquals(-2, PathPrefetch.cellZ(PathPrefetch.cell(0, -PathPrefetch.CELL - 1)));
    }
}
