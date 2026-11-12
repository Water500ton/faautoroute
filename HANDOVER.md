# FlightAssistant AutoRoute — WorkBuddy 交接

> 交接版本：`1.1.8+mc1.21.1`  
> 代码基线：`main@07e98be`  
> 交接时间：2026-11-12  
> 先读本文；旧交接里的“不是 Git 仓库 / 1.1.0 / docs 截图”等内容已过时。

## 0. TL;DR

- 这是一个 **Minecraft 1.21.1 / Fabric 客户端模组**，为 **FlightAssistant 3.0.x** 自动规划地形感知航线，并写入其 FMS。
- 代码已推到：<https://github.com/Water500ton/faautoroute>
- 当前可交付 jar：`dist/faautoroute-1.1.8+mc1.21.1.jar`
- 1.1.8 把落地逻辑从“固定高度拉平”改成“对准落点的下滑道”，并加入离线仿真台。
- **落地只在仿真里验证过，没有实机飞过。** 见第 7 节“已验证 / 未验证”，不要写成已实机验证。
- **尚未发布到 Modrinth**；简介和依赖清单已准备好（见 `MODRINTH.md`）。
- 本机当前**无法启动游戏测试实例**（GLX 配置失败，见第 8 节第 1 条），这是本轮转做仿真测试台的直接原因。

## 1. 项目与交付物

| 项 | 内容 |
| --- | --- |
| 项目名 | FlightAssistant AutoRoute |
| 版本 | `1.1.8+mc1.21.1` |
| 环境 | Minecraft 1.21 – 1.21.1 / Fabric / Java 21 |
| 仓库 | <https://github.com/Water500ton/faautoroute> |
| 分支/提交 | `main` / `07e98be` |
| 许可证 | MIT |
| 发布状态 | 未上传 Modrinth |
| 项目性质 | Vibecoding / 实验性项目 |

本地交付：

| 文件 | SHA-256 |
| --- | --- |
| `dist/faautoroute-1.1.8+mc1.21.1.jar` | `c73061fe7e0f9a1dc14499edb989aa542c7ccdbab5ee5590cd23110d3b2ffb80` |
| `dist/faautoroute-1.1.7+mc1.21.1.jar`（旧，勿再发布） | `7bab99c2a3577337b196b3db7bc911cb6917603028df3a2e6410f2673148b0bd` |

说明：

- `dist/` 被 `.gitignore` 排除，**不在仓库里**。
- `README.md` 英文默认，`README.zh-CN.md` 中文；顶部语言互切。
- `MODRINTH.md` 是可直接粘贴到 Modrinth Description 的英文正文。
- 图标为用户指定的纸飞机版本：`icon.svg` / `icon-512.png` / `src/main/resources/assets/faautoroute/icon.png`。
- sources jar 目前构建会失败：`remapSourcesJar` 在沙箱里往 `/tmp` 写 `fabric.mod.json` 被拒。用 `-x remapSourcesJar` 只出主 jar。

## 2. 依赖（Modrinth 发布用）

必需：

- Fabric API `0.102.0+1.21.1`（或兼容版本）
- FlightAssistant `3.0.1+mc1.21.1-fabric`（3.0.x；编译针对 3.0.1）
- Architectury API `13.0.8`（13.x）
- Fabric Language Kotlin `1.13.0+kotlin.2.1.0`
- Yet Another Config Lib `3.6.2+1.21-fabric`

可选：

- Xaero's Minimap / Xaero's World Map

运行环境：Fabric Loader `0.16.10+`，Java 21。仅客户端，不需要装到服务端。

## 3. 当前功能

- `F8` / `/autoroute` 打开 GUI。
- 目标 X/Z、巡航高度（留空自动）、目标速度、Auto land、Arm AP。
- 多途经点：
  - GUI `Waypoints → Add current`
  - `Add Xaero`
  - `/autoroute addhere`
  - `/autoroute waypoints`
  - `/autoroute clearvia`
- Xaero：
  - 读取 Xaero 航点作为目标/途经点。
  - 发布临时航点 `AR START`、`AR 1...`、`AR DEST`。
