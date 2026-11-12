# AutoRoute 交接文档

## 1. 当前状态

- 项目：**FlightAssistant AutoRoute**
- 版本：`1.1.7+mc1.21.1`
- 目标环境：Minecraft **1.21.1** / Fabric
- 项目路径：`/home/Water500t/Documents/deepseek-harness/default-workspace/faautoroute`
- 状态：**已构建、已实机启动测试、可交付**
- 当前不是 Git 仓库，没有提交历史

最终产物：

| 文件 | SHA-256 |
| --- | --- |
| `dist/faautoroute-1.1.7+mc1.21.1.jar` | `56bc91d0e4ddf643c49dce7173e8db11d951c62e1e9c2e638c4ed9fa18b279a3` |
| `dist/faautoroute-1.1.7+mc1.21.1-sources.jar` | `16f4802772fc8a6bdd418c7459050052c79166fb5ecdfce2695e9ddef916f0dc` |

依赖（运行环境）：

- Fabric Loader >= 0.16.10
- Fabric API（1.21.1）
- FlightAssistant **3.0.x**（编译/测试使用 3.0.1）
- Architectury API 13.x
- Fabric Language Kotlin
- Yet Another Config Lib（FlightAssistant 需要）
- Xaero's Minimap / World Map（可选，地图联动）

## 2. 已完成功能

### 基础规划

- 客户端命令 `/autoroute` 和 **F8** 打开 GUI
- 从当前位置到目标自动生成：
  - 爬升点
  - 巡航点（约每 512 格）
  - 下降顶点 TOD
  - 最后进近点
  - 可选到达/自动降落数据
- 地形感知：沿航迹左右各 48 格采样已加载区块高度图
- 默认至少 50 格地形净空；下界高度上限 Y=118
- 未加载区块会使用保守巡航高度并提示
- 规划结果写入 FlightAssistant `FlightPlanComputer`

### 多途经点

- `RouteOptions.viaPoints` 支持按顺序经过多个 `RoutePoint`
- 每个途经点生成转弯航点
- GUI `Waypoints` 界面：
  - `Add current`：把当前坐标加入航线
  - `Add Xaero`：从 Xaero 航点中选择
  - 点击列表项移除
  - `Remove` / `Clear all`
- 如果显式加入当前位置，它会作为 `ORIGIN` 航点；下一个航点作为 `TARGET`
- 命令：
  - `/autoroute addhere`
  - `/autoroute waypoints`
  - `/autoroute clearvia`

### GUI

- 主界面：目标 X/Z、巡航高度（留空自动）、速度、自动降落、自动驾驶开关
- 2D 航线预览、距离、巡航高度、航点数、途经点数、ETA、警告
- 响应式布局：在 854×480 / GUI Scale 3 下已测试完整显示
- 小窗口使用短标签；大窗口使用完整标签
- 渲染顺序已修复：标题和字段标签不会再变暗
- `Land` / `AP` 为可切换按钮，带 tooltip

### Xaero 联动

- 反射调用 Xaero 第三方航点 API，不强制编译依赖 Xaero
- 规划成功后，把路线发布为 Xaero **临时航点**：
  - `AR START`
  - `AR 1`、`AR 2`...
  - `AR DEST`
- `Clear` 会清除临时航线
- Xaero 航点选择器读取当前世界持久化航点
- 已用 Xaero Minimap **26.6.0** / World Map **1.47.0** 实测

## 3. 关键文件

```text
src/main/java/com/water500t/faautoroute/
  AutoRouteClient.java        客户端入口、F8、ComputerBus 捕获、viaPoints 静态列表
  AutoRouteCommands.java      /autoroute 命令树
  RoutePlanner.java           多段航迹、地形采样、航点/进近生成
  RoutePlannerScreen.java     主 GUI（响应式）
  WaypointListScreen.java     途经点管理
  XaeroWaypointScreen.java    Xaero 航点选择器
  XaeroIntegration.java       Xaero 反射联动
  RouteOptions.java           规划参数（含 viaPoints）
  RoutePoint.java             途经点
  RouteResult.java            规划结果/预览数据
  RoutePlanningException.java
```

资源：

```text
src/main/resources/assets/faautoroute/lang/en_us.json
src/main/resources/assets/faautoroute/lang/zh_cn.json
src/main/resources/fabric.mod.json
src/main/resources/assets/faautoroute/icon.png
```

截图与测试记录：

- `README.md` 的“实机测试记录”
- `docs/gui.png`
- `docs/gui-toggle.png`
- `docs/xaero-waypoints.png`
- `docs/command.png`

## 4. 构建环境

当前系统：

- 工作区：`/home/Water500t/Documents/deepseek-harness/default-workspace`
- `$HOME` 是只读的，必须设置 `GRADLE_USER_HOME` 到工作区
- JDK 21：`/home/Water500t/android/aviumui/prebuilts/jdk/jdk21/linux-x86`
- Gradle Wrapper：9.2.1
- Loom：1.14.6
- 代理：`127.0.0.1:7897`（如果仍然可用；否则省略代理参数）

重新构建：

```bash
cd /home/Water500t/Documents/deepseek-harness/default-workspace/faautoroute
mkdir -p .gradle
cat > .gradle/gradle.properties <<'GRADLE'
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7897
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7897
GRADLE

JAVA_HOME=/home/Water500t/android/aviumui/prebuilts/jdk/jdk21/linux-x86 \
GRADLE_USER_HOME=$PWD/.gradle \
GRADLE_OPTS='-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897' \
./gradlew clean build
```

