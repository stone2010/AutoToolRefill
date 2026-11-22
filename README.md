# Auto Tool Refill

**English** | [简体中文](README.zh-CN.md)

[![Minecraft](https://img.shields.io/badge/Minecraft-1.9.4-green)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-12.17.0.2051-orange)](https://files.minecraftforge.net/)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

Your pickaxe breaks halfway through a tunnel. Instead of the empty-hand scramble, this mod drops a spare from your inventory into the same hotbar slot — or, if you have no spare, crafts one on the spot from the materials you're already carrying. No crafting table, no GUI, no interruption.

> If you only take one thing from this README: **in 1.9.4 a tool wearing out fires no Forge event at all.** That is why this mod looks more complicated than it sounds. Details in [How it works](#how-it-works).

---

## Features

- **Swap** — the instant a tool breaks, the best same-type tool in your inventory takes its slot. Priority: identical item → higher tier → more durability remaining. A tool with a single point of durability left is only used as a last resort.
- **Craft** — no spare? It crafts one on the spot, trying the same tier first, then diamond → iron → stone → wooden → golden, whichever you can afford.
- **Silent when it can't help** — no spare, no materials: nothing happens. No message, no noise.
- **Vanilla and modded** — handles vanilla pickaxe / axe / shovel / sword / hoe. Damaged tools from other mods are swapped if you have a spare, but never crafted, since only vanilla recipes are known.
- **Never crashes** — every handler is wrapped in a catch-all, and log output is rate-limited.
- **Localized** — English and 简体中文 out of the box.

## How it works

In 1.9.4 vanilla, a tool reaching zero durability does **not** fire a Forge event:

- `ItemStack.damageItem` just decrements `stackSize` to `0` and resets `itemDamage`;
- `PlayerInteractionManager.tryHarvestBlock` then calls `setHeldItem(null)` — with no hook in between;
- `PlayerDestroyItemEvent` is only posted **client-side** by `PlayerControllerMP`, and only when `stackSize <= 0`.

A mod that only listens to `PlayerDestroyItemEvent` therefore never fires when your pickaxe actually wears out. This one uses three paths:

| # | Trigger | Covers |
|---|---------|--------|
| A | client `PlayerDestroyItemEvent` | the vanilla break path, mapped to the server player and queued |
| B | server tick: a leftover `stackSize <= 0` stack in the inventory | sword hits, VeinMiner aborting mid-vein, anything that leaves a shell behind |
| C | server tick: held slot had a tool *in use* last tick, empty this tick | the plain "pickaxe wore out while mining" case |

Two details that matter:

- **Refills are queued for a couple of ticks.** The write has to happen *after* the server clears the slot, otherwise the following `setHeldItem(null)` wipes the freshly placed tool straight out again.
- **"In use"** means the tool lost durability within the last 40 ticks, or has one point of durability left. That is what stops trigger C from firing when you merely scroll away from a tool or throw it out.

## Install

1. Minecraft **1.9.4** with Forge **12.17.0.2051** (any 1.9.4 Forge build works).
2. Drop `autorefill-1.2.0.jar` into `.minecraft/mods/`.
3. Done. It is client-side; servers do not need it (`acceptableRemoteVersions = "*"`).

Download the jar from [Releases](../../releases).

## Configuration

`config/autorefill.cfg`:

```ini
general {
    B:enableRefill=true      # swap a spare tool from the inventory
    B:enableAutoCraft=true   # craft one from materials when no spare exists
    B:showMessage=true       # chat message on success
}
```

The file is created on first launch.

## Limitations

- **Single-player and LAN hosts only.** The refill has to happen server-side, so on a remote server this mod cannot touch your inventory — a client-side change would simply desync.
- **Creative mode is ignored.** You do not need it there.
- Crafting knows **vanilla recipes only**. Modded tools are swapped when a spare exists, never crafted.
- Known edge case: if you drop a tool that is *currently in use*, or move it out of the hotbar, within that 40-tick window, it can be misread as "broken" and get a replacement. Rare. Disable `enableRefill` if it ever bothers you.

## Building

The source is written in MCP names for 1.9.4.

**Expected toolchain:** JDK 8, Gradle 2.14.1, ForgeGradle 2.2 (`mappings = "snapshot_20160524"`).

```bash
gradle build     # -> build/libs/autorefill-1.2.0.jar
```

A caveat worth stating up front: this is a 2016-era toolchain. The MCP snapshot artifact `20160524` has been pruned from the Forge maven, so `gradle build` may not resolve out of the box today. The released jar was produced with a manual MCP toolchain (Forge `deobfuscation_data` + MCP CSVs + SpecialSource) — see [docs/BUILD-NOTES.md](docs/BUILD-NOTES.md) if you need to reproduce it exactly.

## License

[MIT](LICENSE).

Not affiliated with Mojang or Microsoft. Minecraft is a trademark of Mojang Synergies AB.
