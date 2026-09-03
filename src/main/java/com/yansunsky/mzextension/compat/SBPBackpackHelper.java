package com.yansunsky.mzextension.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * 精妙背包（Sophisticated Backpacks）快照辅助类。
 * <p>
 * SBP 的背包物品内容不存储在 ItemStack 的 NBT/DataComponent 中，
 * 而是存储在全局 SavedData {@link BackpackStorage} 中（覆世界的独立数据）。
 * ItemStack 仅持有指向该存储的 UUID 引用（{@code STORAGE_UUID} DataComponent）。
 * <p>
 * MineZero 的回档机制只保存/恢复 ItemStack（即 UUID 引用），
 * 不会操作 BackpackStorage。这导致回档时背包 ItemStack 正确恢复，
 * 但通过 UUID 访问到的实际内容仍然是回档前的状态。
 * <p>
 * 本类通过在检查点保存时捕获 BackpackStorage 快照、恢复时写回，
 * 来解决此不兼容问题。
 *
 * @see BackpackStorage
 * @see BackpackWrapper
 * @see PlayerInventoryProvider
 */
public class SBPBackpackHelper {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String SNAPSHOT_KEY = "backpackStorageSnapshot";

    // ====== 调试计数器 ======
    private static int captureCallCount = 0;
    private static int applyCallCount = 0;
    /** 由 SafeCheckpointTicker 设置，标记当前 setCheckpoint 是否由 ticker 触发 */
    public static volatile boolean tickerInitiated = false;

    /**
     * 当前未持久化的快照。
     * <p>每次 setCheckpoint 调用后更新此值；
     * CheckpointDataMixin.save 将其写入 CheckpointData NBT 以支持跨重启持久化。</p>
     */
    private static CompoundTag pendingSnapshot = null;

    /**
     * 从 CheckpointData NBT 反序列化加载的快照。
     * <p>CheckpointDataMixin.load 中设置此值；
     * restoreCheckpoint 时优先使用此值（如果有），否则回退到 pendingSnapshot。</p>
     */
    private static CompoundTag loadedSnapshot = null;

    // ============ 公开 API ============

    /**
     * 捕获所有在线玩家背包的 BackpackStorage 快照。
     * <p>在 {@code CheckpointManager.setCheckpoint()} 的 TAIL 处由 Mixin 调用。</p>
     *
     * @param anchorPlayer 锚点玩家（用于获取服务端实例和在线玩家列表）
     */
    public static void captureSnapshot(ServerPlayer anchorPlayer) {
        captureCallCount++;
        long tick = anchorPlayer != null ? anchorPlayer.level().getGameTime() : -1;

        LOGGER.info("[MExt SBP DEBUG] ===== captureSnapshot #{} tick={} tickerInit={} anchor={} =====",
                captureCallCount, tick, tickerInitiated,
                anchorPlayer != null ? anchorPlayer.getName().getString() : "null");

        if (anchorPlayer == null || anchorPlayer.getServer() == null) {
            LOGGER.warn("[MExt SBP DEBUG] captureSnapshot ABORTED: null anchor or server");
            return;
        }
        try {
            CompoundTag snapshot = new CompoundTag();
            BackpackStorage storage = BackpackStorage.get();
            LOGGER.info("[MExt SBP] BackpackStorage obtained: {}", storage.getClass().getName());
            int[] count = {0};
            int playerCount = anchorPlayer.getServer().getPlayerList().getPlayers().size();
            LOGGER.info("[MExt SBP] Scanning {} online players for backpacks...", playerCount);

            for (ServerPlayer player : anchorPlayer.getServer().getPlayerList().getPlayers()) {
                final String playerName = player.getName().getString();
                LOGGER.debug("[MExt SBP] Scanning player: {}", playerName);

                PlayerInventoryProvider.get().runOnBackpacks(player, (backpackStack, handlerName, identifier, slot) -> {
                    LOGGER.debug("[MExt SBP]   Found backpack in {}[{}] slot={} item={}",
                            handlerName, identifier, slot, backpackStack.getHoverName().getString());

                    BackpackWrapper.fromStack(backpackStack).getContentsUuid().ifPresent(uuid -> {
                        LOGGER.debug("[MExt SBP]     UUID={} new={}", uuid, !snapshot.contains(uuid.toString()));
                        if (!snapshot.contains(uuid.toString())) {
                            CompoundTag contents = storage.getOrCreateBackpackContents(uuid).copy();
                            int contentKeys = contents.getAllKeys().size();
                            LOGGER.debug("[MExt SBP]     Contents keys={} empty={}", contentKeys, contents.isEmpty());
                            if (!contents.isEmpty()) {
                                snapshot.put(uuid.toString(), contents);
                                count[0]++;
                            }
                        }
                    });
                    return false;
                });
            }

            // 保护旧快照：捕获前先保存，空快照时不覆盖
            CompoundTag pendingSnapshot_before = pendingSnapshot;
            pendingSnapshot = snapshot;
            LOGGER.info("[MExt SBP DEBUG] Snapshot CAPTURED #{}: {} backpacks, NBT keys={}, data={}",
                    captureCallCount, count[0], snapshot.getAllKeys().size(), snapshot);

            // 如果是空快照且之前有数据，不覆盖（保护有效快照不被空快照覆盖）
            if (count[0] == 0 && pendingSnapshot_before != null && !pendingSnapshot_before.isEmpty()) {
                LOGGER.warn("[MExt SBP] EMPTY snapshot detected! Keeping previous snapshot with {} keys to prevent data loss",
                        pendingSnapshot_before.getAllKeys().size());
                pendingSnapshot = pendingSnapshot_before;
            }
        } catch (Exception e) {
            LOGGER.error("[MExt SBP] Failed to capture BackpackStorage snapshot", e);
        }
    }

