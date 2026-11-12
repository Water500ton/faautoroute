package com.water500t.faautoroute;

/**
 * Landing-assist law for the last part of an auto-land.
 *
 * <p>This class is intentionally free of Minecraft imports so it can be
 * exercised by the offline simulation harness ({@code sim/LandingSim.java})
 * without a game client. {@link AutoRouteClient} only translates a
 * {@link Decision} into FlightAssistant vertical modes.</p>
 *
 * <p>Instead of "dim the sink rate at a fixed height", which makes the aircraft
 * float past the aim point, the law steers a <em>glide path to the aim point</em>:
 * the sink rate required to be at ground level when the remaining distance runs
 * out is {@code agl / (distance / horizontal speed)}. Commanding that sink makes
 * the last few hundred metres converge on the aim point whether the aircraft
 * arrives high, low, fast or slow. It is clamped between {@code minSink} and
 * {@code maxSink} so the law never dives nor stalls the glide, and replaced by a
 * fixed small {@code touchdownVs} in the last couple of metres.</p>
 *
 * <p>Terrain between the aircraft and the aim point is handled by a look-ahead
 * constraint: the caller samples the ground along the track (the caller has the
 * world; the law does not) and passes the steepest still-safe sink rate as
 * {@code terrainLimit}. Ground standing above the aim elevation therefore
 * shallows the descent until it is crossed, and terrain above the aircraft
 * triggers a climb instead of a controlled flight into a hillside.</p>
 *
 * <p>The stage is {@code ROLLOUT} after touchdown, which releases the autopilot
 * so the plan cannot keep pushing a grounded aircraft into the ground.</p>
 */
public final class LandingLaw {

    /** What the caller should do this tick. */
    public enum Action {
        /** Keep following the flight plan. */
        NONE,
        /** Follow the glide path to the aim point. */
        FLARE,
        /** Clear terrain standing above the aim elevation before the aim point. */
        TERRAIN,
        /** Approach is unrecoverable: climb away and re-plan. */
        GO_AROUND,
        /** On the ground: hold altitude target and release the autopilot. */
        ROLLOUT
    }

    /** Tunable constants; defaults match the shipped configuration. */
    public record Config(
            double engageAgl,
            double engageDistance,
            double minSink,
            double maxSink,
            double touchdownVs,
            double settleAgl,
            double speedTarget,
            double maxGlideRatio,
            double goAroundExcess,
            double goAroundAltitude,
            double goAroundMinDistance,
            double goAroundClimb,
            double taperTime,
            double terrainMargin,
            double terrainClimb,
            long releaseDelayTicks
    ) {
        public static final Config DEFAULT = new Config(
                70.0,   // engageAgl: take over the vertical mode below this AGL
                420.0,  // engageDistance: ... and inside this distance
                0.15,   // minSink: shallowest glide-path sink the law will command
                4.0,    // maxSink: steepest glide-path sink the law will command
                0.5,    // touchdownVs: sink rate used in the last metres
                0.3,    // settleAgl: below this AGL the sink is pinned to touchdownVs
                11.0,   // speedTarget: descent is capped at speedTarget / glideRatio
                9.0,    // maxGlideRatio: shallowest glide the airframe sustains
                6.0,    // goAroundExcess: altitude left at the aim that means "go around"
                60.0,   // goAroundAltitude: climb out to this height above the aim
                60.0,   // goAroundMinDistance: never go around closer than this
                1.2,    // goAroundClimb: vertical speed used to climb away (m/s)
                0.5,    // taperTime: only smooths the last ~2m, see glidePathSink
                4.0,    // terrainMargin: clearance kept over terrain sampled ahead
                1.0,    // terrainClimb: vertical speed used to climb over terrain
                20L     // releaseDelayTicks: ticks after touchdown before releasing AP
        );
    }

    /**
     * Sampled aircraft state for one tick.
     *
     * <p>{@code terrainLimit} is the steepest sink rate (m/s) the caller's
     * look-ahead terrain sampling allows, or {@link Double#POSITIVE_INFINITY}
     * when no terrain between the aircraft and the aim point constrains the
     * descent. Negative values mean terrain ahead is above the aircraft: the
     * law climbs.</p>
     */
    public record State(
            double agl,
            double heightAboveAim,
            double verticalSpeed,
            double horizontalSpeed,
            double distanceToAim,
            double terrainLimit,
            boolean onGround
    ) {
    }

    /** Outcome of {@link #evaluate}. */
    public record Decision(Action action, double targetVerticalSpeed, boolean releaseAutopilot) {
    }

    /** Smoothing factor for the online glide-ratio estimate. */
    private static final double GLIDE_RATIO_ALPHA = 0.05;
    /** Distance to the aim inside which the soft-touchdown floor applies. */
    private static final double FINAL_FLOOR_DISTANCE = 60.0;

    private final Config config;
    private final double initGlideRatio;
    private double glideRatioEstimate;
    private int groundTicks;

