# Bedrock Miner Extra

**[English](README.md) | [简体中文](README_zh-CN.md)**

A **client-side** Fabric mod that lets you break bedrock in survival using the **vanilla headless-piston trick** (Mojang bug **MC-27056**). Mine the piston at **any speed** — slow hand-mining *or* instant-mining with an Efficiency V pickaxe + Haste II both work: the mod handles both vanilla mining paths at the `MultiPlayerGameMode.destroyBlock` entry point. A built-in **auto mode** left-clicks bedrock to place the piston + lever and break it for you, with a 5-slot queue.

**Supported versions**: 26.2 / 26.1 / 26.1.2, 1.21.11 / 1.21.1 / 1.21, 1.20.6 / 1.20.5 / 1.20.1 (one source tree, Stonecutter multi-version build).

> This mod does **not** modify any vanilla behaviour: it only sends the same packets a tick-perfect player would send. The bedrock breaking itself comes from the vanilla bug.

## Features

- Break bedrock (or any whitelisted block) in survival
- **Any mining speed**: slow hand-mining or instant-mining (Efficiency V + Haste II) both work
- **Auto mode** (`/bedrockMinerExtra auto`): left-click bedrock to auto-place a piston + lever and break it, with a 5-slot FIFO queue (in-range clicks are prioritized)
  - **Searches another face** when the piston slot is blocked (e.g. by the player standing there) instead of failing
  - **Exact lever placement**: prefers the target bedrock itself, then the piston's side — the attachment face is deterministic, independent of the server's view lag
  - **Auto-cleanup**: after a successful break, leftover pistons (no bedrock neighbour / cannot extend) and levers are mined away automatically
- Fully **client-side** — works on any server, nothing to install there
- Toggleable with `/bedrockMinerExtra`; the two switches are **linked** — turning the mod off also turns auto mode off, and turning auto mode on keeps the manual flow on (auto mode depends on it)
- **Block whitelist**: add other blocks to break with the same trick
- All status messages (success, no space, failure) are shown in the **action bar** (above the hotbar, like vanilla prompts), not the chat
- Settings persisted to `config/bedrock-miner-extra.json`

## How to use

1. Place a piston **next to** the block you want to break, with the piston head facing **air** so it can extend freely (a piston blocked by bedrock does *not* work).
2. Power the piston with a **lever** placed next to it, so it extends.
3. Mine the piston at **any speed** — including instant-mining with an Efficiency V + Haste II pickaxe.
4. The mod cuts the power and places a fresh piston **facing the target block** right after the break packet, in the same server tick — the vanilla bug then replaces the target block with a piston head.

```
Example: breaking a nether ceiling bedrock layer (standing below it)
  [bedrock][bedrock][bedrock]      ← the layer to break
  [ piston→][lever]                ← piston head faces air (horizontal), lever next to it
  Mine the piston → the mod places a fresh piston facing up (towards the bedrock)
  and cuts the lever → bedrock breaks
```

Works best vertically (e.g. mining the nether/end bedrock layers); horizontal facings work too (the view-rotation lag is handled, see below).

## Auto mode

Enable it with `/bedrockMinerExtra auto`, then **left-click bedrock** to break it automatically:

1. It checks the 6 neighbour faces (closest to the player first) for a spot where a piston can be placed with its head facing air (so it can extend) plus a lever slot; if none exists you get a "no space" action-bar message. **Existing setups are reused**: an already-placed extendable piston next to the bedrock is used as-is (no new piston placed), and an existing lever that powers it is reused too (flipped on automatically if it is off — its state is checked).
2. It places the piston (head facing air; the rotation packet is sent one tick before placement so the server derives the correct facing) and the lever — **preferring a spot on the target bedrock itself** (the lever powers the bedrock, which powers the piston — 100% reliable), falling back to the piston's side; it then **flips the lever on** to extend the piston, and waits for it.
3. If the chosen piston slot is blocked (e.g. you are standing there), it **tries the other faces** instead of failing.
4. It switches to the hotbar item with the highest mining speed on pistons (26.x has no pickaxe class — ranked by actual destroy speed), mines the piston, and the manual mechanism above does the rest.
5. After the bedrock breaks, it **cleans up**: pistons it placed that no longer have a bedrock neighbour (or cannot extend) and any leftover levers are mined away automatically.
6. Missing piston/lever/tool in the hotbar → action-bar message, skipped.

