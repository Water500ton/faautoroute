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
 * <p>A short brake stage runs before that when the aircraft is still fast with
 * time to spare, trading speed for a small height gain instead of a hard
 * deceleration at low altitude.</p>
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
        /** Bleed horizontal speed before the final descent. */
        BRAKE,
        /** Follow the glide path to the aim point. */
        FLARE,
        /** Clear terrain standing above the aim elevation before the aim point. */
        TERRAIN,
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
            double speedLimit,
            double speedBrakeGain,
            double speedBrakeMinTime,
            double taperTime,
            double terrainMargin,
            double terrainClimb,
            long releaseDelayTicks
    ) {
        public static final Config DEFAULT = new Config(
                70.0,   // engageAgl: take over the vertical mode below this AGL
                420.0,  // engageDistance: ... and inside this distance
                0.3,    // minSink: shallowest glide-path sink the law will command
                4.0,    // maxSink: steepest glide-path sink the law will command
                0.5,    // touchdownVs: sink rate used in the last metres
                0.3,    // settleAgl: below this AGL the sink is pinned to touchdownVs
                13.0,   // speedLimit: above this speed the path is flown shallower
                0.12,   // speedBrakeGain: m/s of sink removed per m/s of excess speed
                8.0,    // speedBrakeMinTime: only slow down with this much time left
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

    private final Config config;
    private int groundTicks;

    public LandingLaw(Config config) {
        this.config = config;
    }

    public void reset() {
        groundTicks = 0;
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

        double sink = glidePathSink(agl, Math.max(0.0, state.heightAboveAim()), timeToAim);
        boolean slowing = horizontalSpeed > config.speedLimit() && timeToAim > config.speedBrakeMinTime();
        if (slowing) {
            sink = Math.max(0.0, sink - config.speedBrakeGain() * (horizontalSpeed - config.speedLimit()));
        }
        // Keep the last couple of seconds of descent gentle: never ask for a sink
        // rate that would cross the remaining height faster than taperTime allows.
        sink = Math.min(sink, Math.max(config.touchdownVs(), agl / config.taperTime()));
        double commanded = Math.max(sink, config.touchdownVs());

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
        double target = -commanded;
        return new Decision(slowing ? Action.BRAKE : Action.FLARE, target, false);
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
