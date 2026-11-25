package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PlayerGridTest {
    @Test void findsEveryEntryWithinItsSizeAsAFullScanWould() {
        Random random = new Random(7);
        int size = 14;
        List<int[]> players = new ArrayList<>();
        PlayerGrid<int[]> grid = new PlayerGrid<>(size);
        for (int i = 0; i < 200; i++) {
            int[] at = {random.nextInt(2000) - 1000, random.nextInt(2000) - 1000};
            players.add(at);
            grid.add(at[0], at[1], at);
        }
        for (int i = 0; i < 20_000; i++) {
            int x = random.nextInt(2100) - 1050, z = random.nextInt(2100) - 1050;
            boolean scan = players.stream().anyMatch(at -> CubeOffsets.within(x - at[0], 0, z - at[1], size, 0));
            assertEquals(scan, grid.any(x, z, at -> CubeOffsets.within(x - at[0], 0, z - at[1], size, 0)), x + ", " + z);
        }
    }

    @Test void anEntryAddedTwiceInOneSquareIsAskedOnce() {
        PlayerGrid<Object> grid = new PlayerGrid<>(10);
        Object player = new Object();
        grid.add(3, 3, player);
        grid.add(4, 4, player);
        int[] asked = {0};
        assertFalse(grid.any(3, 3, entry -> { asked[0]++; return false; }));
        assertEquals(1, asked[0]);
        assertFalse(new PlayerGrid<>(10).any(0, 0, entry -> true));
    }
}
