# Bedrock Miner Extra

A **client-side** Fabric mod that lets you break bedrock in survival using the **vanilla headless-piston trick** (Mojang bug MC-27056) — **without** needing Haste II or an Efficiency V pickaxe. Mine the piston at any speed.

> This mod does **not** modify any vanilla behaviour: it only sends the same packets a tick-perfect player would send. The bedrock breaking itself comes from the vanilla bug.

## Features

- Break bedrock (or any whitelisted block) in survival
- **No Haste / no Efficiency pickaxe required** — mine the piston at any speed
- Fully **client-side** — works on any server, nothing to install there
- Toggleable with `/bedrockMinerExtra`
- **Block whitelist**: add other blocks to break with the same trick
- Settings persisted to `config/bedrock-miner-extra.json`

## How to use

1. Place a piston **next to** the block you want to break, with the piston head facing **air** so it can extend freely (a piston blocked by bedrock does *not* work).
2. Power the piston with a **lever** placed next to it, so it extends.
3. Mine the piston at any speed.
4. The mod instantly cuts the power and places a fresh piston **facing the target block** in the same server tick — the vanilla bug then replaces the target block with a piston head.

Works best vertically (e.g. mining the nether/end bedrock layers); horizontal facings usually work too.

## Commands

| Command | Description |
|---|---|
| `/bedrockMinerExtra` | Toggle the mod ON/OFF |
| `/bedrockMinerExtra on` / `off` | Set the state explicitly |
| `/bedrockMinerExtra blockWhitelist add <block>` | Add a block to the whitelist (`obsidian` or `minecraft:obsidian`) |
| `/bedrockMinerExtra blockWhitelist remove <block>` | Remove a block from the whitelist |
| `/bedrockMinerExtra blockWhitelist list` | List whitelisted blocks |

Default whitelist: `minecraft:bedrock`.

## Notes & limitations

- **Lever only**: redstone torches are not supported (the protocol makes it impossible at normal mining speeds — the torch's break packet resets the server's mining progress).
- Horizontal facings: a few fixed directions may occasionally fail due to the server's one-tick view-rotation delay; vertical breaking is 100%.
- You need a **piston in your hotbar** (the mod switches to it automatically).
- The mod depends on the vanilla MC-27056 bug; if Mojang ever fixes it, the mod stops working.
- On multiplayer servers, please ask other players first — the mod reminds you when you join a server with it enabled.

## Compatibility

- Minecraft **26.1.2**, Fabric Loader ≥ 0.19.3, Fabric API
- Client-side only; works in singleplayer, LAN, and on servers (the server does not need the mod installed)

## License

CC0-1.0
