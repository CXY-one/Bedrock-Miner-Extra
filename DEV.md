# Bedrock Miner Extra — 开发者文档

> 面向后续开发 / Debug 的技术文档。如果你要改这个项目，先读这里，能少踩很多坑。
>
> 源码主目录：`src/client/java/dev/cxy/bedrockminer/`（纯客户端，无 common/server 代码）。

---

## 1. 项目概览

一个 **Fabric 纯客户端** mod：在生存模式利用原版"无头活塞"特性（Mojang bug **MC-27056**）破坏基岩。

- **不修改任何原版行为**：只通过模拟玩家操作发送网络包。
- 支持**手动**与**自动**两种模式，覆盖 **11 个 MC 版本**（1.19.4 ~ 26.3）。
- 目标：让"无头活塞破基岩"这个 tick 级人类做不到的操作变得稳定可用（任意挖掘速度、可自动化）。

### 破基岩原理（MC-27056）
1. 在基岩旁放一个**普通活塞**，活塞头朝**空气**（能伸出）。
2. 用**拉杆**给活塞充能，活塞伸出（extended）。
3. 在活塞**仍伸出但已断电**的状态下挖掉它 → 底座移除、活塞头残留（"无头活塞"）。
4. 在同一 tick 内于原位**放置一个朝向基岩的新活塞** → 原版机制把基岩替换成活塞头 → 基岩被"破坏"（无掉落）。

**关键时序要求**（服务器同一 tick 按序处理）：`断信号（关拉杆）→ 挖活塞 → 放朝向基岩的新活塞`。三者必须都发生在活塞"伸出但断电"的瞬间（断电后 1 tick 活塞就收回了，太早太晚都不行）。

---

## 2. 技术栈与工具链

| 项 | 值 |
|---|---|
| Minecraft | 26.1.2（active 版本），支持 1.19.4 / 1.20.1 / 1.20.5 / 1.20.6 / 1.21 / 1.21.1 / 1.21.11 / 26.1 / 26.1.2 / 26.2 / 26.3 |
| 构建 | **Stonecutter**（`dev.kikugie.stonecutter` 0.9.6）多版本，一套源码 `//? if` 条件化 |
| Mod loader | Fabric Loader 0.19.3 |
| Loom | `gradle.properties` 里 `loom_version=1.17.19` |
| Gradle | wrapper 9.6.0 |
| JDK | **26**（`org.gradle.java.home=C:/Program Files/Java/jdk-26.0.2`，机器默认 JAVA_HOME 是 21，必须靠这个属性指定） |
| 编译目标 | release 25（26.x）/ 21（1.20.5~1.21.x）/ 17（1.19.4~1.20.1） |
| 映射 | 26.x 用 Mojang 官方映射（`loomx.applyMojangMappings()`，`loomx.unobfuscated` 对 26.x）；旧版本由 Loom 自动用 yarn |
| Fabric API | 0.155.2+26.1.2（26.1.2）；各版本在 `stonecutter.properties.toml` 里 | 
| 可选依赖 | **malilib**（配置 GUI，每个版本一个版本）、**modmenu**（Config 入口，每个版本一个版本） |

### 版本号分段（stonecutter.properties.toml）
- `mod.version = "2.5.3"`（**注意：每次更新必须 bump，小更新小版本、大更新大版本，见 §7**）。
- 每个版本段有 `deps.fabric_api` / `deps.malilib_mc` / `deps.malilib` / `deps.mod_menu`。
- **1.20.5 没有自己版本的 malilib**：`deps.malilib_mc = "1.20.6"`（复用 1.20.6 的 jar，其 `minecraft` 范围是 `>=1.20.5 <=1.20.6`）。

---

## 3. 目录结构