- 命令：
  - `/autoroute plan <x> <z> [cruise] [speed]`
  - `/autoroute land <x> <z> [cruise] [speed]`
  - `/autoroute ap`
  - `/autoroute info`
  - `/autoroute clear`
  - `/autoroute <x> <z> [cruise] [speed]`（plan 快捷形式）

## 4. 关键文件

```text
src/main/java/com/water500t/faautoroute/
  AutoRouteClient.java      客户端入口、F8、AP/AT 模式同步、落地律接线、pending landing
  LandingLaw.java           ★ 落地律（纯 Java，无 Minecraft 依赖，可离线测试）
  FlightTelemetry.java      可选飞行遥测 CSV（-Dfaautoroute.telemetry=1）
  AutoRouteCommands.java    /autoroute 命令树
  RoutePlanner.java         地形采样、航段/进近/盘旋生成、目标区块检查
  RoutePlannerScreen.java   主 GUI
  WaypointListScreen.java   途经点管理
  XaeroWaypointScreen.java  Xaero 航点选择器
  XaeroIntegration.java     Xaero 第三方航点反射桥接
  RouteOptions.java         规划参数
  RoutePoint.java           途经点
  RouteResult.java          规划结果/预览
  RoutePlanningException.java

sim/                        离线测试台（不进 jar）
  LandingSim.java           对比报告 / 自检 / 参数扫描
  run.sh                    ./sim/run.sh [check|sweep]
```

资源：

```text
src/main/resources/assets/faautoroute/lang/en_us.json
src/main/resources/assets/faautoroute/lang/zh_cn.json
src/main/resources/fabric.mod.json
src/main/resources/assets/faautoroute/icon.png
```

## 5. 飞行控制逻辑（重点）

### 5.1 AP/AT 同步

`AutoRouteClient.syncPlanModes()` 每 tick 把 FlightAssistant 的：

- vertical mode
- lateral mode
- thrust mode

同步到当前 `FlightPlanComputer`。如果只加载计划但不持续同步，FA 会保持旧模式，飞机不会跟航线。

`/autoroute ap` 调用 `enableAutopilot()`，显式接通 AP + AT。

### 5.2 目标区块与延迟自动降落

`RoutePlanner.isChunkLoaded()` 使用 `ChunkSource.hasChunk()`；
`heightAt()` 在高度图异常时做了**直列方块扫描兜底**。

目标区块未加载时：

- 先装订过境计划；
- 在 `AutoRouteClient.tickPendingLanding()` 中等待；
- 目标加载且距离 ≤ 512 后重新规划自动降落。

### 5.3 盘旋下降

`RoutePlanner.addHoldingApproach()`：

- 条件：直线距离不足以完成下降。
- 在目标上空生成 4 点下降盘旋（半径 240），再进入最后进近。
- 触发时计划通常为 7 个航点。

### 5.4 落地律（1.1.8 重写）

调参入口：`LandingLaw.Config.DEFAULT`（单处 record，全部带注释）。

**旧逻辑（1.1.4–1.1.7）的问题**：

- 用固定门限触发：`距离<80 && 离地<18` 就拉到 -1.5 m/s。
- -1.5 m/s 意味着最后 18 格要飞十几秒，于是**浮空冲过目标**；反之进入偏高时又会提前拉平。
- 刹车模式是 `+1.0 m/s`（抬头上升），在离地 25 格内还可能反向爬升。
- `shouldFlare` 没有 `onGround` 判断，接地后仍持续压 -1.5 m/s。
- 接地后不释放 AP，计划会一直把飞机往地面推。

**新逻辑**：单一律 + 三个动作（`BRAKE` / `FLARE` / `ROLLOUT`）。

1. 接管窗口：`离地 < 70` 且 `距落点 < 420`，否则把垂直模式还给飞行计划。
2. 下滑道：`所需下降率 = 相对落点标高的高度 ÷ (剩余距离 ÷ 地速)`，夹在 `[minSink 0.3, maxSink 4.0]`。
   - 基准是**落点标高**（`player.getY() - arrival.getElevation()`），不是脚下地面高度：山谷/缓坡不会把整条进近带偏。
   - 本质是“保持到落点的角度”，高度和距离同时归零；地速变化时自动修正。
