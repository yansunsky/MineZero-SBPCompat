package com.yansunsky.mzextension.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 战利品容器（奖励箱）回档辅助 v2（2026-09）。
 *
 * <p>背景：MineZero 只在存档时刻对"已加载区块内的方块实体"做整体快照。存档之后**新生成/新加载**
 * 的奖励箱不在快照里，玩家打开取物后死亡回档，箱子不会被回溯 → 取走的东西"不回来"。
 *
 * <p>两条兼容分支（都只影响"检查点之后首次打开的奖励箱"）：
 * <ul>
 *   <li><b>vanilla 分支（原版战利品容器）</b>：右击时若容器仍持有 {@code LootTable} 引用（未解包），
 *       记录 {@code (维度, 位置, lootTable, seed)}；回档时把它重置为"未解包"（清空内容 + 重设引用），
 *       下次打开按原 seed 重新生成——等价于"从没开过"。</li>
 *   <li><b>Lootr 分支（可选装 Lootr 时）</b>：Lootr 把箱子换成自己的方块/BE，内容按
 *       {@code 容器UUID → Map<玩家UUID, LootrInventory>} 存在独立 SavedData，BE 不参与填内容、
 *       始终持有 lootTable 引用。本分支在右击时记录 {@code (维度, 位置, 容器UUID, 玩家UUID)}；
 *       回档时对每个记录调用 Lootr 的 {@code clearInventories(玩家UUID)}——只清除<b>该玩家</b>
 *       在该容器已开的 loot，下次该玩家打开重新独立 roll，不误伤检查点前其他玩家已开的 loot。
 *       Lootr 相关调用全部走反射（编译期零依赖）。</li>
 * </ul>
 *
 * <p>生命周期（由既有 Mixin 接入，见 docs §11）：
 * <ul>
 *   <li>setCheckpoint TAIL → {@link #startNewCheckpoint()}：清空上一轮记录。</li>
 *   <li>运行期右击 → {@link #onRightClickContainer}：按容器类型走 vanilla/Lootr 分支记录。</li>
 *   <li>restoreCheckpoint RETURN → {@link #resetAll(MinecraftServer)}：对记录做重置，然后清空。</li>
 *   <li>CheckpointData save/load → {@link #writeSnapshot}/{@link #readSnapshot}：跨重启持久化。</li>
 * </ul>
 */
public class LootContainerHelper {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String SNAPSHOT_KEY = "lootContainerSnapshot";

    // ===== vanilla 分支：位置 → (table, seed) =====
    private static final class LootRef {
        final ResourceKey<LootTable> table;
        final long seed;
        LootRef(ResourceKey<LootTable> table, long seed) {
            this.table = table;
            this.seed = seed;
        }
    }

    // ===== Lootr 分支：位置 → 容器UUID + 打开过的玩家UUID =====
    private static final class LootrRef {
        final UUID containerId;
        final Map<String, UUID> players = new HashMap<>(); // key=player uuid string 去重
        LootrRef(UUID containerId) {
            this.containerId = containerId;
        }
    }

    /** 维度 location → (位置 → vanilla 记录) */
    private static final Map<String, Map<BlockPos, LootRef>> tracked = new HashMap<>();
    /** 维度 location → (位置 → Lootr 记录) */
    private static final Map<String, Map<BlockPos, LootrRef>> trackedLootr = new HashMap<>();

    // ===== Lootr 反射句柄（惰性初始化）=====
    private static boolean lootrReflectAttempted = false;
    private static Method lootrResolveBlockEntity;    // LootrAPI.resolveBlockEntity(BlockEntity)
    private static Method lootrOfPosLevel;            // ILootrInfoProvider.of(BlockPos, Level)
    private static Method providerGetInfoUUID;        // ILootrInfoProvider.getInfoUUID()
    private static Method providerGetInfoPos;         // ILootrInfoProvider.getInfoPos()
    private static Method providerGetInfoDimension;   // ILootrInfoProvider.getInfoDimension()
    private static Method lootrGetData;               // LootrAPI.getData(ILootrInfoProvider)
    private static Method savedDataClearInventories;  // ILootrSavedData.clearInventories(UUID)

    private static boolean isLootrLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded("lootr");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void initLootrReflection() {
        if (lootrReflectAttempted) return;
        lootrReflectAttempted = true;
        try {
            Class<?> api = Class.forName("noobanidus.mods.lootr.common.api.LootrAPI");
            Class<?> providerIface = Class.forName("noobanidus.mods.lootr.common.api.data.ILootrInfoProvider");
            Class<?> savedDataIface = Class.forName("noobanidus.mods.lootr.common.api.data.ILootrSavedData");
            lootrResolveBlockEntity = api.getMethod("resolveBlockEntity", BlockEntity.class);
            lootrOfPosLevel = providerIface.getMethod("of", BlockPos.class, Level.class);
            providerGetInfoUUID = providerIface.getMethod("getInfoUUID");
            providerGetInfoPos = providerIface.getMethod("getInfoPos");
            providerGetInfoDimension = providerIface.getMethod("getInfoDimension");
            lootrGetData = api.getMethod("getData", providerIface);
            savedDataClearInventories = savedDataIface.getMethod("clearInventories", UUID.class);
            LOGGER.info("[MExt Loot] Lootr reflection OK");
        } catch (Throwable t) {
            LOGGER.error("[MExt Loot] Lootr reflection failed; Lootr compat disabled", t);
            lootrResolveBlockEntity = lootrOfPosLevel = providerGetInfoUUID = providerGetInfoPos =
                    providerGetInfoDimension = lootrGetData = savedDataClearInventories = null;
        }
    }

    // ============ 事件：玩家右击时捕获 ============

    @SubscribeEvent
    public static void onRightClickContainer(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = (ServerLevel) event.getLevel();
        BlockPos pos = event.getPos();

        try {
            // 1) Lootr 分支（装了 Lootr 且是 Lootr 容器时优先）
            if (isLootrLoaded() && tryTrackLootr(level, pos, player)) {
                return;
            }

            // 2) vanilla 分支：仅原版 RandomizableContainerBlockEntity 且未解包
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof RandomizableContainerBlockEntity rcb)) return;

            // 防御：Lootr 方块反射失败走到这里时跳过 lootr 命名空间，避免误当 vanilla
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
            if (blockId != null && "lootr".equals(blockId.getNamespace())) return;

            ResourceKey<LootTable> table = rcb.getLootTable();
            if (table == null) return; // 已解包，引用丢失，无法重新生成
            long seed = rcb.getLootTableSeed();
            track(level.dimension(), pos, table, seed);
            LOGGER.debug("[MExt Loot] tracked vanilla container {} table={} seed={}", pos, table.location(), seed);
        } catch (Exception e) {
            LOGGER.error("[MExt Loot] Failed to track container at {}", pos, e);
        }
    }

    /** 尝试按 Lootr 容器记录；返回 true 表示确实是 Lootr 容器且已记录（或不应走 vanilla 分支） */
    private static boolean tryTrackLootr(ServerLevel level, BlockPos pos, ServerPlayer player) {
        initLootrReflection();
        if (lootrResolveBlockEntity == null) return false;
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) return false;
            Object provider = lootrResolveBlockEntity.invoke(null, be);
            if (provider == null && lootrOfPosLevel != null) {
                provider = lootrOfPosLevel.invoke(null, pos, level);
            }
            if (provider == null) return false;

            UUID containerId = (UUID) providerGetInfoUUID.invoke(provider);
            if (containerId == null) return false;

            LootrRef ref = trackedLootr
                    .computeIfAbsent(level.dimension().location().toString(), k -> new HashMap<>())
                    .computeIfAbsent(pos.immutable(), k -> new LootrRef(containerId));
            ref.players.put(player.getUUID().toString(), player.getUUID());
            LOGGER.debug("[MExt Loot] tracked Lootr container {} (id={}) player={}", pos, containerId, player.getName().getString());
            return true;
        } catch (Throwable t) {
            LOGGER.error("[MExt Loot] Lootr track failed at {}", pos, t);
            return false;
        }
    }

    // ============ 生命周期钩子（由 Mixin 调用） ============

    public static void startNewCheckpoint() {
        if (!tracked.isEmpty() || !trackedLootr.isEmpty()) {
            LOGGER.debug("[MExt Loot] startNewCheckpoint: cleared vanilla={} lootr={}",
                    countVanilla(), countLootr());
            tracked.clear();
            trackedLootr.clear();
        }
    }

    /**
     * 回档完成后的最终处理。
     * <ul>
     *   <li>vanilla：重置为"未解包"（清空内容 + 恢复 LootTable/seed）。</li>
     *   <li>Lootr：对每个 (容器, 玩家) 调用 clearInventories，仅撤该玩家的 loot。</li>
     * </ul>
     * 必须在 MineZero restoreCheckpoint RETURN 阶段调用。处理完清空记录。可重复调用。
     */
    public static void resetAll(MinecraftServer server) {
        if (server == null) return;
        resetVanilla(server);
        resetLootr(server);
    }

    private static void resetVanilla(MinecraftServer server) {
        if (tracked.isEmpty()) return;
        int resetCount = 0;
        for (Map.Entry<String, Map<BlockPos, LootRef>> dimEntry : new HashMap<>(tracked).entrySet()) {
            ServerLevel level = server.getLevel(parseDim(dimEntry.getKey()));
            if (level == null) continue;
            for (Map.Entry<BlockPos, LootRef> posEntry : new HashMap<>(dimEntry.getValue()).entrySet()) {
                BlockPos pos = posEntry.getKey();
                LootRef ref = posEntry.getValue();
                try {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof RandomizableContainerBlockEntity rcb) {
                        rcb.clearContent();
                        rcb.setLootTable(ref.table, ref.seed);
                        rcb.setChanged();
                        resetCount++;
                        LOGGER.info("[MExt Loot] reset vanilla container {} to regenerate loot table={} seed={}",
                                pos, ref.table.location(), ref.seed);
                    } else {
                        LOGGER.warn("[MExt Loot] container at {} no longer randomizable ({}), skipped",
                                pos, be == null ? "null" : be.getClass().getName());
                    }
                } catch (Exception e) {
                    LOGGER.error("[MExt Loot] Failed to reset vanilla container at {}", pos, e);
                }
            }
        }
        tracked.clear();
        LOGGER.info("[MExt Loot] resetVanilla done: {} container(s)", resetCount);
    }

    private static void resetLootr(MinecraftServer server) {
        if (trackedLootr.isEmpty()) return;
        initLootrReflection();
        if (lootrGetData == null || savedDataClearInventories == null) {
            LOGGER.warn("[MExt Loot] Lootr reset skipped: reflection unavailable");
            trackedLootr.clear();
            return;
        }
        int resetCount = 0;
        for (Map.Entry<String, Map<BlockPos, LootrRef>> dimEntry : new HashMap<>(trackedLootr).entrySet()) {
            ResourceKey<Level> dim = parseDim(dimEntry.getKey());
            ServerLevel level = server.getLevel(dim);
            if (level == null) continue;
            for (Map.Entry<BlockPos, LootrRef> posEntry : new HashMap<>(dimEntry.getValue()).entrySet()) {
                BlockPos pos = posEntry.getKey();
                LootrRef ref = posEntry.getValue();
                try {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be == null) {
                        LOGGER.warn("[MExt Loot] Lootr container at {} missing during reset, skipped", pos);
                        continue;
                    }
                    Object provider = lootrResolveBlockEntity.invoke(null, be);
                    if (provider == null) continue;
                    Object data = lootrGetData.invoke(null, provider);
                    if (data == null) {
                        LOGGER.warn("[MExt Loot] Lootr data for {} (id={}) missing", pos, ref.containerId);
                        continue;
                    }
                    int cleared = 0;
                    for (UUID playerId : ref.players.values()) {
                        Boolean ok = (Boolean) savedDataClearInventories.invoke(data, playerId);
                        if (Boolean.TRUE.equals(ok)) cleared++;
                    }
                    if (cleared > 0) {
                        resetCount += cleared;
                        LOGGER.info("[MExt Loot] reset Lootr container {} (id={}): cleared loot for {} player(s)",
                                pos, ref.containerId, cleared);
                    }
                } catch (Throwable t) {
                    LOGGER.error("[MExt Loot] Failed to reset Lootr container at {}", pos, t);
                }
            }
        }
        trackedLootr.clear();
        LOGGER.info("[MExt Loot] resetLootr done: {} player-container loot cleared", resetCount);
    }

    private static ResourceKey<Level> parseDim(String s) {
        try {
            return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(s));
        } catch (Exception e) {
            return null;
        }
    }

    // ============ 持久化 ============

    public static void writeSnapshot(CompoundTag targetNbt) {
        boolean any = false;
        ListTag entries = new ListTag();
        // vanilla
        for (Map.Entry<String, Map<BlockPos, LootRef>> dimEntry : tracked.entrySet()) {
            for (Map.Entry<BlockPos, LootRef> posEntry : dimEntry.getValue().entrySet()) {
                CompoundTag e = new CompoundTag();
                e.putString("kind", "vanilla");
                e.putString("dim", dimEntry.getKey());
                e.putInt("x", posEntry.getKey().getX());
                e.putInt("y", posEntry.getKey().getY());
                e.putInt("z", posEntry.getKey().getZ());
                e.putString("table", posEntry.getValue().table.location().toString());
                e.putLong("seed", posEntry.getValue().seed);
                entries.add(e);
                any = true;
            }
        }
        // lootr
        for (Map.Entry<String, Map<BlockPos, LootrRef>> dimEntry : trackedLootr.entrySet()) {
            for (Map.Entry<BlockPos, LootrRef> posEntry : dimEntry.getValue().entrySet()) {
                LootrRef ref = posEntry.getValue();
                for (UUID playerId : ref.players.values()) {
                    CompoundTag e = new CompoundTag();
                    e.putString("kind", "lootr");
                    e.putString("dim", dimEntry.getKey());
                    e.putInt("x", posEntry.getKey().getX());
                    e.putInt("y", posEntry.getKey().getY());
                    e.putInt("z", posEntry.getKey().getZ());
                    e.putUUID("containerId", ref.containerId);
                    e.putUUID("playerId", playerId);
                    entries.add(e);
                    any = true;
                }
            }
        }
        if (any) {
            targetNbt.put(SNAPSHOT_KEY, entries);
            LOGGER.debug("[MExt Loot] writeSnapshot: wrote {} entry(ies)", entries.size());
        }
    }

    public static void readSnapshot(CompoundTag sourceNbt) {
        if (!sourceNbt.contains(SNAPSHOT_KEY, Tag.TAG_LIST)) return;
        ListTag entries = sourceNbt.getList(SNAPSHOT_KEY, Tag.TAG_COMPOUND);
        int added = 0;
        for (int i = 0; i < entries.size(); i++) {
            try {
                CompoundTag e = entries.getCompound(i);
                ResourceKey<Level> dim = parseDim(e.getString("dim"));
                BlockPos pos = new BlockPos(e.getInt("x"), e.getInt("y"), e.getInt("z"));
                if ("lootr".equals(e.getString("kind"))) {
                    UUID containerId = e.getUUID("containerId");
                    UUID playerId = e.getUUID("playerId");
                    LootrRef ref = trackedLootr
                            .computeIfAbsent(e.getString("dim"), k -> new HashMap<>())
                            .computeIfAbsent(pos, k -> new LootrRef(containerId));
                    ref.players.put(playerId.toString(), playerId);
                } else {
                    ResourceKey<LootTable> table = ResourceKey.create(Registries.LOOT_TABLE,
                            ResourceLocation.parse(e.getString("table")));
                    track(dim, pos, table, e.getLong("seed"));
                }
                added++;
            } catch (Exception ex) {
                LOGGER.warn("[MExt Loot] failed to parse one tracked container entry", ex);
            }
        }
        LOGGER.info("[MExt Loot] readSnapshot: loaded {} tracked entry(ies)", added);
    }

    // ============ 内部 ============

    private static void track(ResourceKey<Level> dim, BlockPos pos, ResourceKey<LootTable> table, long seed) {
        tracked.computeIfAbsent(dim.location().toString(), k -> new HashMap<>())
                .put(pos.immutable(), new LootRef(table, seed));
    }

    private static int countVanilla() {
        int n = 0;
        for (Map<BlockPos, LootRef> v : tracked.values()) n += v.size();
        return n;
    }

    private static int countLootr() {
        int n = 0;
        for (Map<BlockPos, LootrRef> v : trackedLootr.values()) {
            for (LootrRef r : v.values()) n += r.players.size();
        }
        return n;
    }
}