```
src/client/java/dev/cxy/bedrockminer/
├── BedrockBreaker.java            # 手动模式核心 + headless 包时序（静态方法集合）
├── AutoBreaker.java               # 自动模式（状态机 + 队列 + 放置/挖掘）
├── BedrockMinerExtraClient.java   # 客户端入口（指令、事件注册、tick 回调）
├── mixin/
│   ├── MultiPlayerGameModeMixin.java      # 三个注入点（见 §4）
│   ├── MultiPlayerGameModeAccessor.java   # 读 destroyBlockPos/destroyProgress（私有字段）
│   ├── EntityAccessor.java               # 写 xRot/yRot（26.1.2 私有、无 setter）
│   └── InventoryAccessor.java            # 写 selected（热栏槽位）
├── config/
│   ├── MalilibConfigs.java       # malilib 配置项（IConfigHandler + IKeybindProvider）
│   └── MalilibGui.java           # malilib 配置界面（GuiConfigsBase 子类）
└── compat/
    ├── MalilibCompat.java        # 可选 malilib 入口（isModLoaded 门控 + 反射）
    └── modmenu/ModMenuImpl.java  # modmenu Config 入口（反射加载 MalilibGui）

src/client/resources/bedrock-miner-extra.mixins.json
src/main/resources/fabric.mod.json
build.gradle.kts / settings.gradle.kts / stonecutter.gradle.kts
stonecutter.properties.toml / versions.json
```

---

## 4. 核心机制与关键类

### 4.1 手动模式：BedrockBreaker + MultiPlayerGameModeMixin

`MultiPlayerGameModeMixin` 有 **3 个注入点**，把 mod 的包精确卡在挖掘完成包前后：

```java
@Inject(method = "destroyBlock", at = @At("HEAD"))          // (A) 捕获场景 + 断信号
@Inject(method = "startPrediction", at = @At("RETURN"))      // (B) 破坏包发出后 → 放新活塞
@Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)   // (C) 保护新活塞
@Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)// (D) 保护新活塞
```

**两条挖掘路径**（都经过 `destroyBlock`，所以 (A) 是公共锚点）：
- **慢速**：`continueDestroyBlock` 进度累加至 1.0 → `startPrediction(seq -> { destroyBlock(pos); return STOP_DESTROY_BLOCK; })`。
- **瞬间**（效率V+急迫II）：`startDestroyBlock` 里的 instant 特判（`getDestroyProgress >= 1.0`）**在 `startPrediction` lambda 内直接调 `destroyBlock`，完全绕过 `continueDestroyBlock`** —— 这是之前瞬间破坏失败的根因（时序钩子一度只挂在 continueDestroyBlock 上）。

**时序**（以瞬间路径为例，`inCutPower` 防重入标志防止断电的嵌套 startPrediction 提前触发放活塞）：
```java
onBlockBreakStarted(pos) {          // destroyBlock HEAD
    scene = capture(...);           // 活塞还在、伸出、带电、附近有拉杆
    pendingScene = scene;
    inCutPower = true;
    cutPower(scene);                // useItemOn(lever) → 嵌套 startPrediction → RETURN
    inCutPower = false;             //   该嵌套 RETURN 被 inCutPower 跳过
}
onPredictionPacketSent() {          // startPrediction RETURN（破坏包已发）
    if (pendingScene != null && !inCutPower) {
        pendingScene = null;
        placeFreshPiston(scene);    // 切活塞热栏/副手 + 放朝向基岩的活塞
    }
}
```

**关键字段**：`pendingScene`（一次挖掘只消费一次）、`inCutPower`、`earlyRotSent`（水平朝向预发）、`protectedPos/protectedUntilMs`（新活塞保护窗口，100ms）。

**`capture()` 触发条件**（全部满足）：
- 被挖的是普通活塞（`Blocks.PISTON`）+ `EXTENDED=true`（头已伸出）；
- 活塞相邻某个白名单方块（默认 bedrock，`isWhitelisted`）；
- 活塞 `hasNeighborSignal`（带电）；
- 2 格 manhattan 内有通电拉杆（`findPowerSource`）。

**`placePiston()`**：临时用 `EntityAccessor` 改玩家 `xRot/yRot` + `yHeadRot`，发 `rotPacket`，再 `useItemOn`。**服务器从玩家视角推导活塞朝向**（`getNearestLookingDirection().getOpposite()`），而服务器端 `yHeadRot` 有 **1 tick 延迟**——这就是水平朝向问题的根源（见 §8.3）。

**`getYawPitch(dir)`**：返回让服务器放下朝向 `dir` 的活塞所需的 pitch/yaw。`rotPacket` 在 1.21.11+ 是 4 参构造（多了 `horizontalCollision`）。

### 4.2 自动模式：AutoBreaker

状态机（每客户端 tick 推进一次）：`IDLE → PLACE_PISTON → PLACE_PISTON2 → PLACE_LEVER → POWER_LEVER → WAIT_EXTEND → MINE_ROTATE → MINE → (CLEANUP)`。

