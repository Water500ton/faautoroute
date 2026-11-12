# FlightAssistant AutoRoute — WorkBuddy 交接

> 交接版本：`1.1.7+mc1.21.1`  
> 代码基线：`main@d626c56`  
> 交接时间：2026-11-12  
> 先读本文；旧交接里的“不是 Git 仓库 / 1.1.0 / docs 截图”等内容已过时。

## 0. TL;DR

- 这是一个 **Minecraft 1.21.1 / Fabric 客户端模组**，为 **FlightAssistant 3.0.x** 自动规划地形感知航线，并写入其 FMS。
- 代码已推到：<https://github.com/Water500ton/faautoroute>
- 当前可交付 jar：`dist/faautoroute-1.1.7+mc1.21.1.jar`
- 核心能力：GUI 规划、地形净空、自动降落、盘旋下降、低空拉平/减速、AP/AT 同步、Xaero 航点联动。
- **尚未发布到 Modrinth**；简介和依赖清单已准备好（见 `MODRINTH.md`）。
- 主要未完成项：**1.1.7 的完整自然地形落地还没有干净复测**；落地精度和最后速度仍需调参。

## 1. 项目与交付物

| 项 | 内容 |
| --- | --- |
| 项目名 | FlightAssistant AutoRoute |
| 版本 | `1.1.7+mc1.21.1` |
| 环境 | Minecraft 1.21 – 1.21.1 / Fabric / Java 21 |
| 仓库 | <https://github.com/Water500ton/faautoroute> |
| 分支/提交 | `main` / `d626c56` |
| 许可证 | MIT |
| 发布状态 | 未上传 Modrinth |
| 项目性质 | Vibecoding / 实验性项目 |

本地交付：

| 文件 | SHA-256 |
| --- | --- |
| `dist/faautoroute-1.1.7+mc1.21.1.jar` | `7bab99c2a3577337b196b3db7bc911cb6917603028df3a2e6410f2673148b0bd` |
| `dist/faautoroute-1.1.7+mc1.21.1-sources.jar` | `f91dfb86f674668f767cc843726427121b788ac0c17a60334a43d680dec1381f` |

说明：

- `dist/` 被 `.gitignore` 排除，**不在仓库里**；jar 在本地，也已通过微信发给用户。
- `README.md` 英文默认，`README.zh-CN.md` 中文；顶部语言互切。
- `MODRINTH.md` 是可直接粘贴到 Modrinth Description 的英文正文。
- 图标为用户指定的纸飞机版本：`icon.svg` / `icon-512.png` / `src/main/resources/assets/faautoroute/icon.png`。

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
  AutoRouteClient.java      客户端入口、F8、AP/AT 模式同步、pending landing、flare/brake
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
`heightAt()` 在高度图异常时做了**直列方块扫描兜底**，避免区块已加载但高度图为空时误判“目标区块未加载”。

目标区块未加载时：

- 先装订过境计划；
- 在 `AutoRouteClient.tickPendingLanding()` 中等待；
- 目标加载且距离 ≤ 512 后重新规划自动降落。

### 5.3 盘旋下降

`RoutePlanner.addHoldingApproach()`：

- 条件：直线距离不足以完成下降。
- 在目标上空生成 4 点下降盘旋（半径 240），再进入最后进近。
- 触发时计划通常为 7 个航点，HUD 会提示“已加入盘旋下降”。

### 5.4 低空拉平 / 减速（1.1.7）

`AutoRouteClient` 中：

- `shouldBrakeLanding()`：距目标 < 120 格、离地 < 25 格、水平速度 > 12 m/s
  - 选择 `LANDING_BRAKE_MODE = VerticalSpeedVerticalMode(+1.0)`
  - 抬头/拉平，先耗掉水平速度。
- `shouldFlare()`：距目标 < 80 格、离地 < 18 格、正在下降
  - 选择 `FLARE_MODE = VerticalSpeedVerticalMode(-1.5)`
  - 以约 -1.5 m/s 下沉接地。
- **1.1.7 已撤掉高空/巡航段的 -3 m/s 下降限速**；正常巡航下降按计划，不再被强制压慢。

调参入口就在 `AutoRouteClient` 顶部的 `FLARE_MODE` / `LANDING_BRAKE_MODE` 和 `shouldFlare()` / `shouldBrakeLanding()` 阈值。

