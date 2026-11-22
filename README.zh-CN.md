# Auto Tool Refill（自动补位）

[English](README.md) | **简体中文**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.9.4-green)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-12.17.0.2051-orange)](https://files.minecraftforge.net/)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

挖矿挖到一半镐子爆了——不用手忙脚乱翻背包，这个模组会把包里的备用工具直接塞进那个快捷栏槽位；如果连备用工具都没有，就用你身上带的材料现场合成一把。不需要工作台，不弹界面，不打断操作。

> 如果这份说明你只记住一件事：**1.9.4 里工具耐久耗尽根本不会发 Forge 事件。** 所以这个模组比听上去要复杂一些，原因见下面的[实现原理](#实现原理)。

---

## 功能

- **补位** —— 工具坏掉的瞬间，背包里最合适的同类工具顶上去。优先级：同款物品 → 等级更高 → 剩余耐久更多。只剩 1 点耐久的工具只在实在没别的时才会用。
- **合成** —— 没有备用工具？现场合一把。先试同级，再按 钻石 → 铁 → 石 → 木 → 金 的顺序，看你手上的材料够哪一档。
- **帮不上忙就安静** —— 没工具也没材料：什么也不做，不提示、不打扰。
- **原版与模组工具** —— 支持原版镐 / 斧 / 铲 / 剑 / 锄。其它模组的工具只会从背包里换同款，不会合成（只认原版配方）。
- **不会崩游戏** —— 每个处理入口都包了兜底，日志也做了限流。
- **本地化** —— 内置简体中文与英文。

## 实现原理

1.9.4 原版里，工具耐久归零**不会**触发任何 Forge 事件：

- `ItemStack.damageItem` 只是把 `stackSize` 减到 `0`、把 `itemDamage` 清零；
- 紧接着服务端 `PlayerInteractionManager.tryHarvestBlock` 直接 `setHeldItem(null)`，中间没有任何 hook；
- `PlayerDestroyItemEvent` 只在**客户端**由 `PlayerControllerMP` 抛出，且条件是 `stackSize <= 0`。

所以只监听 `PlayerDestroyItemEvent` 的模组，在镐子真的用坏时永远不会触发。本模组用了三条路径：

| # | 触发 | 覆盖场景 |
|---|------|----------|
| A | 客户端 `PlayerDestroyItemEvent` | 原版破坏路径，换算成服务端玩家后排队 |
| B | 服务端每 tick 扫描：背包里残留 `stackSize <= 0` 的空壳 | 剑砍怪、VeinMiner 连到一半中断等留下空壳的情况 |
| C | 服务端每 tick 比对：上一 tick 手上是「正在用」的工具，这一 tick 槽位空了 | 最普通的「挖矿挖到镐子报废」 |

两个关键细节：

- **补位要排队等两个 tick。** 必须等服务端把槽位真正清空之后再写，否则紧接着的 `setHeldItem(null)` 会把刚补上的新工具一起抹掉。
- **「正在用」的判定**：40 tick 内耐久掉过，或者只剩 1 点耐久。这样只是切走工具、把工具丢出去时，不会误判成坏掉。

## 安装

1. Minecraft **1.9.4** + Forge **12.17.0.2051**（任意 1.9.4 的 Forge 版本均可）。
2. 把 `autorefill-1.2.0.jar` 放进 `.minecraft/mods/`。
3. 完事。纯客户端模组，服务端不需要装（`acceptableRemoteVersions = "*"`）。

jar 在 [Releases](../../releases) 里下载。

## 配置

`config/autorefill.cfg`：

```ini
general {
    B:enableRefill=true      # 工具坏了从背包补位
    B:enableAutoCraft=true   # 没备用工具就用材料合成
    B:showMessage=true       # 成功时聊天栏提示
}
```

首次启动自动生成。

## 已知限制

- **只对单人 / 自己开的局域网房有效。** 补位必须发生在服务端，连别人的远程服务器时改不了你的背包（客户端改了只会导致不同步）。
- **创造模式跳过。** 那里不需要。
- 合成只认**原版配方**。模组工具能从背包换同款，但不会合成。
- 已知小瑕疵：如果你把那把「正在用」的工具在 40 tick 内丢出去、或者移出快捷栏，可能被误判成坏掉而补一把。概率很低，真觉得烦就关掉 `enableRefill`。

## 自行构建

源码用的是 1.9.4 的 MCP 名称。

**需要的工具链：** JDK 8、Gradle 2.14.1、ForgeGradle 2.2（`mappings = "snapshot_20160524"`）。

```bash
gradle build     # -> build/libs/autorefill-1.2.0.jar
```

要先说清楚：这是 2016 年的工具链，MCP 快照 `20160524` 已从 Forge maven 下架，`gradle build` 今天不一定能直接跑通。发布出来的 jar 是用手工 MCP 工具链（Forge `deobfuscation_data` + MCP CSV + SpecialSource）构建的，完整步骤见 [docs/BUILD-NOTES.md](docs/BUILD-NOTES.md)。

## 许可证

[MIT](LICENSE)。

与 Mojang / Microsoft 无关。Minecraft 是 Mojang Synergies AB 的商标。