- **找位置 `findPlacement`**：复用已有可伸出活塞（`reusePiston`，读已有拉杆 `LeverBlock.POWERED` 开关状态）；否则找新的（6 面按离玩家近排序，要求"活塞格空气 + 活塞头方向空气 + 拉杆格空气"）。
- **拉杆位置**：**优先附着在要破的基岩上**（充能基岩→充能活塞，100% 可靠），其次活塞侧面；排除活塞头方向。
- **放活塞前先发视角包等 1 tick**（`PLACE_PISTON → PLACE_PISTON2` 隔一 tick，服务器 `yHeadRot` 生效）。
- **放拉杆后再开启**（`POWER_LEVER`，拉杆默认关）。
- **MINE_ROTATE**：挖活塞前先发"朝向基岩"的视角包（替换活塞朝向正确），下一 tick 才 `startDestroyBlock`（瞬间/慢速两条路径都走现有 `destroyBlock` mixin 自动完成断电+放新活塞）。
- **队列**：`ArrayDeque` + `HashSet` 去重，上限 5，满删队头（最旧）；范围内点击插队首；每 tick 取第一个范围内位置处理。**关闭自动模式时清空队列**（tick 开头判断）。
- **MINE** 模拟挖掘：`startDestroyBlock` + 每 tick `continueDestroyBlock`，直到 `isBroken`（目标不再是白名单方块）。
- **CLEANUP**：失败时只挖**自己放置**的活塞/拉杆（`needPlacePiston`/`needPlaceLever` 标记），**玩家已有装置不动**。

### 4.3 GUI：malilib + modmenu（可选依赖）

- **配置真相源**：`config/bedrock-miner-extra.json`（`BedrockBreaker` 手写 JSON）。malilib 配置项是"视图"，提交时由注册的 `ConfigManager` handler 写回 `BedrockBreaker`，GUI 打开时只在首次初始化同步；自动模式切换快捷键默认未绑定，粘性活塞支持默认关闭，也只在安装 malilib 时可配置。
- **`MalilibCompat` 必须用反射**（关键）：这个类在启动时被无条件加载（`BedrockMinerExtraClient`），**绝对不能直接 import / 引用任何 malilib 类型**，否则类加载时连带加载 `MalilibConfigs`（其静态字段 `new ConfigBoolean(...)` 触碰 malilib 类）→ 不加 malilib 时 `NoClassDefFoundError` 崩溃。所有 malilib 访问都用 `Class.forName(...)` + `getMethod(...).invoke(...)`。
- **modmenu**：`ModMenuImpl` 用反射加载 `MalilibGui`，`isModLoaded("malilib")` 检查后返回屏幕 / null（未装 modmenu 时 fabric 不会实例化该 entrypoint，天然安全）。
- **`/bedrockMinerExtra gui` 打不开的坑**：指令在聊天屏提交链里 `setScreen` 会被聊天屏关闭覆盖 → 用 `pendingOpenGui` 标志**延迟到下一客户端 tick** 再打开。

---

## 5. 多版本适配（Stonecutter）

一套源码，`//? if <版本条件> { ... } else /*...*/` 条件编译。**Stonecutter active 版本是 26.1.2**，所以 `src` 里的代码保持 26.1.2 的最终形态（条件为真的分支保留为真实代码，条件为假的分支用 `/* */` 注释）。

构建时 Stonecutter 按版本把源码 chisel 到 `versions/<版本>/build/generated/stonecutter/` 再编译。

- **版本条件写法**：`//? if >=26.1 {`、`//?} else {`、`//?}`。注意 `src`（active 26.1.2）里条件分支要保持 **26.1.2 走哪个分支**。
- **改 //? if 条件**：`grep -rn "//? if" src` 先确认每处的语义，别把 `sendChatMessage` 的 `//? if >=26.1`（那是 `sendSystemMessage` vs `displayClientMessage`，消息 API 区分）误改成别的。
- **GUI 相关类（MalilibConfigs/MalilibGui/MalilibCompat）** 整个文件内容用 `//? if >=1.19.4 {` 包裹（package 声明在外）。低版本 = 空文件（只剩 package），所以旧版本无 GUI 且编译通过。

