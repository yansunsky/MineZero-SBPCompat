package com.yansunsky.mzextension.core;

import boomcow.minezero.checkpoint.CheckpointData;
import boomcow.minezero.checkpoint.CheckpointManager;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import org.slf4j.Logger;

/**
 * 新玩家自动存档延迟（方案 A，2026-09）。
 *
 * <p>背景：MineZero 默认"新角色进入世界将自动被存档"，但它是在 {@code PlayerLoggedInEvent}
 * 中<b>立即</b>建档/补录的。部分模组会在玩家登录后的下一个 tick 甚至更晚才发放初始物资，
 * 导致这些初始物品没有被纳入那次快照，之后回档会丢失。
 *
 * <p>本类不改 MineZero 源码，而是以扩展身份：
 * <ol>
 *   <li>用 {@link EventPriority#HIGHEST} 监听登录（先于 MineZero 的 NORMAL 处理器），
 *       判断该玩家登录时是否<b>尚无存档</b>（即"新玩家"）。</li>
 *   <li>若是，调度 {@code TickTask} 延后 20 tick（1 秒）执行一次
 *       {@link CheckpointManager#setCheckpoint(ServerPlayer)}——此时初始物资模组通常已发放，
 *       重新建档即可把物资纳入。</li>
 *   <li>回调里<b>仅在"当前无锚 或 锚就是该玩家"时</b>才执行：这正是 MineZero
 *       "checkpointOnWorldCreation 首档 / 无锚自动设锚" 的路径，重存不会改变锚归属；
 *       若锚是其他玩家（加入已有存档世界的新玩家），则跳过，避免夺走锚或推进他人检查点。</li>
 * </ol>
 *
 * <p>延迟时长用常量 {@link #DELAY_TICKS}（20 = 1 秒），后续需要可移到配置。
 */
public class NewPlayerLoginDelay {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 新玩家登录后延迟多少 tick 再重存一次（20 tick = 1 秒） */
    private static final int DELAY_TICKS = 20;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerLogin(PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;

        MinecraftServer server = player.getServer();
        if (server == null) return;

        try {
            ServerLevel level = player.serverLevel();
            CheckpointData data = CheckpointData.get(level);
            // 先于 MineZero 判断：该玩家登录时是否尚无存档（新玩家）
            boolean isNew = data.getPlayerData(player.getUUID(), level.registryAccess()) == null;
            if (!isNew) return;

            final ServerPlayer loginPlayer = player;
            final String name = player.getName().getString();
            final java.util.UUID uuid = player.getUUID();
            int targetTick = server.getTickCount() + DELAY_TICKS;

            server.tell(new TickTask(targetTick, () -> {
                try {
                    // 延迟期间可能下线
                    ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                    if (online == null || online.level().isClientSide()) {
                        LOGGER.debug("[MExt LoginDelay] {} logged out before delayed checkpoint.", name);
                        return;
                    }
                    CheckpointData d = CheckpointData.get(online.serverLevel());
                    java.util.UUID anchor = d.getAnchorPlayerUUID();
                    // 只有当前无锚，或锚就是该玩家（首档路径）才重存；否则不夺锚
                    if (anchor != null && !anchor.equals(uuid)) {
                        LOGGER.info("[MExt LoginDelay] New player {} joined an anchored world; skip re-save to avoid changing anchor {}.",
                                name, anchor);
                        return;
                    }
                    CheckpointManager.setCheckpoint(online);
                    LOGGER.info("[MExt LoginDelay] Re-saved checkpoint for new player {} after {} ticks (initial gear included).",
                            name, DELAY_TICKS);
                } catch (Exception e) {
                    LOGGER.error("[MExt LoginDelay] Delayed checkpoint failed for {}", name, e);
                }
            }));
        } catch (Exception e) {
            LOGGER.error("[MExt LoginDelay] Failed to schedule delayed checkpoint for login", e);
        }
    }
}