3. 速度偏大（`>13 m/s` 且还有 `8 s` 以上）时把下滑道走平，不再切抬头模式。
4. 近地衰减：`sink ≤ 离地/0.5`，只影响最后约 2 米，避免砸地。
5. `离地 ≤ 0.3` 时固定 -0.5 m/s 接地。
6. 接地后 `ROLLOUT`：垂直目标 0，20 tick（约 1 秒）后 `setAutoPilot(false)` + `setAutoThrust(false)`。

另外，`RoutePlanner` 在自动降落时会检查最后进近走廊：若出现高出落点 8 格以上的地形，给出
`warning.faautoroute.approach_terrain` 警告（因为落地律是往落点标高降的，会先撞上这段地形）。

## 6. 构建

要求 JDK 21。当前开发机（注意沙箱把 `RLIMIT_AS` 卡在 4 GB，必须压小堆）：

```bash
cd /home/Water500t/Documents/deepseek-harness/default-workspace/faautoroute

JAVA_HOME=/home/Water500t/android/aviumui/prebuilts/jdk/jdk21/linux-x86 \
GRADLE_USER_HOME=$PWD/.gradle \
./gradlew build --no-daemon -x remapSourcesJar \
  -Dorg.gradle.jvmargs="-Xmx1200m -XX:MaxMetaspaceSize=512m -XX:ReservedCodeCacheSize=128m"
```

产物：`build/libs/faautoroute-1.1.8+mc1.21.1.jar`

首次构建会下载 Minecraft/Mappings/依赖。需要代理时加
`GRADLE_OPTS='-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897'`。

### 6.1 离线仿真测试台

```bash
./sim/run.sh          # 对比报告：1.1.7 旧启发式 vs 新落地律
./sim/run.sh check    # 落地律行为断言（9 项，全部应 ok）
./sim/run.sh sweep    # 参数扫描，按“落点误差 + 接地速度 + 接地下降率”排序
```

`sim/` 只依赖 JDK，不碰 Minecraft。当前基线结果（4 种机体参数 × 8 种进入状态）：

| | 平均落点误差 | 最差 | 冲过目标次数 | 平均接地速度 |
| --- | ---: | ---: | ---: | ---: |
| 1.1.7 旧逻辑 | 70.3 m | 306.5 m | 12/32 | 10.3 m/s |
| 1.1.8 新落地律 | 6.2 m | 34.1 m | 4/32 | 12.8 m/s |

典型场景（baseline 机体）：

| 进入状态 | 1.1.7 | 1.1.8 |
| --- | ---: | ---: |
| plan-nominal（55 m / 440 m / 8 m/s） | -16.2 m | +1.0 m |
| high（70 m / 380 m / 9 m/s） | +90.1 m | +1.7 m |
| low（25 m / 220 m / 8 m/s） | -26.7 m | +0.6 m |
| fast-hot（60 m / 380 m / 20 m/s） | +144.0 m | +17.5 m |
| steep（40 m / 150 m / 15 m/s） | +172.8 m | +23.3 m |

**怎么读这张表**：仿真里的机体是**简化滑翔模型**（一阶垂直响应 + 阻力/下降换取速度），不是原版鞘翅物理的复刻，绝对值不能当成实机结果。可信的是**相对改善**和趋势：新律在正常进入下几乎零误差，在偏高/偏低进入下把 ±90 米量级收敛到个位数。`fast-hot` / `steep` 这类“又高又近又快”的进入仍然会冲过 17–23 米且接地偏快——那是几何上无解，只能靠进近段把速度压到 ≤12 m/s，或者以后加复飞。

### 6.2 地形敏感性（重要）

仿真台有 6 种地面剖面（`flat` / `rise10` / `drop10` / `terrace` / `bump` / `cliffdown`），
都按“相对落点地面”的起伏定义，不是平地假设。baseline 机体、8 种进入状态的平均绝对落点误差：

