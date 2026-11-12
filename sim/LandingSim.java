package com.water500t.faautoroute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Offline landing testbed. No Minecraft classes are touched, so this runs with
 * plain {@code javac}/{@code java}.
 *
 * <p>It is not a replica of vanilla elytra physics. It is a deliberately small
 * glide model whose coefficients can be varied, used to answer two questions
 * that do not depend on exact physics:</p>
 * <ol>
 *   <li>does the landing law put the aircraft near the aim point, and</li>
 *   <li>is that true across a family of aircraft behaviours and entry states?</li>
 * </ol>
 *
 * <p>Run the comparison report:</p>
 * <pre>
 *   javac -d /tmp/sim src/main/java/com/water500t/faautoroute/LandingLaw.java sim/LandingSim.java
 *   java -cp /tmp/sim com.water500t.faautoroute.LandingSim
 * </pre>
 * <p>Run the parameter search instead by passing {@code sweep}:</p>
 * <pre>
 *   java -cp /tmp/sim com.water500t.faautoroute.LandingSim sweep
 * </pre>
 */
public final class LandingSim {

    private static final double DT = 0.05;
    private static final double TERRAIN_SAMPLE_STEP = 8.0;
    private static final double TERRAIN_SAMPLE_RANGE = 320.0;

    /** Glide-model coefficients; every plant is a plausible elytra behaviour. */
    record Plant(String name, double tauVs, double dragC, double liftFromSink, double speedBleedPerClimb) {
        static final List<Plant> ALL = List.of(
                new Plant("baseline", 0.8, 0.20, 1.82, 3.0),
                new Plant("sluggish", 1.4, 0.24, 1.55, 3.6),
                new Plant("responsive", 0.5, 0.17, 2.10, 2.4),
                new Plant("draggy", 0.8, 0.26, 2.30, 3.2)
        );
    }

    /** Where the aircraft is when the landing logic first gets a say. */
    record Entry(String name, double agl, double distance, double horizontalSpeed, double verticalSpeed) {
        static final List<Entry> ALL = List.of(
                // Nominal hand-off from the plan: ~8 m/s on a ~1:8 path.
                new Entry("plan-nominal", 55.0, 440.0, 8.0, -1.0),
                new Entry("plan-fast", 55.0, 440.0, 11.0, -1.4),
                new Entry("plan-slow", 55.0, 440.0, 6.0, -0.8),
                new Entry("high", 70.0, 380.0, 9.0, -1.2),
                new Entry("low", 25.0, 220.0, 8.0, -1.0),
                new Entry("fast-hot", 60.0, 380.0, 20.0, -2.5),
                new Entry("steep", 40.0, 150.0, 15.0, -4.0),
                new Entry("very-low", 10.0, 70.0, 9.0, -1.8)
        );
    }

    record Result(double touchdownDistanceError, double touchdownSpeed,
                  double touchdownSink, boolean flewPast, double maxClimb, double duration,
                  int goArounds) {
    }

    /**
     * Ground profile along the approach: {@code rise(t)} returns how much the
     * ground sits above the entry ground level at fraction {@code t} of the
     * distance to the aim point (0 = start, 1 = aim).
     */
    record Terrain(String name, java.util.function.DoubleUnaryOperator rise) {
        double ground(double t) {
            return rise.applyAsDouble(Math.max(0.0, Math.min(1.0, t)));
        }

        static final List<Terrain> ALL = List.of(
                new Terrain("flat", t -> 0.0),
                new Terrain("rise10", t -> 10.0 * t),
                new Terrain("drop10", t -> -10.0 * t),
                new Terrain("terrace", t -> t < 0.9 ? 0.0 : 6.0 * (t - 0.9) / 0.1),
                new Terrain("bump", t -> 8.0 * Math.exp(-Math.pow((t - 0.6) / 0.12, 2.0))),
                new Terrain("cliffdown", t -> t < 0.95 ? 0.0 : -8.0)
        );
    }

    private static final Terrain FLAT = Terrain.ALL.get(0);

    private LandingSim() {
    }