    /**
     * 将捕获的背包快照写回 BackpackStorage。
     * <p>在 {@code CheckpointManager.restoreCheckpoint()} 的 HEAD 处由 Mixin 调用。
     * 必须在 ItemStack 恢复之前执行，因为恢复后的 ItemStack 上的 STORAGE_UUID
     * 会被 BackpackWrapper 立即解析——如果 BackpackStorage 内容不同步则结果错误。</p>
     *
     * @param anchorPlayer 锚点玩家
     */
    public static void applySnapshot(ServerPlayer anchorPlayer) {
        applyCallCount++;
        long tick = anchorPlayer != null ? anchorPlayer.level().getGameTime() : -1;

        LOGGER.info("[MExt SBP DEBUG] ===== applySnapshot #{} tick={} anchor={} loadedSnap={} pendingSnap={} =====",
                applyCallCount, tick,
                anchorPlayer != null ? anchorPlayer.getName().getString() : "null",
                loadedSnapshot != null ? loadedSnapshot.getAllKeys().size() + "keys" : "null",
                pendingSnapshot != null ? pendingSnapshot.getAllKeys().size() + "keys" : "null");

        CompoundTag snapshot = resolveSnapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            LOGGER.warn("[MExt SBP DEBUG] applySnapshot #{} ABORTED: no snapshot available", applyCallCount);
            return;
        }

        LOGGER.info("[MExt SBP DEBUG] Applying snapshot #{} with {} UUIDs, data={}",
                applyCallCount, snapshot.getAllKeys().size(), snapshot);
        try {
            BackpackStorage storage = BackpackStorage.get();
            int count = 0;

            for (String uuidKey : snapshot.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(uuidKey);
                    CompoundTag savedContents = snapshot.getCompound(uuidKey);
                    if (savedContents.isEmpty()) continue;

                    // 调试：打印恢复前的内容
                    CompoundTag currentContents = storage.getOrCreateBackpackContents(uuid);
                    int itemCountBefore = countItemsInNbt(currentContents);
                    int itemCountAfter = countItemsInNbt(savedContents);

                    LOGGER.info("[MExt SBP] Restoring UUID={} items: {} -> {}",
                            uuidKey, itemCountBefore, itemCountAfter);

                    storage.removeBackpackContents(uuid);
                    storage.setBackpackContents(uuid, savedContents.copy());
                    count++;

                    // 验证写入
                    CompoundTag verifyContents = storage.getOrCreateBackpackContents(uuid);
                    int verifyCount = countItemsInNbt(verifyContents);
                    LOGGER.info("[MExt SBP]   Verified UUID={} items={}", uuidKey, verifyCount);
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("[MExt SBP] Invalid UUID in snapshot: {}", uuidKey);
                }
            }

            storage.setDirty();
            LOGGER.info("[MExt SBP DEBUG] Snapshot APPLIED #{}: {} UUIDs restored", applyCallCount, count);
            loadedSnapshot = null;
        } catch (Exception e) {
            LOGGER.error("[MExt SBP] Failed to apply BackpackStorage snapshot", e);
        }
    }

    /**
     * 将快照写入指定的 NBT CompoundTag（供 CheckpointDataMixin.save 使用）。
     */
    public static void writeSnapshot(CompoundTag targetNbt, String key) {
        if (pendingSnapshot != null && !pendingSnapshot.isEmpty()) {
            targetNbt.put(key, pendingSnapshot.copy());
            LOGGER.debug("[MExt SBP] writeSnapshot: wrote {} UUIDs to NBT", pendingSnapshot.getAllKeys().size());
        } else {
            LOGGER.debug("[MExt SBP] writeSnapshot: no pending snapshot to write");
        }
    }

    /**
     * 从指定的 NBT CompoundTag 读取快照（供 CheckpointDataMixin.load 使用）。
     */
    public static void readSnapshot(CompoundTag sourceNbt, String key) {
        if (sourceNbt.contains(key)) {
            loadedSnapshot = sourceNbt.getCompound(key);
            LOGGER.info("[MExt SBP] readSnapshot: loaded {} UUIDs from NBT", loadedSnapshot.getAllKeys().size());
        } else {
            LOGGER.debug("[MExt SBP] readSnapshot: key '{}' not found in NBT", key);
        }
    }

    // ============ 内部逻辑 ============

    /**
     * 解析要使用的快照：优先使用从持久化 NBT 加载的，回退到内存中的。
     */
    private static CompoundTag resolveSnapshot() {
        // 优先使用当前会话的 pendingSnapshot（最新捕获）
        if (pendingSnapshot != null && !pendingSnapshot.isEmpty()) {
            LOGGER.info("[MExt SBP] resolveSnapshot: using pendingSnapshot ({} UUIDs)", pendingSnapshot.getAllKeys().size());
            return pendingSnapshot;
        }
        // 回退到从 CheckpointData NBT 加载的持久化快照
        if (loadedSnapshot != null && !loadedSnapshot.isEmpty()) {
            LOGGER.info("[MExt SBP] resolveSnapshot: using loadedSnapshot ({} UUIDs)", loadedSnapshot.getAllKeys().size());
            return loadedSnapshot;
        }
        return null;
    }

    /**
     * 统计 NBT 背包数据中的物品数量（用于调试日志）。
     */
    private static int countItemsInNbt(CompoundTag contentsNbt) {
        if (contentsNbt == null || !contentsNbt.contains("inventory")) return 0;
        CompoundTag inventoryNbt = contentsNbt.getCompound("inventory");
        if (!inventoryNbt.contains("Items")) return 0;
        return inventoryNbt.getList("Items", 10 /* TAG_Compound */).size();
    }
}
