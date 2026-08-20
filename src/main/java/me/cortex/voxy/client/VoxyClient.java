package me.cortex.voxy.client;

import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.VoxyVulkanRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

public class VoxyClient implements ClientModInitializer {
    private static final HashSet<String> FREX = new HashSet<>();
    private static boolean instanceFactorySet;

    public static void initVoxyClient() {
        var ctx = VkContext.INSTANCE;
        if (!ctx.shouldActivate()) {
            // Voxy is a Vulkan-only renderer: auto-deactivate with a clear reason.
            Logger.warn("Voxy (Vulkan): disabled - " + ctx.getDeactivationReason());
            return;
        }

        var caps = ctx.capabilities();
        if (caps != null) {
            Logger.info("Voxy (Vulkan): detected " + caps);
            if (!caps.isSystemSupported()) {
                Logger.error("Voxy (Vulkan): required device features are missing, Voxy disabled. " + caps);
                return;
            }
        }

        try {
            VoxyVulkanRenderSystem.INSTANCE.init();
            Logger.info("Voxy (Vulkan): render system initialized: " + VoxyVulkanRenderSystem.INSTANCE.describe());
        } catch (RuntimeException e) {
            Logger.error("Voxy (Vulkan): render system initialization failed, Voxy disabled", e);
            return;
        }

        //World engine reactivation: from now on a Voxy instance (CPU octree + ingest) is created
        // on session start, feeding the GPU traversal.
        if (!instanceFactorySet) {
            instanceFactorySet = true;
            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);
            Logger.info("Voxy (Vulkan): world engine reactivated, instance factory registered");
        }
    }

    @Override
    public void onInitializeClient() {
        DebugEntries.init();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            if (VoxyCommon.isAvailable()) {
                dispatcher.register(VoxyCommands.register());
            }
        });

        FabricLoader.getInstance()
                .getEntrypoints("frex_flawless_frames", Consumer.class)
                .forEach(api -> ((Consumer<Function<String,Consumer<Boolean>>>)api).accept(name->active->{if (active) {
                    FREX.add(name);
                } else {
                    FREX.remove(name);
                }}));
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}