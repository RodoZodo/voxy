package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Synchronization helpers for Voxy's work on Minecraft's shared primary command buffer.
 * Wraps Minecraft's own conservative all-commands/all-commands memory barrier
 * ({@link VulkanCommandEncoder#memoryBarrier}) - everything lives in VK_IMAGE_LAYOUT_GENERAL,
 * so memory barriers are sufficient.
 */
public final class VkSync {
    public static void memoryBarrier(VkCommandBuffer cb) {
        try (var stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(cb, stack);
        }
    }

    private VkSync() {
    }
}
