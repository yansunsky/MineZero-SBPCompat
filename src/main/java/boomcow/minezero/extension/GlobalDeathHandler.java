package boomcow.minezero.extension;

import boomcow.minezero.ConfigHandler;
import boomcow.minezero.MineZero;
import boomcow.minezero.ModSoundEvents;
import boomcow.minezero.checkpoint.CheckpointData;
import boomcow.minezero.checkpoint.CheckpointManager;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.RegistryObject;

/**
 * 全局死亡触发 — 任意玩家死亡都触发死亡回归。
 * <p>
 * 由 gamerule {@code globalDeathTrigger} 控制，默认关闭。
 * 非锚玩家触发时播放与 MineZero 相同的死亡铃声。
 * </p>
 */
public class GlobalDeathHandler {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!ExtensionGameRules.isGlobalDeathTriggerEnabled(player.serverLevel())) return;

        var data = CheckpointData.get(player.serverLevel());
        // 锚玩家交给 MineZero 原生 handler
        if (data.getAnchorPlayerUUID() != null && player.getUUID().equals(data.getAnchorPlayerUUID())) return;

        // 非锚玩家：有存档才触发回归
        if (data.getPlayerData(player.getUUID()) == null) return;

        event.setCanceled(true);
        ServerPlayer sp = player;
        player.getServer().execute(() -> CheckpointManager.restoreCheckpoint(sp));

        // 播放死亡铃声（MineZero 原生只给锚玩家播放，这里补上）
        playChime(player);
    }

    private static void playChime(ServerPlayer player) {
        String chime = ConfigHandler.getDeathChime();
        if ("CLASSIC".equalsIgnoreCase(chime)) {
            stopAndPlay(player, ResourceLocation.fromNamespaceAndPath(MineZero.MODID, "death_chime"),
                    ModSoundEvents.DEATH_CHIME);
        } else if ("ALTERNATE".equalsIgnoreCase(chime)) {
            stopAndPlay(player, ResourceLocation.fromNamespaceAndPath(MineZero.MODID, "alt_death_chime"),
                    ModSoundEvents.ALT_DEATH_CHIME);
        }
    }

    private static void stopAndPlay(ServerPlayer player, ResourceLocation soundId,
                                     RegistryObject<SoundEvent> holder) {
        var stop = new ClientboundStopSoundPacket(soundId, SoundSource.PLAYERS);
        if (player.connection != null) {
            player.connection.send(stop);
        }
        player.playNotifySound(holder.get(), SoundSource.PLAYERS, 0.8F, 1.0F);
    }
}
