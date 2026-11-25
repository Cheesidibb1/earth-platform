package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MotionTest {
    private static Motion moving(double perTickX, double perTickZ, int ticks) {
        Motion motion = new Motion();
        for (int t = 0; t <= ticks; t++) motion.update(1000 + perTickX * t, -500 + perTickZ * t);
        return motion;
    }

    @Test void walkingIsNotFast() {
        Motion motion = moving(0.28, 0, 100);
        assertFalse(motion.fast());
        assertArrayEquals(new int[2], motion.lead(12), "no cubes ahead while slow");
    }

    @Test void aShipCruisingSettlesOnItsSpeedAndHeading() {
        Motion motion = moving(0, 3, 40);
        assertTrue(motion.fast());
        assertEquals(3, motion.speed(), 0.01);
        // Yaw 0 faces south, toward +z, as ViewOrder reads a look.
        assertEquals(0, motion.yaw(), 0.5);
        assertEquals(1, ViewOrder.lookZ(motion.yaw()), 1e-3);
        Motion east = moving(3, 0, 40);
        assertEquals(1, ViewOrder.lookX(east.yaw()), 1e-3, "heading east");
        assertEquals(0, ViewOrder.sector(motion.yaw()));
    }

    @Test void theLeadIsThreeSecondsOfTravelAtMostTheGivenCubes() {
        // 1.6 blocks a tick for 60 ticks is 96 blocks: six cubes.
        assertArrayEquals(new int[] {0, 6}, moving(0, 1.6, 60).lead(12));
        assertArrayEquals(new int[] {-12, 0}, moving(-20, 0, 60).lead(12), "clamped to the reach");
        int[] diagonal = moving(10, 10, 60).lead(12);
        assertEquals(12, Math.hypot(diagonal[0], diagonal[1]), 1, "clamped along the way, not each axis");
    }

    @Test void slowingDownBelowTheThresholdLeavesFastOnlyOnceWellUnder() {
        Motion motion = moving(0, 1.2, 40);
        assertTrue(motion.fast());
        double z = -500 + 1.2 * 40;
        for (int t = 1; t <= 40; t++) motion.update(1000, z += 0.9);
        assertTrue(motion.fast(), "0.9 a tick is under the start but over the stop: no flicker");
        for (int t = 1; t <= 40; t++) motion.update(1000, z += 0.2);
        assertFalse(motion.fast());
    }

    @Test void aTeleportIsNotSpeed() {
        Motion motion = moving(0, 2, 40);
        motion.update(5_000_000, 5_000_000);
        assertFalse(motion.fast());
        assertEquals(0, motion.speed());
        motion.update(5_000_000, 5_000_000);
        assertFalse(motion.fast(), "standing where it landed");
    }
}
