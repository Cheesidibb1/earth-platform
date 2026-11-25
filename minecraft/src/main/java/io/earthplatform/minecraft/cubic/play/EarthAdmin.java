package io.earthplatform.minecraft.cubic.play;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.google.common.net.InetAddresses;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.Downloads;
import io.earthplatform.geography.globe.NamedThreads;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.platform.Loader;
import io.earthplatform.minecraft.platform.Platform;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tools for whoever runs the server: downloading an area ahead of time, a status page in chat,
 * and a live web map of the players over the globe for sharing outside the game.
 */
public final class EarthAdmin {
    /** How far apart preloaded points are, in blocks: each fetches what the cubes within 3.5 km read. */
    static final int PRELOAD_STEP = 6000;
    private static final double PRELOAD_MOST_KM = 250;
    /** Largest circle or square a preload takes, in kilometres each way; the area limit binds well before. */
    private static final double SHAPE_MOST_KM = 2500;
    /** Longest route {@code /earth preload route} takes, in points: 30,000 km of a corridor one point wide. */
    static final int ROUTE_MOST_POINTS = 5000;
    /** Widest corridor of a route, in kilometres. */
    private static final double ROUTE_MOST_WIDTH_KM = 60;
    /**
     * Most areas one preload fetches: each covers a 6 km square, so 20,000 is about 720,000 square kilometres, the
     * size of Texas. {@code -Dearthplatform.preload.mostAreas} changes it.
     */
    static final int MOST_AREAS = Math.max(1, Integer.getInteger("earthplatform.preload.mostAreas", 20_000));
    /** Most squares a shape's bounds may hold before it is turned down unlooked at: a country across the date line spans the world. */
    private static final long MOST_SQUARES = 4_000_000;
    /**
     * Points fetched at once. Each point's own pieces already download together, but a point waits on its slowest
     * piece, so one at a time left the connection idle between points; a few at once keep it busy without crowding
     * out the downloads players' cubes are waiting on.
     */
    static final int PRELOAD_PARALLEL = Math.clamp(Integer.getInteger("earthplatform.preload.parallel", 3), 1, 8);
    /** How often a running preload writes its progress, so a crash loses little. */
    private static final long SAVE_EVERY_MILLIS = 30_000;
    /** Areas fetched before the data they take is measured against the cache's limit. */
    private static final int MEASURE_AFTER = 40;

    @Nullable private static volatile Run preloading;

    private EarthAdmin() {}

    /** Works out a preload's areas, on the preload's own thread; may be turned down as too large. */
    @FunctionalInterface private interface Planner {
        List<int[]> plan(java.util.function.BooleanSupplier cancelled) throws PreloadPlan.TooLarge;
    }

    /** A preload under way: its areas once worked out, which are fetched, and how it goes. */
    private static final class Run {
        final AtomicBoolean running = new AtomicBoolean(true);
        /** Stopped with its progress thrown away, not kept for {@code continue}. */
        volatile boolean cancelled;
        final String what;
        final Path file;
        final int doneBefore;
        final long started = Util.getMillis();
        final Map<String, long[]> before = Downloads.totals();
        final AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger();
        volatile List<int[]> points;
        volatile java.util.concurrent.atomic.AtomicIntegerArray fetched;
        volatile boolean warnedCache;

        Run(String what, Path file, int doneBefore) {
            this.what = what;
            this.file = file;
            this.doneBefore = doneBefore;
        }

        int total() { List<int[]> now = points; return doneBefore + (now == null ? 0 : now.size()); }
        int doneInAll() { return doneBefore + done.get(); }

        /** Writes the areas not fetched yet, failed ones included, for {@code continue}; nothing while still being worked out. */
        synchronized void save() {
            List<int[]> now = points;
            var marks = fetched;
            if (now == null || marks == null || cancelled) return;
            List<int[]> left = new ArrayList<>();
            for (int i = 0; i < now.size(); i++) if (marks.get(i) == 0) left.add(now.get(i));
            try { PreloadPlan.save(file, what, total() - left.size(), total(), left); }
            catch (IOException failure) { EarthPlatformMod.LOGGER.warn("Could not save the preload's progress to {}", file, failure); }
        }

