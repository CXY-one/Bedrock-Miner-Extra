# Bedrock Miner Extra

Fabric **纯客户端**模组：在生存模式中利用原版"无头活塞"特性（Mojang bug **MC-27056**）破坏基岩——**不需要急迫效果、不需要高等级镐**，普通速度挖活塞即可。

**支持版本**：26.2 / 26.1 / 26.1.2、1.21.11 / 1.21.1 / 1.21、1.20.6 / 1.20.5 / 1.20.1（Stonecutter 多版本构建，一套源码）。

## 特点

- **纯客户端**：只运行在客户端，服务器无需安装；单机、自建服、远程服务器均可使用（mod 只向服务器发送模拟玩家操作的网络包）。
- **不修改任何原版特性**：不直接破坏基岩、不改方块属性；mod 只是自动完成"同一刻内切断信号 + 放置朝向基岩的新活塞"这个人类做不到的 tick 级操作，基岩破坏完全来自原版 bug。
- **不限挖掘速度**：空手慢慢挖也行（对比同类 mod 需要 效率V镐 + 急迫II 的瞬间破坏）。
- **可开关**：用 `/bedrockMinerExtra` 指令随时开/关（也支持 `/bedrockMinerExtra on|off`），状态保存到 `config/bedrock-miner-extra.json`；开启/进世界时聊天栏提示 `Bedrock Miner Extra 已启动！`，在联机服务器使用时额外提示先征询其他玩家意见。
- **可破方块白名单**：默认只有基岩；可用 `/bedrockMinerExtra blockWhitelist add <方块名>` 加入其它方块（同样方法破）、`remove` 删除、`list` 查看。

## 使用方法（重要：活塞头不能朝基岩）

1. **放置活塞**：放在基岩**旁边或上下任意一面**，但**活塞头必须朝向空气**（活塞能被充能后正常伸出）。活塞头被基岩挡住是**无法**破基岩的。
2. **充能**：拉杆放在活塞**旁边**（侧面、底面都可以，只要能让活塞充能伸出；不要放活塞顶面，也不必附着在活塞上）。
3. **挖掉活塞**（任意速度）。
4. mod 在挖掘完成前的一瞬间自动：**先瞬间取消充能**（关掉拉杆 / 打掉红石火把，让活塞处于失去信号的状态）→ 再在活塞原位放置一个**朝向基岩**的新活塞 → 服务器同一刻处理 → 原版无头活塞特性触发，**基岩被破坏**（无掉落）。

```
示例：破下界顶部基岩（站在基岩层下方）
  [基岩][基岩][基岩]        ← 要破的基岩层
  [ 活塞→][拉杆]            ← 活塞头朝水平（空气，能伸出），拉杆放活塞旁边
  挖掉活塞 → mod 原位放一个头朝上（指向基岩）的活塞 + 关拉杆 → 基岩破
```

## 触发条件（全部满足才生效）

- 被挖掉的是**普通活塞**（粘性活塞不触发）；
- 活塞头方向是**空气**（活塞能伸出）；
- 活塞**旁边任意一面**存在**基岩**（新活塞将朝向它）；
- 活塞当前**处于充能状态**，且充能源是附近（2 格内）的**拉杆**（拉杆在挖掘完成前被关闭，保证新活塞放置时已断电）；
- 玩家**热栏里有活塞**（mod 自动切换到活塞槽并放置）。

## 指令

```
/bedrockMinerExtra                    开关切换（ON/OFF）
/bedrockMinerExtra on | off           显式开/关
/bedrockMinerExtra blockWhitelist add <方块名>   添加可破方块（如 obsidian、minecraft:obsidian）
/bedrockMinerExtra blockWhitelist remove <方块名> 移除
/bedrockMinerExtra blockWhitelist list           查看白名单
```

方块名支持 `bedrock` 简写（自动补 `minecraft:` 前缀）或完整 ID `minecraft:bedrock`；未知方块会提示错误。白名单持久化在 `config/bedrock-miner-extra.json`。

## 已知限制

- 竖直方向（基岩在活塞正上/正下方）100% 稳定；水平方向（基岩在活塞侧面）通过"提前发送视角包"解决 yaw 延迟，多数情况可用，若个别朝向仍失败请优先采用竖直布置。
- 若热栏没有活塞，mod 会跳过（日志警告），基岩不会被破坏。
- **不支持红石火把充能**：headless 特性要求"先断信号再挖活塞"，但火把只能靠挖掘包破坏（会重置服务器挖掘进度，普通速度下活塞无法同 tick 破坏），协议层无法实现；请使用拉杆充能。
- 依赖原版 MC-27056 特性：若未来 Mojang 修复该 bug，本 mod 将失效。

## 开发

环境：JDK 26（`gradle.properties` 中 `org.gradle.java.home` 指向 `C:/Program Files/Java/jdk-26.0.2`）、Gradle wrapper 9.5.1。

```bash
./gradlew build   # 编译打包（产物在 build/libs/bedrock-miner-extra-*.jar）
```

版本锁定：Minecraft `26.1.2` + Fabric Loader `0.19.3` + Fabric API `0.155.2+26.1.2` + Loom `1.17-SNAPSHOT`。

## 代码结构

- `BedrockBreaker` — 纯客户端核心：判定触发条件（活塞能伸出 + 旁有基岩 + 带电）、发包序列（断信号 → 切活塞 → 放置朝向基岩的新活塞）；
- `MultiPlayerGameModeMixin` — 时序钩子：在客户端挖掘完成前先发"断信号"包，再在挖掘完成包（`STOP_DESTROY_BLOCK`）发送之后紧跟"放新活塞"的包，保证服务器同刻按"断信号 → 挖活塞 → 放新活塞"处理；
- `EntityAccessor` / `MultiPlayerGameModeAccessor` — 26.1.2 私有化字段（视角、挖掘目标/进度）的访问器。


## 多版本构建（Stonecutter）

本项目用 **Stonecutter** 维护多版本：一套源码，`//? if` 条件化版本差异，按版本生成并构建。

- **构建指定版本**：`./gradlew :<版本>:build`，如 `./gradlew :1.21.11:build`、`./gradlew :26.2:build`
- **构建当前 active 版本**：`./gradlew build`（active 版本 = `stonecutter.gradle.kts` 中 `stonecutter active "26.1.2"`）
- **版本列表**：`versions.json`；各版本依赖（fabric-api 等）在 `stonecutter.properties.toml`
- **产物**：`versions/<版本>/build/libs/bedrock-miner-extra-<版本号>+<MC版本>.jar`
- **切换开发版本**：改 `stonecutter.gradle.kts` 的 `stonecutter active "..."` 与 `versions.json` 的 `"vcs"`

> 编译要求：Java 25（26.x 目标）/ Java 21（1.20.5~1.21.x）/ Java 17（1.20.1）；本项目 `gradle.properties` 已指定 `org.gradle.java.home`。
