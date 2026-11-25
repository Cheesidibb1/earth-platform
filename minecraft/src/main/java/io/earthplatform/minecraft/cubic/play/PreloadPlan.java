package io.earthplatform.minecraft.cubic.play;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * The areas a preload fetches (see {@link EarthAdmin}) and its progress on disk, kept apart from the game so they can
 * be tested alone. An area is a point of the block grid: fetching its near data covers the {@code step}-block square
 * round it. A shape is covered by every square that any part of it touches, as far as its middle and four corners
 * tell, nearest its middle first, so a preload stopped early has done the middle, as Chunky's does.
 */
final class PreloadPlan {
    private PreloadPlan() {}

    /** Whether a block position, x then z, lies in a shape. */
    @FunctionalInterface interface Shape { boolean contains(double x, double z); }

    /** A shape with too many squares in its bounds to look at all of them: one the size of a continent or more. */
    static final class TooLarge extends Exception {
        final long squares;
        TooLarge(long squares) {
            super(squares + " squares");
            this.squares = squares;
        }
    }

    /**
     * The grid points {@code step} blocks apart, lined up on {@code (centreX, centreZ)}, whose squares a shape within
     * the bounds touches, nearest the centre first. Throws {@link TooLarge} when the bounds hold more than
     * {@code mostSquares} squares, before looking at any; {@code cancelled} stops the search early, giving what was found.
     */
    static List<int[]> area(double minX, double minZ, double maxX, double maxZ, double centreX, double centreZ, int step,
                            Shape shape, long mostSquares, java.util.function.BooleanSupplier cancelled) throws TooLarge {
        long i0 = (long) Math.floor((minX - centreX) / step - 0.5), i1 = (long) Math.ceil((maxX - centreX) / step + 0.5);
        long j0 = (long) Math.floor((minZ - centreZ) / step - 0.5), j1 = (long) Math.ceil((maxZ - centreZ) / step + 0.5);
        long squares = (i1 - i0 + 1) * (j1 - j0 + 1);
        if (squares > mostSquares) throw new TooLarge(squares);
        double half = step / 2.0;
        List<int[]> points = new ArrayList<>();
        for (long j = j0; j <= j1; j++) {
            if (cancelled.getAsBoolean()) break;
            for (long i = i0; i <= i1; i++) {
                double x = centreX + i * step, z = centreZ + j * step;
                if (x + half < minX || x - half > maxX || z + half < minZ || z - half > maxZ) continue;
                if (shape.contains(x, z) || shape.contains(x - half, z - half) || shape.contains(x + half, z - half)
                        || shape.contains(x - half, z + half) || shape.contains(x + half, z + half)) {
                    points.add(new int[] {(int) Math.round(x), (int) Math.round(z)});
                }
            }
        }
        points.sort(Comparator.comparingDouble(p -> (p[0] - centreX) * (p[0] - centreX) + (p[1] - centreZ) * (p[1] - centreZ)));
        return points;
    }

    /** A circle of {@code radius} blocks. */
    static Shape circle(double centreX, double centreZ, double radius) {
        return (x, z) -> (x - centreX) * (x - centreX) + (z - centreZ) * (z - centreZ) <= radius * radius;
    }

    /** A square reaching {@code radius} blocks each way, as Chunky's square. */
    static Shape square(double centreX, double centreZ, double radius) {
        return (x, z) -> Math.abs(x - centreX) <= radius && Math.abs(z - centreZ) <= radius;
    }

    // Progress on disk -----------------------------------------------------------------------------

    /** A preload saved part way: what it was, the areas done before, and those still to fetch in order. */
    record Saved(String what, int doneBefore, int total, List<int[]> left) {}

    private static final String HEADER = "earthplatform preload 1";

    /**
     * Writes a preload's progress, replacing any before it at once: a server stopped while writing keeps the last
     * whole copy.
     */
    static void save(Path file, String what, int doneBefore, int total, List<int[]> left) throws IOException {
        StringBuilder text = new StringBuilder(32 + left.size() * 16);
        text.append(HEADER).append('\n').append(what.replace('\n', ' ')).append('\n').append(doneBefore).append(' ').append(total).append('\n');
        for (int[] point : left) text.append(point[0]).append(' ').append(point[1]).append('\n');
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** A saved preload, or null when there is none or it cannot be read. */
    @Nullable static Saved load(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() < 3 || !lines.get(0).equals(HEADER)) return null;
            String[] counts = lines.get(2).trim().split(" ");
            List<int[]> left = new ArrayList<>(lines.size() - 3);
            for (String line : lines.subList(3, lines.size())) {
                if (line.isBlank()) continue;
                String[] parts = line.trim().split(" ");
                left.add(new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])});
            }
            return new Saved(lines.get(1), Integer.parseInt(counts[0]), Integer.parseInt(counts[1]), left);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    static void forget(Path file) {
        try { Files.deleteIfExists(file); } catch (IOException ignored) { /* left for the next preload to replace */ }
    }

    // Telling how it goes --------------------------------------------------------------------------

    /** "1 h 20 min", "4 min 5 s" or "12 s". */
    static String duration(long seconds) {
        if (seconds >= 3600) return String.format(Locale.ROOT, "%d h %d min", seconds / 3600, seconds % 3600 / 60);
        if (seconds >= 60) return String.format(Locale.ROOT, "%d min %d s", seconds / 60, seconds % 60);
        return seconds + " s";
    }

    /** Seconds left at the rate so far, or -1 before anything is done. */
    static long secondsLeft(int done, int total, long elapsedMillis) {
        if (done <= 0 || elapsedMillis <= 0) return -1;
        return (long) Math.ceil((total - done) * (elapsedMillis / 1000.0) / done);
    }
}