        String progress() {
            List<int[]> now = points;
            if (now == null) return "working out the areas of " + what;
            int all = doneInAll(), total = total();
            long elapsed = Util.getMillis() - started, left = PreloadPlan.secondsLeft(done.get(), now.size(), elapsed);
            double perMinute = done.get() * 60_000.0 / Math.max(1, elapsed);
            return String.format(Locale.ROOT, "%s: %d of %d areas (%.0f%%), %.1f a minute%s%s", what, all, total, 100.0 * all / Math.max(1, total), perMinute,
                    left < 0 ? "" : ", about " + PreloadPlan.duration(left) + " left", failed.get() > 0 ? ", " + failed.get() + " failed so far" : "");
        }
    }

    private static Path preloadFile(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("earthplatform-preload.txt");
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("preload").requires(EarthCommands::op)
                .then(Commands.literal("stop").executes(context -> stop(context.getSource(), false)))
                .then(Commands.literal("cancel").executes(context -> stop(context.getSource(), true)))
                .then(Commands.literal("continue").executes(context -> resume(context.getSource())))
                .then(Commands.literal("status").executes(context -> preloadStatus(context.getSource())))
                .then(Commands.literal("circle")
                        .then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-85, 85))
                        .then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180))
                        .then(Commands.argument("km", DoubleArgumentType.doubleArg(1, SHAPE_MOST_KM))
                                .executes(context -> around(context.getSource(), DoubleArgumentType.getDouble(context, "latitude"),
                                        DoubleArgumentType.getDouble(context, "longitude"), DoubleArgumentType.getDouble(context, "km"), false))))))
                .then(Commands.literal("square")
                        .then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-85, 85))
                        .then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180))
                        .then(Commands.argument("km", DoubleArgumentType.doubleArg(1, SHAPE_MOST_KM))
                                .executes(context -> around(context.getSource(), DoubleArgumentType.getDouble(context, "latitude"),
                                        DoubleArgumentType.getDouble(context, "longitude"), DoubleArgumentType.getDouble(context, "km"), true))))))
                .then(Commands.literal("box")
                        .then(Commands.argument("south", DoubleArgumentType.doubleArg(-85, 85))
                        .then(Commands.argument("west", DoubleArgumentType.doubleArg(-180, 180))
                        .then(Commands.argument("north", DoubleArgumentType.doubleArg(-85, 85))
                        .then(Commands.argument("east", DoubleArgumentType.doubleArg(-180, 180))
                                .executes(context -> box(context.getSource(), DoubleArgumentType.getDouble(context, "south"), DoubleArgumentType.getDouble(context, "west"),
                                        DoubleArgumentType.getDouble(context, "north"), DoubleArgumentType.getDouble(context, "east"))))))))
                .then(Commands.literal("country")
                        .then(Commands.argument("country", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(EarthCountries.all().stream().map(EarthCountries.Country::name), builder))
                                .executes(context -> country(context.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(context, "country")))))
                .then(Commands.literal("border").executes(context -> border(context.getSource())))
                .then(Commands.literal("route")
                        .then(Commands.argument("fromLatitude", DoubleArgumentType.doubleArg(-85, 85))
                        .then(Commands.argument("fromLongitude", DoubleArgumentType.doubleArg(-180, 180))
                        .then(Commands.argument("toLatitude", DoubleArgumentType.doubleArg(-85, 85))
                        .then(Commands.argument("toLongitude", DoubleArgumentType.doubleArg(-180, 180))
                                .executes(context -> route(context.getSource(), DoubleArgumentType.getDouble(context, "fromLatitude"),
                                        DoubleArgumentType.getDouble(context, "fromLongitude"), DoubleArgumentType.getDouble(context, "toLatitude"),
                                        DoubleArgumentType.getDouble(context, "toLongitude"), 0))
                                .then(Commands.argument("widthKm", DoubleArgumentType.doubleArg(0, ROUTE_MOST_WIDTH_KM))
                                        .executes(context -> route(context.getSource(), DoubleArgumentType.getDouble(context, "fromLatitude"),
                                                DoubleArgumentType.getDouble(context, "fromLongitude"), DoubleArgumentType.getDouble(context, "toLatitude"),
                                                DoubleArgumentType.getDouble(context, "toLongitude"), DoubleArgumentType.getDouble(context, "widthKm")))))))))
                .then(Commands.argument("km", DoubleArgumentType.doubleArg(1, PRELOAD_MOST_KM))
                        .executes(context -> preload(context.getSource(), DoubleArgumentType.getDouble(context, "km")))));
        root.then(Commands.literal("admin").requires(EarthCommands::op).executes(context -> status(context.getSource())));
    }

    // Preloading ---------------------------------------------------------------------------------

    /** Null, after telling {@code source} why, when a preload is running or the world has no Earth. */
    @Nullable private static EarthGenerator ready(CommandSourceStack source) {
        if (preloading != null) { source.sendFailure(Component.literal("A preload is already running; /earth preload stop pauses it.")); return null; }
        EarthGenerator earth = EarthCommands.earth(source);
        if (earth == null) source.sendFailure(Component.literal("This world has no Earth."));
        return earth;
    }

    /**
     * Downloads the Earth data within {@code km} of the one running it (or of the server spawn from
     * the console), so first visits there are instant. Nearest first, off the server thread.
     */
    private static int preload(CommandSourceStack source, double km) {
        EarthGenerator earth = ready(source);
        if (earth == null) return 0;
        ServerPlayer player = source.getPlayer();
        GeoPoint here = player == null ? null : EarthRules.where(player);
        GeoPoint centre = here != null ? here : EarthCommands.spawnPoint(source, earth);
        return shape(source, earth, centre.latitude(), centre.longitude(), km, false,
                String.format(Locale.ROOT, "%.0f km around %.4f, %.4f", km, centre.latitude(), centre.longitude()));
    }

    /** A circle, or with {@code square} a square, reaching {@code km} each way from a place, as Chunky's shapes. */
    private static int around(CommandSourceStack source, double latitude, double longitude, double km, boolean square) {
        EarthGenerator earth = ready(source);
        if (earth == null) return 0;
        return shape(source, earth, latitude, longitude, km, square,
                String.format(Locale.ROOT, "a %s %.0f km each way around %.4f, %.4f", square ? "square" : "circle", km, latitude, longitude));
    }

    private static int shape(CommandSourceStack source, EarthGenerator earth, double latitude, double longitude, double km, boolean square, String what) {
        int[] middle = earth.block(latitude, longitude);
        // Blocks are about a meter; the grid is in blocks, so its reach is close enough to kilometers.
        double reach = km * 1000 / earth.grid().groundMetresPerBlock(middle[0], middle[1]);
        PreloadPlan.Shape shape = square ? PreloadPlan.square(middle[0], middle[1], reach) : PreloadPlan.circle(middle[0], middle[1], reach);
        return start(source, earth, what, cancelled -> PreloadPlan.area(middle[0] - reach, middle[1] - reach, middle[0] + reach, middle[1] + reach,
                middle[0], middle[1], PRELOAD_STEP, shape, MOST_SQUARES, cancelled));
    }

    /** A latitude and longitude box, nearest its middle first. */
    private static int box(CommandSourceStack source, double south, double west, double north, double east) {
        EarthGenerator earth = ready(source);
        if (earth == null) return 0;
        if (south >= north || west >= east) { source.sendFailure(Component.literal("The box needs south below north and west below east.")); return 0; }
        return geographic(source, earth, south, west, north, east, (latitude, longitude) -> true,
                String.format(Locale.ROOT, "%.3f to %.3f latitude, %.3f to %.3f longitude", south, north, west, east));
    }

    /** A country within its borders, nearest the middle of its extent first. */
    private static int country(CommandSourceStack source, String name) {
        EarthGenerator earth = ready(source);
        if (earth == null) return 0;
        EarthCountries.Country found = EarthCountries.find(name).orElse(null);
        if (found == null) { source.sendFailure(Component.literal("No country called " + name + ".")); return 0; }
        return geographic(source, earth, found.south(), found.west(), found.north(), found.east(), found::contains, found.name());
    }

    /** Everywhere inside the server's border (see {@code /earth border}), as Chunky's world border mode. */
    private static int border(CommandSourceStack source) {
        EarthGenerator earth = ready(source);
        if (earth == null) return 0;
        EarthSpawns.Border border = EarthSpawns.of(source.getServer()).border().orElse(null);
        if (border == null) { source.sendFailure(Component.literal("There is no border; set one with /earth border, or name an area.")); return 0; }
        return geographic(source, earth, border.south(), border.west(), border.north(), border.east(), border::contains, "inside the border (" + border.label() + ")");
    }

    /** Whether a latitude and longitude lies in a shape. */
    @FunctionalInterface private interface Inside { boolean contains(double latitude, double longitude); }

    /** A shape given in latitude and longitude inside a box, on whichever projection the world uses. */
    private static int geographic(CommandSourceStack source, EarthGenerator earth, double south, double west, double north, double east, Inside inside, String what) {
        double southIn = Math.max(-85, south), northIn = Math.min(85, north);
        int[] middle = earth.block((southIn + northIn) / 2, (west + east) / 2);
        var grid = earth.grid();
        return start(source, earth, what, cancelled -> {
            // The box's bounds in blocks, from points along and across it: on some projections its edges bend.
            double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (int a = 0; a <= 16; a++) for (int b = 0; b <= 16; b++) {
                int[] at = earth.block(southIn + (northIn - southIn) * a / 16, west + (east - west) * b / 16);
                minX = Math.min(minX, at[0]); maxX = Math.max(maxX, at[0]);
                minZ = Math.min(minZ, at[1]); maxZ = Math.max(maxZ, at[1]);
            }
            return PreloadPlan.area(minX, minZ, maxX, maxZ, middle[0], middle[1], PRELOAD_STEP, (x, z) -> {
                GeoPoint point = grid.tryGeographic(x, z);
                return point != null && point.latitude() >= south && point.latitude() <= north && point.longitude() >= west && point.longitude() <= east
                        && inside.contains(point.latitude(), point.longitude());
            }, MOST_SQUARES, cancelled);
        });
    }

    /**
     * Downloads the Earth data along a straight line on the map between two places, the way a ship or an airship
     * flies a steady heading, {@code widthKm} across (one point's reach when 0), from the first place on: a server
     * whose players run a regular route has it ready before they set out.
     */
    private static int route(CommandSourceStack source, double fromLatitude, double fromLongitude, double toLatitude, double toLongitude, double widthKm) {
        EarthGenerator earth = ready(source);
        if (earth == null) return 0;
        int[] from = earth.block(fromLatitude, fromLongitude), to = earth.block(toLatitude, toLongitude);
        double metres = earth.grid().groundMetresPerBlock((from[0] + to[0]) / 2.0, (from[1] + to[1]) / 2.0);
        List<int[]> points = routePoints(from, to, widthKm * 1000 / metres, PRELOAD_STEP);
        if (points.size() > ROUTE_MOST_POINTS) {
            source.sendFailure(Component.literal(String.format(Locale.ROOT, "That route needs %d areas; at most %d are fetched at once. Split it or make it narrower.",
                    points.size(), ROUTE_MOST_POINTS)));
            return 0;
        }
        return start(source, earth, String.format(Locale.ROOT, "the route from %.4f, %.4f to %.4f, %.4f, %.0f km wide",
                fromLatitude, fromLongitude, toLatitude, toLongitude, widthKm), cancelled -> points);
    }

    /**
     * Points {@code step} blocks apart along the line from {@code from} to {@code to}, both ends included, and as many
     * either side of it as fit in {@code width} blocks across: in order along the line, the middle of each row first.
     */
    static List<int[]> routePoints(int[] from, int[] to, double width, int step) {
        double dx = to[0] - from[0], dz = to[1] - from[1], length = Math.hypot(dx, dz);
        double ux = length > 0 ? dx / length : 1, uz = length > 0 ? dz / length : 0;
        int along = (int) Math.ceil(length / step), side = (int) Math.floor(width / 2 / step);
        List<int[]> points = new ArrayList<>();
        for (int i = 0; i <= along; i++) {
            double t = Math.min(length, (double) i * step);
            for (int k = 0; k <= 2 * side; k++) {
                // 0, 1, -1, 2, -2 and so on: the line itself before its sides.
                int offset = (k + 1) / 2 * (k % 2 == 1 ? 1 : -1) * step;
                points.add(new int[]{(int) Math.round(from[0] + ux * t - uz * offset), (int) Math.round(from[1] + uz * t + ux * offset)});
            }
        }
        return points;
    }

    /** Pauses the preload, keeping its progress for {@code continue}, or with {@code cancel} throws it away. */
    private static int stop(CommandSourceStack source, boolean cancel) {
        Path file = preloadFile(source.getServer());
        Run run = preloading;
        if (run == null) {
            if (cancel && PreloadPlan.load(file) != null) {
                PreloadPlan.forget(file);
                source.sendSuccess(() -> Component.literal("Forgot the paused preload."), true);
                return 1;
            }
            source.sendFailure(Component.literal("Nothing is preloading."));
            return 0;
        }
        run.cancelled = cancel;
        run.running.set(false);
        source.sendSuccess(() -> Component.literal(cancel ? "Cancelling the preload." : "Pausing the preload; /earth preload continue carries on from here, after a restart too."), true);
        return 1;
    }

    /** Carries on a paused preload, or one a server stop interrupted, with the areas it had left. */
    private static int resume(CommandSourceStack source) {
        EarthGenerator earth = ready(source);
        if (earth == null) return 0;
        Path file = preloadFile(source.getServer());
        PreloadPlan.Saved saved = PreloadPlan.load(file);
        if (saved == null || saved.left().isEmpty()) { source.sendFailure(Component.literal("There is no paused preload to continue.")); return 0; }
        return start(source, earth, saved.what(), saved.doneBefore(), file, cancelled -> saved.left());
    }

    private static int preloadStatus(CommandSourceStack source) {
        Run run = preloading;
        if (run != null) {
            String text = "Preloading " + run.progress() + ". " + Downloads.since(run.before) + ".";
            source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        PreloadPlan.Saved saved = PreloadPlan.load(preloadFile(source.getServer()));
        if (saved != null) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Paused: %s, %d of %d areas done. /earth preload continue carries on; /earth preload cancel forgets it.",
                    saved.what(), saved.doneBefore(), saved.total())).withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Nothing is preloading.").withStyle(ChatFormatting.GRAY), false);
        return 0;
    }

    private static int start(CommandSourceStack source, EarthGenerator earth, String what, Planner planner) {
        return start(source, earth, what, 0, preloadFile(source.getServer()), planner);
    }

    /**
     * Works out the areas, then fetches each one's near data, {@link #PRELOAD_PARALLEL} at a time in their order,
     * telling {@code source} how it goes and writing its progress now and then so it can be continued.
     */
    private static int start(CommandSourceStack source, EarthGenerator earth, String what, int doneBefore, Path file, Planner planner) {
        PreloadPlan.Saved replaced = doneBefore == 0 ? PreloadPlan.load(file) : null;
        if (replaced != null) source.sendSuccess(() -> Component.literal("This replaces the paused preload of " + replaced.what() + ".").withStyle(ChatFormatting.YELLOW), false);
        Run run = new Run(what, file, doneBefore);
        preloading = run;
        MinecraftServer server = source.getServer();
        java.util.function.Consumer<Component> tell = line -> server.execute(() -> source.sendSuccess(() -> line, true));
        Thread worker = new Thread(() -> {
            List<int[]> planned;
            try {
                planned = planner.plan(() -> !run.running.get());
            } catch (PreloadPlan.TooLarge large) {
                server.execute(() -> {
                    if (preloading == run) preloading = null;
                    source.sendFailure(Component.literal(String.format(Locale.ROOT, "%s is too large to preload at once (%d candidate areas). Name a smaller box or circle.",
                            what, large.squares)));
                });
                return;
            } catch (RuntimeException failure) {
                EarthPlatformMod.LOGGER.warn("Could not work out the areas to preload for {}", what, failure);
                server.execute(() -> {
                    if (preloading == run) preloading = null;
                    source.sendFailure(Component.literal("Could not work out the areas of " + what + ": " + failure.getMessage()));
                });
                return;
            }
            List<int[]> points = planned;
            if (points.size() > MOST_AREAS) {
                server.execute(() -> {
                    if (preloading == run) preloading = null;
                    source.sendFailure(Component.literal(String.format(Locale.ROOT, "%s needs %d areas; at most %d are fetched at once (-Dearthplatform.preload.mostAreas). Name a smaller area.",
                            what, points.size(), MOST_AREAS)));
                });
                return;
            }
            if (points.isEmpty() || !run.running.get()) {
                if (run.cancelled) PreloadPlan.forget(file);
                server.execute(() -> {
                    if (preloading == run) preloading = null;
                    source.sendFailure(Component.literal(points.isEmpty() ? "There is nothing to preload in " + what + "." : "Preload stopped."));
                });
                return;
            }
            run.fetched = new java.util.concurrent.atomic.AtomicIntegerArray(points.size());
            run.points = points;
            run.save();
            tell.accept(Component.literal(String.format(Locale.ROOT, "Preloading %s (%d areas, %d at a time). /earth preload status tells how it goes; /earth preload stop pauses it.",
                    what, run.total() - doneBefore, PRELOAD_PARALLEL)).withStyle(ChatFormatting.GREEN));
            AtomicInteger told = new AtomicInteger();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(PRELOAD_PARALLEL,
                    NamedThreads.numbered("Earth preload", Thread.MIN_PRIORITY));
            for (int index = 0; index < points.size(); index++) {
                int i = index;
                pool.execute(() -> {
                    if (!run.running.get()) return;
                    int[] point = points.get(i);
                    try {
                        earth.prepareNear(point[0], point[1]);
                        run.fetched.set(i, 1);
                    } catch (RuntimeException failure) { run.failed.incrementAndGet(); }
                    int finished = run.done.incrementAndGet(), tenth = finished * 10 / points.size(), last = told.get();
                    if (tenth > last && finished < points.size() && told.compareAndSet(last, tenth)) {
                        tell.accept(Component.literal("Preload " + run.progress() + ".").withStyle(ChatFormatting.GRAY));
                    }
                    if (finished == MEASURE_AFTER && !run.warnedCache) warnIfOverCache(run, tell);
                });
            }
            pool.shutdown();
            long lastSave = Util.getMillis();
            try {
                while (!pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    if (Util.getMillis() - lastSave > SAVE_EVERY_MILLIS) { run.save(); lastSave = Util.getMillis(); }
                }
            } catch (InterruptedException interrupted) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            boolean all = run.done.get() == points.size() && run.failed.get() == 0;
            if (all || run.cancelled) PreloadPlan.forget(file);
            else run.save();
            String fetched = Downloads.since(run.before);
            long seconds = (Util.getMillis() - run.started) / 1000;
            server.execute(() -> {
                if (preloading == run) preloading = null;
                String how = run.cancelled ? "Preload cancelled" : run.running.get() ? "Preload finished" : "Preload paused";
                String retry = run.failed.get() > 0 ? ", " + run.failed.get() + " could not be fetched (/earth preload continue tries them again)" : "";
                source.sendSuccess(() -> Component.literal(how + ": " + run.doneInAll() + " of " + run.total() + " areas, " + PreloadPlan.duration(seconds)
                        + " this time" + retry + ". " + fetched + ".").withStyle(ChatFormatting.GREEN), true);
            });
        }, "Earth preload");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
        source.sendSuccess(() -> Component.literal("Working out the areas of " + what + "...").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /**
     * Tells the operator, once, when the areas fetched so far say the whole preload takes more than the data cache
     * keeps: the cache would then throw away the first areas to make room for the last.
     */
    private static void warnIfOverCache(Run run, java.util.function.Consumer<Component> tell) {
        run.warnedCache = true;
        long limitGb = EarthConfig.CACHE_LIMIT_GB.get();
        if (limitGb <= 0) return;
        long bytes = 0;
        Map<String, long[]> now = Downloads.totals();
        for (Map.Entry<String, long[]> entry : now.entrySet()) bytes += entry.getValue()[1] - run.before.getOrDefault(entry.getKey(), new long[3])[1];
        double projectedGb = bytes / (double) Math.max(1, run.done.get()) * (run.total() - run.doneBefore) / 1e9;
        if (projectedGb > limitGb * 0.8) {
            tell.accept(Component.literal(String.format(Locale.ROOT, "This preload looks like about %.0f GB of data, more than the data cache keeps (%d GB): the first areas "
                    + "would be thrown away for the last. Raise cacheLimitGb in config/earthplatform-common.toml, or preload less.", projectedGb, limitGb))
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    private static long dist(int[] a, int[] b) { long dx = a[0] - b[0], dz = a[1] - b[1]; return dx * dx + dz * dz; }

    // Status -------------------------------------------------------------------------------------

    private static int status(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        double mspt = server.getAverageTickTimeNanos() / 1e6;
        long[] cubicTimes = Platform.INSTANCE.tickTimes(server, Cubic.LEVEL);
        double cubicMspt = cubicTimes == null || cubicTimes.length == 0 ? 0 : java.util.Arrays.stream(cubicTimes).average().orElse(0) / 1e6;
        double tps = Math.min(20, 1000 / Math.max(1e-3, mspt));
        line(source, String.format(Locale.ROOT, "TPS %.1f, %.1f ms a tick (Earth %.1f ms)", tps, mspt, cubicMspt), mspt > 50 ? ChatFormatting.RED : ChatFormatting.GREEN);
        ServerLevel level = server.getLevel(Cubic.LEVEL);
        if (level != null && CubicLevel.of(level) instanceof ServerCubeWorld cubes) line(source, "Earth: " + cubes.describe(), ChatFormatting.GRAY);
        Runtime runtime = Runtime.getRuntime();
        line(source, String.format(Locale.ROOT, "Memory: %d of %d MB used", (runtime.totalMemory() - runtime.freeMemory()) >> 20, runtime.maxMemory() >> 20), ChatFormatting.GRAY);
        long files = 0, bytes = 0;
        for (long[] tally : Downloads.totals().values()) { files += tally[0]; bytes += tally[1]; }
        line(source, String.format(Locale.ROOT, "Downloaded since start: %d files, %.1f MB%s", files, bytes / 1e6,
                EarthStatus.offlineMode() ? " (offline mode)" : ""), ChatFormatting.GRAY);
        EarthSpawns spawns = EarthSpawns.of(server);
        line(source, "Border: " + spawns.border().map(EarthSpawns.Border::label).orElse("none") + "; warps: " + spawns.warps().size()
                + "; preload: " + preloadLine(server), ChatFormatting.GRAY);
        if (EarthConfig.WEB_MAP.get()) line(source, "Web map on port " + EarthConfig.WEB_MAP_PORT.get() + ", address " + EarthConfig.WEB_MAP_ADDRESS.get().trim() + (web == null ? " (not running)" : ""), ChatFormatting.GRAY);
        // The cache size walks the whole folder, so it comes in after.
        Path cache = Loader.INSTANCE.gameDir().resolve("earthplatform").resolve("cache");
        CompletableFuture.supplyAsync(() -> size(cache), Util.ioPool()).thenAcceptAsync(size -> line(source,
                String.format(Locale.ROOT, "Data cache: %.2f GB (limit %d GB)", size / 1e9, EarthConfig.CACHE_LIMIT_GB.get()), ChatFormatting.GRAY), server);
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        line(source, players.size() + " players online (click one to go there):", ChatFormatting.GREEN);
        for (ServerPlayer player : players) {
            GeoPoint point = EarthRules.where(player);
            String name = player.getGameProfile().getName();
            if (point == null) { line(source, "  " + name + ": not on the Earth", ChatFormatting.GRAY); continue; }
            String country = EarthRules.country(player);
            String text = String.format(Locale.ROOT, "  %s: %.4f, %.4f%s, Y %d", name, point.latitude(), point.longitude(),
                    country.isEmpty() ? "" : " in " + country, player.getBlockY());
            String command = String.format(Locale.ROOT, "/earth send @s to %.6f %.6f", point.latitude(), point.longitude());
            Component entry = Component.literal(text).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
            source.sendSuccess(() -> entry, false);
        }
        return 1;
    }

    private static void line(CommandSourceStack source, String text, ChatFormatting color) {
        source.sendSuccess(() -> Component.literal(text).withStyle(color), false);
    }

    private static long size(Path folder) {
        if (!Files.isDirectory(folder)) return 0;
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> { try { return Files.size(file); } catch (IOException gone) { return 0; } }).sum();
        } catch (IOException | java.io.UncheckedIOException failure) {
            return 0;
        }
    }

    // The web map --------------------------------------------------------------------------------

    @Nullable private static com.sun.net.httpserver.HttpServer web;
    /** The port and address the running web map listens on. */
    private static int webPort;
    private static String webAddress = "";
    /** The last address setting that could not be read, so the warning about it is logged once. */
    @Nullable private static String badWebAddress;
    /** What the web map shows, made on the server thread each second and served from the web map's own thread. */
    private static volatile String snapshot = "{\"players\":[]}";

    public static void started(MinecraftServer server) { webMap(server); }

    /** Starts or stops the web map as the settings say, or moves it to a new port or address. */
    static void webMap(MinecraftServer minecraft) {
        int port = EarthConfig.WEB_MAP_PORT.get();
        String address = EarthConfig.WEB_MAP_ADDRESS.get().trim();
        boolean wanted = EarthConfig.WEB_MAP.get() && EarthWorlds.isEarth(minecraft);
        if (web != null && (!wanted || webPort != port || !webAddress.equals(address))) {
            web.stop(0);
            web = null;
            EarthPlatformMod.LOGGER.info("Earth web map stopped");
        }
        if (!wanted || web != null) return;
        InetAddress bind = webMapAddress(address);
        if (bind == null) {
            if (!address.equals(badWebAddress)) EarthPlatformMod.LOGGER.warn("The Earth web map is off: webMapAddress \"{}\" in earthplatform-common.toml"
                    + " is not an IP address. Use 0.0.0.0 for every network, 127.0.0.1 for this computer only, or one of the server's own IP addresses.", address);
            badWebAddress = address;
            return;
        }
        badWebAddress = null;
        try {
            var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(bind, port), 16);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                try (exchange) {
                    switch (path) {
                        case "/", "/index.html" -> send(exchange, "text/html; charset=utf-8", page());
                        case "/players.json" -> send(exchange, "application/json", snapshot.getBytes(StandardCharsets.UTF_8));
                        case "/earth.jpg" -> send(exchange, "image/jpeg", globe());
                        default -> { exchange.sendResponseHeaders(404, -1); }
                    }
                }
            });
            server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(NamedThreads.daemon("Earth web map")));
            server.start();
            web = server;
            webPort = port;
            webAddress = address;
            EarthPlatformMod.LOGGER.info("Earth web map at http://{}:{}/ (answering on {})",
                    bind.isAnyLocalAddress() ? "localhost" : InetAddresses.toUriString(bind), port, bind.isAnyLocalAddress() ? "every network" : bind.getHostAddress());
        } catch (IOException | RuntimeException | LinkageError failure) {
            EarthPlatformMod.LOGGER.warn("The Earth web map could not start on {} port {}: {}", bind.getHostAddress(), port, failure.toString());
        }
    }

    /**
     * The address the web map answers on, from the webMapAddress setting: an IP address (blank means every
     * network, as 0.0.0.0 does) or "localhost". Null for anything else; host names are not looked up.
     */
    @Nullable static InetAddress webMapAddress(String setting) {
        String address = setting.trim();
        if (address.isEmpty()) address = "0.0.0.0";
        if (address.equalsIgnoreCase("localhost")) return InetAddress.getLoopbackAddress();
        if (address.startsWith("[") && address.endsWith("]")) address = address.substring(1, address.length() - 1);
        try {
            return InetAddresses.forString(address);
        } catch (IllegalArgumentException notAnAddress) {
            return null;
        }
    }

    private static String preloadLine(MinecraftServer server) {
        Run run = preloading;
        if (run != null) return run.progress();
        PreloadPlan.Saved saved = PreloadPlan.load(preloadFile(server));
        return saved == null ? "idle" : "paused at " + saved.doneBefore() + " of " + saved.total() + " areas (/earth preload continue)";
    }

    /** The server is stopping: a running preload is paused, its progress kept for {@code /earth preload continue}. */
    public static void stopping() {
        Run run = preloading;
        if (run != null) {
            run.running.set(false);
            run.save();
        }
        preloading = null;
        if (web != null) web.stop(0);
        web = null;
        EarthRace.stopped();
        EarthGuess.stopped();
    }

    /** Refreshes what the web map shows. */
    static void second(MinecraftServer server) {
        if (web == null) return;
        StringBuilder json = new StringBuilder("{\"players\":[");
        boolean first = true;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            GeoPoint point = EarthRules.where(player);
            if (point == null) continue;
            if (!first) json.append(',');
            first = false;
            json.append(String.format(Locale.ROOT, "{\"name\":%s,\"lat\":%.5f,\"lon\":%.5f,\"country\":%s}",
                    quote(player.getGameProfile().getName()), point.latitude(), point.longitude(), quote(EarthRules.country(player))));
        }
        json.append("],\"warps\":[");
        first = true;
        for (Map.Entry<String, EarthSpawns.Spot> warp : EarthSpawns.of(server).warps().entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append(String.format(Locale.ROOT, "{\"name\":%s,\"lat\":%.5f,\"lon\":%.5f}", quote(warp.getKey()), warp.getValue().latitude(), warp.getValue().longitude()));
        }
        json.append(']');
        EarthGenerator earth = EarthCommands.earth(server.createCommandSourceStack());
        if (earth != null) {
            GeoPoint spawn = EarthCommands.spawnPoint(server.createCommandSourceStack(), earth);
            json.append(String.format(Locale.ROOT, ",\"spawn\":{\"lat\":%.5f,\"lon\":%.5f}", spawn.latitude(), spawn.longitude()));
        }
        json.append(String.format(Locale.ROOT, ",\"motd\":%s}", quote(server.getMotd())));
        snapshot = json.toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20 || c == '<' || c == '>' || c == '&') out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", type.startsWith("image") ? "max-age=86400" : "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
    }

    private static byte[] page;
    private static byte[] globe;

    private static synchronized byte[] page() throws IOException { return page != null ? page : (page = resource("/earthplatform/webmap.html")); }
    private static synchronized byte[] globe() throws IOException { return globe != null ? globe : (globe = resource("/assets/earthplatform/textures/globe/blue_marble.jpg")); }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = EarthAdmin.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException(name + " is missing from the jar");
            return in.readAllBytes();
        }
    }
}
