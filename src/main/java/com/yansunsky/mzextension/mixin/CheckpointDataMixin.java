package com.yansunsky.mzextension.mixin;

import boomcow.minezero.checkpoint.CheckpointData;
import com.yansunsky.mzextension.compat.CuriosHelper;
import com.yansunsky.mzextension.compat.LootContainerHelper;
import com.yansunsky.mzextension.compat.PersistentDataHelper;
import com.yansunsky.mzextension.compat.SBPBackpackHelper;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin：将 SBP 背包快照持久化到 CheckpointData 的 NBT 中。
 * <p>
 * CheckpointData 是继承自 {@code SavedData} 的持久化类。通过钩入其
 * {@code save()} / {@code load()} 方法，我们可以在世界存档时将背包快照
 * 一并写入，在加载时一并恢复。
 * <p>
 * 为什么需要持久化：
 * <ul>
 *   <li>场景 1（无持久化）：setCheckpoint → 服务器重启 → 玩家死亡 → restoreCheckpoint。
 *       此时 {@code SBPBackpackHelper.pendingSnapshot} 已随 JVM 重启丢失，
 *       如果没有持久化，restoreCheckpoint 将无快照可用。</li>
 *   <li>场景 2（有持久化）：setCheckpoint → 世界存档（触发 save） → 服务器重启 →
 *       世界加载（触发 load） → 玩家死亡 → restoreCheckpoint。loadedSnapshot
 *       已从 CheckpointData NBT 中恢复，restoreCheckpoint 可以找到快照。</li>
 * </ul>
 *
 * @see SBPBackpackHelper#writeSnapshot(CompoundTag, String)
 * @see SBPBackpackHelper#readSnapshot(CompoundTag, String)
 */
@Mixin(value = CheckpointData.class, remap = false)
public abstract class CheckpointDataMixin {
    private static final String SBP_SNAPSHOT_KEY = "backpackStorageSnapshot";
    private static final String CURIOS_SNAPSHOT_KEY = "curiosSnapshot";

    /**
     * 在 CheckpointData.save() 返回前，将背包快照追加到 NBT。
     * <p>
     * RETURN 注入允许我们修改已构建好的 CompoundTag，
     * 在不影响原逻辑的前提下追加额外数据。
     */
    @Inject(method = "save", at = @At("RETURN"), remap = false)
    private void minezeroSbp$onSaveReturn(CompoundTag nbt, HolderLookup.Provider provider,
                                          CallbackInfoReturnable<CompoundTag> cir) {
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            SBPBackpackHelper.writeSnapshot(cir.getReturnValue(), SBP_SNAPSHOT_KEY);
        }
        if (ModList.get().isLoaded("curios")) {
            CuriosHelper.writeSnapshot(cir.getReturnValue(), CURIOS_SNAPSHOT_KEY);
        }
        PersistentDataHelper.writeSnapshot(cir.getReturnValue());
        // 战利品容器追踪持久化（只引用 MC 类，无需可选依赖守卫）
        LootContainerHelper.writeSnapshot(cir.getReturnValue());
    }

    /**
     * 在 CheckpointData.load() 返回前，从 NBT 中提取背包快照和 ForgeData。
     * <p>
     * load 方法是静态工厂方法，参数 nbt 是来源 NBT。
     * RETURN 注入允许我们在构建完 CheckpointData 后读取额外的快照数据。
     */
    @Inject(method = "load", at = @At("RETURN"), remap = false)
    private static void minezeroSbp$onLoadReturn(CompoundTag nbt, HolderLookup.Provider lookupProvider,
                                                  CallbackInfoReturnable<CheckpointData> cir) {
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            SBPBackpackHelper.readSnapshot(nbt, SBP_SNAPSHOT_KEY);
        }
        if (ModList.get().isLoaded("curios")) {
            CuriosHelper.readSnapshot(nbt, CURIOS_SNAPSHOT_KEY);
        }
        PersistentDataHelper.readSnapshot(nbt);
        LootContainerHelper.readSnapshot(nbt);
    }
}
