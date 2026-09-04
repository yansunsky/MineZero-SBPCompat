# MineZero Extension

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green.svg)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.172-orange.svg)](https://neoforged.net/)

MineZero 拓展附属模组。这是一个完全由DeepSeekV4生成的项目，除非万不得已我不打算自己检阅代码。这只是一项实验。

## Features

### 1. Sophisticated Backpacks Compatibility
MineZero 触发回归后，精妙背包内的物品正确回档。通过 Mixin 注入实现。

### 2. 安全条件触发检查点
当玩家处于安全状态（主世界、白天、满血、满饥饿、无负面效果、无敌对生物）时，通过**级联概率**自动创建检查点。每个条件先掷骰再判断，减少性能开销。

启用后自动关闭 MineZero 原生的 `autoCheckpointEnabled`。

## Dependencies

| 模组 | 要求 |
|------|------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.0+ |
| [MineZero](https://github.com/AMPerez04/MineZero) | 1.0+ (required) |
| Sophisticated Backpacks | 3.20+ (optional) |

## Commands

| 命令 | 说明 |
|------|------|
| `/gamerule safeCheckpointEnabled true\|false` | 运行时切换安全检查点 |
| `/minezeroextension debugmode true\|false` | 开启/关闭保存通知广播 |

## Configuration

`config/minezero_extension-common.toml`：

```toml
[safeCheckpoint]
enabled = true                    # 初始默认状态。false = 使用 MineZero 原生
checkIntervalTicks = 400          # 20秒
enemySearchRadius = 24

[safeCheckpoint.conditions]
overworld = true
daytime = true
healthFull = true
hungerFull = true
noNegativeEffects = true
noHostileNearby = true

[safeCheckpoint.chances]
overworldChance = 0.90
daytimeChance = 0.75
healthFullChance = 0.75
hungerFullChance = 0.55
noNegativeEffectsChance = 0.60
noHostileNearbyChance = 1.00
```

累积: 0.90 × 0.75 × 0.75 × 0.55 × 0.60 × 1.00 ≈ 0.167 → ~2分钟1次。

## Build

```bash
./gradlew jar
```

## License

Apache License 2.0
