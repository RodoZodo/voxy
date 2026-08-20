package me.cortex.voxy.client;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.VoxyVulkanRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

public class VoxyClient implements ClientModInitializer {
    private static final HashSet<String> FREX = new HashSet<>();
    private static boolean instanceFactorySet;
    private static boolean rendererBootstrapped;

    /**
     * Capture the GPU backend (if any) and init Voxy. Idempotent and never throws — Lunar/Ichor
     * and Minecraft's crash ladder both abort the whole process if renderer startup throws.
     *
     * <p>A null device with no live {@link GpuDevice} is ignored (too early, e.g. an unused
     * {@code Minecraft} constructor) so we do not lock bootstrap before the backend exists.
     */
    public static void bootstrapRenderer(@Nullable GpuDevice device) {
        try {
            if (device == null) {
                device = RenderSystem.tryGetDevice();
            }
            if (device == null) {
                return;
            }
            VkContext.INSTANCE.captureFromGpuDevice(device);
            initVoxyClient();
        } catch (Throwable t) {
            Logger.warn("Voxy (Vulkan): renderer bootstrap failed", t);
        }
    }

    public static void initVoxyClient() {
        if (rendererBootstrapped) {
            return;
        }
        rendererBootstrapped = true;

        var ctx = VkContext.INSTANCE;
        if (!ctx.shouldActivate()) {
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

    public static boolean isLunarClient() {
        var loader = FabricLoader.getInstance();
        return loader.isModLoaded("ichor") || loader.isModLoaded("lunar") || loader.isModLoaded("lunarclient");
    }

    @Override
    public void onInitializeClient() {
        Logger.info("Voxy (Vulkan): client entrypoint lunar=" + isLunarClient()
                + " ctx=" + VkContext.INSTANCE);

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