## 6. 构建

要求 JDK 21。当前开发机：

```bash
cd /home/Water500t/Documents/deepseek-harness/default-workspace/faautoroute

JAVA_HOME=/home/Water500t/android/aviumui/prebuilts/jdk/jdk21/linux-x86 \
GRADLE_USER_HOME=$PWD/.gradle \
GRADLE_OPTS='-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897' \
./gradlew build --no-daemon
```

产物：

```text
build/libs/faautoroute-1.1.7+mc1.21.1.jar
build/libs/faautoroute-1.1.7+mc1.21.1-sources.jar
```

如果没有代理，去掉 `GRADLE_OPTS` 和 `.gradle/gradle.properties` 里的代理配置。首次构建会下载 Minecraft/Mappings/依赖。

## 7. 测试环境与已验证结果

独立测试实例：`game-test/`，世界 `新的世界`，不碰用户原 `.minecraft`。

已做过的设置：

- `keepInventory=true`。
- 测试角色有鞘翅 + 320 烟花。
- 测试 harness：`robot/Chat2.java`（可靠聊天输入）、`robot/Keys.java`、`robot/XWin.java`。
- 启动：`game-test/launch.sh --quickPlaySingleplayer '新的世界'`。

已验证：

- GUI、F8、命令、途经点、Xaero 临时航点。
- 自动降落计划、AP/AT 接通。
- **1.1.4 拉平版**在自然草坡接地：
  - 目标 `1273,1655`
  - 实际 `1273,76,1627`
  - 短约 28 格，地速 0，满血，无平台。
- 1.1.5 下降限速、1.1.6 接地减速做过部分飞行测试；**1.1.7 尚未完成一次干净的自然地形落地复测**。

不要把“1.1.4 的 28 格落地”误写成“1.1.7 已验证”。

## 8. 已知问题 / 风险

1. **1.1.7 完整落地未复测**：最后 brake/flare 可能造成拉平过高、复飞或再次硬接地；需要实机调阈值。
2. 接地精度受地形坡度、树、鞘翅滑跑距离影响；28 格是 1.1.4 的结果，不是保证值。
3. 测试 harness 的聊天输入曾多次截断命令（尤其数字/符号）；测试时先确认 `Chat2` 生成的命令完整。
4. `XaeroIntegration` 是反射实现，针对 Xaero Minimap 26.6.0 / World Map 1.47.0；升级后需回归。
5. 使用 FlightAssistant `impl` 包；FA 大版本升级可能破坏兼容。
6. 自动降落只检查地形高度/坡度/水/树叶/障碍，不保证建筑、服务器规则、反作弊、烟花数量。
7. 下界禁用自动降落（高度图会读到屋顶基岩）。
8. 规划只读客户端已加载区块；长航线会保守抬高巡航高度。
9. 未发布 Modrinth；`MODRINTH.md` 只是文案，还没有上传。

## 9. 建议下一步（按优先级）

1. **先复测 1.1.7 完整落地**：自然地形、Y=400 起跳、keepInventory、记录 HUD 地速/垂直速度/落点/血量。
2. 根据结果调 `LANDING_BRAKE_MODE` 的 +1.0 和 `FLARE_MODE` 的 -1.5；目标是接地水平速度低、不过度拉平、不冲出目标。
3. 补一次 2–4 个途经点的完整航线测试。
4. 发布 Modrinth：按 `MODRINTH.md` 的 summary/description，依赖见本文第 2 节，上传 `dist/faautoroute-1.1.7+mc1.21.1.jar`。
5. 补 `RoutePlanner` 的纯数学测试（航段/TOD/进近/盘旋顺序）。
6. 复核 README 与实际行为；README 已移除测试记录和截图，不要再加回去，除非用户要求。

## 10. 接手检查清单

```bash
cd /home/Water500t/Documents/deepseek-harness/default-workspace/faautoroute
git pull
git log --oneline -3
sha256sum dist/faautoroute-1.1.7+mc1.21.1.jar
```

确认：

- 当前提交是 `d626c56` 或之后；
- jar 哈希与第 1 节一致；
- 没覆盖用户原 `.minecraft`；
- 测试前先备份/确认 `game-test` 世界与装备；
- 不要声称 1.1.7 已完成未验证的落地。
