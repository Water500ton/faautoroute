<p align="center">
  <a href="README.md">English README</a> · <a href="README.zh-CN.md">中文 README</a>
</p>

# FlightAssistant AutoRoute

**Terrain-aware route planning and experimental auto-land for FlightAssistant.**

FlightAssistant AutoRoute is a client-side Fabric mod for Minecraft 1.21–1.21.1. It turns a destination into a complete FlightAssistant FMS route: climb, terrain-aware cruise, descent, final approach, and an optional auto-land sequence.

> **Vibecoding project / experimental.** This project was built primarily through AI-assisted “vibecoding”. Flight planning and auto-land behavior may contain mistakes. Test in a safe world before using it on a server or in a survival world.

## Features

- **Plan a full FMS route** from a destination X/Z — no manual waypoint entry.
- **Terrain-aware cruise altitude** — samples the client-side heightmap around the route and raises the cruise altitude above the highest terrain in the corridor.
- **Auto-land** — builds a final approach and FlightAssistant arrival data, with checks for water, lava, leaves, obstacles, and steep ground.
- **Holding descent** — inserts a descending holding pattern when the remaining distance is too short to lose altitude.
- **Landing assist with terrain avoidance and go-around** — flies a glide path to the landing point, keeps clearance over terrain sampled ahead, caps the descent rate to control touchdown speed, climbs away and re-plans if the approach becomes unrecoverable, and releases the autopilot after touchdown. Normal cruise descent is not artificially slowed.
- **GUI planner** — press `F8` to enter coordinates, preview the route, manage via points, and toggle auto-land/autopilot.
- **Current position and via points** — add the player's current X/Z, or chain multiple waypoints before the destination.
- **Xaero integration** — pick Xaero waypoints as destinations/via points and publish the planned route as temporary Xaero waypoints.
- **Autopilot sync** — keeps FlightAssistant's selected vertical/lateral/thrust modes aligned with the active flight plan; `/autoroute ap` engages AP and auto-throttle.
- **Commands and bilingual UI** — useful for macros; English and Simplified Chinese are included.

## Requirements

| Dependency | Version |
| --- | --- |
| Minecraft | 1.21 – 1.21.1 |
| Fabric Loader | 0.16.10+ |
| Fabric API | matching 1.21.1 build |
| FlightAssistant | 3.0.x (3.0.1 recommended) |
| Architectury API | 13.x (as required by FlightAssistant) |
| Fabric Language Kotlin | as required by FlightAssistant |
| Yet Another Config Lib | as required by FlightAssistant |
| Xaero's Minimap / World Map | optional |

This is a **client-side** mod. It does not need to be installed on the server.

## Installation

1. Install Fabric Loader for Minecraft 1.21.1.
2. Install Fabric API, FlightAssistant, and FlightAssistant's dependencies.
3. Put `faautoroute-1.1.7+mc1.21.1.jar` into `.minecraft/mods/`.
4. Launch the Fabric profile.

## Quick start

1. Press `F8` (or run `/autoroute`) to open the planner.
2. Enter the destination X/Z. Leave `Cruise Y` empty for automatic altitude.
3. Enable `Auto land` if you want a full approach and landing plan.
4. Click `Plan & Load`, then `Arm AP` (or run `/autoroute ap`).

### Commands

| Command | Action |
| --- | --- |
| `/autoroute` / `/autoroute gui` | Open the GUI |
| `/autoroute plan <x> <z> [cruise] [speed]` | Plan an overflight route |
| `/autoroute land <x> <z> [cruise] [speed]` | Plan with auto-land |
| `/autoroute ap` | Engage autopilot and auto-throttle |
| `/autoroute addhere` | Add the current position as a via point |
| `/autoroute waypoints` | Manage via points |
| `/autoroute info` | Show plan information |
| `/autoroute clear` | Clear the plan and disconnect autopilot |
| `/autoroute <x> <z> [cruise] [speed]` | Shortcut for `plan` |

`cruise`: `-120..1200` · `speed`: `0..30`

## Auto-land notes

Auto-land is **experimental**. It checks the terrain height and slope, but cannot guarantee that buildings, trees, protected structures, server flight restrictions, or anti-cheat rules will not interfere.

- Autopilot is off by default. It only engages when you explicitly use `Arm AP` or `/autoroute ap`.
- Auto-land is not available in the Nether; the vanilla heightmap cannot reliably represent the ground below the bedrock roof.
- Always test in creative mode or a safe area first.

## Links

- Source and issue tracker: https://github.com/Water500ton/faautoroute
- FlightAssistant: https://modrinth.com/mod/flightassistant

## License

MIT. Not affiliated with FlightAssistant or Xaero's Minimap.
