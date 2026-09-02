package boomcow.minezero.extension;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * MineZero Extension 配置文件。
 * <p>安全条件触发检查点参数：概率门 + 每条件启用开关。</p>
 */
public class ModConfigs {
    public static final ForgeConfigSpec COMMON_CONFIG_SPEC;
    static final SafeCheckpointConfig SAFE_CHECKPOINT;

    static {
        var pair = new ForgeConfigSpec.Builder().configure(SafeCheckpointConfig::new);
        COMMON_CONFIG_SPEC = pair.getRight();
        SAFE_CHECKPOINT = pair.getLeft();
    }

    public static class SafeCheckpointConfig {
        public final ForgeConfigSpec.BooleanValue enabled;
        public final ForgeConfigSpec.IntValue checkIntervalTicks;
        public final ForgeConfigSpec.IntValue enemySearchRadius;
        public final ForgeConfigSpec.BooleanValue globalDeathTrigger;

        public final ForgeConfigSpec.BooleanValue overworldEnabled;
        public final ForgeConfigSpec.BooleanValue daytimeEnabled;
        public final ForgeConfigSpec.BooleanValue healthFullEnabled;
        public final ForgeConfigSpec.BooleanValue hungerFullEnabled;
        public final ForgeConfigSpec.BooleanValue noNegativeEffectsEnabled;
        public final ForgeConfigSpec.BooleanValue noHostileNearbyEnabled;

        public final ForgeConfigSpec.DoubleValue overworldChance;
        public final ForgeConfigSpec.DoubleValue daytimeChance;
        public final ForgeConfigSpec.DoubleValue healthFullChance;
        public final ForgeConfigSpec.DoubleValue hungerFullChance;
        public final ForgeConfigSpec.DoubleValue noNegativeEffectsChance;
        public final ForgeConfigSpec.DoubleValue noHostileNearbyChance;

        SafeCheckpointConfig(ForgeConfigSpec.Builder builder) {
            builder.comment("Safe checkpoint trigger settings")
                   .comment("Runtime toggle: /gamerule safeCheckpointEnabled true|false")
                   .push("safeCheckpoint");

            enabled = builder
                    .comment("Initial default state. false = use MineZero's native auto-checkpoint. Default: true")
                    .define("enabled", true);

            checkIntervalTicks = builder
                    .comment("How often (in ticks) to check safe conditions. Default: 800 (40 seconds)")
                    .defineInRange("checkIntervalTicks", 800, 20, 1200);

            enemySearchRadius = builder
                    .comment("Radius (in blocks) to search for hostile mobs. Default: 24")
                    .defineInRange("enemySearchRadius", 24, 4, 128);

            globalDeathTrigger = builder
                    .comment("Global death trigger: any player death triggers Return by Death. Default: false")
                    .define("globalDeathTrigger", false);

            builder.comment("Enable/disable each condition (true = check this condition)")
                   .push("conditions");

            overworldEnabled = builder.define("overworld", true);
            daytimeEnabled = builder.define("daytime", true);
            healthFullEnabled = builder.define("healthFull", true);
            hungerFullEnabled = builder.define("hungerFull", true);
            noNegativeEffectsEnabled = builder.define("noNegativeEffects", true);
            noHostileNearbyEnabled = builder.define("noHostileNearby", true);

            builder.pop();

            builder.comment("Probability gates (0.0-1.0). Rolled BEFORE the condition check.")
                   .comment("20s interval → 3 checks/min → 6 checks/2min.")
                   .comment("Cumulative target: ~1 save/2min (0.167 per check).")
                   .push("chances");

            overworldChance = builder
                    .comment("Probability gate for overworld. Default: 0.90")
                    .defineInRange("overworldChance", 0.90, 0.0, 1.0);

            daytimeChance = builder
                    .comment("Probability gate for daytime. Default: 0.75")
                    .defineInRange("daytimeChance", 0.75, 0.0, 1.0);

            healthFullChance = builder
                    .comment("Probability gate for health. Default: 0.75")
                    .defineInRange("healthFullChance", 0.75, 0.0, 1.0);

            hungerFullChance = builder
                    .comment("Probability gate for hunger. Default: 0.55")
                    .defineInRange("hungerFullChance", 0.55, 0.0, 1.0);

            noNegativeEffectsChance = builder
                    .comment("Probability gate for negative effects. Default: 0.60")
                    .defineInRange("noNegativeEffectsChance", 0.60, 0.0, 1.0);

            noHostileNearbyChance = builder
                    .comment("Probability gate for no-hostile. Default: 1.00 (condition only)")
                    .defineInRange("noHostileNearbyChance", 1.00, 0.0, 1.0);

            builder.pop();
            builder.pop();
        }
    }
}
