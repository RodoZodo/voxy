package me.cortex.voxy.client.config;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.config.SodiumConfigBuilder.*;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class VoxyConfigMenu implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder B) {
        try {
            registerConfigLateInner(B);
        } catch (Throwable t) {
            System.out.println("[Voxy] Sodium config registration failed: " + t);
            t.printStackTrace(System.out);
        }
    }

    private void registerConfigLateInner(ConfigBuilder B) {
        var CFG = VoxyConfig.CONFIG;

        var cc = B.registerModOptions("voxy", "Voxy", VoxyCommon.MOD_VERSION)
                .setIcon(Identifier.parse("voxy:icon.png"));

        //The mod options are always registered so the player can see the enabled state / status,
        //even while Voxy is deactivated (non-Vulkan backend).
        SodiumConfigBuilder.buildToSodium(B, cc, CFG::save, postOp->{
                    postOp.register("voxy:update_threads", ()->{
                        var instance = VoxyCommon.getInstance();
                        if (instance != null) {
                            instance.updateDedicatedThreads();
                        }
                    }, "voxy:enabled");
                },
                new Page(Component.translatable("voxy.config.general"),
                        new Group(
                                new BoolOption(
                                        "voxy:enabled",
                                        Component.translatable("voxy.config.general.enabled"),
                                        ()->CFG.enabled, v->{
                                            CFG.enabled=v;
                                            //we need to special case enabled, since the render reload flag runs befor us and its quite important we get it right
                                            if (v && ClientSessionEvents.inSession) {//We should only load when we are in session
                                                VoxyCommon.createInstance();
                                            }
                                        })
                                        .setPostChangeRunner(c->{
                                            if (!c) {
                                                VoxyCommon.shutdownInstance();
                                            }
                                        })
                        ), new Group(
                                new IntOption(
                                        "voxy:thread_count",
                                        Component.translatable("voxy.config.general.serviceThreads"),
                                        ()->CFG.serviceThreads, v->CFG.serviceThreads=v,
                                        new Range(1, CpuLayout.getCoreCount(), 1))
                                        .setPostChangeFlags("voxy:update_threads"),
                                new BoolOption(
                                        "voxy:use_sodium_threads",
                                        Component.translatable("voxy.config.general.useSodiumBuilder"),
                                        ()->!CFG.dontUseSodiumBuilderThreads, v->CFG.dontUseSodiumBuilderThreads=!v)
                                        .setPostChangeFlags("voxy:update_threads")
                        ).setEnabler("voxy:enabled"), new Group(
                                new BoolOption(
                                        "voxy:ingest_enabled",
                                        Component.translatable("voxy.config.general.ingest"),
                                        ()->CFG.ingestEnabled, v->CFG.ingestEnabled=v)
                        ).setEnabler("voxy:enabled")
                ));
    }
}
