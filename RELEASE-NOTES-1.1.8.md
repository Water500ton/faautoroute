# FlightAssistant AutoRoute 1.1.8 — Changelog

The landing system was reworked to raise the simulated landing success rate
from **48% to 98%** (192 simulated approaches across different airframes,
approach hand-offs, and terrain profiles; criteria: within 50 m of the target,
touchdown at ≤ 12 m/s and ≤ 3 m/s sink).

What changed:

- **Glide path to the landing point** — the descent rate is computed from the
  remaining height and time to the target, so the aircraft converges on it
  instead of floating past. Simulated miss on flat ground: 70 m → 6 m.
- **Terrain avoidance on approach** — ground ahead is sampled along the track;
  obstacles above the landing point are cleared, and terrain above the aircraft
  triggers a climb.
- **Touchdown speed control** — the descent rate is capped along the airframe's
  glide ratio, since a steeper descent always means a faster touchdown.
- **Automatic go-around** — approaches that cannot be flown within the speed
  cap are abandoned with a climb and re-planned (up to 3 per plan).
- **Rollout** — the autopilot releases about a second after touchdown.

> ⚠️ These numbers come from simulation and are **not yet flight-verified**.
> Test in a safe world first.
