package boomcow.minezero.extension;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Curios 饰品栏快照辅助类。
 * <p>
 * Curios 的饰品栏物品存储在玩家的 {@code ICuriosItemHandler}（Capability）中，
 * 不包含在玩家的常规背包数据中。MineZero 的回档机制可能不会正确处理这些饰品栏物品。
 * <p>
 * 本类通过在检查点保存时捕获 Curios 背包快照、恢复时写回，
 * 来解决此不兼容问题。尤其解决了精妙背包放在饰品栏中的场景。
 * <p>
 * 使用 {@code ICuriosItemHandler.saveInventory(false)} 保存物品（不清理槽位），
 * {@code loadInventory(ListTag)} 恢复物品到正确的槽位类型。
 *
 * @see SBPBackpackHelper 精妙背包兼容（全局 BackpackStorage 快照）
 * @see PersistentDataHelper ForgeData 快照
 */
public class CuriosHelper {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String NBT_KEY = "curiosSnapshot";

    // ====== 调试计数器 ======
    private static int captureCallCount = 0;
    private static int applyCallCount = 0;

    /**
     * 当前未持久化的快照：UUID → ListTag（玩家饰品栏数据）。
     * <p>每次 setCheckpoint 调用后更新此值；
     * CheckpointDataMixin.save 将其写入 CheckpointData NBT 以支持跨重启持久化。</p>
     */
    private static final Map<UUID, ListTag> pendingSnapshot = new HashMap<>();

    /**
     * 从 CheckpointData NBT 反序列化加载的快照。
     * <p>CheckpointDataMixin.load 中设置此值；
     * restoreCheckpoint 时优先使用此值（如果有），否则回退到 pendingSnapshot。</p>
     */
    private static CompoundTag loadedSnapshot = null;

    // ============ 公开 API ============

