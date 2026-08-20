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

    public static void tick(Minecraft client) {
        boolean inWorld = client != null && client.level != null && client.player != null;
        if (inWorld && !inSession) {
            sessionStart();
        } else if (!inWorld && inSession) {
            sessionEnd();
        }

        if (inSession && VoxyCommon.getInstance() == null && VoxyCommon.isAvailable() && VoxyConfig.CONFIG.enabled) {
            Logger.info("Voxy: creating world engine (late)");
            VoxyCommon.createInstance();
        }

        if (inSession) {
            ClientChunkIngest.tick(client);
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
                notifyPlayer("Voxy: world engine started"
                        + (VoxyVulkanRenderSystem.INSTANCE.isInitialized()
                        ? " (Vulkan LoDs)"
                        : " (ingest/save only — Graphics API is not Vulkan, far LoDs will not draw)"));
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
