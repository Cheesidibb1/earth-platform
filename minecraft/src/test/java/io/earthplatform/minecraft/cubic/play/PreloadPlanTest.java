package io.earthplatform.minecraft.cubic.play;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreloadPlanTest {
    private static List<int[]> area(PreloadPlan.Shape shape, double reach) throws PreloadPlan.TooLarge {
        return PreloadPlan.area(-reach, -reach, reach, reach, 0, 0, 6000, shape, 1_000_000, () -> false);
    }

    @Test void aCircleIsCoveredNearestItsMiddleFirst() throws Exception {
        List<int[]> points = area(PreloadPlan.circle(0, 0, 20_000), 20_000);
        assertArrayEquals(new int[] {0, 0}, points.get(0), "the middle first");
        for (int i = 1; i < points.size(); i++) {
            assertTrue(Math.hypot(points.get(i)[0], points.get(i)[1]) >= Math.hypot(points.get(i - 1)[0], points.get(i - 1)[1]), "nearest first");
        }
        // Every block of the circle lies in some square of the plan.
        for (int x = -20_000; x <= 20_000; x += 500) for (int z = -20_000; z <= 20_000; z += 500) {
            if (Math.hypot(x, z) > 20_000) continue;
            int bx = x, bz = z;
            assertTrue(points.stream().anyMatch(p -> Math.abs(p[0] - bx) <= 3000 && Math.abs(p[1] - bz) <= 3000), bx + ", " + bz);
        }
        assertTrue(points.size() < area(PreloadPlan.square(0, 0, 20_000), 20_000).size(), "a circle takes fewer than its square");
    }

    @Test void aSquareTakesEverySquareItTouches() throws Exception {
        // 20 km each way on a 6 km grid lined up on the middle: points -18 to 18 km, whose squares reach 21 km; those at 24 km start past it.
        assertEquals(7 * 7, area(PreloadPlan.square(0, 0, 20_000), 20_000).size());
        assertEquals(1, area(PreloadPlan.square(0, 0, 100), 100).size(), "a small one is one area");
    }

    @Test void aShapeOnlyPartOfItsBoundsTakesOnlyThatPart() throws Exception {
        // A thin diagonal band through a 60 km box.
        PreloadPlan.Shape band = (x, z) -> Math.abs(x - z) < 1000;
        List<int[]> points = area(band, 30_000);
        // The 11 squares on the diagonal and the 20 beside them, whose corners touch it; not the box's 121.
        assertEquals(31, points.size());
        assertTrue(points.stream().allMatch(p -> Math.abs(p[0] - p[1]) <= 6000));
    }

    @Test void tooLargeIsTurnedDownBeforeLookingAndCancelStopsTheSearch() {
        PreloadPlan.TooLarge large = assertThrows(PreloadPlan.TooLarge.class,
                () -> PreloadPlan.area(-2e7, -1e7, 2e7, 1e7, 0, 0, 6000, (x, z) -> { throw new AssertionError("looked"); }, 1_000_000, () -> false));
        assertTrue(large.squares > 1_000_000);
        int[] rows = {0};
        assertDoesNotThrow(() -> PreloadPlan.area(-1e6, -1e6, 1e6, 1e6, 0, 0, 6000, (x, z) -> { rows[0]++; return true; }, 1_000_000, () -> rows[0] > 0));
        assertTrue(rows[0] < 2000, "stopped after the first row");
    }

    @Test void progressSurvivesARestartAndAnUnreadableFileIsNone(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("world").resolve("earthplatform-preload.txt");
        assertNull(PreloadPlan.load(file));
        PreloadPlan.save(file, "Switzerland\nsecond line", 120, 400, List.of(new int[] {6000, -12000}, new int[] {-3, 7}));
        PreloadPlan.Saved saved = PreloadPlan.load(file);
        assertNotNull(saved);
        assertEquals("Switzerland second line", saved.what());
        assertEquals(120, saved.doneBefore());
        assertEquals(400, saved.total());
        assertEquals(2, saved.left().size());
        assertArrayEquals(new int[] {6000, -12000}, saved.left().get(0));
        assertArrayEquals(new int[] {-3, 7}, saved.left().get(1));
        Files.writeString(file, "something else");
        assertNull(PreloadPlan.load(file));
        PreloadPlan.forget(file);
        assertFalse(Files.exists(file));
        PreloadPlan.forget(file);
    }

    @Test void timeLeftAndDurationsRead() {
        assertEquals(-1, PreloadPlan.secondsLeft(0, 100, 5000));
        assertEquals(90, PreloadPlan.secondsLeft(10, 100, 10_000));
        assertEquals("12 s", PreloadPlan.duration(12));
        assertEquals("4 min 5 s", PreloadPlan.duration(245));
        assertEquals("1 h 20 min", PreloadPlan.duration(4800));
    }
}