产物：`build/libs/faautoroute-1.1.0+mc1.21.1.jar`。

> `.gradle/`、`build/` 已在交付前删除，首次构建会重新下载依赖；没有代理时可能很慢。

## 5. 实机测试结果

在独立测试实例中实际启动客户端并进入世界：

- 客户端正常启动，83 个模组加载，`faautoroute 1.1.0+mc1.21.1` 在列
- `F8` 打开 GUI
- `/autoroute 1000 1000` 成功：
  - `AutoRoute: loaded 3 waypoints, cruise Y=213, 1411 blocks.`
  - `267 terrain samples were in unloaded chunks; cruise altitude was set conservatively.`
- `Waypoints → Add current` 成功
- `Land` / `AP` 切换成功
- Xaero 航点列表出现 `AR START`、`AR 1`、`AR 2`、`AR 3`
- Xaero 世界地图显示临时航点
- 测试中发现并修复：
  - GUI Scale 3 下按钮裁切 → 响应式布局 + 短标签
  - 自绘标题/标签发暗 → 调整 `super.render()` 顺序

## 5.1 1.1.3 追加修复

- AP/AT 模式每 tick 同步到计划；新增 `/autoroute ap`。
- 目标区块检测改为 `ChunkSource.hasChunk`，并在高度图为空时做直列扫描兜底。
- 目标未加载时先装订过境计划，目标加载后自动补全自动降落。
- 自动降落时根据可用下降距离限制巡航高度；无法满足地形净空时抛出 `descent_too_steep`，不再装订不可落地的进近。
- 实测：自动降落计划可以在平台区块加载后装订；AP 模式显示为 `航迹导航/自动推力/自动驾驶`。
- 已加入盘旋下降：直线距离不足时会在目标上空生成 4 点下降盘旋，再进入最后进近。
- 实测盘旋计划能装订（7 个航点）；但测试飞机在盘旋中掉到平台下方（Y≈205，平台 Y=220），HUD 报 `推力 无可用推力源`，无法再爬升接地。
- 已改用自然生成地形测试：目标为 `/locate biome minecraft:plains` 找到的 `1273,1655`，计划 3 个航点、巡航 Y=101、距离 239 格。
- 实测在自然草坡 `1286,112,1724` 触地停住（速度 0、存活、无平台），比目标短约 70 格；说明自然地面自动降落链路已跑通，但落点精度仍受地形/树影响。
- 证据：`docs/natural-landing.png`。
- 1.1.4 已加入最后拉平：距离目标 80 格内、离地 18 格以下且正在下降时，临时切换到 -1.5 m/s 的垂直速度模式。
- 拉平版实机验证通过：keepInventory=true，Y≈288 空中接管，7 航点盘旋下降，最终在自然草坡 `1273,76,1627` 接地停住（目标 `1273,1655`，短 28 格，速度 0，存活）。相比 1.1.3 的 69 格偏差明显缩小。
- 证据：`docs/natural-landing-flare.png`。
- 1.1.7 撤掉高空/巡航阶段的下降限速，只在目标 120 格内、离地 25 格内做接地拉平减速。

## 6. 已知风险 / 未完成测试

1. **未实际飞完整条航线**，也未在生存模式实测自动降落全过程。
2. **未测试从持久化 Xaero 航点中选择**：测试世界没有持久化 Xaero 航点，只验证了空列表和临时航线发布。
3. Xaero 联动使用反射，已针对 **26.6.0** 验证；升级 Xaero 后方法名/构造器可能变化。
4. 使用 FlightAssistant 的 `impl` 包（`FlightPlanComputer` 等），属于可访问但不保证稳定的 API；FlightAssistant 大版本升级后需重新适配。
5. 规划只读取客户端已加载区块；长航线会保守抬高巡航高度。
6. 下界自动降落被禁用（高度图会看到顶部基岩）。
7. 自动降落只检查地形高度/坡度/水/树叶/上方障碍，不保证建筑、服务器限制、反作弊或烟花数量。
8. 极端小窗口/GUI 缩放仍可能裁切短标签，未做完整的最小尺寸测试。

## 7. 建议的下一步

优先级从高到低：

1. 在测试世界创建一个持久化 Xaero 航点，完整验证“Xaero → 目的地/途经点”的选择流程。
2. 在超平坦/创造世界测试 `Auto land`：最后进近、最低高度、复飞、实际接地。
3. 测试 2–4 个途经点的折线航线，确认转弯航点和预览地图。
4. 在真实服务器测试 FlightAssistant 3.0.1 + Fabric API + Xaero 的类加载/反射兼容性。
5. 给 `RoutePlanner` 增加纯数学单元测试（航段长度、TOD、最后进近顺序、去重）。
6. 考虑把净空（50 格）、最大距离（16000）、航点间距做成配置项。
7. 可选：添加 Mod Menu 配置页；当前没有。
8. 如果要发布，补 `modrinth`/`curseforge` 发布配置，并确认 FlightAssistant 的 LGPL 合规说明。

## 8. 注意事项

- 不要覆盖用户原来的 `.minecraft`；测试应使用独立 gameDir。
- 当前交付目录已清理 `.gradle`、`build`、`game-test`，只保留源码、dist 和 docs。
- 修改 GUI 后重点回归：
  - 854×480 / GUI Scale 3
  - 1920×1080 / GUI Scale 4
  - 进入世界后 F8
  - `Waypoints` / `Xaero` / `Land` / `AP` 按钮