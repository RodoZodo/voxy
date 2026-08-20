package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ClientSessionEvents {
    public static boolean inSession = false;
    private static boolean deactivationNoticeShown = false;

    public static void sessionStart() {
        if (inSession) throw new IllegalStateException("Cannot start new session while in a session");
        inSession = true;

        //Should never try creating multiple instances via session start
        if (VoxyCommon.getInstance() != null) throw new IllegalStateException();

        if (VoxyCommon.isAvailable()) {
            if (VoxyConfig.CONFIG.enabled) {
                VoxyCommon.createInstance();
            }
        } else {
            //Voxy is inactive; if it is because the Vulkan backend is not active, tell the player
            //why, once per game run. (Silent when Vulkan is active but the pipeline is not built yet.)
            if (!deactivationNoticeShown) {
                deactivationNoticeShown = true;
                var reason = VkContext.INSTANCE.getDeactivationReason();
                if (reason != null) {
                    Minecraft.getInstance().gui.chatListener().handleSystemMessage(Component.literal("Voxy: " + reason), false);
                }
            }
        }
    }

    public static void sessionEnd() {
        if (!inSession) throw new IllegalStateException("Cannot end a session while not in a session");
        inSession = false;

        VoxyCommon.shutdownInstance();
    }
}
