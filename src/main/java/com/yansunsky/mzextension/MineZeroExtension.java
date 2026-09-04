package com.yansunsky.mzextension;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.logging.LogUtils;
import com.yansunsky.mzextension.compat.LootContainerHelper;
import com.yansunsky.mzextension.config.ModConfigs;
import com.yansunsky.mzextension.core.ExtensionGameRules;
import com.yansunsky.mzextension.core.GlobalDeathHandler;
import com.yansunsky.mzextension.core.NewPlayerLoginDelay;
import com.yansunsky.mzextension.core.SafeCheckpointTicker;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * MineZero Extension — SBP 兼容 | 安全检查点 | 全局死亡回归
 */
@Mod("minezero_extension")
public class MineZeroExtension {
    private static final Logger LOGGER = LogUtils.getLogger();

    public MineZeroExtension(IEventBus modEventBus, ModContainer modContainer) {
        var dummy = ExtensionGameRules.SAFE_CHECKPOINT_ENABLED;
        var dummy2 = ExtensionGameRules.GLOBAL_DEATH_TRIGGER;

        LOGGER.info("[MineZeroExtension] Initializing...");
        LOGGER.info("[MineZeroExtension] Gamerule: safeCheckpointEnabled, globalDeathTrigger");

        NeoForge.EVENT_BUS.register(SafeCheckpointTicker.class);
        NeoForge.EVENT_BUS.register(GlobalDeathHandler.class);
        NeoForge.EVENT_BUS.register(LootContainerHelper.class);
        NeoForge.EVENT_BUS.register(NewPlayerLoginDelay.class);
        NeoForge.EVENT_BUS.register(this);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, ModConfigs.COMMON_CONFIG_SPEC);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("minezeroextension")
                        .requires(cs -> cs.hasPermission(2))
                        .then(Commands.literal("debugmode")
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                            boolean val = BoolArgumentType.getBool(ctx, "enabled");
                                            SafeCheckpointTicker.debugMode = val;
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Debug mode " + (val ? "enabled" : "disabled")),
                                                    true);
                                            return 1;
                                        }))
                        )
        );
    }

    /** 服务器启动后，读配置文件同步 gamerule 初始值 */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;

        syncGamerule(level, ExtensionGameRules.SAFE_CHECKPOINT_ENABLED,
                ModConfigs.SAFE_CHECKPOINT.enabled.get(), "safeCheckpointEnabled", event);
        syncGamerule(level, ExtensionGameRules.GLOBAL_DEATH_TRIGGER,
                ModConfigs.SAFE_CHECKPOINT.globalDeathTrigger.get(), "globalDeathTrigger", event);
    }

    private static void syncGamerule(ServerLevel level, GameRules.Key<GameRules.BooleanValue> key,
                                      boolean configVal, String name, ServerStartedEvent event) {
        var rule = level.getGameRules().getRule(key);
        if (rule == null) return;
        if (rule.get() != configVal) {
            rule.set(configVal, event.getServer());
            LOGGER.info("[MineZeroExtension] Config {}={} -> gamerule synced", name, configVal);
        }
    }
}
