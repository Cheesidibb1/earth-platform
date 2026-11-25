package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.earthplatform.minecraft.cubic.gen.FarColumn;
import java.nio.file.Path;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ColumnSurveyTest {
    private static CubeGenerator ground(int y, boolean fails) {
        return new CubeGenerator() {
            public String id() { return "test-survey"; }
            public LevelChunkSection generate(int x, int y, int z) { return null; }
            public void farColumn(int level, int x, int z, FarColumn out) {}
            public int surfaceY(int x, int z) { if (fails) throw new IllegalStateException("no data"); return y; }
            public int waterY(int x, int z) { return 63; }
            public int seaLevel() { return 63; }
        };
    }

    /** Review: with surface flight off, each survey still carried two more longs than before it; it carries one, the water gap. */
    @Test void offASurveyIsTheColumnAndItsGroundAsBefore(@TempDir Path directory) throws Exception {
        CubeStorage storage = new CubeStorage(directory);
        long column = CubeKey.column(2, -3);
        assertEquals(3, ColumnSurvey.saved(false));
        assertArrayEquals(new long[] {column, 40, ColumnSurvey.NO_GAP}, ColumnSurvey.of(column, 2, -3, ground(40, false), storage, false));
        assertArrayEquals(new long[] {column, Integer.MIN_VALUE, Integer.MIN_VALUE}, ColumnSurvey.of(column, 2, -3, ground(40, true), storage, false));
        storage.close();
    }

    @Test void onASurveyAlsoCarriesTheSurfaceBounds(@TempDir Path directory) throws Exception {
        CubeStorage storage = new CubeStorage(directory);
        long column = CubeKey.column(2, -3);
        assertEquals(5, ColumnSurvey.saved(true));
        assertArrayEquals(new long[] {column, 40, 63, 63, ColumnSurvey.NO_GAP}, ColumnSurvey.of(column, 2, -3, ground(40, false), storage, true));
        assertArrayEquals(new long[] {column, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE},
                ColumnSurvey.of(column, 2, -3, ground(40, true), storage, true));
        storage.close();
    }

    @Test void deepWaterBetweenTheBedAndTheSurfaceIsAGap() {
        // A bed 3,000 blocks down under the sea's top at 63: water only from five cubes over the bed to three under the top.
        long gap = ColumnSurvey.waterGap(ground(-3000, false), 0, 0);
        assertEquals((-3000 >> 4) + ColumnSurvey.FLOOR_KEEP + 1, ColumnSurvey.gapFrom(gap));
        assertEquals((63 >> 4) - ColumnSurvey.SURFACE_KEEP, ColumnSurvey.gapTo(gap));
        assertFalse(ColumnSurvey.inGap(gap, (-3000 >> 4) + ColumnSurvey.FLOOR_KEEP), "kelp and wrecks over the bed are kept");
        assertFalse(ColumnSurvey.inGap(gap, 63 >> 4), "the surface is kept");
        assertTrue(ColumnSurvey.inGap(gap, -100));
        // Shallow sea, dry land and land over the water's top have none.
        assertEquals(ColumnSurvey.NO_GAP, ColumnSurvey.waterGap(ground(20, false), 0, 0));
        assertEquals(ColumnSurvey.NO_GAP, ColumnSurvey.waterGap(ground(200, false), 0, 0));
        for (int y = -2000; y < 2000; y++) assertFalse(ColumnSurvey.inGap(ColumnSurvey.NO_GAP, y));
        // A failed survey's gap reads as none.
        assertFalse(ColumnSurvey.inGap(ColumnSurvey.FAILED, 0));
        assertFalse(ColumnSurvey.inGap(ColumnSurvey.FAILED, -1));
    }
}