| 地形 | 1.1.7 | 1.1.8 | 说明 |
| --- | ---: | ---: | --- |
| flat 平地 | 62.2 m | 5.8 m | 正常基线 |
| rise10 上坡 10 格 | 51.8 m | 7.0 m | 好 |
| drop10 下坡 10 格 | 129.2 m | 17.7 m | 可用；地面下落会多飞一点 |
| terrace 落点前 6 格台地 | 45.3 m | 1.6 m | 好 |
| bump 进近 60% 处 8 格土丘 | 67.4 m | 10.0 m | 可用 |
| cliffdown 落点前 22 米断崖下 8 格 | 101.0 m | 24.2 m | 平均被拉高，正常进入已修复 |

逐场景看（`./sim/run.sh` 的地形矩阵，格式“落点误差/接地速度”）：

| 场景 | 旧 | 新 |
| --- | --- | --- |
| cliffdown × plan-nominal | -54 m（撞坎） | **+5.2 m / 10.6 m/s** |
| cliffdown × plan-slow | 撞坎 | **+1.6 m / 8.6 m/s** |
| terrace × 全部 | 撞坎或 -6 | +0.1 ~ +3.0 |
| rise10 × low | -26.7 | -20.9 m / **6.9 m/s**（柔和小偏差） |
| bump × very-low | -33.8 | -33.8 m / **9.2 m/s**（落在近坡上，慢） |

**地形避让的实现**（`LandingLaw.terrainConstraint` + `AutoRouteClient.terrainSinkLimit`）：

- 沿到落点的航迹每 8 格采样一次地面（128 格后每 32 格，最远 320 格）。
- 高出落点标高 4 格以上的地形按“地面 + 4 格”清障：压浅下降率；
  地形高过飞机时切 `TERRAIN` 模式爬升（HUD 显示「地形避让」）。
- 只比落点高 0–4 格的缓坡不当障碍，仍按下滑道对准落点——所以平地/缓坡行为不变。

仍然要人工判断的场景：

- `fast-hot` / `steep`（15–20 m/s 热进近）在 drop10/cliffdown 上仍会冲过 50–70 米——
  速度问题，不是地形问题，落地律救不了，得靠进近段压速度。
- 平滑上坡 + 低空进入会“柔和地落在坡上”（短 20–30 米，速度 <9 m/s），
  是物理上合理的结果，不是撞地。

## 7. 测试环境与已验证结果

独立测试实例：`game-test/`，世界 `新的世界`，不碰用户原 `.minecraft`。

已做过的设置：

- `keepInventory=true`。
- 测试角色有鞘翅 + 320 烟花。
- 测试 harness：`robot/Chat2.java`（可靠聊天输入）、`robot/Keys.java`、`robot/XWin.java`。
- 启动：`game-test/launch.sh --quickPlaySingleplayer '新的世界'`；
  带遥测的版本是 `game-test/launch-test.sh`（加 `-Dfaautoroute.telemetry=1`，输出 `game-test/faautoroute-telemetry.csv`）。

已验证：

- GUI、F8、命令、途经点、Xaero 临时航点。
- 自动降落计划、AP/AT 接通。
- **1.1.4 拉平版**在自然草坡接地：目标 `1273,1655`，实际 `1273,76,1627`，短约 28 格，地速 0，满血，无平台。
- **1.1.8 落地律**：离线仿真 + 9 项行为断言通过（见 6.1）。

未验证：

- **1.1.7 从未实机飞过**（`game-test/mods` 里当时放的是 1.1.6）。
- **1.1.8 从未实机飞过。** 下滑道接管窗口、近地衰减、接地后释放 AP 都只有仿真支撑。
- 2–4 个途经点的完整航线回归还没有做。

不要把“1.1.4 的 28 格落地”写成 1.1.7/1.1.8 已验证。

## 8. 已知问题 / 风险

