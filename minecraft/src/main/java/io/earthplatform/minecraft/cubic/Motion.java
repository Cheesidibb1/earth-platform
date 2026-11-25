package io.earthplatform.minecraft.cubic;

/**
 * How fast and which way a player travels, from where the server saw them tick by tick. A player riding a
 * Create Aeronautics ship, a fast boat or a minecart moves with what carries them, so their own look says nothing
 * about where the ground they need next lies: a pilot looks about the deck while the ship flies on. Over a few
 * ticks the server's positions say it well enough, whatever carries the player.
 *
 * <p>With {@code -Dearthplatform.cubic.motionAhead} on (the default), a player moving faster than {@link #FAST}
 * has their cubes made and sent in order of where they are going rather than where they look, has the cubes a
 * little way ahead made before they are in reach (see {@link ServerCubeWorld}), and has the map data along their
 * way fetched ahead (see {@link PathPrefetch}).
 */
public final class Motion {
    public static final boolean ON = !"false".equals(System.getProperty("earthplatform.cubic.motionAhead"));
    /**
     * Blocks a tick (20 blocks a second) from which a player counts as travelling fast: past sprinting, swimming and
     * vanilla elytra cruising at a walk, about what a minecart, a boat on ice or a flying ship goes.
     */
    public static final double FAST = 1.0;
    /** Below this a fast player counts as slow again, so one cruising at about {@link #FAST} does not switch back and forth. */
    static final double SLOW = 0.7;
    /** A move longer than this in one tick is a teleport, not travel: the speed starts over from there. */
    static final double TELEPORT = 256;
    /** How much of each tick's move the speed takes in: about a quarter, so a second's travel settles it. */
    static final double SMOOTH = 0.25;
    /** Ticks of travel the cubes ahead are made for: three seconds. */
    static final int LEAD_TICKS = 60;

    private double lastX, lastZ;
    private boolean seen, fast;
    private double vx, vz;

    /** Takes in where the player is this tick. */
    public void update(double x, double z) {
        if (!seen) {
            seen = true;
            lastX = x; lastZ = z;
            return;
        }
        double dx = x - lastX, dz = z - lastZ;
        lastX = x; lastZ = z;
        if (dx * dx + dz * dz > TELEPORT * TELEPORT) {
            vx = vz = 0;
            fast = false;
            return;
        }
        vx += (dx - vx) * SMOOTH;
        vz += (dz - vz) * SMOOTH;
        double speed = speed();
        fast = ON && (fast ? speed >= SLOW : speed >= FAST);
    }

    /** Horizontal speed in blocks a tick. */
    public double speed() { return Math.sqrt(vx * vx + vz * vz); }

    public double vx() { return vx; }
    public double vz() { return vz; }

    /** Whether the player travels fast enough for their cubes to follow where they go rather than where they look. */
    public boolean fast() { return fast; }

    /** The way the player travels as a Minecraft yaw in degrees, as {@link ViewOrder#lookX} reads one. */
    public double yaw() { return Math.toDegrees(Math.atan2(-vx, vz)); }

    /**
     * Cubes ahead of the player, across, that their travel reaches in {@link #LEAD_TICKS}, at most {@code most}: x in
     * the first, z in the second. Nothing while slow.
     */
    public int[] lead(int most) {
        if (!fast) return new int[2];
        double speed = speed(), cubes = Math.min(most, speed * LEAD_TICKS / 16.0);
        return new int[] {(int) Math.round(vx / speed * cubes), (int) Math.round(vz / speed * cubes)};
    }
}
