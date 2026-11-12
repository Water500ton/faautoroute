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
                  double touchdownSink, boolean flewPast, double maxClimb, double duration) {
    }

    private LandingSim() {
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (mode) {
            case "sweep" -> runSweep();
            case "check" -> runChecks();
            default -> System.out.println(report(LandingLaw.Config.DEFAULT));
        }
    }

    // ------------------------------------------------------------------ checks

    /** Behavioural assertions the law must satisfy regardless of tuning. */
    private static void runChecks() {
        int failures = 0;
        LandingLaw.Config config = LandingLaw.Config.DEFAULT;

        LandingLaw law = new LandingLaw(config);
        LandingLaw.Decision grounded = law.evaluate(new LandingLaw.State(0.0, 0.0, 0.0, 5.0, true));
        failures += expect("grounded -> ROLLOUT", grounded.action() == LandingLaw.Action.ROLLOUT);
        failures += expect("grounded does not release AP immediately", !grounded.releaseAutopilot());

        boolean released = false;
        for (int i = 0; i < config.releaseDelayTicks(); i++) {
            released = law.evaluate(new LandingLaw.State(0.0, 0.0, 0.0, 5.0, true)).releaseAutopilot();
        }
        failures += expect("grounded releases AP after the delay", released);

        law.reset();
        LandingLaw.Decision airborne = law.evaluate(new LandingLaw.State(50.0, -1.0, 9.0, 400.0, false));
        failures += expect("back in the air does not release AP", !airborne.releaseAutopilot());

        law.reset();
        LandingLaw.Decision farAway = law.evaluate(new LandingLaw.State(200.0, -1.0, 9.0, 4000.0, false));
        failures += expect("outside the landing zone -> NONE", farAway.action() == LandingLaw.Action.NONE);

        law.reset();
        LandingLaw.Decision pastAim = law.evaluate(new LandingLaw.State(12.0, -1.5, 10.0, 0.0, false));
        failures += expect("past the aim point still descends",
                pastAim.targetVerticalSpeed() <= -config.touchdownVs() + 1.0E-6);

        law.reset();
        boolean withinBand = true;
        boolean sinked = false;
        for (double agl = 70.0; agl > 0.4; agl -= 0.5) {
            LandingLaw.Decision decision = law.evaluate(new LandingLaw.State(agl, -1.0, 9.0, agl * 8.0, false));
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
        LandingLaw.Decision steep = law.evaluate(new LandingLaw.State(20.0, -8.0, 10.0, 40.0, false));
        failures += expect("steep entry is clamped, not followed",
                steep.targetVerticalSpeed() >= -config.maxSink() - 1.0E-6);

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
                Result oldLaw = simulate(plant, entry, false, config);
                Result newLaw = simulate(plant, entry, true, config);
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
        return report.toString();
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

    // ------------------------------------------------------------------- sweep

    private static void runSweep() {
        List<Candidate> candidates = new ArrayList<>();
        for (double settle : new double[]{0.3, 0.6}) {
            for (double touchdown : new double[]{0.3, 0.5}) {
                for (double minSink : new double[]{0.3, 0.5}) {
                    for (double maxSink : new double[]{3.0, 4.0}) {
                        for (double taper : new double[]{0.5, 0.6, 1.0}) {
                            LandingLaw.Config base = LandingLaw.Config.DEFAULT;
                            LandingLaw.Config config = new LandingLaw.Config(
                                    base.engageAgl(), base.engageDistance(), minSink, maxSink,
                                    touchdown, settle, base.speedLimit(), base.speedBrakeGain(),
                                    base.speedBrakeMinTime(), taper, base.releaseDelayTicks());
                            candidates.add(score(config));
                        }
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score));
        System.out.println("| settle | td | minSink | maxSink | taper | mean |err| | worst |err| | mean td m/s | mean sink | score |");
        System.out.println("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
        for (int i = 0; i < Math.min(12, candidates.size()); i++) {
            Candidate candidate = candidates.get(i);
            System.out.printf(Locale.ROOT, "| %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.2f | %.2f |%n",
                    candidate.config().settleAgl(), candidate.config().touchdownVs(),
                    candidate.config().minSink(), candidate.config().maxSink(),
                    candidate.config().taperTime(), candidate.meanError(), candidate.worstError(),
                    candidate.meanSpeed(), candidate.meanSink(), candidate.score());
        }
    }

    private record Candidate(LandingLaw.Config config, double score, double meanError,
                             double worstError, double meanSpeed, double meanSink) {
    }

    private static Candidate score(LandingLaw.Config config) {
        double sumAbs = 0.0;
        double worst = 0.0;
        double sumSpeed = 0.0;
        double sumSink = 0.0;
        int count = 0;
        for (Plant plant : Plant.ALL) {
            for (Entry entry : Entry.ALL) {
                Result result = simulate(plant, entry, true, config);
                double error = Math.abs(result.touchdownDistanceError());
                sumAbs += error;
                worst = Math.max(worst, error);
                sumSpeed += result.touchdownSpeed();
                sumSink += result.touchdownSink();
                count++;
            }
        }
        double meanError = sumAbs / count;
        double meanSpeed = sumSpeed / count;
        double meanSink = sumSink / count;
        // Distance error dominates; fast or hard touchdowns are penalised but not ignored.
        double scoreValue = meanError
                + 0.35 * worst
                + 1.5 * Math.max(0.0, meanSpeed - 9.0)
                + 3.0 * Math.max(0.0, meanSink - 1.5);
        return new Candidate(config, scoreValue, meanError, worst, meanSpeed, meanSink);
    }

    // -------------------------------------------------------------- simulation

    private static Result simulate(Plant plant, Entry entry, boolean useNewLaw, LandingLaw.Config config) {
        LandingLaw law = new LandingLaw(config);
        double agl = entry.agl();
        double distance = entry.distance();
        double hs = entry.horizontalSpeed();
        double vs = entry.verticalSpeed();

        double travelled = 0.0;
        double startAgl = agl;
        double maxAgl = agl;
        double time = 0.0;

        while (agl > 0.0 && time < 600.0) {
            double commanded = useNewLaw
                    ? newLawCommand(law, agl, distance, hs, vs)
                    : oldLawCommand(agl, distance, hs, vs);

            vs += (commanded - vs) * Math.min(1.0, DT / plant.tauVs());

            double dhs = -plant.dragC() * hs / 10.0;
            dhs += plant.liftFromSink() * Math.max(0.0, -vs) / 10.0;
            dhs -= plant.speedBleedPerClimb() * Math.max(0.0, vs);
            hs = Math.max(0.5, hs + dhs * DT);

            double step = hs * DT;
            travelled += step;
            distance = Math.max(0.0, distance - step);
            agl += vs * DT;
            maxAgl = Math.max(maxAgl, agl);
            time += DT;
        }

        double error = startAgl > 0 ? travelled - entry.distance() : 0.0;
        return new Result(error, hs, Math.abs(vs), error > 20.0, maxAgl - startAgl, time);
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

    private static double newLawCommand(LandingLaw law, double agl, double distance, double hs, double vs) {
        LandingLaw.Decision decision = law.evaluate(
                new LandingLaw.State(agl, vs, hs, distance, false));
        return switch (decision.action()) {
            case BRAKE, FLARE, ROLLOUT -> decision.targetVerticalSpeed();
            default -> nominalSink(agl, distance, hs);
        };
    }
}
