package com.yansunsky.mzextension.core;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameRules.BooleanValue;

/**
 * MineZero Extension 自定义游戏规则。
 * <p>安全检查点启用开关通过 gamerule 控制，与 MineZero 的 autoCheckpointEnabled 风格一致。</p>
 */
public class ExtensionGameRules {
    public static final GameRules.Key<BooleanValue> SAFE_CHECKPOINT_ENABLED =
            GameRules.register("safeCheckpointEnabled", GameRules.Category.PLAYER, GameRules.BooleanValue.create(true));

    public static final GameRules.Key<BooleanValue> GLOBAL_DEATH_TRIGGER =
            GameRules.register("globalDeathTrigger", GameRules.Category.PLAYER, GameRules.BooleanValue.create(false));

    public static boolean isGlobalDeathTriggerEnabled(ServerLevel level) {
        var rule = level.getGameRules().getRule(GLOBAL_DEATH_TRIGGER);
        return rule != null && rule.get();
    }
}
