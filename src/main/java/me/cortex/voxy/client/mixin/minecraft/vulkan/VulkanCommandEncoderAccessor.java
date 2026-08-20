package me.cortex.voxy.client.mixin.minecraft.vulkan;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {
    /** The render pass currently being recorded, if any. */
    @Accessor("currentRenderPass")
    @Nullable
    VulkanRenderPass voxy$getCurrentRenderPass();

    /** The shared primary command buffer for the current frame. */
    @Invoker("commandBuffer")
    VkCommandBuffer voxy$invokeCommandBuffer();
}
