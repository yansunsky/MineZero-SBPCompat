package com.yansunsky.mzextension.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 持久化玩家 ForgeData（{@code getPersistentData()}）快照。
 * <p>在检查点保存时捕获所有在线玩家的 ForgeData，回档时恢复，
 * 确保其他模组的自定义玩家数据不会丢失。</p>
 */
public class PersistentDataHelper {
    static final String NBT_KEY = "forgeDataSnapshot";
    /** 当前未持久化的快照：UUID → ForgeData CompoundTag */
    static final Map<UUID, CompoundTag> snapshot = new HashMap<>();

    /** 捕获单个玩家的 ForgeData */
    public static void capture(ServerPlayer player) {
        CompoundTag forge = player.getPersistentData();
        if (forge != null && !forge.isEmpty()) {
            snapshot.put(player.getUUID(), forge.copy());
        } else {
            snapshot.remove(player.getUUID());
        }
    }

    /** 应用快照到单个玩家 */
    public static void apply(ServerPlayer player) {
        CompoundTag saved = snapshot.get(player.getUUID());
        if (saved != null && !saved.isEmpty()) {
            player.getPersistentData().merge(saved);
        }
    }

    /** 将快照写入 CheckpointData NBT（跨重启持久化） */
    public static void writeSnapshot(CompoundTag nbt) {
        CompoundTag out = new CompoundTag();
        for (var entry : snapshot.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                out.put(entry.getKey().toString(), entry.getValue());
            }
        }
        if (!out.isEmpty()) {
            nbt.put(NBT_KEY, out);
        }
    }

    /** 从 CheckpointData NBT 读取快照 */
    public static void readSnapshot(CompoundTag nbt) {
        snapshot.clear();
        if (!nbt.contains(NBT_KEY)) return;
        CompoundTag data = nbt.getCompound(NBT_KEY);
        for (String key : data.getAllKeys()) {
            try {
                UUID uuid = UUID.fromString(key);
                CompoundTag forgeData = data.getCompound(key);
                if (!forgeData.isEmpty()) {
                    snapshot.put(uuid, forgeData);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
