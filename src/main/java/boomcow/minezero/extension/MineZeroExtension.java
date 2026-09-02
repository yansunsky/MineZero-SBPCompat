package boomcow.minezero.extension;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * MineZero Extension — SBP 兼容 | 安全检查点 | 全局死亡回归
 */
@Mod("minezero_extension")
public class MineZeroExtension {
    private static final Logger LOGGER = LogUtils.getLogger();

    public MineZeroExtension() {
        var dummy = ExtensionGameRules.SAFE_CHECKPOINT_ENABLED;
        var dummy2 = ExtensionGameRules.GLOBAL_DEATH_TRIGGER;

        LOGGER.info("[MineZeroExtension] Initializing...");
        LOGGER.info("[MineZeroExtension] Gamerule: safeCheckpointEnabled, globalDeathTrigger");

        MinecraftForge.EVENT_BUS.register(SafeCheckpointTicker.class);
        MinecraftForge.EVENT_BUS.register(GlobalDeathHandler.class);
        MinecraftForge.EVENT_BUS.register(this);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ModConfigs.COMMON_CONFIG_SPEC);
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