    // ---------------------------------------------------------------- success

    /** Distance inside which a touchdown still counts as "at the aim point". */
    private static final double SUCCESS_DISTANCE = 50.0;
    /** Touchdown horizontal speed a landing must not exceed to be survivable. */
    private static final double SUCCESS_SPEED = 12.0;
    /**
     * Touchdown sink rate a landing must not exceed. An elytra touchdown does
     * not take fall damage (the glide ends at ground level), so this guards
     * bounce and control loss rather than health.
     */
    private static final double SUCCESS_SINK = 3.0;


    static boolean isSuccessful(Result result) {
        return Math.abs(result.touchdownDistanceError()) <= SUCCESS_DISTANCE
                && result.touchdownSpeed() <= SUCCESS_SPEED
                && result.touchdownSink() <= SUCCESS_SINK;
    }

    /** One landing attempt: everything the fleet matrix is aggregated over. */
    record Scenario(Plant plant, Entry entry, Terrain terrain) {
        static final List<Scenario> ALL = new ArrayList<>();
        static {
            for (Plant plant : Plant.ALL) {
                for (Entry entry : Entry.ALL) {
                    for (Terrain terrain : Terrain.ALL) {
                        ALL.add(new Scenario(plant, entry, terrain));
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (mode) {
            case "sweep" -> runSweep();
            case "curve" -> runCurve();
            case "success" -> runSuccessReport();
            case "check" -> runChecks();
            default -> System.out.println(report(LandingLaw.Config.DEFAULT));
        }
    }

    /** Fleet-wide success rate with a per-scenario failure list. */
    private static void runSuccessReport() {
        LandingLaw.Config config = LandingLaw.Config.DEFAULT;
        List<Result> results = Scenario.ALL.parallelStream()
                .map(scenario -> simulate(scenario.plant(), scenario.entry(), true, config, scenario.terrain()))
                .toList();
        int goArounds = results.stream().mapToInt(Result::goArounds).sum();
        List<Scenario> failed = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            if (!isSuccessful(results.get(i))) {
                failed.add(Scenario.ALL.get(i));
            }
        }
        System.out.printf(Locale.ROOT,
                "success criteria: |d-error| <= %.0f m, td speed <= %.0f m/s, td sink <= %.1f m/s%n",
                SUCCESS_DISTANCE, SUCCESS_SPEED, SUCCESS_SINK);
        System.out.printf(Locale.ROOT, "success rate: %d/%d = %.1f%% (go-arounds used: %d)%n%n",
                Scenario.ALL.size() - failed.size(), Scenario.ALL.size(),
                100.0 * (Scenario.ALL.size() - failed.size()) / Scenario.ALL.size(),
                goArounds);
        if (!failed.isEmpty()) {
            System.out.println("failures (plant / entry / terrain):");
            for (Scenario scenario : failed) {
                Result result = simulate(scenario.plant(), scenario.entry(), true, config, scenario.terrain());
                System.out.printf(Locale.ROOT, "  %-11s %-13s %-10s err %+.1f m, td %s%.1f m/s, sink %.2f m/s%n",
                        scenario.plant().name(), scenario.entry().name(), scenario.terrain().name(),
                        result.touchdownDistanceError(),
                        result.touchdownSpeed() > SUCCESS_SPEED ? "SLOW " : "     ",
                        result.touchdownSpeed(), result.touchdownSink());
            }
        }
    }

    // ------------------------------------------------------------------ checks

    /** Behavioural assertions the law must satisfy regardless of tuning. */
    private static void runChecks() {
        int failures = 0;
        LandingLaw.Config config = LandingLaw.Config.DEFAULT;

        LandingLaw law = new LandingLaw(config);
        LandingLaw.Decision grounded = law.evaluate(new LandingLaw.State(0.0, 0.0, 0.0, 0.0, 5.0, Double.POSITIVE_INFINITY, true));
        failures += expect("grounded -> ROLLOUT", grounded.action() == LandingLaw.Action.ROLLOUT);
        failures += expect("grounded does not release AP immediately", !grounded.releaseAutopilot());

        boolean released = false;
        for (int i = 0; i < config.releaseDelayTicks(); i++) {
            released = law.evaluate(new LandingLaw.State(0.0, 0.0, 0.0, 0.0, 5.0, Double.POSITIVE_INFINITY, true)).releaseAutopilot();
        }
        failures += expect("grounded releases AP after the delay", released);

        law.reset();
        LandingLaw.Decision airborne = law.evaluate(new LandingLaw.State(50.0, 50.0, -1.0, 9.0, 400.0, Double.POSITIVE_INFINITY, false));
        failures += expect("back in the air does not release AP", !airborne.releaseAutopilot());

        law.reset();
        LandingLaw.Decision farAway = law.evaluate(new LandingLaw.State(200.0, 200.0, -1.0, 9.0, 4000.0, Double.POSITIVE_INFINITY, false));
        failures += expect("outside the landing zone -> NONE", farAway.action() == LandingLaw.Action.NONE);

        law.reset();
        LandingLaw.Decision pastAim = law.evaluate(new LandingLaw.State(12.0, 12.0, -1.5, 10.0, 0.0, Double.POSITIVE_INFINITY, false));
        failures += expect("past the aim point still descends",
                pastAim.targetVerticalSpeed() <= -config.touchdownVs() + 1.0E-6);

        law.reset();
        boolean withinBand = true;
        boolean sinked = false;
        for (double agl = 70.0; agl > 0.4; agl -= 0.5) {
            LandingLaw.Decision decision = law.evaluate(new LandingLaw.State(agl, agl, -1.0, 9.0, agl * 8.0, Double.POSITIVE_INFINITY, false));
            if (decision.action() == LandingLaw.Action.NONE) {
                continue;
            }
            double target = decision.targetVerticalSpeed();
            withinBand &= target <= -config.touchdownVs() + 1.0E-6;
            withinBand &= target >= -config.maxSink() - 1.0E-6;
            if (decision.action() == LandingLaw.Action.FLARE) {
                sinked = true;
            }
        }
        failures += expect("glide path stays inside [touchdownVs, maxSink]", withinBand);
        failures += expect("glide path actually commands a descent", sinked);

        law.reset();
        LandingLaw.Decision steep = law.evaluate(new LandingLaw.State(20.0, 20.0, -8.0, 10.0, 40.0, Double.POSITIVE_INFINITY, false));
        failures += expect("steep entry is clamped, not followed",
                steep.targetVerticalSpeed() >= -config.maxSink() - 1.0E-6);

        law.reset();
        LandingLaw.Decision climb = law.evaluate(new LandingLaw.State(
                30.0, 30.0, -1.0, 9.0, 200.0, -0.5, false));
        failures += expect("terrain above the aircraft -> TERRAIN climb",
                climb.action() == LandingLaw.Action.TERRAIN && climb.targetVerticalSpeed() > 0.0);

        law.reset();
        LandingLaw.Decision capped = law.evaluate(new LandingLaw.State(
                30.0, 30.0, -1.0, 9.0, 200.0, 0.7, false));
        failures += expect("terrain limit caps the descent, not the soft floor",
                capped.action() == LandingLaw.Action.TERRAIN
                        && Math.abs(capped.targetVerticalSpeed() + 0.7) < 1.0E-6);

        law.reset();
        LandingLaw.Decision unconstrained = law.evaluate(new LandingLaw.State(
                30.0, 30.0, -1.0, 9.0, 200.0, 5.0, false));
        failures += expect("distant terrain does not interfere",
                unconstrained.action() == LandingLaw.Action.FLARE);

        System.out.println(failures == 0 ? "checks passed" : failures + " check(s) failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static int expect(String description, boolean condition) {
        System.out.println((condition ? "  ok   " : "  FAIL ") + description);
        return condition ? 0 : 1;
    }

    // ------------------------------------------------------------------ report

    private static String report(LandingLaw.Config config) {
        StringBuilder report = new StringBuilder();
        report.append("# Landing law simulation\n\n");
        report.append(String.format(Locale.ROOT, "Model dt=%.2fs.\n", DT));
        report.append("Distance error is along track: negative = landed short, ")
                .append("positive = overflew the aim point.\n\n");

        List<Double> oldErrors = new ArrayList<>();
        List<Double> newErrors = new ArrayList<>();
        List<Double> oldSpeeds = new ArrayList<>();
        List<Double> newSpeeds = new ArrayList<>();
        int oldOverfly = 0;
        int newOverfly = 0;

        for (Plant plant : Plant.ALL) {
            report.append("## Plant: ").append(plant.name()).append("\n\n");
            report.append("| entry | law | d-error m | td speed m/s | td sink m/s | overfly | climb m | s |\n");
            report.append("| --- | --- | ---: | ---: | ---: | :---: | ---: | ---: |\n");
            for (Entry entry : Entry.ALL) {
                Result oldLaw = simulate(plant, entry, false, config, FLAT);
                Result newLaw = simulate(plant, entry, true, config, FLAT);
                oldErrors.add(oldLaw.touchdownDistanceError());
                newErrors.add(newLaw.touchdownDistanceError());
                oldSpeeds.add(oldLaw.touchdownSpeed());
                newSpeeds.add(newLaw.touchdownSpeed());
                if (oldLaw.flewPast()) {
                    oldOverfly++;
                }
                if (newLaw.flewPast()) {
                    newOverfly++;
                }
                report.append(row(entry.name(), "1.1.7", oldLaw));
                report.append(row(entry.name(), "new", newLaw));
            }
            report.append('\n');
        }

        report.append(summary("1.1.7", oldErrors, oldSpeeds, oldOverfly));
        report.append(summary("new", newErrors, newSpeeds, newOverfly));
        report.append(terrainSection(config));
        return report.toString();
    }

    /** How the law copes when the ground under the approach is not flat. */
    private static String terrainSection(LandingLaw.Config config) {
        StringBuilder section = new StringBuilder();
        Plant plant = Plant.ALL.get(0);

        section.append("## Terrain sensitivity\n\n");
        section.append("Ground profile relative to the entry ground level, as a fraction of ")
                .append("the distance to the aim. Plant: baseline.\n\n");
        section.append("Cells show touchdown distance error / touchdown speed (m, m/s).\n\n");
        section.append("| terrain | ").append(String.join(" | ", Entry.ALL.stream().map(Entry::name).toList()))
                .append(" |\n");
        section.append("| --- |").append(" ---: |".repeat(Entry.ALL.size())).append('\n');
        for (Terrain terrain : Terrain.ALL) {
            section.append("| ").append(terrain.name()).append(" |");
            for (Entry entry : Entry.ALL) {
                Result result = simulate(plant, entry, true, config, terrain);
                section.append(String.format(Locale.ROOT, " %+.1f/%.1f |",
                        result.touchdownDistanceError(), result.touchdownSpeed()));
            }
            section.append('\n');
        }
        section.append('\n');

        section.append("| terrain | law | mean abs err m | worst m | mean td speed |\n");
        section.append("| --- | --- | ---: | ---: | ---: |\n");
        for (Terrain terrain : Terrain.ALL) {
            for (boolean newLaw : new boolean[]{false, true}) {
                double sum = 0.0;
                double worst = 0.0;
                double speeds = 0.0;
                for (Entry entry : Entry.ALL) {
                    Result result = simulate(plant, entry, newLaw, config, terrain);
                    double error = Math.abs(result.touchdownDistanceError());
                    sum += error;
                    worst = Math.max(worst, error);
                    speeds += result.touchdownSpeed();
                }
                section.append(String.format(Locale.ROOT, "| %s | %s | %.1f | %.1f | %.1f |%n",
                        terrain.name(), newLaw ? "1.1.8" : "1.1.7",
                        sum / Entry.ALL.size(), worst, speeds / Entry.ALL.size()));
            }
        }
        section.append('\n');
        return section.toString();
    }

    private static String row(String entry, String law, Result result) {
        return String.format(Locale.ROOT,
                "| %s | %s | %+.1f | %.1f | %.2f | %s | %.1f | %.1f |%n",
                entry, law, result.touchdownDistanceError(), result.touchdownSpeed(),
                result.touchdownSink(), result.flewPast() ? "yes" : "",
                result.maxClimb(), result.duration());
    }

    private static String summary(String law, List<Double> errors, List<Double> speeds, int overfly) {
        double meanAbs = errors.stream().mapToDouble(Math::abs).average().orElse(0.0);
        double maxAbs = errors.stream().mapToDouble(Math::abs).max().orElse(0.0);
        double meanSpeed = speeds.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double worstSpeed = speeds.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        return String.format(Locale.ROOT,
                "**%s**: mean |d-error| %.1f m, worst %.1f m, overfly %d/%d, "
                        + "mean td speed %.1f m/s, worst %.1f m/s%n%n",
                law, meanAbs, maxAbs, overfly, errors.size(), meanSpeed, worstSpeed);
    }

    // ------------------------------------------------------------------- curve

    /**
     * Success rate vs go-around count as the two knobs that trade them against
     * each other are swept. Every other constant stays at its default.
     */
    private static void runCurve() {
        System.out.println("| speedTarget | goAroundExcess | success% | go-arounds | mean abs err m | worst m | mean td m/s |");
        System.out.println("| ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
        LandingLaw.Config base = LandingLaw.Config.DEFAULT;
        for (double speedTarget : new double[]{10.5, 11.0, 11.5, 12.0, 12.5, 13.0}) {
            for (double goAroundExcess : new double[]{3.0, 4.0, 6.0, 8.0, 10.0}) {
                LandingLaw.Config config = new LandingLaw.Config(
                        base.engageAgl(), base.engageDistance(), base.minSink(), base.maxSink(),
                        base.touchdownVs(), base.settleAgl(), speedTarget, base.maxGlideRatio(),
                        goAroundExcess, base.goAroundAltitude(), base.goAroundMinDistance(),
                        base.goAroundClimb(), base.taperTime(), base.terrainMargin(),
                        base.terrainClimb(), base.releaseDelayTicks());
                List<Result> results = Scenario.ALL.parallelStream()
                        .map(scenario -> simulate(scenario.plant(), scenario.entry(), true, config, scenario.terrain()))
                        .toList();
                int passed = 0;
                double sumAbs = 0.0;
                double worst = 0.0;
                double sumSpeed = 0.0;
                int goArounds = 0;
                for (Result result : results) {
                    if (isSuccessful(result)) {
                        passed++;
                    }
                    double error = Math.abs(result.touchdownDistanceError());
                    sumAbs += error;
                    worst = Math.max(worst, error);
                    sumSpeed += result.touchdownSpeed();
                    goArounds += result.goArounds();
                }
                System.out.printf(Locale.ROOT, "| %.1f | %.1f | %.1f | %d | %.1f | %.1f | %.1f |%n",
                        speedTarget, goAroundExcess, 100.0 * passed / Scenario.ALL.size(),
                        goArounds, sumAbs / Scenario.ALL.size(), worst,
                        sumSpeed / Scenario.ALL.size());
            }
        }
    }

    // ------------------------------------------------------------------- sweep

    private static void runSweep() {
        List<Candidate> candidates = new ArrayList<>();
        for (double settle : new double[]{0.3, 0.6}) {
            for (double touchdown : new double[]{0.3, 0.5}) {
                for (double minSink : new double[]{0.3, 0.5}) {
                    for (double maxSink : new double[]{3.0, 4.0}) {
                        for (double taper : new double[]{0.5, 0.6, 1.0}) {
                            for (double speedTarget : new double[]{10.5, 11.0, 11.5, 12.0, 12.5, 13.0}) {
                                for (double goAroundExcess : new double[]{4.0, 6.0, 8.0, 10.0}) {
                            LandingLaw.Config base = LandingLaw.Config.DEFAULT;
                            LandingLaw.Config config = new LandingLaw.Config(
                                    base.engageAgl(), base.engageDistance(), minSink, maxSink,
                                    touchdown, settle, speedTarget, base.maxGlideRatio(),
                                    goAroundExcess, base.goAroundAltitude(),
                                    base.goAroundMinDistance(), base.goAroundClimb(),
                                    taper, base.terrainMargin(), base.terrainClimb(),
                                    base.releaseDelayTicks());
                            candidates.add(score(config));
                            }
                            }
                        }
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score));
        System.out.println("| settle | td | minSink | maxSink | taper | speedTarget | success% | GAs | mean |err| | worst |err| | mean td m/s | mean sink | score |");
        System.out.println("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
        for (int i = 0; i < Math.min(12, candidates.size()); i++) {
            Candidate candidate = candidates.get(i);
                System.out.printf(Locale.ROOT, "| %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %d | %.1f | %.1f | %.1f | %.2f | %.2f |%n",
                    candidate.config().settleAgl(), candidate.config().touchdownVs(),
                    candidate.config().minSink(), candidate.config().maxSink(),
                    candidate.config().taperTime(), candidate.config().speedTarget(),
                    candidate.successRate(), candidate.goArounds(),
                    candidate.meanError(), candidate.worstError(),
                    candidate.meanSpeed(), candidate.meanSink(), candidate.score());
        }
    }

    private record Candidate(LandingLaw.Config config, double score, double meanError,
                             double worstError, double meanSpeed, double meanSink,
                             double successRate, int goArounds) {
    }

    private static Candidate score(LandingLaw.Config config) {
        double sumAbs = 0.0;
        double worst = 0.0;
        double sumSpeed = 0.0;
        double sumSink = 0.0;
        int count = 0;
        int passed = 0;
        List<Result> results = Scenario.ALL.parallelStream()
                .map(scenario -> simulate(scenario.plant(), scenario.entry(), true, config, scenario.terrain()))
                .toList();
        for (Result result : results) {
            double error = Math.abs(result.touchdownDistanceError());
            sumAbs += error;
            worst = Math.max(worst, error);
            sumSpeed += result.touchdownSpeed();
            sumSink += result.touchdownSink();
            if (isSuccessful(result)) {
                passed++;
            }
            count++;
        }
        int gaTotal = results.stream().mapToInt(Result::goArounds).sum();
        double meanError = sumAbs / count;
        double meanSpeed = sumSpeed / count;
        double meanSink = sumSink / count;
        double successRate = 100.0 * passed / count;
        // Distance error dominates; fast or hard touchdowns are penalised but not
        // ignored. Go-arounds are counted so a lazy always-go-around tuning is
        // visible in the ranking.
        double scoreValue = meanError
                + 0.35 * worst
                + 1.5 * Math.max(0.0, meanSpeed - 9.0)
                + 3.0 * Math.max(0.0, meanSink - 1.5)
                + 2.0 * Math.max(0.0, 90.0 - successRate)
                + 0.15 * gaTotal;
        return new Candidate(config, scoreValue, meanError, worst, meanSpeed, meanSink,
                successRate, gaTotal);
    }

    // -------------------------------------------------------------- simulation

    private static Result simulate(Plant plant, Entry entry, boolean useNewLaw,
                                   LandingLaw.Config config, Terrain terrain) {
        LandingLaw law = new LandingLaw(config);
        double startDistance = entry.distance();
        double height = entry.agl();
        double distance = startDistance;
        double hs = entry.horizontalSpeed();
        double vs = entry.verticalSpeed();

        double travelled = 0.0;
        double maxHeight = height;
        double time = 0.0;
        double agl = height - terrain.ground(0.0);
        int goAroundsLeft = 2;
        boolean climbingOut = false;
        int goArounds = 0;

        while (agl > 0.0 && time < 600.0) {
            double aimElevation = terrain.ground(1.0);
            double heightAboveAim = height - aimElevation;
            double terrainLimit = Double.POSITIVE_INFINITY;
            for (double ahead = TERRAIN_SAMPLE_STEP;
                    ahead < distance && ahead <= TERRAIN_SAMPLE_RANGE;
                    ahead += ahead < 128.0 ? TERRAIN_SAMPLE_STEP : TERRAIN_SAMPLE_STEP * 4) {
                double groundAhead = terrain.ground((travelled + ahead) / startDistance);
                double constraint = LandingLaw.terrainConstraint(groundAhead, aimElevation, config);
                terrainLimit = Math.min(terrainLimit, (height - constraint) * hs / ahead);
            }
            double commanded;
            if (climbingOut) {
                commanded = config.goAroundClimb();
                if (heightAboveAim >= config.goAroundAltitude() && hs <= 11.0 && goAroundsLeft > 0) {
                    // The mod re-plans from the current position. A re-planned
                    // approach gives itself enough room for its own glide ratio;
                    // model that as a fresh approach over the same terrain with
                    // the distance the airframe needs.
                    goAroundsLeft--;
                    climbingOut = false;
                    // The re-planned approach uses the planner's ~1:8 final
                    // approach geometry plus its approach-fix margin, so the
                    // retry flies a slope the airframe can actually hold.
                    startDistance = Math.max(440.0, (heightAboveAim + 10.0) * 8.0 + 120.0);
                    distance = startDistance;
                    height = aimElevation + 55.0;
                    hs = 8.0;
                    vs = -1.0;
                    travelled = 0.0;
                    maxHeight = height;
                    law.reset();
                    agl = height - terrain.ground(0.0);
                    continue;
                }
                if (heightAboveAim >= config.goAroundAltitude() && goAroundsLeft <= 0) {
                    break; // cannot recover; scored as a miss
                }
            } else {
                LandingLaw.Decision decision = law.evaluate(
                        new LandingLaw.State(agl, heightAboveAim, vs, hs, distance, terrainLimit, false));
                // NONE means "follow the plan", which is the shared nominal
                // guidance, not "fly level".
                commanded = decision.action() == LandingLaw.Action.NONE
                        ? nominalSink(agl, distance, hs)
                        : decision.targetVerticalSpeed();
                if (decision.action() == LandingLaw.Action.GO_AROUND) {
                    climbingOut = true;
                    goArounds++;
                }
                if (!useNewLaw) {
                    commanded = oldLawCommand(agl, distance, hs, vs);
                }
            }

            vs += (commanded - vs) * Math.min(1.0, DT / plant.tauVs());

            double dhs = -plant.dragC() * hs / 10.0;
            dhs += plant.liftFromSink() * Math.max(0.0, -vs) / 10.0;
            dhs -= plant.speedBleedPerClimb() * Math.max(0.0, vs);
            hs = Math.max(0.5, hs + dhs * DT);

            double step = hs * DT;
            travelled += step;
            distance = Math.max(0.0, distance - step);
            height += vs * DT;
            agl = height - terrain.ground(travelled / startDistance);
            maxHeight = Math.max(maxHeight, height);
            time += DT;
        }

        double error = travelled - startDistance;
        return new Result(error, hs, Math.abs(vs), error > 20.0, maxHeight - entry.agl(), time,
                goArounds);
    }

    /** Re-implementation of the 1.1.7 heuristics, for a like-for-like comparison. */
    private static double oldLawCommand(double agl, double distance, double hs, double vs) {
        double nominal = nominalSink(agl, distance, hs);
        if (distance < 120.0 && agl < 25.0 && hs > 12.0) {
            return 1.0;
        }
        if (distance < 80.0 && agl < 18.0 && vs < 0.0) {
            return -1.5;
        }
        return nominal;
    }

    /**
     * The vertical guidance both laws fall back on: FlightAssistant descending
     * along the plan to the aim point. Sharing it keeps the comparison about the
     * flare/brake overlay alone.
     */
    private static double nominalSink(double agl, double distance, double hs) {
        if (distance < 5.0) {
            return -2.5;
        }
        double timeToAim = distance / Math.max(0.5, hs);
        return -Math.min(4.0, Math.max(0.5, agl / Math.max(0.3, timeToAim)));
    }

}