### 版本条件 vs 依赖条件一致性
- `build.gradle.kts` 里 malilib 依赖：`if (sc.current.parsed >= "1.19.4") modCompileOnly(...)`。modmenu 依赖：无条件 `modCompileOnly`（所有版本都有 `deps.mod_menu`）。
- 某个版本如果 `stonecutter.properties.toml` 没有 `deps.malilib`，但 `//? if >=1.19.4` 为真（比如加了 1.19.4 之前的老版本），会编译失败。**加新版本时要同步补 `deps.malilib_mc`/`deps.malilib`/`deps.mod_menu`**。

---

## 6. 构建与产物

```bash
./gradlew build               # 构建 active 版本（26.1.2）——实际会构建**所有 11 个版本**子项目
./gradlew :26.1.2:build       # 只构建单个版本；Stonecutter 使用当前 src 生成该版本源码
./gradlew :26.3:build
./gradlew :1.21.11:build
```

**⚠️ 重要坑**：
- 修改 `src` 后可直接构建目标版本；如果 Gradle 判断任务已是最新，追加 `--rerun-tasks` 强制重新 chisel。只有需要验证全部版本时才运行 root 的 `./gradlew build`。
- **`./gradlew build` 并行构建 11 个版本，`gradle.properties` 的 `-Xmx1G` 不够** → GC thrashing 崩溃。用 `./gradlew build -Dorg.gradle.jvmargs="-Xmx4G"`。
- 产物在 `versions/<版本>/build/libs/bedrock-miner-extra-<mod.version>+<mc>.jar`（含 -sources.jar）。
- **旧版本产物不要删**（`build/libs` 里历史 jar 保留当备份；用户偶尔会让你清理特定版本）。

---

## 7. 版本号规则（用户明确要求）

- 每次更新**必须** bump `stonecutter.properties.toml` 的 `mod.version`：
  - **小更新** → 小版本（如 2.4.0 → 2.4.1）；
  - **大更新** → 大版本（历史上 1.3.2 → 2.0.0 自动模式；**别学我 bump 到 3.0.0 又被改回**，大更新默认升次版本如 2.3.0，除非用户明确要主版本+1）。
- 发布：先 `git add` + `commit` + `push origin main`；GitHub Release 用 `gh` 或网页手动创建（本机无 gh、无 token）。

---

## 8. 常见坑与 API 差异（Debug 优先看这里）

### 8.1 26.x 工具系统重构
- **没有 `PickaxeItem` 类**（工具系统组件化，`Tool` component + `ToolMaterial`）。选择"最好镐"必须按 `ItemStack.getDestroySpeed(BlockState)` 排序（该 API 在 yarn/mojmap 全版本同名）。
- 只有 `AxeItem`/`HoeItem`/`ShovelItem` 等；`getDestroySpeed` 全版本可用。

### 8.2 26.x 的 `Level.isClientSide` 是私有字段
- 用 **`level.isClientSide()`（方法）**，不是字段访问。yarn 里字段公开但方法也在，方法调用全版本通用。

### 8.3 服务器端 `yHeadRot` 1 tick 延迟（水平朝向问题的根源）
- 活塞朝向由服务器从玩家视角推导；服务器 `yHeadRot` 要等玩家实体 tick 才更新。
- 解决：**先发视角包，等 1 tick，再 `useItemOn` 放置**（自动模式的 `PLACE_PISTON→PLACE_PISTON2`、`MINE_ROTATE`；手动模式的 `onClientTick` earlyRot 预发）。

### 8.4 `Direction` API
- 26.x **没有 `Direction.fromDelta(int,int,int)`** → 用 `Direction.fromAxisAndDirection(Axis, AxisDirection)`（自写 `directionFromDelta` 辅助）。
- 26.x **`BlockPos` 没有 `getDirection()`** → 放置时直接记录方向，别从 `subtract` 推导。

### 8.5 26.2 的 `Gui` → `Hud` 拆分
- action bar 显示：26.1.2 及以前 `Minecraft.getInstance().gui.setOverlayMessage(Component, boolean)`；**26.2 是 `Minecraft.getInstance().gui.hud.setOverlayMessage(...)`**（用 `//? if >=26.2` 区分，且 `src` 保持 active 26.1.2 的形态）。

