# Bedrock Miner Extra

A **client-side** Fabric mod that lets you break bedrock in survival using the **vanilla headless-piston trick** (Mojang bug MC-27056). Mine the piston at **any speed** — slow hand-mining *or* instant-mining with an Efficiency V pickaxe + Haste II both work: the mod handles both vanilla mining paths at the `MultiPlayerGameMode.destroyBlock` entry point. A built-in **auto mode** left-clicks bedrock to place the piston + lever and break it for you, with a 5-slot queue.

> This mod does **not** modify any vanilla behaviour: it only sends the same packets a tick-perfect player would send. The bedrock breaking itself comes from the vanilla bug.

## Features

- Break bedrock (or any whitelisted block) in survival
- **Any mining speed**: slow hand-mining or instant-mining (Efficiency V + Haste II) both work
- **Auto mode** (`/bedrockMinerExtra auto`): left-click bedrock to auto-place a piston + lever and break it, with a 5-slot FIFO queue (in-range clicks are prioritized)
  - **Searches another face** when the piston slot is blocked (e.g. by the player standing there) instead of failing
  - **Exact lever placement**: prefers the target bedrock itself, then the piston's side — the attachment face is deterministic, independent of the server's view lag
  - **Auto-cleanup**: after a successful break, leftover pistons (no bedrock neighbour / cannot extend) and levers are mined away automatically
- Fully **client-side** — works on any server, nothing to install there
- Toggleable with `/bedrockMinerExtra`; the two switches are **linked** — turning the mod off also turns auto mode off, and turning auto mode on keeps the manual flow on
- **Block whitelist**: add other blocks to break with the same trick
- All status messages (success, no space, failure) are shown in the **action bar** (above the hotbar, like vanilla prompts), not the chat
- **Config GUI (malilib, all versions)**: toggle the mod / auto mode, edit the whitelist and set the open-GUI hotkey — **optional**, detected at runtime (also reachable from **Mod Menu** and `/bedrockMinerExtra gui`)
- Settings persisted to `config/bedrock-miner-extra.json`

## How to use

1. Place a piston **next to** the block you want to break, with the piston head facing **air** so it can extend freely (a piston blocked by bedrock does *not* work).
2. Power the piston with a **lever** placed next to it, so it extends.
3. Mine the piston at **any speed** — including instant-mining with an Efficiency V + Haste II pickaxe.
4. The mod cuts the power and places a fresh piston **facing the target block** right after the break packet, in the same server tick — the vanilla bug then replaces the target block with a piston head.

Works best vertically (e.g. mining the nether/end bedrock layers); horizontal facings usually work too.

## Auto mode

Enable it with `/bedrockMinerExtra auto`, then **left-click bedrock** to break it automatically:

1. It checks the 6 neighbour faces (closest to the player first) for a spot where a piston can be placed with its head facing air (so it can extend) plus a lever slot; if none exists you get a "no space" action-bar message. **Existing setups are reused**: an already-placed extendable piston next to the bedrock is used as-is (no new piston placed), and an existing lever that powers it is reused too (flipped on automatically if it is off — its state is checked). **Flat bedrock layers** (e.g. breaking the nether roof, y128, from above): when every neighbour of the target is bedrock, the piston is placed on top of the target with its head pointing up, and the lever goes on top of an adjacent bedrock block — power flows lever → bedrock → target → piston. **Already-powered spots**: an existing lever powering the lever's attach block (even off-axis) is reused; a non-lever source (dust, repeater, button, ...) makes the spot skipped with a "position powered by redstone" message instead of wasting blocks. **Tab completion**: `blockWhitelist add` completes every registered block id, `remove` completes the current whitelist.
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
| `/bedrockMinerExtra gui` | Open the config GUI (requires malilib) |

Default whitelist: `minecraft:bedrock`.

## Config GUI (malilib)

On any supported version, if **malilib** is installed, the mod adds a config screen to toggle the mod / auto mode, add/view/remove whitelist blocks, and set the hotkey that opens the GUI (default `LEFT_ALT + B`). **malilib and Mod Menu are optional** — without them everything works through the commands only.

Open it with the hotkey, `/bedrockMinerExtra gui`, or **Mod Menu → Bedrock Miner Extra → Config**. It reads/writes the same `config/bedrock-miner-extra.json` as the commands.

## Notes & limitations

- **Lever only**: redstone torches are not supported (the protocol makes it impossible at normal mining speeds — the torch's break packet resets the server's mining progress).
- The mod protects the freshly placed piston for ~0.1s (2 ticks) after placement, blocking vanilla's re-mining (e.g. the instant-mining + offhand-piston case no longer mines the replacement away). Still, release the attack button after the bedrock breaks — holding it past the window may make vanilla mine the piston again.
- Horizontal facings: the server's one-tick view-rotation delay is solved by pre-sending the rotation — the auto mode does it one tick before mining, and the manual mode does it while you aim at the piston (the server view is temporarily turned towards the bedrock while aiming, and restored when the crosshair leaves it — placing a block by right-clicking while aiming may use that facing). In auto mode the **lever's attachment face** is decided by the clicked face, so it is not affected by this lag.
- You need a **piston in your hotbar or offhand** (the mod switches to the hotbar slot, or places from the offhand, automatically).
- The mod depends on the vanilla MC-27056 bug; if Mojang ever fixes it, the mod stops working.
- On multiplayer servers, please ask other players first — the mod reminds you when you join a server with it enabled.

## Compatibility

- Minecraft **26.2 / 26.1 / 26.1.2 / 1.21.11 / 1.21.1 / 1.21 / 1.20.6 / 1.20.5 / 1.20.1 / 1.19.4**, Fabric Loader ≥ 0.19.3, Fabric API. **1.18.2 and older are not supported** (no block-break prediction system before 1.19.3 — the headless trick there needs instant mining inside the retraction window, which conflicts with this mod's any-speed design).
- **Optional**: malilib (config GUI, all versions) and Mod Menu (a "config" entry); both are detected at runtime and never required.
- Client-side only; works in singleplayer, LAN, and on servers (the server does not need the mod installed)

## License

CC0-1.0
