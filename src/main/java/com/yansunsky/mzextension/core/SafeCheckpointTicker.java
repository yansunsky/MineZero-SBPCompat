package com.yansunsky.mzextension.core;

import boomcow.minezero.ModGameRules;
import boomcow.minezero.checkpoint.CheckpointManager;
import com.mojang.logging.LogUtils;
import com.yansunsky.mzextension.compat.SBPBackpackHelper;
import com.yansunsky.mzextension.config.ModConfigs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Random;

/**
 * 安全条件触发检查点 — 级联概率系统，由 gamerule safeCheckpointEnabled 控制开关。
 * <p>条件链：主世界 → 白天 → 满血 → 饱食度(>15) → 无负面效果 → 无敌对生物。</p>
 * <p>伙伴加成（companionBonusEnabled）：附近(enemySearchRadius)有存活的已驯服伙伴（狗/猫/鹦鹉等 TamableAnimal）时，
 * 一次概率门掷骰失败可获得豁免（提升存档频率）；不豁免硬性安全条件。</p>
 */
public class SafeCheckpointTicker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static int tickCounter = 0;
    private static final Random random = new Random();
    public static boolean debugMode = false;

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
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

    /** 对单个玩家执行级联概率检查。先掷骰再判硬条件，节省开销；概率门失败可被"伙伴加成"豁免一次。 */
    private static boolean tryTrigger(ServerPlayer player) {
        var c = ModConfigs.SAFE_CHECKPOINT;
        Level level = player.level();
        int radius = c.enemySearchRadius.get();

        // 伙伴加成：附近(enemySearchRadius)是否有存活的已驯服伙伴（狗/猫/鹦鹉等 TamableAnimal）。
        // 有则本玩家本轮拥有一次"概率门掷骰失败豁免"（提升存档频率），不影响硬性安全条件。
        boolean[] companionForgive = { c.companionBonusEnabled.get() && hasNearbyTamedAlive(player, radius) };

        // P1: 主世界（先掷骰，再判条件）
        if (c.overworldEnabled.get()) {
            if (!rollOrForgive(c.overworldChance.get(), companionForgive)) return false;
            if (level.dimension() != Level.OVERWORLD) return false;
        }

        // P2: 白天
        if (c.daytimeEnabled.get()) {
            if (!rollOrForgive(c.daytimeChance.get(), companionForgive)) return false;
            if (!level.isDay()) return false;
        }

        // P3: 满血
        if (c.healthFullEnabled.get()) {
            if (!rollOrForgive(c.healthFullChance.get(), companionForgive)) return false;
            if (player.getHealth() < player.getMaxHealth()) return false;
        }

        // P4: 饱食度充足（门槛 >15，不再是满饥饿 20；有足够体力即可，避免过度苛刻）
        if (c.hungerFullEnabled.get()) {
            if (!rollOrForgive(c.hungerFullChance.get(), companionForgive)) return false;
            if (player.getFoodData().getFoodLevel() <= 15) return false;
        }

        // P5: 无负面效果
        if (c.noNegativeEffectsEnabled.get()) {
            if (!rollOrForgive(c.noNegativeEffectsChance.get(), companionForgive)) return false;
            if (hasNegativeEffect(player)) return false;
        }

        // P6: 无敌对生物
        if (c.noHostileNearbyEnabled.get()) {
            if (!rollOrForgive(c.noHostileNearbyChance.get(), companionForgive)) return false;
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

    /** 概率门：掷骰通过返回 true；失败时若还有"伙伴豁免"剩余则消耗一次并放行 */
    private static boolean rollOrForgive(double chance, boolean[] companionForgive) {
        if (roll(chance)) return true;
        if (companionForgive[0]) {
            companionForgive[0] = false;
            return true;
        }
        return false;
    }

    /** 附近(enemySearchRadius)是否有存活的已驯服伙伴（Wolf/Cat/Parrot 等都继承 TamableAnimal） */
    private static boolean hasNearbyTamedAlive(ServerPlayer player, int radius) {
        var bb = player.getBoundingBox().inflate(radius);
        List<TamableAnimal> pets = player.level().getEntitiesOfClass(TamableAnimal.class, bb,
                t -> t.isAlive() && t.isTame());
        return !pets.isEmpty();
    }

    private static boolean hasNegativeEffect(ServerPlayer player) {
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (!effect.getEffect().value().isBeneficial()) return true;
        }
        return false;
    }

    private static boolean hasNearbyHostile(ServerPlayer player, int radius) {
        var bb = player.getBoundingBox().inflate(radius);
        List<Monster> hostiles = player.level().getEntitiesOfClass(Monster.class, bb, m -> m.isAlive());
        return !hostiles.isEmpty();
    }
}
