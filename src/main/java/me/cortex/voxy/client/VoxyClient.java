package me.cortex.voxy.client;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.VoxyVulkanRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

public class VoxyClient implements ClientModInitializer {
    private static final HashSet<String> FREX = new HashSet<>();
    private static boolean instanceFactorySet;
    private static boolean rendererBootstrapped;

    /** Capture only — never compile pipelines. Safe during Minecraft graphics-backend startup. */
    public static void captureRenderer(@Nullable GpuDevice device) {
        try {
            if (device == null) {
                device = RenderSystem.tryGetDevice();
            }
            if (device != null) {
                VkContext.INSTANCE.captureFromGpuDevice(device);
            }
        } catch (Throwable t) {
            Logger.warn("Voxy (Vulkan): device capture failed", t);
        }
    }

    /**
     * Capture + init pipelines. Must not run inside {@code Minecraft.<init>} / backend createDevice:
     * a throw there trips the crash ladder (Graphics API forced to OpenGL).
     */
    public static void bootstrapRenderer(@Nullable GpuDevice device) {
        try {
            captureRenderer(device);
            initVoxyClient();
        } catch (Throwable t) {
            Logger.warn("Voxy (Vulkan): renderer bootstrap failed", t);
        }
    }

    private static void ensureInstanceFactory() {
        if (instanceFactorySet) {
            return;
        }
        instanceFactorySet = true;
        VoxyCommon.setInstanceFactory(VoxyClientInstance::new);
        Logger.info("Voxy: instance factory registered (ingest/save available)");
    }

    public static void initVoxyClient() {
        if (rendererBootstrapped) {
            return;
        }
        try {
            if (RenderSystem.tryGetDevice() == null && !VkContext.INSTANCE.isVulkanActive()) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        rendererBootstrapped = true;
        ensureInstanceFactory();

        try {
            var ctx = VkContext.INSTANCE;
            if (!ctx.shouldActivate()) {
                Logger.warn("Voxy (Vulkan): GPU path disabled - " + ctx.getDeactivationReason());
                return;
            }

            var caps = ctx.capabilities();
            if (caps != null) {
                Logger.info("Voxy (Vulkan): detected " + caps);
                if (!caps.isSystemSupported()) {
                    Logger.error("Voxy (Vulkan): required device features are missing, GPU LoDs disabled. " + caps);
                    return;
                }
            }

            VoxyVulkanRenderSystem.INSTANCE.init();
            Logger.info("Voxy (Vulkan): render system initialized: " + VoxyVulkanRenderSystem.INSTANCE.describe());
        } catch (Throwable t) {
            Logger.warn("Voxy (Vulkan): render system initialization failed, GPU LoDs disabled", t);
        }
    }

    public static boolean isLunarClient() {
        try {
            var loader = FabricLoader.getInstance();
            return loader.isModLoaded("ichor") || loader.isModLoaded("lunar") || loader.isModLoaded("lunarclient");
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onInitializeClient() {
        try {
            System.out.println("[Voxy] client entrypoint lunar=" + isLunarClient()
                    + " ctx=" + VkContext.INSTANCE);
            Logger.info("Voxy (Vulkan): client entrypoint lunar=" + isLunarClient()
                    + " ctx=" + VkContext.INSTANCE);

            ensureInstanceFactory();
            DebugEntries.init();

            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                bootstrapRenderer(null);
                ClientSessionEvents.tick(client);
            });

            ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
                dispatcher.register(VoxyCommands.register());
            });

            FabricLoader.getInstance()
                    .getEntrypoints("frex_flawless_frames", Consumer.class)
                    .forEach(api -> ((Consumer<Function<String,Consumer<Boolean>>>)api).accept(name->active->{if (active) {
                        FREX.add(name);
                    } else {
                        FREX.remove(name);
                    }}));
        } catch (Throwable t) {
            System.out.println("[Voxy] onInitializeClient failed: " + t);
            t.printStackTrace(System.out);
        }
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
