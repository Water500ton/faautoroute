# FlightAssistant AutoRoute 1.1.8 — Changelog

The landing system has been rewritten. Instead of threshold-style protection
("flare below this height, brake below that distance"), the final approach is
now continuously guided: the descent rate is computed from the landing-point
geometry, speed is controlled from energy, terrain is avoided, and an
unrecoverable approach goes around.

## Landing guidance

- **Glide path to the aim point** — the sink target is `height above the aim
  point ÷ time remaining`, so the aircraft converges on the target instead of
  floating past it (simulated mean miss on flat ground: 70 m → 6 m).
- The path is referenced to the **aim point's own elevation**, not the ground
  under the aircraft — valleys and slopes no longer tilt the whole approach.
- The sink rate decays with height and settles at -0.5 m/s in the last ~2 m
  for a soft touchdown.
- **Rollout** — after touchdown the vertical target goes to zero and the
  autopilot is released about a second later, instead of pushing a grounded
  aircraft into the ground.

## Complex terrain

- **Look-ahead terrain avoidance** — the ground is sampled every 8–32 blocks
  along the approach. Terrain standing above the landing point caps the descent
  rate to keep clearance; terrain above the aircraft triggers a climb (shown as
  "Terrain" on the HUD). Simulated miss on a cliff-edge landing: -54 m → +5 m.
- New planning warning when the final approach crosses ground more than 8
  blocks above the aim point.

## Touchdown speed and go-arounds

- **Speed cap** — with an elytra, a steeper descent means a faster touchdown.
  The descent rate is capped at `speedTarget / glide ratio`, with the glide
  ratio estimated online, so touchdown speed is controlled at the source.
- **Automatic go-around** — if the approach cannot be flown within the speed
  cap (still too high at the aim point), the mod climbs away and re-plans
  automatically, up to 3 go-arounds per plan (announced in chat as "Go around
  x/3").
- The go-around frequency is tunable: `speedTarget` and `goAroundExcess` trade
  success rate against go-around count.

## Verification and tooling

- The landing logic lives in `LandingLaw`, free of Minecraft imports, with an
  offline simulation harness (`sim/`): comparison reports, 12 behavioural
  checks, parameter sweeps, and a success-vs-go-around curve.
- Simulated success rate **48.4% → 98.4%** (4 airframes × 8 approach hand-offs
  × 6 terrain profiles = 192 cases; criteria: miss ≤ 50 m, touchdown speed
  ≤ 12 m/s, touchdown sink ≤ 3 m/s), 98 of them recovered by a go-around.
- Optional flight telemetry: launch with `-Dfaautoroute.telemetry=1` to write
  ground speed, vertical speed, height above ground, distance to the aim point,
  and health to a CSV in the game directory (off by default).

> ⚠️ All of the above numbers come from simulation and are **not yet
> flight-verified**. The go-around path in particular (FlightAssistant's
> behaviour during the climb-out, and rocket consumption) needs a real flight.