    /**
     * 捕获单个玩家的 Curios 饰品栏快照。
     * <p>在 {@code CheckpointManager.setCheckpoint()} 的 TAIL 处由 Mixin 调用。</p>
     *
     * @param player 要捕获饰品栏的玩家
     */
    public static void captureInventory(ServerPlayer player) {
        if (player == null) return;
        captureCallCount++;
        long tick = player.level().getGameTime();
        String name = player.getName().getString();

        LOGGER.info("[MExt Curios DEBUG] ===== captureInventory #{} tick={} player={} =====",
                captureCallCount, tick, name);
        try {
            CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
                // 捕获前先打印当前饰品栏状态
                LOGGER.info("[MExt Curios DEBUG]   PRE-capture state:");
                for (Map.Entry<String, ICurioStacksHandler> entry : handler.getCurios().entrySet()) {
                    ICurioStacksHandler sh = entry.getValue();
                    IDynamicStackHandler stacks = sh.getStacks();
                    for (int i = 0; i < stacks.getSlots(); i++) {
                        ItemStack s = stacks.getStackInSlot(i);
                        if (!s.isEmpty()) {
                            LOGGER.info("[MExt Curios DEBUG]     slot {}[{}]: {} x{} tags={}",
                                    entry.getKey(), i,                                     s.getHoverName().getString(),
                                    s.getCount(), s.getTag());
                        }
                    }
                }

                ListTag data = handler.saveInventory(false); // false = 不清理槽位
                LOGGER.info("[MExt Curios DEBUG]   saveInventory returned: isEmpty={} size={} raw={}",
                        data.isEmpty(), data.size(), data);

                if (data != null && !data.isEmpty()) {
                    pendingSnapshot.put(player.getUUID(), data);
                    LOGGER.info("[MExt Curios DEBUG]   Capture #{} STORED: {} curios stacks for {}",
                            captureCallCount, data.size(), name);
                } else {
                    // 空饰品栏也记录（覆盖旧快照）
                    LOGGER.warn("[MExt Curios DEBUG]   Capture #{} EMPTY: removing {} from snapshot",
                            captureCallCount, player.getUUID());
                    pendingSnapshot.remove(player.getUUID());
                }
            });
            if (CuriosApi.getCuriosInventory(player).isEmpty()) {
                LOGGER.warn("[MExt Curios DEBUG]   NO CuriosInventory for player {}!", name);
            }
        } catch (Exception e) {
            LOGGER.error("[MExt Curios DEBUG] Failed to capture curios for {}", name, e);
        }
    }

    /**
     * 将饰品栏快照恢复到单个玩家。
     * <p>在 {@code CheckpointManager.restoreCheckpoint()} 的 HEAD 处由 Mixin 调用。
     * 必须在 MineZero 恢复玩家物品之前执行，确保饰品栏中的物品与
     * BackpackStorage（由 SBPBackpackHelper 恢复）等全局数据同步。</p>
     *
     * @param player 要恢复饰品栏的玩家
     */
    public static void applyInventory(ServerPlayer player) {
        if (player == null) return;
        applyCallCount++;
        long tick = player.level().getGameTime();
        String name = player.getName().getString();

        LOGGER.info("[MExt Curios DEBUG] ===== applyInventory #{} tick={} player={} loadedSnap={} pendingHas={} =====",
                applyCallCount, tick, name,
                loadedSnapshot != null ? loadedSnapshot.getAllKeys().size() + "p" : "null",
                pendingSnapshot.containsKey(player.getUUID()));

        ListTag data = resolveSnapshot(player.getUUID());
        if (data == null || data.isEmpty()) {
            LOGGER.warn("[MExt Curios DEBUG] applyInventory #{} ABORTED: snapshot is {} for {}",
                    applyCallCount, data == null ? "null" : "empty(" + data.size() + ")", name);
            return;
        }

        LOGGER.info("[MExt Curios DEBUG]   snapshot data: size={} raw={}", data.size(), data);
        try {
            CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
                // 清空前状态
                LOGGER.info("[MExt Curios DEBUG]   PRE-clear state:");
                for (Map.Entry<String, ICurioStacksHandler> entry : handler.getCurios().entrySet()) {
                    ICurioStacksHandler sh = entry.getValue();
                    IDynamicStackHandler stacks = sh.getStacks();
                    for (int i = 0; i < stacks.getSlots(); i++) {
                        ItemStack s = stacks.getStackInSlot(i);
                        LOGGER.info("[MExt Curios DEBUG]     slot {}[{}]: {}",
                                entry.getKey(), i, s.isEmpty() ? "EMPTY" : s.getHoverName().getString());
                    }
                }

                // 清空所有槽位
                for (Map.Entry<String, ICurioStacksHandler> entry : handler.getCurios().entrySet()) {
                    ICurioStacksHandler sh = entry.getValue();
                    clearStackHandler(sh.getStacks());
                    clearStackHandler(sh.getCosmeticStacks());
                }
                LOGGER.info("[MExt Curios DEBUG]   All slots cleared, calling loadInventory...");

                // 加载快照
                handler.loadInventory(data);

                // 验证结果
                LOGGER.info("[MExt Curios DEBUG]   POST-load state:");
                for (Map.Entry<String, ICurioStacksHandler> entry : handler.getCurios().entrySet()) {
                    ICurioStacksHandler sh = entry.getValue();
                    IDynamicStackHandler stacks = sh.getStacks();
                    for (int i = 0; i < stacks.getSlots(); i++) {
                        ItemStack s = stacks.getStackInSlot(i);
                        LOGGER.info("[MExt Curios DEBUG]     slot {}[{}]: {}",
                                entry.getKey(), i, s.isEmpty() ? "EMPTY" : s.getHoverName().getString() + " x" + s.getCount());
                    }
                }
                LOGGER.info("[MExt Curios DEBUG] applyInventory #{} COMPLETE: {} curios stacks for {}",
                        applyCallCount, data.size(), name);
            });
            if (CuriosApi.getCuriosInventory(player).isEmpty()) {
                LOGGER.warn("[MExt Curios DEBUG]   NO CuriosInventory for player {}!", name);
            }
        } catch (Exception e) {
            LOGGER.error("[MExt Curios DEBUG] Failed to apply curios for {}", name, e);
        }
    }

    /** 清空一个 StackHandler 中的所有槽位 */
    private static void clearStackHandler(IDynamicStackHandler stacks) {
        for (int i = 0; i < stacks.getSlots(); i++) {
            stacks.setStackInSlot(i, ItemStack.EMPTY);
        }
    }

    /**
     * 将快照写入指定的 NBT CompoundTag（供 CheckpointDataMixin.save 使用）。
     *
     * @param targetNbt 目标 NBT
     * @param key       NBT 中的键名
     */
    public static void writeSnapshot(CompoundTag targetNbt, String key) {
        LOGGER.info("[MExt Curios DEBUG] writeSnapshot: pending has {} players", pendingSnapshot.size());
        if (pendingSnapshot.isEmpty()) return;
        CompoundTag out = new CompoundTag();
        for (var entry : pendingSnapshot.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                out.put(entry.getKey().toString(), entry.getValue());
                LOGGER.info("[MExt Curios DEBUG]   writing {}: {} curios stacks",
                        entry.getKey().toString().substring(0, 8) + "...", entry.getValue().size());
            }
        }
        if (!out.isEmpty()) {
            targetNbt.put(key, out);
            LOGGER.info("[MExt Curios DEBUG] writeSnapshot: wrote {} players to NBT key='{}'",
                    out.getAllKeys().size(), key);
        }
    }

    /**
     * 从指定的 NBT CompoundTag 读取快照（供 CheckpointDataMixin.load 使用）。
     *
     * @param sourceNbt 来源 NBT
     * @param key       NBT 中的键名
     */
    public static void readSnapshot(CompoundTag sourceNbt, String key) {
        LOGGER.info("[MExt Curios DEBUG] readSnapshot: looking for key='{}' in NBT (has={})",
                key, sourceNbt.contains(key));
        if (!sourceNbt.contains(key)) {
            LOGGER.warn("[MExt Curios DEBUG] readSnapshot: key '{}' NOT FOUND in CheckpointData NBT!", key);
            return;
        }
        loadedSnapshot = sourceNbt.getCompound(key);
        LOGGER.info("[MExt Curios DEBUG] readSnapshot: loaded {} players from NBT, keys={}",
                loadedSnapshot.getAllKeys().size(), loadedSnapshot.getAllKeys());
    }

    // ============ 内部逻辑 ============

    /**
     * 解析指定玩家的快照：优先从持久化 NBT 中加载的，回退到内存中的 pendingSnapshot。
     */
    private static ListTag resolveSnapshot(UUID uuid) {
        // 优先使用当前会话的 pendingSnapshot（最新的捕获数据）
        ListTag data = pendingSnapshot.get(uuid);
        if (data != null && !data.isEmpty()) {
            LOGGER.info("[MExt Curios DEBUG] resolveSnapshot for {}: using pendingSnapshot ({} curios stacks)",
                    uuid.toString().substring(0, 8), data.size());
            return data;
        }
        // 回退到从 CheckpointData NBT 加载的持久化快照（跨重启场景）
        if (loadedSnapshot != null && loadedSnapshot.contains(uuid.toString())) {
            data = loadedSnapshot.getList(uuid.toString(), 10 /* TAG_Compound */);
            if (!data.isEmpty()) {
                LOGGER.info("[MExt Curios DEBUG] resolveSnapshot for {}: using loadedSnapshot ({} curios stacks)",
                        uuid.toString().substring(0, 8), data.size());
                return data;
            }
        }
        LOGGER.warn("[MExt Curios DEBUG] resolveSnapshot for {}: NO data available", uuid.toString().substring(0, 8));
        return null;
    }
}
