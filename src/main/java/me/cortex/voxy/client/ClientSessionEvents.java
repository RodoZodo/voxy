package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.VoxyVulkanRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ClientSessionEvents {
    public static boolean inSession = false;
    private static boolean deactivationNoticeShown = false;
    private static int startWaitTicks = 0;
    private static boolean lodHookNoticeSent = false;

    public static void tick(Minecraft client) {
        boolean inWorld = client != null && client.level != null && client.player != null;
        if (inWorld && !inSession) {
            if (!readyToStartSession()) {
                return;
            }
            sessionStart();
        } else if (!inWorld && inSession) {
            sessionEnd();
        }

        if (inSession && VoxyCommon.getInstance() == null && VoxyCommon.isAvailable() && VoxyConfig.CONFIG.enabled) {
            Logger.info("Voxy: creating world engine (late)");
            VoxyCommon.createInstance();
        }

        if (inSession) {
            tryAttachGpu();
            ClientChunkIngest.tick(client);
        }
    }

    private static boolean readyToStartSession() {
        if (VoxyVulkanRenderSystem.INSTANCE.isInitialized() || VkContext.INSTANCE.isVulkanActive()) {
            return true;
        }
        startWaitTicks++;
        return startWaitTicks >= 40;
    }

    private static void tryAttachGpu() {
        var instance = VoxyCommon.getInstance();
        if (!(instance instanceof VoxyClientInstance clientInstance)) {
            return;
        }
        if (!VoxyVulkanRenderSystem.INSTANCE.isInitialized()) {
            return;
        }
        if (clientInstance.getNodeManager() != null) {
            return;
        }
        clientInstance.tryAttachRenderer();
        if (!lodHookNoticeSent && clientInstance.getNodeManager() != null) {
            lodHookNoticeSent = true;
            notifyPlayer("Voxy: Vulkan LoDs hooked");
        }
    }

    public static void sessionStart() {
        if (inSession) {
            return;
        }
        inSession = true;
        ClientChunkIngest.reset();

        Logger.info("Voxy: session start available=" + VoxyCommon.isAvailable()
                + " enabled=" + VoxyConfig.CONFIG.enabled
                + " ingest=" + VoxyConfig.CONFIG.ingestEnabled
                + " vulkan=" + VkContext.INSTANCE.isVulkanActive()
                + " gpu=" + VoxyVulkanRenderSystem.INSTANCE.isInitialized());

        if (VoxyCommon.isAvailable()) {
            if (VoxyConfig.CONFIG.enabled) {
                VoxyCommon.createInstance();
                boolean gpu = VoxyVulkanRenderSystem.INSTANCE.isInitialized();
                var ctx = VkContext.INSTANCE;
                if (gpu) {
                    notifyPlayer("Voxy: world engine started (Vulkan LoDs)");
                } else if (ctx.isVulkanActive()) {
                    notifyPlayer("Voxy: VulkanDevice hooked, but GPU LoDs failed to start (ingest still running). Check latest.log for OutOfMemoryError / render system init.");
                } else if (ctx.gpuLooksLikeVulkan()) {
                    notifyPlayer("Voxy: Minecraft is on " + ctx.lastBackendName()
                            + " but Voxy could not hook VulkanDevice yet (gpu class="
                            + ctx.lastGpuClass() + "). Check latest.log for [Voxy] lines.");
                } else {
                    VoxyClient.persistVulkanPreference();
                    notifyPlayer("Voxy: this session's GpuDevice is " + ctx.lastBackendName()
                            + ", not Vulkan. Far LoDs need Prefer Vulkan — fully quit Lunar and relaunch.");
                }
            } else {
                Logger.info("Voxy: session started but Voxy is disabled in Sodium settings");
            }
        } else if (!deactivationNoticeShown) {
            deactivationNoticeShown = true;
            var reason = VkContext.INSTANCE.getDeactivationReason();
            if (reason != null) {
                notifyPlayer("Voxy: " + reason);
            }
        }
    }

    public static void sessionEnd() {
        if (!inSession) {
            return;
        }
        inSession = false;
        startWaitTicks = 0;
        lodHookNoticeSent = false;
        Logger.info("Voxy: session end, ingested=" + ClientChunkIngest.ingestedCount());
        ClientChunkIngest.reset();
        VoxyCommon.shutdownInstance();
    }

    private static void notifyPlayer(String message) {
        try {
            var mc = Minecraft.getInstance();
            if (mc != null && mc.gui != null) {
                mc.gui.chatListener().handleSystemMessage(Component.literal(message), false);
            }
        } catch (Throwable ignored) {
        }
        Logger.info(message);
    }
}