    public LandingLaw(Config config) {
        this.config = config;
        this.initGlideRatio = config.maxGlideRatio();
        this.glideRatioEstimate = initGlideRatio;
    }

    public void reset() {
        groundTicks = 0;
        glideRatioEstimate = initGlideRatio;
    }

    public Decision evaluate(State state) {
        if (state.onGround()) {
            groundTicks++;
            return new Decision(Action.ROLLOUT, 0.0, groundTicks >= config.releaseDelayTicks());
        }
        groundTicks = 0;

        double agl = Math.max(0.0, state.agl());
        double horizontalSpeed = Math.max(0.5, state.horizontalSpeed());
        double distance = Math.max(0.0, state.distanceToAim());
        double timeToAim = distance / horizontalSpeed;

        if (agl <= config.settleAgl()) {
            return new Decision(Action.FLARE, -config.touchdownVs(), false);
        }
        if (agl >= config.engageAgl() || distance >= config.engageDistance()) {
            return new Decision(Action.NONE, 0.0, false);
        }

        // Sustained gliding re-accelerates the airframe along its glide ratio:
        // descending at vs ends in a touchdown near vs * glideRatio. The ratio
        // is estimated online from the current speed and sink, so the cap works
        // for different airframes instead of assuming a fixed one.
        if (state.verticalSpeed() < -0.5) {
            double ratio = horizontalSpeed / -state.verticalSpeed();
            ratio = Math.max(3.0, Math.min(16.0, ratio));
            glideRatioEstimate += GLIDE_RATIO_ALPHA * (ratio - glideRatioEstimate);
        }
        double speedCap = config.speedTarget() / Math.max(3.0, glideRatioEstimate);

        // A hot or high approach cannot be fixed by diving: diving is exactly
        // what rebuilds speed. If the altitude left at the aim while descending
        // at the speed cap is still large, climb away and re-plan instead.
        double heightAboveAim = Math.max(0.0, state.heightAboveAim());
        double excessAtAim = heightAboveAim - speedCap * timeToAim;
        if (excessAtAim > config.goAroundExcess() && distance > config.goAroundMinDistance()) {
            return new Decision(Action.GO_AROUND, config.goAroundClimb(), false);
        }

        double sink = glidePathSink(agl, heightAboveAim, timeToAim);
        // Keep the last couple of seconds of descent gentle: never ask for a sink
        // rate that would cross the remaining height faster than taperTime allows.
        sink = Math.min(sink, Math.max(config.touchdownVs(), agl / config.taperTime()));
        // The soft-touchdown floor only applies near the aim point. On a long
        // shallow approach it would otherwise force a faster descent than the
        // glide path asks for and put the aircraft down early.
        double floor = distance < FINAL_FLOOR_DISTANCE ? config.touchdownVs() : 0.0;
        double commanded = Math.min(Math.max(sink, floor), speedCap);

        // Terrain look-ahead: ground standing above the aim elevation between
        // here and the aim point is ground the glide path would fly into. The
        // caller's terrainLimit caps the descent, and a negative limit means
        // the terrain is above the aircraft and must be climbed over.
        double terrainLimit = state.terrainLimit();
        if (terrainLimit < 0.0) {
            return new Decision(Action.TERRAIN, config.terrainClimb(), false);
        }
        if (terrainLimit < commanded) {
            // Shallower than the soft-touchdown floor is allowed here: staying
            // above terrain matters more than a gentle touchdown.
            return new Decision(Action.TERRAIN, -Math.max(0.0, terrainLimit), false);
        }
        return new Decision(Action.FLARE, -commanded, false);
    }

    /**
     * Altitude one terrain sample constrains the descent to, given the ground
     * height there and the aim point's elevation.
     *
     * <p>Ground standing clearly above the aim elevation is an obstacle: the
     * aircraft keeps {@code terrainMargin} over it. Ground that only slopes up
     * to the aim is the approach surface itself and constrains nothing beyond
     * the aim elevation, so smooth hillside landings are not floated.</p>
     */
    public static double terrainConstraint(double groundHeight, double aimElevation, Config config) {
        double excess = groundHeight - aimElevation;
        if (excess > config.terrainMargin()) {
            return groundHeight + config.terrainMargin();
        }
        return aimElevation + Math.max(0.0, excess);
    }

    /**
     * Sink rate that puts the aircraft at the aim elevation exactly at the aim
     * point.
     *
     * <p>The path is measured against the aim point's own elevation rather than
     * the ground under the aircraft, so a valley or a hillside between the
     * aircraft and the target does not tilt the whole approach: the aircraft
     * still converges on the same point in space.</p>
     */
    private double glidePathSink(double agl, double heightAboveAim, double timeToAim) {
        double height = heightAboveAim > 0.0 ? heightAboveAim : agl;
        double required = height / Math.max(0.3, timeToAim);
        return Math.min(config.maxSink(), Math.max(config.minSink(), required));
    }
}
