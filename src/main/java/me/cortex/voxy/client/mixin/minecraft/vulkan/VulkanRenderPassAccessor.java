package me.cortex.voxy.client.mixin.minecraft.vulkan;

import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = VulkanRenderPass.class, remap = false)
public interface VulkanRenderPassAccessor {
    /** The primary command buffer the pass records into (dynamic rendering, no secondary CBs). */
    @Invoker("commandBuffer")
    VkCommandBuffer voxy$invokeCommandBuffer();
}