**Queue** (max 5, FIFO, oldest dropped when full): clicks on out-of-range bedrock are queued and processed once you get close; clicks in range are prioritized (moved to the head). **Turning auto mode off clears the queue.** Each break costs 2 pistons (1 setup + 1 replacement) and 1 lever; the leftover setup is auto-recovered afterwards.

## Commands

| Command | Description |
|---|---|
| `/bedrockMinerExtra` | Toggle the mod ON/OFF |
| `/bedrockMinerExtra on` / `off` | Set the state explicitly |
| `/bedrockMinerExtra auto` | Toggle auto mode |
| `/bedrockMinerExtra auto on` / `off` | Set auto mode explicitly |
| `/bedrockMinerExtra blockWhitelist add <block>` | Add a block to the whitelist (`obsidian` or `minecraft:obsidian`) |
| `/bedrockMinerExtra blockWhitelist remove <block>` | Remove a block from the whitelist |
| `/bedrockMinerExtra blockWhitelist list` | List whitelisted blocks |

Default whitelist: `minecraft:bedrock`.

## Trigger conditions (all must hold)

- The mined block is a **regular piston** (sticky pistons do not trigger);
- The piston head faces **air** (it can extend);
- A whitelisted block (bedrock) is adjacent to the piston — the fresh piston will face it;
- The piston is currently **powered**, and the power source is a **lever** within 2 blocks (the lever is turned off right before the piston is mined, so the fresh piston is placed un-powered);
- You have a **piston in your hotbar or offhand** (the mod switches to the hotbar slot, or places from the offhand, automatically).

## Notes & limitations

- **Lever only**: redstone torches are not supported (the protocol makes it impossible at normal mining speeds — the torch's break packet resets the server's mining progress).
- The mod protects the freshly placed piston for ~0.1s (2 ticks) after placement, blocking vanilla's re-mining (e.g. the instant-mining + offhand-piston case no longer mines the replacement away). Still, release the attack button after the bedrock breaks — holding it past the window may make vanilla mine the piston again.
- Horizontal facings: the server's one-tick view-rotation delay is solved by pre-sending the rotation — the auto mode does it one tick before mining, and the manual mode does it while you aim at the piston (the server view is temporarily turned towards the bedrock while aiming, and restored when the crosshair leaves it — placing a block by right-clicking while aiming may use that facing).
- You need a **piston in your hotbar or offhand** for the mod to place the replacement.
- The mod depends on the vanilla MC-27056 bug; if Mojang ever fixes it, the mod stops working.
- On multiplayer servers, please ask other players first — the mod reminds you when you join a server with it enabled.

## Changelog (1.3.0 → 2.1.1)

### 2.1.1
- The two switches are now linked: `/bedrockMinerExtra off` also turns auto mode off; `/bedrockMinerExtra auto on` keeps the manual flow on (auto mode depends on it)

### 2.1.0
- Debug logging commented out (the code is kept — re-enable it when debugging)
- All status messages (no space / break failed / no slow-mining tool) now show in the **action bar** instead of the chat

### 2.0.18
- **Auto mode searches another face** when the piston slot is blocked (e.g. the player standing there): player-bounding-box filtering while picking a spot + failed-placement retry on the remaining faces
- **Exact lever placement**: the lever is placed by clicking the attach target's face (the bedrock or the piston side) — the attachment face no longer depends on the server's one-tick view lag; prefers the target bedrock, then the piston's side
- **Cache + auto-cleanup**: recently placed pistons/levers are remembered; after a successful break, useless pistons (no bedrock neighbour left, or unable to extend) and leftover levers are mined away automatically