1. **本机启动测试实例失败**：`GLFW error 65545: GLX: Failed to find a suitable GLXFBConfig`（`class_1041$class_4716`）。
   - `glxinfo -B` 在 `:1`（Xwayland / Intel Iris Xe）显示 `direct rendering: Yes`，但 GLFW 拿不到合适 FBConfig。
   - 沙箱内外都试过（`dangerouslyDisableSandbox` 同样失败），所以不是权限问题。
   - 试过 `-Xmx2G` + SerialGC 压内存，仍在建窗口时失败。
   - 下一步可以试：真 Xorg（`:0`，需要 `XAUTHORITY`）、装 `Xvfb`、或换台机器跑实机测试。
2. **1.1.8 落地律未实机验证**：仿真机体是简化的，实机可能仍需按 CSV 微调 `minSink` / `maxSink` / `taperTime`。
3. `fast-hot` / `steep` 进入仍会冲过目标 17–23 米并偏快接地；根因是进近段速度没压下来，落地律救不了。在 drop10/cliffdown 上会放大到 50–70 米。
4. **进近地形避让只覆盖“高出落点 4 格以上”的障碍**：0–4 格的缓坡不避让（避免平地/坡地落点浮空），低空+上坡进入会柔和地落在坡上（短 20–30 米）。规划期有 `warning.faautoroute.approach_terrain` 警告辅助。
4. 接地精度还受地形坡度、树、鞘翅滑跑距离影响；28 格是 1.1.4 的结果，不是保证值。
5. 测试 harness 的聊天输入曾多次截断命令（尤其数字/符号）；测试时先确认 `Chat2` 生成的命令完整。
6. `XaeroIntegration` 是反射实现，针对 Xaero Minimap 26.6.0 / World Map 1.47.0；升级后需回归。
7. 使用 FlightAssistant `impl` 包；FA 大版本升级可能破坏兼容。
8. 自动降落只检查地形高度/坡度/水/树叶/障碍，不保证建筑、服务器规则、反作弊、烟花数量。
9. 下界禁用自动降落（高度图会读到屋顶基岩）。
10. 规划只读客户端已加载区块；长航线会保守抬高巡航高度。
11. sources jar 构建失败（`/tmp` 写权限），发布只发主 jar 即可。

## 9. 建议下一步（按优先级）

1. **找一个能跑 GL（Xorg 或 Xvfb+Xvfb 的 GLX）的环境，实机飞一次 1.1.8 落地**，开 `-Dfaautoroute.telemetry=1`，重点看 `faautoroute-telemetry.csv` 里最后 400 米的 `agl / v_speed / h_speed / dist`：
   - 接管是否在 70 m / 420 m 附近；
   - 接地是否落在落点 ±10 米；
   - 接地 `h_speed` 是否 ≤10 m/s；
   - 接地后 1 秒 AP 是否断开（`ap` 列变 `false`）。
2. 按 CSV 微调 `LandingLaw.Config`：先把 `maxSink` 往 3.0 收（更柔和），再考虑 `engageAgl`。
3. 补 2–4 个途经点的完整航线回归。
4. 若实机仍偏快接地，考虑在进近段就把 `approachSpeed`（`RoutePlanner` 里的 `min(speed, 8)`）再压低。
5. 发布 Modrinth：按 `MODRINTH.md` 文案、第 2 节依赖，上传 `dist/faautoroute-1.1.8+mc1.21.1.jar`；需要 API token。
6. README 已移除测试记录和截图，**不要再加回去**，除非用户要求。

## 10. 接手检查清单

```bash
cd /home/Water500t/Documents/deepseek-harness/default-workspace/faautoroute
git pull
git log --oneline -3
sha256sum dist/faautoroute-1.1.8+mc1.21.1.jar
./sim/run.sh check
```

确认：

- 当前提交是 `07e98be` 或之后；
- jar 哈希与第 1 节一致；
- `./sim/run.sh check` 全 ok；
- 没覆盖用户原 `.minecraft`；
- 测试前先确认 `game-test` 世界与装备；
- 不要声称 1.1.8 已完成未验证的实机落地。
