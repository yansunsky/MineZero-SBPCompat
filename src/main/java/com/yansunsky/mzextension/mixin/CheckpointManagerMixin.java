package com.yansunsky.mzextension.mixin;

import boomcow.minezero.checkpoint.CheckpointManager;
import com.yansunsky.mzextension.compat.CuriosHelper;
import com.yansunsky.mzextension.compat.LootContainerHelper;
import com.yansunsky.mzextension.compat.PersistentDataHelper;
import com.yansunsky.mzextension.compat.SBPBackpackHelper;
import com.yansunsky.mzextension.core.SafeCheckpointTicker;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin：钩入 MineZero 的检查点管理器的 setCheckpoint / restoreCheckpoint 方法。
 * <p>
 * 注入策略：
 * <ul>
 *   <li>{@code setCheckpoint(TAIL)} — 在 MineZero 保存完所有玩家和世界数据后，
 *       立即捕获 BackpackStorage 的快照，确保快照与检查点一致。</li>
 *   <li>{@code restoreCheckpoint(HEAD)} — 在 MineZero 开始恢复玩家背包之前，
 *       先将 BackpackStorage 回档到检查点状态，确保后续恢复的背包 ItemStack
 *       （及其 UUID 引用）指向正确的回档内容。</li>
 *   <li>{@code restoreCheckpoint(RETURN)} — MineZero 全部恢复完成后再次写回
 *       BackpackStorage（最终权威状态，幂等）。</li>
 * </ul>
 *
 * @see SBPBackpackHelper#captureSnapshot(ServerPlayer)
 * @see SBPBackpackHelper#applySnapshot(ServerPlayer)
 */
@Mixin(value = CheckpointManager.class, remap = false)
public abstract class CheckpointManagerMixin {
    private static final Logger LOGGER = LogUtils.getLogger();

    static {
        LOGGER.info("[MineZeroExtension] CheckpointManagerMixin STATIC INIT — Mixin class loaded!");
    }

    /**
     * 在 setCheckpoint 完成后捕获 BackpackStorage 快照。
     * <p>
     * TAIL 注入确保 MineZero 已经完成所有状态保存（玩家、世界、实体等），
     * 此时 BackpackStorage 中的状态与检查点一致。
     */
    @Inject(method = "setCheckpoint", at = @At("TAIL"), remap = false)
    private static void minezeroSbp$onSetCheckpointTail(ServerPlayer anchorPlayer, CallbackInfo ci) {
        LOGGER.info("[MExt DEBUG] setCheckpoint TAIL tick={} tickerInit={} anchor={}",
                anchorPlayer != null ? anchorPlayer.level().getGameTime() : -1,
                ModList.get().isLoaded("sophisticatedbackpacks") ? SBPBackpackHelper.tickerInitiated : false,
                anchorPlayer != null ? anchorPlayer.getName().getString() : "null");
        // 捕获 Curios 饰品栏快照（必须先于 SBP，因为饰品栏可能含有背包引用）
        if (anchorPlayer != null && anchorPlayer.getServer() != null && ModList.get().isLoaded("curios")) {
            for (ServerPlayer p : anchorPlayer.getServer().getPlayerList().getPlayers()) {
                CuriosHelper.captureInventory(p);
            }
        }
        // 捕获 SBP BackpackStorage 快照
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            SBPBackpackHelper.captureSnapshot(anchorPlayer);
        }
        if (anchorPlayer != null && anchorPlayer.getServer() != null) {
            for (ServerPlayer p : anchorPlayer.getServer().getPlayerList().getPlayers()) {
                PersistentDataHelper.capture(p);
            }
            if (SafeCheckpointTicker.debugMode) {
                String name = anchorPlayer.getName().getString();
                for (ServerPlayer p : anchorPlayer.getServer().getPlayerList().getPlayers()) {
                    p.displayClientMessage(
                            Component.translatable("minezero_extension.safe_checkpoint.triggered", name)
                                    .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                            false);
                }
            }
        }
        // 新检查点：清空上一轮"战利品容器"追踪（本轮 diff 从此刻开始记录）
        LootContainerHelper.startNewCheckpoint();
    }

    /**
     * 在 restoreCheckpoint 开始前恢复 BackpackStorage 快照。
     * <p>
     * HEAD 注入必须在任何恢复逻辑之前执行，因为：
     * <ol>
     *   <li>MineZero 会清除玩家背包（playerInventory.clearContent()）</li>
     *   <li>然后从 NBT 恢复 ItemStack（包含 STORAGE_UUID）</li>
     *   <li>如果 BackpackStorage 内容不同步，恢复后的背包将显示错误内容</li>
     * </ol>
     * 因此 BackpackStorage 必须在第 2 步之前恢复到检查点状态。
     * 该方法可安全重复调用（幂等）；RETURN 阶段还会再执行一次最终写回，
     * 以覆盖 MineZero 在中间步骤清理实体时 SBP 对怪物背包 UUID 的二次删除。
     */
    @Inject(method = "restoreCheckpoint", at = @At("HEAD"), remap = false)
    private static void minezeroSbp$onRestoreCheckpointHead(ServerPlayer anchorPlayer, CallbackInfo ci) {
        LOGGER.info("[MExt DEBUG] restoreCheckpoint HEAD tick={} anchor={}",
                anchorPlayer != null ? anchorPlayer.level().getGameTime() : -1,
                anchorPlayer != null ? anchorPlayer.getName().getString() : "null");
        // 恢复 Curios 饰品栏（先于 SBP，确保饰品栏物品就位后背包内容能正确解析）
        if (anchorPlayer != null && anchorPlayer.getServer() != null && ModList.get().isLoaded("curios")) {
            for (ServerPlayer p : anchorPlayer.getServer().getPlayerList().getPlayers()) {
                CuriosHelper.applyInventory(p);
            }
        }
        // 恢复 SBP BackpackStorage（确保背包 UUID 引用指向正确内容）
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            SBPBackpackHelper.applySnapshot(anchorPlayer);
        }
    }

    /**
     * 在 restoreCheckpoint 完成后做最终收尾：
     * <ol>
     *   <li>合并所有玩家的 ForgeData 快照（MineZero 全部恢复完成后才做，避免被中间步骤覆盖）</li>
     *   <li>再次写回 SBP BackpackStorage 快照（最终权威状态）。MineZero 恢复过程中会
     *       discard 全部非玩家实体，若实体是 SBP 自带背包的怪物，SBP 的
     *       EntityLeaveLevelEvent → removeBackpackUuid 会把对应 UUID 内容删掉；
     *       因此必须在实体清理与重生都完成后的 RETURN 再覆盖一次，BackpackStorage
     *       的最终状态才与检查点时刻一致。</li>
     * </ol>
     */
    @Inject(method = "restoreCheckpoint", at = @At("RETURN"), remap = false)
    private static void minezeroForge$onRestoreCheckpointReturn(ServerPlayer anchorPlayer, CallbackInfo ci) {
        if (anchorPlayer != null && anchorPlayer.getServer() != null) {
            for (ServerPlayer p : anchorPlayer.getServer().getPlayerList().getPlayers()) {
                PersistentDataHelper.apply(p);
            }
        }
        // SBP 最终写回（幂等；与 ForgeData 互不影响）
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            SBPBackpackHelper.applySnapshot(anchorPlayer);
        }
        // 战利品容器重置：把检查点后首次打开过的奖励箱重置为"未解包"，下次打开按原 seed 重新生成
        if (anchorPlayer != null && anchorPlayer.getServer() != null) {
            LootContainerHelper.resetAll(anchorPlayer.getServer());
        }
    }
}
