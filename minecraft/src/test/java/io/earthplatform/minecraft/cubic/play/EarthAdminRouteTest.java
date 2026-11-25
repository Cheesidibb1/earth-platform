package io.earthplatform.minecraft.cubic.play;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class EarthAdminRouteTest {
    @Test void aRouteRunsFromOneEndToTheOtherAStepApart() {
        List<int[]> points = EarthAdmin.routePoints(new int[] {0, 0}, new int[] {20_000, 0}, 0, 6000);
        assertEquals(5, points.size(), "0, 6,000, 12,000, 18,000 and the far end");
        assertArrayEquals(new int[] {0, 0}, points.get(0));
        assertArrayEquals(new int[] {6000, 0}, points.get(1));
        assertArrayEquals(new int[] {20_000, 0}, points.get(4), "the far end itself");
    }

    @Test void aWideRouteTakesItsSidesAfterTheLineRowByRow() {
        List<int[]> points = EarthAdmin.routePoints(new int[] {100, 100}, new int[] {100, 12_100}, 25_000, 6000);
        // Two steps either side fit in 25 km: five a row, three rows.
        assertEquals(15, points.size());
        assertArrayEquals(new int[] {100, 100}, points.get(0), "the line first");
        for (int i = 0; i < 5; i++) assertEquals(100, points.get(i)[1], "a row across the line, going south");
        assertEquals(5, points.stream().filter(p -> p[1] == 12_100).count());
        assertTrue(points.stream().allMatch(p -> Math.abs(p[0] - 100) <= 12_000));
    }

    @Test void aRouteToTheSamePlaceIsOnePoint() {
        assertEquals(1, EarthAdmin.routePoints(new int[] {5, 5}, new int[] {5, 5}, 0, 6000).size());
    }
}
