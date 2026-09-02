package boomcow.minezero.extension;

import boomcow.minezero.ModGameRules;
import boomcow.minezero.checkpoint.CheckpointManager;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.List;
import java.util.Random;

/**
 * 安全条件触发检查点 — 级联概率系统，由 gamerule safeCheckpointEnabled 控制开关。
 * <p>条件链：主世界 → 白天 → 满血 → 满饥饿 → 无负面效果 → 无敌对生物</p>
 */
public class SafeCheckpointTicker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static int tickCounter = 0;
    private static final Random random = new Random();
    public static boolean debugMode = false;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // 仅处理 END 相位（对应 NeoForge 的 ServerTickEvent.Post）
        if (event.phase != TickEvent.Phase.END) return;
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;

        // gamerule 可能尚未注册，必须 null-check
        var ourRule = level.getGameRules().getRule(ExtensionGameRules.SAFE_CHECKPOINT_ENABLED);
        boolean ourEnabled = ourRule != null && ourRule.get();

        var minezeroRule = level.getGameRules().getRule(ModGameRules.AUTO_CHECKPOINT_ENABLED);
        boolean minezeroAutoEnabled = minezeroRule != null && minezeroRule.get();

        // 我们的规则启用 → 禁用 MineZero 原生自动检查点
        // 我们的规则禁用 → 恢复 MineZero 原生自动检查点
        if (ourEnabled && minezeroAutoEnabled && minezeroRule != null) {
            minezeroRule.set(false, event.getServer());
            //LOGGER.info("[MExt Safe] MineZero autoCheckpointEnabled -> DISABLED");
        } else if (!ourEnabled && !minezeroAutoEnabled && minezeroRule != null) {
            minezeroRule.set(true, event.getServer());
            //LOGGER.info("[MExt Safe] MineZero autoCheckpointEnabled -> RESTORED");
        }

        if (!ourEnabled) return;
        if (++tickCounter < ModConfigs.SAFE_CHECKPOINT.checkIntervalTicks.get()) return;
        tickCounter = 0;

        int total = event.getServer().getPlayerList().getPlayers().size();
        LOGGER.info("[MExt Safe] ===== Checking {} player(s) (interval={}t) =====", total,
                ModConfigs.SAFE_CHECKPOINT.checkIntervalTicks.get());

        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (tryTrigger(player)) break;
        }
    }

    /** 对单个玩家执行级联概率检查。先掷骰再判条件，节省开销。 */
    private static boolean tryTrigger(ServerPlayer player) {
        var c = ModConfigs.SAFE_CHECKPOINT;
        //String name = player.getName().getString();
        Level level = player.level();
        int radius = c.enemySearchRadius.get();

        // P1: 主世界（先掷骰，再判条件）
        if (c.overworldEnabled.get()) {
            if (!roll(c.overworldChance.get())) return false;
            if (level.dimension() != Level.OVERWORLD) return false;
        }

        // P2: 白天
        if (c.daytimeEnabled.get()) {
            if (!roll(c.daytimeChance.get())) return false;
            if (!level.isDay()) return false;
        }

        // P3: 满血
        if (c.healthFullEnabled.get()) {
            if (!roll(c.healthFullChance.get())) return false;
            if (player.getHealth() < player.getMaxHealth()) return false;
        }

        // P4: 满饥饿
        if (c.hungerFullEnabled.get()) {
            if (!roll(c.hungerFullChance.get())) return false;
            if (player.getFoodData().getFoodLevel() < 20) return false;
        }

        // P5: 无负面效果
        if (c.noNegativeEffectsEnabled.get()) {
            if (!roll(c.noNegativeEffectsChance.get())) return false;
            if (hasNegativeEffect(player)) return false;
        }

        // P6: 无敌对生物
        if (c.noHostileNearbyEnabled.get()) {
            if (!roll(c.noHostileNearbyChance.get())) return false;
            if (hasNearbyHostile(player, radius)) return false;
        }

        // 全部通过（通知已由 CheckpointManagerMixin 统一处理）
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            SBPBackpackHelper.tickerInitiated = true;
        }
        CheckpointManager.setCheckpoint(player);
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            SBPBackpackHelper.tickerInitiated = false;
        }
        return true;
    }

    private static int pct(double d) { return (int)(d * 100); }

    private static boolean roll(double chance) {
        return random.nextDouble() < chance;
    }

    private static boolean hasNegativeEffect(ServerPlayer player) {
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (!effect.getEffect().isBeneficial()) return true;
        }
        return false;
    }

    private static boolean hasNearbyHostile(ServerPlayer player, int radius) {
        var bb = player.getBoundingBox().inflate(radius);
        List<Monster> hostiles = player.level().getEntitiesOfClass(Monster.class, bb, m -> m.isAlive());
        return !hostiles.isEmpty();
    }
}
