package com.yansunsky.mzextension.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 精妙背包（Sophisticated Backpacks）{@link BackpackStorage} 快照辅助类（v2 修复版）。
 *
 * <p>背景：SBP 的背包内容不存放在 ItemStack 上，ItemStack 只持有
 * {@code STORAGE_UUID} DataComponent 引用；真实内容在覆世界 SavedData
 * {@link BackpackStorage}（{@code Map<UUID, CompoundTag>}）中。MineZero 原生回档
 * 只恢复物品栏 ItemStack（即“钥匙”），不会操作 BackpackStorage，导致“背包物品回来了、
 * 里面的东西没回来”。
 *
 * <p>v2 相对 v1 修复的三个缺陷：
 * <ol>
 *   <li><b>捕获改为“整库快照”</b>：v1 用 {@link PlayerInventoryProvider#runOnBackpacks}
 *       只扫在线玩家 vanilla 主背包 / 副手 / 胸甲槽——藏在潜影盒里、Curios 槽、地上掉落物、
 *       容器、怪物身上的背包全部漏掉。v2 通过反射枚举 {@code BackpackStorage.backpackContents}
 *       的<b>全部 UUID</b>（SBP 没有公开的“列出所有内容”API），逐条深拷贝真实存储空间，
 *       与背包放在什么位置无关。这正是“存档时根据背包 UUID 找真实存储、单独记录”的语义。</li>
 *   <li><b>恢复改为“原地替换内容对象”</b>：v1 先 {@code removeBackpackContents} 再
 *       {@code setBackpackContents}，会把该 UUID 的 CompoundTag 对象整个换掉。而 SBP 内部
 *       {@code setBackpackContents} 对已存在的 key 是“字段级 merge 进原对象”，说明它刻意
 *       保持对象身份，让已打开的 GUI / 缓存的 handler 引用仍然有效。v2 对现存对象做
 *       “清空 → 灌入快照”，对象身份不变，活跃引用立即读到回档内容，后续写入也不会落到
 *       已脱离存储的孤儿对象上。</li>
 *   <li><b>整库回溯（含清理新增 UUID）</b>：把 BackpackStorage 恢复到“检查点时刻的完整状态”。
 *       快照里有的 UUID 覆写成检查点内容；当前存在但快照里没有的 UUID（检查点之后才产生
 *       内容的背包，例如新捡的背包、检查点时为空后来被填满的背包）一并移除，避免“该空的
 *       没空”。注意：这属于“整库回到过去”语义，连末影箱/未跟踪容器里的背包存储也会一并
 *       回溯，与 MineZero 只回滚被跟踪容器略有差异，但更符合本扩展“外部存储整体回档”的定位。</li>
 * </ol>
 *
 * <p>Mixin 会在 restoreCheckpoint 的 HEAD（玩家物品栏恢复前）与 RETURN（MineZero 全部恢复
 * 完成后，规避“实体清理阶段 SBP 删除怪物身上背包内容”造成的二次丢失）各调用一次
 * {@link #applySnapshot(ServerPlayer)}。
 *
 * <p>兼容性说明：若反射枚举失败（SBP 改过私有字段名），自动回退到 v1 的“逐在线玩家扫描”，
 * 保证旧行为不退化；恢复侧对快照内 UUID 的覆盖不受反射影响。
 *
 * @see BackpackStorage
 * @see PlayerInventoryProvider
 */
public class SBPBackpackHelper {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String SNAPSHOT_KEY = "backpackStorageSnapshot";

    /** 反射读取的 BackpackStorage.backpackContents 字段（SBP 无公开的枚举 API） */
    private static Field backpackContentsField = null;
    private static boolean reflectionAttempted = false;

    // ====== 调试计数器 ======
    private static int captureCallCount = 0;
    private static int applyCallCount = 0;
    /** 由 SafeCheckpointTicker 设置，标记当前 setCheckpoint 是否由 ticker 触发 */
    public static volatile boolean tickerInitiated = false;

    /**
     * 当前会话最新捕获的快照（UUID → 内容 CompoundTag）。
     * 每次 setCheckpoint 后刷新；CheckpointDataMixin.save 会把它写入 CheckpointData NBT。
     */
    private static CompoundTag pendingSnapshot = null;

    /**
     * 从 CheckpointData NBT 反序列化加载的快照（跨服务器重启场景）。
     * CheckpointDataMixin.load 中填充；restoreCheckpoint 时作为 pendingSnapshot 的回退。
     */
    private static CompoundTag loadedSnapshot = null;

    // ============ 公开 API ============

    /**
     * 捕获 BackpackStorage 快照（检查点时刻）。
     * <p>在 {@code CheckpointManager.setCheckpoint()} 的 TAIL 处由 Mixin 调用。
     * 优先整库快照（反射枚举全部 UUID）；反射不可用时回退到逐在线玩家扫描。</p>
     *
     * @param anchorPlayer 锚点玩家（用于获取服务端实例）
     */
    public static void captureSnapshot(ServerPlayer anchorPlayer) {
        captureCallCount++;
        if (anchorPlayer == null || anchorPlayer.getServer() == null) {
            LOGGER.warn("[MExt SBP] captureSnapshot #{} aborted: no anchor/server", captureCallCount);
            return;
        }
        try {
            BackpackStorage storage = BackpackStorage.get();
            Map<UUID, CompoundTag> allContents = readAllBackpackContents(storage);

            if (allContents != null) {
                // ---- 方式 A（首选）：整库快照 ----
                CompoundTag snapshot = new CompoundTag();
                for (Map.Entry<UUID, CompoundTag> entry : allContents.entrySet()) {
                    if (entry.getValue() == null) {
                        continue;
                    }
                    snapshot.put(entry.getKey().toString(), entry.getValue().copy());
                }
                pendingSnapshot = snapshot;
                LOGGER.info("[MExt SBP] captureSnapshot #{} FULL-STORE: {} backpack UUID(s) recorded (ticker={})",
                        captureCallCount, snapshot.getAllKeys().size(), tickerInitiated);
                return;
            }

            // ---- 方式 B（回退）：逐在线玩家扫描（原 v1 逻辑，仅覆盖 vanilla 背包栏）----
            CompoundTag snapshot = new CompoundTag();
            int[] count = {0};
            for (ServerPlayer player : anchorPlayer.getServer().getPlayerList().getPlayers()) {
                final String playerName = player.getName().getString();
                LOGGER.debug("[MExt SBP] fallback scan player: {}", playerName);
                PlayerInventoryProvider.get().runOnBackpacks(player, (backpackStack, handlerName, identifier, slot) -> {
                    BackpackWrapper.fromStack(backpackStack).getContentsUuid().ifPresent(uuid -> {
                        if (!snapshot.contains(uuid.toString())) {
                            CompoundTag contents = storage.getOrCreateBackpackContents(uuid).copy();
                            snapshot.put(uuid.toString(), contents);
                            count[0]++;
                        }
                    });
                    return false;
                });
            }
            CompoundTag previous = pendingSnapshot;
            pendingSnapshot = snapshot;
            if (count[0] == 0 && previous != null && !previous.isEmpty()) {
                // 空快照保护（v1 遗留）：背包可能在扫描不到的位置，不覆盖已有有效快照
                LOGGER.warn("[MExt SBP] captureSnapshot #{} FALLBACK produced empty snapshot; keeping previous {} uuid(s)",
                        captureCallCount, previous.getAllKeys().size());
                pendingSnapshot = previous;
            } else {
                LOGGER.warn("[MExt SBP] captureSnapshot #{} FALLBACK scan: {} backpack(s) recorded",
                        captureCallCount, count[0]);
            }
        } catch (Exception e) {
            LOGGER.error("[MExt SBP] Failed to capture BackpackStorage snapshot", e);
        }
    }

    /**
     * 把 BackpackStorage 恢复到检查点快照状态。
     * <p>在 {@code CheckpointManager.restoreCheckpoint()} 的 HEAD 与 RETURN 处由 Mixin 调用
     * （HEAD 保证玩家物品栏恢复前存储即已就位；RETURN 兜底，覆盖 MineZero 在中间步骤
     * 清理实体时 SBP 对怪物背包 UUID 的二次删除）。可安全重复调用（幂等）。</p>
     *
     * @param anchorPlayer 锚点玩家
     */
    public static void applySnapshot(ServerPlayer anchorPlayer) {
        applyCallCount++;
        long tick = anchorPlayer != null ? anchorPlayer.level().getGameTime() : -1;
        CompoundTag snapshot = resolveSnapshot();
        if (snapshot == null) {
            LOGGER.warn("[MExt SBP] applySnapshot #{} aborted: no snapshot available (tick={})", applyCallCount, tick);
            return;
        }
        try {
            BackpackStorage storage = BackpackStorage.get();
            int overwritten = 0;
            // 1) 快照内每个 UUID：把真实存储对象“原地”替换成检查点内容（保留对象身份）
            for (String uuidKey : snapshot.getAllKeys()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidKey);
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("[MExt SBP] Invalid UUID in snapshot: {}", uuidKey);
                    continue;
                }
                CompoundTag saved = snapshot.getCompound(uuidKey);
                // getOrCreate：UUID 当前不存在（如被 SBP 实体清理删除过）时重新建立空对象再灌入
                CompoundTag current = storage.getOrCreateBackpackContents(uuid);
                replaceContentsInPlace(current, saved);
                overwritten++;
            }
            // 2) 整库回溯：移除“当前存在但快照里没有”的 UUID（检查点后才产生内容的背包）
            int removed = removeUuidsNotInSnapshot(storage, snapshot);
            storage.setDirty();
            LOGGER.info("[MExt SBP] applySnapshot #{} done: overwritten {} uuid(s), removed {} orphan(s) (tick={})",
                    applyCallCount, overwritten, removed, tick);
            // 快照已被消费（本次会话恢复后，loadedSnapshot 不再需要）
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
            LOGGER.debug("[MExt SBP] writeSnapshot: wrote {} uuid(s) to NBT key='{}'",
                    pendingSnapshot.getAllKeys().size(), key);
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
            LOGGER.info("[MExt SBP] readSnapshot: loaded {} uuid(s) from NBT key='{}'",
                    loadedSnapshot.getAllKeys().size(), key);
        } else {
            LOGGER.debug("[MExt SBP] readSnapshot: key '{}' not found in NBT", key);
        }
    }

    // ============ 内部逻辑 ============

    /**
     * 解析要使用的快照：优先当前会话的 pendingSnapshot（最新捕获），
     * 回退到从 CheckpointData NBT 加载的 loadedSnapshot（跨重启场景）。
     */
    private static CompoundTag resolveSnapshot() {
        if (pendingSnapshot != null && !pendingSnapshot.isEmpty()) {
            return pendingSnapshot;
        }
        return (loadedSnapshot != null && !loadedSnapshot.isEmpty()) ? loadedSnapshot : null;
    }

    /**
     * 原地整体替换一个 UUID 的内容对象：先清空现存 key，再把快照 key 逐一深拷贝灌入。
     * <p>保持 CompoundTag 对象身份不变，使仍持有该对象引用的活跃 wrapper / 打开的 GUI
     * 立即可见新内容；避免 v1 中 remove+set 换对象导致的“旧引用写孤儿对象、数据丢失”。</p>
     */
    private static void replaceContentsInPlace(CompoundTag current, CompoundTag saved) {
        List<String> keysToRemove = new ArrayList<>(current.getAllKeys());
        for (String key : keysToRemove) {
            current.remove(key);
        }
        for (String key : saved.getAllKeys()) {
            current.put(key, saved.get(key).copy());
        }
    }

    /**
     * 移除“当前 BackpackStorage 中存在、但快照里没有”的 UUID。
     * <p>反射不可用时返回 0（仅跳过清理，不影响快照内 UUID 的覆盖恢复）。
     * 遍历前先拷贝 key 列表，避免在 live map 上迭代时调用 remove 触发 CME。</p>
     */
    private static int removeUuidsNotInSnapshot(BackpackStorage storage, CompoundTag snapshot) {
        Map<UUID, CompoundTag> allContents = readAllBackpackContents(storage);
        if (allContents == null) {
            return 0;
        }
        int removed = 0;
        for (UUID uuid : new ArrayList<>(allContents.keySet())) {
            if (!snapshot.contains(uuid.toString())) {
                storage.removeBackpackContents(uuid);
                removed++;
            }
        }
        return removed;
    }

    /**
     * 反射读取 SBP 私有字段 {@code backpackContents}（{@code Map<UUID, CompoundTag>}）。
     * <p>SBP 的 {@link BackpackStorage} 没有公开“枚举所有内容 / 判断某 UUID 是否存在”的 API
     * （{@code getOrCreateBackpackContents} 会凭空创建空条目），因此只能反射读取该字段。
     * 结果会被缓存；失败返回 null，调用方回退到逐玩家扫描。</p>
     */
    @SuppressWarnings("unchecked")
    private static Map<UUID, CompoundTag> readAllBackpackContents(BackpackStorage storage) {
        if (!reflectionAttempted) {
            reflectionAttempted = true;
            try {
                Field f = BackpackStorage.class.getDeclaredField("backpackContents");
                f.setAccessible(true);
                backpackContentsField = f;
                LOGGER.info("[MExt SBP] Reflection OK: BackpackStorage.backpackContents accessible");
            } catch (Throwable t) {
                backpackContentsField = null;
                LOGGER.error("[MExt SBP] Cannot reflect BackpackStorage.backpackContents; falling back to player scan", t);
            }
        }
        if (backpackContentsField == null) {
            return null;
        }
        try {
            Object value = backpackContentsField.get(storage);
            if (value instanceof Map) {
                return (Map<UUID, CompoundTag>) value;
            }
            LOGGER.error("[MExt SBP] BackpackStorage.backpackContents is not a Map ({}), fallback to player scan",
                    value == null ? "null" : value.getClass().getName());
            return null;
        } catch (Throwable t) {
            LOGGER.error("[MExt SBP] Failed to read BackpackStorage.backpackContents", t);
            return null;
        }
    }
}