### 2.0.17
- Faster finish: once mining progress is ≥ 0.7 (or after 0.2s of slow mining) the mod switches to the instant pickaxe and finishes the piston instantly
- Without an instant pickaxe, it falls back to the fastest safe (slow) tool and mines the piston out completely — still reliable

### 2.0.2 – 2.0.16 (auto-mode reliability pass)
- Reuse existing pistons and levers (the lever's on/off state is detected; flipped on automatically when needed)
- Player attack input is blocked while auto-mining, so it cannot corrupt the client mining state
- The replacement piston faces the exact target bedrock (`expectedBedrockDir`; safe with multi-layer bedrock)
- VERIFY state: waits for the bedrock removal to be confirmed before judging success/failure
- Leftover destroy delay is cleared before mining
- The freshly placed piston is protected (~0.1s) from vanilla re-mining
- Adapted to 26.x APIs (tool components, Efficiency enchantment handled manually, official mappings, `Gui` → `Hud`)

### 2.0.1
- Auto-mode fixes: the rotation packet is sent one tick before placing the piston (server `yHeadRot` lag); the lever is flipped on after placing (it defaults to off); lever placement prefers the target bedrock (powering the bedrock activates the piston 100%), then the piston's side, never the head direction

### 2.0.0
- **Auto mode**: left-click bedrock to auto-place piston + lever and break it — `AutoBreaker` state machine, 5-slot FIFO queue (in-range clicks prioritized), toggled with `/bedrockMinerExtra auto`

### 1.3.x
- Manual-mode refinements (baseline of this changelog; the core headless-piston breaking, any mining speed, and the lever-power cutoff were already in place)

## Development

Environment: JDK 26 (see `org.gradle.java.home` in `gradle.properties`), Gradle wrapper 9.5.1.

```bash
./gradlew build   # builds the active version (see stonecutter.gradle.kts)
```

Version lock: Minecraft `26.1.2` + Fabric Loader `0.19.3` + Fabric API `0.155.2+26.1.2` + Loom `1.17-SNAPSHOT`.

### Code structure

- `BedrockBreaker` — client-side core: trigger detection (extendable piston + adjacent bedrock + powered) and the packet sequence (cut power → mine → place the fresh piston facing the target);
- `AutoBreaker` — auto mode: queues left-clicks, and a client tick state machine does "find a spot → place piston + lever → wait for extension → mine the piston", reusing the `BedrockBreaker` headless flow; includes the blocked-slot retry, the placement cache and the post-success cleanup;
- `MultiPlayerGameModeMixin` — timing hooks: at the `destroyBlock` entry (both mining paths) it cuts the power and captures the scene, then sends the fresh-piston packets right after the break packet so the server processes "cut power → mine piston → place fresh piston" in one tick; also guards the fresh piston against vanilla re-mining;
- `EntityAccessor` / `MultiPlayerGameModeAccessor` — accessors for 26.1.2 privatized fields (view rotation, mining target/progress).

### Multi-version build (Stonecutter)

One source tree, `//? if` conditional version differences, generated per version.

- Build a specific version: `./gradlew :<version>:build` (e.g. `:1.21.11:build`, `:26.2:build`)
- Build the active version: `./gradlew build` (active = `stonecutter active "26.1.2"` in `stonecutter.gradle.kts`)
- Version list: `versions.json`; per-version dependencies in `stonecutter.properties.toml`
- Output: `versions/<version>/build/libs/bedrock-miner-extra-<mod.version>+<mc>.jar`
- Switch dev version: change `stonecutter active "..."` and the `"vcs"` entry in `versions.json`

> Compile requirements: Java 25 (26.x targets) / Java 21 (1.20.5–1.21.x) / Java 17 (1.20.1); `gradle.properties` already sets `org.gradle.java.home`.

## License

CC0-1.0