### 8.6 malilib API（0.15 ~ 0.30）
- 本 mod 只用了**跨版本稳定**的核心 API（`ConfigBoolean`/`ConfigHotkey`/`ConfigStringList`/`GuiConfigsBase`/`ConfigOptionWrapper.createFor`/`IConfigHandler`/`IKeybindProvider`/`InputEventHandler`/`GuiBase.openGui`/`setValueChangeCallback`/`IValueChangeCallback`），目标覆盖 0.15~0.30。
- **别碰易变的**：`JsonUtils`（0.29 移到 `fi.dy.masa.malilib.util.data.json`）、`ConfigBooleanHotkeyed` 的分支差异、`ConfigStringList.getLastStringListValue` 等——参考项目（bedrock-miner）为它们写了大量 `//? if`，本 mod 不需要（load/save 走 `BedrockBreaker`，不用 malilib 持久化）。

### 8.7 可选依赖（malilib / modmenu）类加载隔离
- 引用 malilib 的类必须**在运行时 `isModLoaded("malilib")` 为真才加载**，且通过**反射**访问；`ModMenuImpl` 也反射加载 `MalilibGui`，避免"装了 modmenu 没装 malilib"时崩溃。
- fabric.mod.json **不写 `depends.malilib`**（选项）；modmenu entrypoint 是 modmenu 自己查的（天然可选）。

### 8.8 服务器端对 START/STOP 的处理（决定时序）
`ServerPlayerGameMode.handleBlockBreakAction`：
- `START_DESTROY_BLOCK`：`getDestroyProgress >= 1.0` 直接 `destroyAndAck`（instant）；否则记录进度。
- `STOP_DESTROY_BLOCK`：`destroyProgress >= 0.7` 立即破坏；否则进入 `hasDelayedDestroy` 延迟破坏。
- 所以**瞬间挖掘时是 START 直接触发破坏**，慢速时是 STOP 触发——两条路径都由客户端 `destroyBlock` 的 mixin 收敛。

### 8.9 挖掘的二次触发
- instant 路径下 `destroyBlockPos` 未设置 → 后续 `continueDestroyBlock`（`Minecraft.continueAttack` 每帧调）走 else → 重新 `startDestroyBlock` → 把**刚放的新活塞**再挖掉（旧 bug：副手+瞬间掉 2 个活塞）。**新活塞保护窗口（100ms）** 拦截 `continueDestroyBlock`/`startDestroyBlock` 对该位置的二次挖掘（`BedrockBreaker.isProtected`）。

---

## 9. Debug 指引

- **日志**：`BedrockBreaker.LOGGER`（name "bedrock-miner-extra"），挖掘相关有 info 日志（`mined piston detected`、`cut power`、`placing fresh piston`）。游戏日志在 `run/logs/latest.log`。
- **手动模式失效**排查顺序：是不是挖了粘性活塞？活塞头是不是朝空气？相邻有没有白名单方块且距离 2 格内有**通电**拉杆？热栏/副手有没有活塞？
- **自动模式失败**：看 action bar 提示（空间不足 / 被红石充能 / 缺镐 / 破基岩失败）；先确认它是"放活塞→开拉杆→挖"哪一步卡住（调高日志级别 / 临时加 LOGGER.info 观察状态机流转）。
- **横向（水平）破基岩失败**：优先检查 `yHeadRot` 延迟相关代码（是否预发了视角包并等 1 tick），以及 `MINE_ROTATE` 是否在挖之前发了朝向基岩的视角包。
- **Gradle 构建崩（GC thrashing）**：用 `-Xmx4G`；构建慢/卡先看是否在拉新版本的 malilib/modmenu 依赖。
- **mixin 注入失效**：确认目标方法名/签名在目标版本存在（`javap -c -p -classpath <minecraft jar> net.minecraft.client.multiplayer.MultiPlayerGameMode`），且 `mixins.json` 的 `compatibilityLevel` 与 `defaultRequire: 1` 匹配。

---

## 10. 参考项目

- **bedrock-miner**（`C:\Users\CXY\Desktop\Java\Fabric-Bedrock-Miner-main`）：全自动破基岩 + malilib/modmenu 的参考实现，用 Kotlin + 协程。它的 `ApproachBase.findBest`（找位置）、`placePistonAfter`（先发视角等 1 tick 再放）、`Configs`（malilib 配置 + `//? if` 处理 malilib API 差异）很值得参考。
