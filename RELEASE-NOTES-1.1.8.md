# FlightAssistant AutoRoute 1.1.8 — Changelog

Landing system reworked: simulated landing success rate **48% → 98%**
(192 simulated approaches; within 50 m of target, touchdown ≤ 12 m/s).

- Glide path that converges on the landing point (mean miss on flat ground: 70 m → 6 m)
- Terrain avoidance on approach, including climb-over for obstacles
- Descent-rate cap that controls touchdown speed
- Automatic go-around and re-plan when the approach is unrecoverable
- Autopilot releases about a second after touchdown

> Simulated results, not yet flight-verified. Test in a safe world first.
